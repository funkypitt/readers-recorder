package com.freedomfighter.readersrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readersrecorder.audio.Decode16k
import com.freedomfighter.readersrecorder.audio.Normalize
import com.freedomfighter.readersrecorder.audio.Pcm
import com.freedomfighter.readersrecorder.audio.Resample
import com.freedomfighter.readersrecorder.data.Recording
import com.freedomfighter.readersrecorder.summary.SummaryModel
import com.freedomfighter.readersrecorder.summary.Summariser
import com.freedomfighter.readersrecorder.whisper.Models
import com.freedomfighter.readersrecorder.whisper.Paragraphs
import com.freedomfighter.readersrecorder.whisper.Prompts
import com.freedomfighter.readersrecorder.whisper.Segment
import com.freedomfighter.readersrecorder.whisper.WhisperLib
import com.freedomfighter.readersrecorder.whisper.WhisperSession
import com.freedomfighter.readersrecorder.whisper.transcribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's own worker: a foreground service that, one recording at a time, normalises the
 * sound into `<id>.clean.m4a` and transcribes the original with whisper.cpp into `<id>.txt`.
 * It runs after each recording when the phone is the transcriber (the default), fetching the
 * model on first use. Heavy work on a single background thread; the notification shows
 * where it stands and offers to stop.
 */
class ProcessService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val cancelled = AtomicBoolean(false)
    private var lock: PowerManager.WakeLock? = null
    private var running = false
    /** Asked for by the settings row: fetch the model that writes the main points. */
    private val wantModel = AtomicBoolean(false)
    /** Recordings whose summary failed while this service was up: not tried again before it restarts. */
    private val summaryFailedHere = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_CANCEL -> { cancelled.set(true); WhisperLib.cancel() }
            else -> {
                if (intent?.action == ACTION_FETCH_MODEL) wantModel.set(true)
                if (!running) { running = true; scope.launch { work(); finish() } }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        val app = application as App
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadersRecorder:process").apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        val ticker = scope.launch { while (isActive) { pushNotification(); delay(1500) } }
        try {
            while (!cancelled.get()) {
                if (wantModel.compareAndSet(true, false)) { fetchSummaryModel(app); continue }
                val s = app.prefs.settings.value
                val r = app.store.recordings.value.firstOrNull {
                    needsWork(app, it, s) && it.id != RecordService.Live.id && it.id !in summaryFailedHere
                } ?: break
                Live.id = r.id; Live.percent = 0
                try {
                    withContext(Dispatchers.Default) { processOne(app, r, s.language, s.model) }
                } catch (e: Exception) {
                    if (!cancelled.get()) app.store.update(r.id) { it.copy(error = (e.message ?: e.javaClass.simpleName).take(120)) }
                }
            }
        } finally {
            ticker.cancel()
            Live.id = ""; Live.phase = ""; Live.percent = 0
            runCatching { if (lock?.isHeld == true) lock?.release() }
            app.sync()
        }
    }

    /**
     * The two gigabytes of the model that writes the points, fetched here rather than in a
     * coroutine of the application: such a coroutine dies with the process, and Android ends a
     * backgrounded process long before a download of this size is over. Under the service's
     * notification and wake lock the phone stays on it, and an interrupted download carries on
     * from where it stopped.
     */
    private suspend fun fetchSummaryModel(app: App) {
        Live.id = ""; Live.phase = "model"; Live.percent = 0
        app.modelError.value = ""
        try {
            withContext(Dispatchers.IO) {
                SummaryModel.download(this@ProcessService, { Live.percent = it.coerceIn(0, 100) }, { cancelled.get() })
            }
            if (SummaryModel.isDownloaded(this)) app.prefs.setSummaryOnPhone(true)
        } catch (e: Exception) {
            if (!cancelled.get()) app.modelError.value = (e.message ?: e.javaClass.simpleName).take(120)
        } finally {
            Live.phase = ""; Live.percent = 0
        }
    }

    private fun processOne(app: App, r: Recording, language: String, modelKey: String) {
        val store = app.store
        // Already transcribed: the only thing left is the summary, which the option may have been
        // turned on long after the recording was made.
        if (r.transcribed) { runSummary(app, r, language); return }
        val src = Uri.fromFile(store.audio(r))
        val lang = language.ifBlank { null }
        val totalMs = r.durationMs.coerceAtLeast(1)
        // 1. the model, fetched once
        val model = Models.byKey(modelKey)
        if (!Models.isDownloaded(this, model)) {
            Live.phase = "model"; Models.download(this, model) { Live.percent = it }
            if (cancelled.get()) return
        }
        // 2. transcribe the original in five-minute pieces, the model loaded once: memory stays
        //    flat whatever the length. Each piece gets the style sentence and the end of the one before.
        Live.phase = "transcribe"; Live.percent = 0
        val segments = ArrayList<Segment>()
        var detected = ""
        var aborted = false
        WhisperSession(Models.file(this, model)).use { session ->
            Decode16k.chunks(this, src, CHUNK_SECONDS) { pcm, startMs ->
                if (cancelled.get()) { aborted = true; return@chunks false }
                val chunkMs = pcm.size / 16L
                val prompt = if (segments.isEmpty()) Prompts.style(lang) else Prompts.forPiece(lang, segments.takeLast(12).joinToString(" ") { it.text })
                val segs = session.run(pcm, lang, prompt) { p ->
                    Live.percent = (((startMs + chunkMs * p / 100.0) / totalMs) * 100).toInt().coerceIn(0, 99)
                }
                if (segs == null) { aborted = true; return@chunks false }
                if (detected.isEmpty()) detected = session.language()
                segs.forEach { segments += it.copy(startMs = it.startMs + startMs, endMs = it.endMs + startMs) }
                true
            }
        }
        if (aborted || cancelled.get()) return
        store.setTranscript(r, Paragraphs.build(segments, getString(R.string.no_speech)))
        store.writeSegments(r, segments, lang ?: detected)
        // 3. the listening copy, streamed in two passes: high-pass, loudness, AAC
        if (app.prefs.settings.value.cleanOnPhone) {
            Live.phase = "clean"; Live.percent = 0
            if (Normalize.cleanCopy(this, src, store.cleanTarget(r, "m4a"), totalMs, { Live.percent = it }, { cancelled.get() })) {
                store.update(r.id) { it.copy(cleaned = true) }
            }
        }
        // 4. the main points, written by a small model on the phone. Last on purpose: it is the
        //    slowest and the least essential step, and a recording must never lose its transcript
        //    because the summary failed. Silent when the model is not here — the setting offers it.
        runSummary(app, r, lang ?: detected)
    }

    /**
     * The main points, written on the phone. Separate on purpose: it is the slowest and the least
     * essential step, it must never cost a recording its transcript, and a recording transcribed
     * long ago must be able to reach it without being transcribed again.
     *
     * Two guards, both learned the hard way. The attempt is counted BEFORE it starts, because the
     * failure to fear is the one that takes the whole application with it: without the count, the
     * queue would pick the same recording again as soon as the service came back, and the phone
     * would load two gigabytes of weights in a loop. And a phone whose memory is already spoken
     * for is left alone until later rather than pushed into that failure.
     */
    private fun runSummary(app: App, r: Recording, language: String) {
        val s = app.prefs.settings.value
        if (!s.summaryOnPhone || cancelled.get()) return
        if (!SummaryModel.isDownloaded(this) || app.store.summaryFile(r).exists()) return
        if (r.summaryTries >= Recording.MAX_SUMMARY_TRIES) return
        val text = app.store.transcript(r)
        // Marked transcribed but nothing on disk: leave it, and do not come back to it in this
        // pass — every early return here must drop the recording, or the queue spins on it.
        if (text.isBlank()) { summaryFailedHere += r.id; return }
        if (!SummaryModel.roomRightNow(this)) {
            // Not a failure and not counted, but this pass must let it go — otherwise the queue
            // would come straight back to it and spin on the same check.
            android.util.Log.i("ReadersLlama", "not enough free memory right now, the summary waits")
            summaryFailedHere += r.id
            return
        }
        Live.phase = "summary"; Live.percent = 0
        app.store.countSummaryTry(r)
        val points = Summariser.summarise(
            model = SummaryModel.file(this),
            transcript = text,
            language = language,
            onProgress = { Live.percent = it.coerceIn(0, 99) },
            cancelled = { cancelled.get() },
        )
        if (points != null && !cancelled.get()) {
            app.store.setSummary(r, points)
            app.store.update(r.id) { it.copy(summaryTries = 0) }   // done: nothing left to count
        } else if (!cancelled.get()) {
            summaryFailedHere += r.id                              // not again in this pass
        } else {
            // Stopped by hand: that is not a failure, and it must not use up one of the two goes.
            app.store.update(r.id) { it.copy(summaryTries = (it.summaryTries - 1).coerceAtLeast(0)) }
        }
    }

    private fun finish() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); runCatching { if (lock?.isHeld == true) lock?.release() }; super.onDestroy() }

    // ---- notification ----

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
    }

    private fun pushNotification() = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 3, Intent(this, ProcessService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = (application as App).store.get(Live.id)?.title ?: getString(R.string.app_title)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(phaseLabel(this, Live.phase, Live.percent))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .setProgress(100, Live.percent, Live.phase.isEmpty())
            .addAction(0, getString(R.string.stop), cancel)
            .build()
    }

    /** What the screens and the notification show while a recording is being worked on. */
    object Live {
        var id by mutableStateOf("")
        var phase by mutableStateOf("")   // model | decode | transcribe | clean
        var percent by mutableIntStateOf(0)
    }

    companion object {
        const val ACTION_CANCEL = "com.freedomfighter.readersrecorder.PROCESS_CANCEL"
        const val ACTION_FETCH_MODEL = "com.freedomfighter.readersrecorder.FETCH_SUMMARY_MODEL"
        private const val CHUNK_SECONDS = 300
        private const val CHANNEL_ID = "processing"
        private const val NOTIF_ID = 2

        fun phaseLabel(ctx: Context, phase: String, percent: Int): String = when (phase) {
            "model" -> ctx.getString(R.string.phase_model, percent)
            "decode" -> ctx.getString(R.string.phase_decode)
            "transcribe" -> ctx.getString(R.string.phase_transcribe, percent)
            "clean" -> ctx.getString(R.string.phase_clean)
            "summary" -> ctx.getString(R.string.phase_summary, percent)
            else -> ctx.getString(R.string.phase_waiting)
        }

        /**
         * What is left to do on a recording: transcribe it, or — the setting being on, the model
         * here, the points not written and the goes not used up — summarise one that is already
         * transcribed. That second case is the ordinary one: the option is usually turned on after
         * the fact, and a summary interrupted by a reboot would otherwise never be written again.
         */
        fun needsWork(app: App, r: Recording, s: com.freedomfighter.readersrecorder.data.Settings): Boolean {
            if (r.durationMs <= 0 || r.error.isNotBlank() || r.mode(s.processing) != "phone") return false
            if (!r.transcribed) return true
            return s.summaryOnPhone && r.summaryTries < Recording.MAX_SUMMARY_TRIES &&
                SummaryModel.isDownloaded(app) && !app.store.summaryFile(r).exists()
        }

        /** Start the queue if the phone is the transcriber and something waits. */
        fun kick(ctx: Context) {
            val app = ctx.applicationContext as App
            val s = app.prefs.settings.value
            if (app.store.recordings.value.none { needsWork(app, it, s) }) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, ProcessService::class.java))
        }
        /** Fetch the model that writes the points, under the notification and the wake lock. */
        fun fetchModel(ctx: Context) = ContextCompat.startForegroundService(ctx,
            Intent(ctx, ProcessService::class.java).setAction(ACTION_FETCH_MODEL))

        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, ProcessService::class.java).setAction(ACTION_CANCEL))
    }
}
