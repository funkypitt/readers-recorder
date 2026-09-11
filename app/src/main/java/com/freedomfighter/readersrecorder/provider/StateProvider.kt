package com.freedomfighter.readersrecorder.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.freedomfighter.readersrecorder.App
import com.freedomfighter.readersrecorder.RecordService
import com.freedomfighter.readersrecorder.PlayerService

/**
 * For Reader's Launcher's tile (signature-protected): one row of state at
 * content://com.freedomfighter.readersrecorder/state — whether a recording is running,
 * since when, and the latest recording. The launcher starts and stops through the
 * exported [RecordService].
 */
class StateProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = if (uri.pathSegments.firstOrNull() == "recordings") "vnd.android.cursor.dir/vnd.readersrecorder.recording" else "vnd.android.cursor.item/vnd.readersrecorder.state"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val ctx = context!!
        val store = (ctx.applicationContext as App).store
        if (uri.pathSegments.firstOrNull() == "recordings") {
            // newest first, finished recordings only
            val c = MatrixCursor(RECORDING_COLUMNS)
            store.recordings.value.filter { it.durationMs > 0 }.forEach { r ->
                c.addRow(arrayOf(r.id, r.title, r.whenLabel, r.durationMs, r.status, if (r.transcribed) 1 else 0, r.createdAt))
            }
            c.setNotificationUri(ctx.contentResolver, RECORDINGS)
            return c
        }
        val pl = PlayerService.Live
        val last = store.recordings.value.firstOrNull { it.durationMs > 0 }
        val c = MatrixCursor(COLUMNS)
        c.addRow(arrayOf(
            if (RecordService.Live.recording) 1 else 0, if (RecordService.Live.paused) 1 else 0, RecordService.Live.startedAt, RecordService.Live.elapsedMs,
            last?.id ?: "", last?.title ?: "", last?.durationMs ?: 0L, last?.status ?: "", store.recordings.value.count { it.durationMs > 0 },
            pl.id, if (pl.id.isBlank()) "" else if (pl.playing) "playing" else "paused", pl.positionMs, pl.durationMs, pl.at
        ))
        c.setNotificationUri(ctx.contentResolver, URI)
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0

    companion object {
        val URI: Uri = Uri.parse("content://com.freedomfighter.readersrecorder/state")
        val RECORDINGS: Uri = Uri.parse("content://com.freedomfighter.readersrecorder/recordings")
        val COLUMNS = arrayOf("recording", "paused", "started_at", "elapsed_ms", "last_id", "last_title", "last_duration_ms", "last_status", "count",
            "play_id", "play_state", "play_pos_ms", "play_dur_ms", "play_at")
        val RECORDING_COLUMNS = arrayOf("id", "title", "when", "duration_ms", "status", "transcribed", "created_at")
        fun notify(ctx: Context) = runCatching { ctx.contentResolver.notifyChange(URI, null); ctx.contentResolver.notifyChange(RECORDINGS, null) }
    }
}
