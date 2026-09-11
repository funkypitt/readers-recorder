package com.freedomfighter.readersrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readersrecorder.provider.StateProvider
import com.freedomfighter.readersrecorder.widget.ListenWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one player of the app, shared by the recording page, the listen widget and Reader's
 * Launcher's tile: a foreground service (type mediaPlayback) so playback carries on with the
 * screen off, audio focus taken and given back, paused when headphones are pulled out.
 */
class PlayerService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ticker: Job? = null
    private var noisyRegistered = false
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID) ?: ""
        when (intent?.action) {
            ACTION_TOGGLE -> when {
                Live.id == id && Live.playing -> pause()
                Live.id == id && player != null -> resume()
                id.isNotBlank() -> play(id, 0)
            }
            ACTION_PLAY -> if (id.isNotBlank()) play(id, intent.getIntExtra(EXTRA_POSITION, 0))
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_SEEK -> seek(intent.getIntExtra(EXTRA_POSITION, 0))
            ACTION_STOP -> stopAll()
        }
        if (player == null) stopAll()
        return START_NOT_STICKY
    }

    private fun play(id: String, positionMs: Int) {
        val store = (application as App).store
        val r = store.get(id) ?: return
        val file = store.playable(r)
        if (!file.exists()) return
        release()
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.absolutePath)
                prepare()
                if (positionMs > 0) seekTo(positionMs)
                setOnCompletionListener { stopAll() }
                setOnErrorListener { _, _, _ -> stopAll(); true }
            }
            requestFocus()
            player!!.start()
            Live.id = id; Live.playing = true
            Live.durationMs = player!!.duration.toLong(); Live.positionMs = positionMs.toLong(); Live.at = System.currentTimeMillis()
            if (!noisyRegistered) { registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); noisyRegistered = true }
            startTicker()
        }.onFailure { release() }
        changed()
    }

    private fun pause() {
        val p = player ?: return
        runCatching { p.pause() }
        Live.positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(Live.positionMs); Live.at = System.currentTimeMillis()
        Live.playing = false
        ticker?.cancel()
        changed()
    }

    private fun resume() {
        val p = player ?: return
        requestFocus()
        runCatching { p.start() }
        Live.playing = true; Live.at = System.currentTimeMillis()
        startTicker()
        changed()
    }

    private fun seek(positionMs: Int) {
        val p = player ?: return
        runCatching { p.seekTo(positionMs) }
        Live.positionMs = positionMs.toLong(); Live.at = System.currentTimeMillis()
        changed()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                Live.positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(Live.positionMs); Live.at = System.currentTimeMillis()
                delay(250)
            }
        }
    }

    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (focus == null) focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }.build()
        runCatching { am.requestAudioFocus(focus!!) }
    }

    private fun release() {
        ticker?.cancel(); ticker = null
        runCatching { player?.release() }
        player = null
    }

    private fun stopAll() {
        release()
        focus?.let { f -> runCatching { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(f) } }
        if (noisyRegistered) { runCatching { unregisterReceiver(noisy) }; noisyRegistered = false }
        Live.id = ""; Live.playing = false; Live.positionMs = 0L; Live.durationMs = 0L; Live.at = System.currentTimeMillis()
        changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Tell the widgets and the launcher; keep the notification current. */
    private fun changed() {
        ListenWidgets.refresh(this)
        StateProvider.notify(this)
        if (player != null) pushNotification()
    }

    override fun onDestroy() {
        scope.cancel(); release()
        if (noisyRegistered) runCatching { unregisterReceiver(noisy) }
        super.onDestroy()
    }

    // ---- notification ----

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTIF_ID, n)
    }

    private fun pushNotification() = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val title = (application as App).store.get(Live.id)?.title ?: getString(R.string.app_title)
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(Live.playing)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        if (Live.playing) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - Live.positionMs).setShowWhen(true).setContentText(getString(R.string.notif_playing))
        else b.setShowWhen(false).setContentText(getString(R.string.notif_paused) + " · " + RecordService.clock(Live.positionMs) + " / " + RecordService.clock(Live.durationMs))
        b.addAction(0, getString(if (Live.playing) R.string.pause else R.string.resume), pi(11, if (Live.playing) ACTION_PAUSE else ACTION_RESUME))
        b.addAction(0, getString(R.string.stop), pi(12, ACTION_STOP))
        return b.build()
    }

    private fun pi(code: Int, action: String): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, PlayerService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** What plays, observed by the screens; [at] is when [positionMs] was read, for readers in other processes. */
    object Live {
        var id by mutableStateOf("")
        var playing by mutableStateOf(false)
        var positionMs by mutableLongStateOf(0L)
        var durationMs by mutableLongStateOf(0L)
        @Volatile var at: Long = 0L
    }

    companion object {
        const val ACTION_TOGGLE = "com.freedomfighter.readersrecorder.PLAY_TOGGLE"
        const val ACTION_PLAY = "com.freedomfighter.readersrecorder.PLAY"
        const val ACTION_PAUSE = "com.freedomfighter.readersrecorder.PLAY_PAUSE"
        const val ACTION_RESUME = "com.freedomfighter.readersrecorder.PLAY_RESUME"
        const val ACTION_SEEK = "com.freedomfighter.readersrecorder.PLAY_SEEK"
        const val ACTION_STOP = "com.freedomfighter.readersrecorder.PLAY_STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_POSITION = "position_ms"
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 3

        fun intent(ctx: Context, action: String, id: String = "", positionMs: Int = 0): Intent =
            Intent(ctx, PlayerService::class.java).setAction(action).putExtra(EXTRA_ID, id).putExtra(EXTRA_POSITION, positionMs)
        private fun send(ctx: Context, i: Intent) { runCatching { ContextCompat.startForegroundService(ctx, i) } }
        fun toggle(ctx: Context, id: String) = send(ctx, intent(ctx, ACTION_TOGGLE, id))
        fun play(ctx: Context, id: String, positionMs: Int) = send(ctx, intent(ctx, ACTION_PLAY, id, positionMs))
        fun seek(ctx: Context, positionMs: Int) = send(ctx, intent(ctx, ACTION_SEEK, positionMs = positionMs))
        fun stop(ctx: Context) { if (Live.id.isNotBlank()) send(ctx, intent(ctx, ACTION_STOP)) }
    }
}
