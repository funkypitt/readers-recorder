package com.freedomfighter.readersrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readersrecorder.audio.Decode
import com.freedomfighter.readersrecorder.audio.Normalize
import com.freedomfighter.readersrecorder.audio.Pcm
import com.freedomfighter.readersrecorder.audio.Resample
import com.freedomfighter.readersrecorder.data.Recording
import com.freedomfighter.readersrecorder.whisper.Models
import com.freedomfighter.readersrecorder.whisper.Segment
import com.freedomfighter.readersrecorder.whisper.WhisperLib
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_CANCEL -> { cancelled.set(true); WhisperLib.cancel() }
            else -> if (!running) { running = true; scope.launch { work(); finish() } }
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
                val s = app.prefs.settings.value
                val r = app.store.recordings.value.firstOrNull { it.durationMs > 0 && !it.transcribed && it.error.isBlank() && it.mode(s.processing) == "phone" && it.id != RecordService.Live.id } ?: break
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

    private fun processOne(app: App, r: Recording, language: String, modelKey: String) {
        val store = app.store
        val src = store.audio(r)
        // 1. the model, fetched once
        val model = Models.byKey(modelKey)
        if (!Models.isDownloaded(this, model)) {
            Live.phase = "model"; Models.download(this, model) { Live.percent = it }
            if (cancelled.get()) return
        }
        // 2. decode once, at the recording's own rate
        Live.phase = "decode"; Live.percent = 0
        val pcm = Decode.toPcm(src) { Live.percent = (it * 100).toInt() }
        if (cancelled.get()) return
        // 3. whisper wants 16 kHz; transcribe the original, not the cleaned copy
        Live.phase = "transcribe"; Live.percent = 0
        val pcm16 = Resample.to(Pcm(pcm.samples.copyOf(), pcm.rate), 16_000)
        val poll = scope.launch { while (isActive) { Live.percent = WhisperLib.progress(); delay(500) } }
        val result = try { transcribe(Models.file(this, model), pcm16.samples, language.ifBlank { null }) } finally { poll.cancel() }
        if (result == null || cancelled.get()) return
        val (segments, lang) = result
        store.setTranscript(r, paragraphs(segments))
        store.writeSegments(r, segments, lang)
        // 4. the listening copy: high-pass, loudness, AAC
        Live.phase = "clean"; Live.percent = 0
        if (app.prefs.settings.value.cleanOnPhone) {
            Normalize.process(pcm)
            Normalize.encodeAac(pcm, store.cleanTarget(r, "m4a")) { Live.percent = (it * 100).toInt() }
            store.update(r.id) { it.copy(cleaned = true) }
        }
    }

    /** Segments → paragraphs: a new one on a pause over 3 s once there is some body, or past ~700 characters. */
    private fun paragraphs(segments: List<Segment>): String {
        val out = ArrayList<String>(); val cur = StringBuilder(); var lastEnd = -1L
        for (s in segments) {
            if (s.text.isBlank()) continue
            val gap = if (lastEnd < 0) 0 else s.startMs - lastEnd
            if (cur.isNotEmpty() && (cur.length > 700 || (gap > 3000 && cur.length > 150))) { out.add(cur.toString().trim()); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s.text.trim()); lastEnd = s.endMs
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return if (out.isEmpty()) getString(R.string.no_speech) + "\n" else out.joinToString("\n\n") + "\n"
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
        private const val CHANNEL_ID = "processing"
        private const val NOTIF_ID = 2

        fun phaseLabel(ctx: Context, phase: String, percent: Int): String = when (phase) {
            "model" -> ctx.getString(R.string.phase_model, percent)
            "decode" -> ctx.getString(R.string.phase_decode)
            "transcribe" -> ctx.getString(R.string.phase_transcribe, percent)
            "clean" -> ctx.getString(R.string.phase_clean)
            else -> ctx.getString(R.string.phase_waiting)
        }

        /** Start the queue if the phone is the transcriber and something waits. */
        fun kick(ctx: Context) {
            val app = ctx.applicationContext as App
            val default = app.prefs.settings.value.processing
            if (app.store.recordings.value.none { it.durationMs > 0 && !it.transcribed && it.error.isBlank() && it.mode(default) == "phone" }) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, ProcessService::class.java))
        }
        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, ProcessService::class.java).setAction(ACTION_CANCEL))
    }
}
