package com.freedomfighter.readersrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readersrecorder.data.Prefs
import com.freedomfighter.readersrecorder.data.Recording
import com.freedomfighter.readersrecorder.provider.StateProvider
import com.freedomfighter.readersrecorder.widget.RecordWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * The recorder itself: a foreground service (type microphone) so the OS keeps it alive
 * and the microphone open with the screen off, under a partial wake lock. One recording
 * at a time; AAC 48 kHz mono in an .m4a, which the workstation later cleans and
 * transcribes. The notification shows the running time with pause and stop.
 */
class RecordService : Service() {
    private var recorder: MediaRecorder? = null
    private var lock: PowerManager.WakeLock? = null
    private var id: String = ""
    private var file: File? = null
    private var startedAt = 0L
    /** Time recorded before the current stretch (pauses excluded). */
    private var elapsedBefore = 0L
    private var stretchStart = 0L
    private var paused = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_TOGGLE -> if (recorder == null) start() else stop()
            ACTION_START -> if (recorder == null) start()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // No microphone permission yet: the app has to ask for it first.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stopSelf(); return
        }
        val app = application as App
        id = UUID.randomUUID().toString()
        file = File(app.store.dir, "$id.m4a")
        startedAt = System.currentTimeMillis()
        elapsedBefore = 0L; stretchStart = SystemClock.elapsedRealtime(); paused = false
        goForeground()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadersRecorder:record").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        runCatching {
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                // Memos: the voice-recognition source (light processing, steady level). Lectures and
                // conversations: the unprocessed source when the phone offers it, so the workstation's
                // cleaning starts from the raw signal rather than from the phone's own noise gating.
                val kind = app.prefs.settings.value.kind
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val unprocessed = kind != "memo" && am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else if (kind == "memo") MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file!!.absolutePath)
                prepare()
                start()
            }
            // The entry exists from the first second, so a crash never loses the file.
            app.store.add(Recording(id, Recording.defaultTitle(startedAt), startedAt, 0L, app.prefs.settings.value.kind, uploaded = false, cleaned = false, transcribed = false))
            Live.recording = true; Live.paused = false; Live.id = id; Live.startedAt = startedAt; Live.elapsedMs = 0L
            startTicker()
        }.onFailure { release(); stopSelf() }
        changed()
    }

    private fun pause() {
        val r = recorder ?: return
        if (paused) return
        runCatching { r.pause() }
        elapsedBefore += SystemClock.elapsedRealtime() - stretchStart
        paused = true; Live.paused = true
        changed()
    }

    private fun resume() {
        val r = recorder ?: return
        if (!paused) return
        runCatching { r.resume() }
        stretchStart = SystemClock.elapsedRealtime()
        paused = false; Live.paused = false
        changed()
    }

    private fun elapsed(): Long = elapsedBefore + (if (paused) 0L else SystemClock.elapsedRealtime() - stretchStart)

    private fun stop() {
        val r = recorder
        val app = application as App
        if (r != null) {
            val ms = elapsed()
            runCatching { r.stop() }
            release()
            val f = file
            val duration = runCatching { MediaMetadataRetriever().use { m -> m.setDataSource(f!!.absolutePath); m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() } }.getOrDefault(ms)
            if (duration < 500 || f == null || !f.exists()) app.store.delete(id)      // a tap by mistake is not a recording
            else app.store.update(id) { it.copy(durationMs = duration) }
            Live.recording = false; Live.paused = false; Live.id = ""; Live.elapsedMs = 0L
            app.sync()
        }
        ticker?.cancel(); ticker = null
        changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                Live.elapsedMs = elapsed()
                Live.amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                delay(250)
            }
        }
    }

    private fun changed() { RecordWidgets.refresh(this); StateProvider.notify(this); if (recorder != null) pushNotification() }

    override fun onDestroy() { scope.cancel(); if (recorder != null) runCatching { recorder?.stop() }; release(); super.onDestroy() }

    // ---- notification ----

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)
    }

    private fun pushNotification() = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_desc); setSound(null, null); enableVibration(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(if (paused) R.string.notif_paused else R.string.notif_recording))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (!paused) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed()).setShowWhen(true)
        else b.setContentText(clock(elapsed())).setShowWhen(false)
        b.addAction(0, getString(if (paused) R.string.resume else R.string.pause), servicePi(2, if (paused) ACTION_RESUME else ACTION_PAUSE))
        b.addAction(0, getString(R.string.stop), servicePi(1, ACTION_STOP))
        return b.build()
    }

    private fun servicePi(code: Int, action: String): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, RecordService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** In-memory mirror of the recording in progress, observed by the screens. */
    object Live {
        var recording by mutableStateOf(false)
        var paused by mutableStateOf(false)
        var id by mutableStateOf("")
        var startedAt by mutableLongStateOf(0L)
        var elapsedMs by mutableLongStateOf(0L)
        var amplitude by mutableIntStateOf(0)
    }

    companion object {
        const val ACTION_START = "com.freedomfighter.readersrecorder.START"
        const val ACTION_STOP = "com.freedomfighter.readersrecorder.STOP"
        const val ACTION_PAUSE = "com.freedomfighter.readersrecorder.PAUSE"
        const val ACTION_RESUME = "com.freedomfighter.readersrecorder.RESUME"
        const val ACTION_TOGGLE = "com.freedomfighter.readersrecorder.TOGGLE"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1

        fun intent(ctx: Context, action: String): Intent = Intent(ctx, RecordService::class.java).setAction(action)
        fun send(ctx: Context, action: String) = ContextCompat.startForegroundService(ctx, intent(ctx, action))
        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)

        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        }
    }
}
