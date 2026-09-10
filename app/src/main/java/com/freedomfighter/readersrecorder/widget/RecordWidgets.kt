package com.freedomfighter.readersrecorder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.freedomfighter.readersrecorder.App
import com.freedomfighter.readersrecorder.MainActivity
import com.freedomfighter.readersrecorder.R
import com.freedomfighter.readersrecorder.RecordService

/**
 * The one-gesture widget: "● record" when idle (the latest recording under it), a running
 * Chronometer and "■" while recording. One tap starts, one tap stops; the text opens the app.
 */
object RecordWidgets {
    fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_record)
        val (bg, fg, dim) = WidgetUi.colors(ctx)
        v.setInt(R.id.widget_root, "setBackgroundColor", bg)
        listOf(R.id.widget_title, R.id.widget_button, R.id.widget_timer).forEach { v.setTextColor(it, fg) }
        v.setTextColor(R.id.widget_sub, dim)
        val store = (ctx.applicationContext as App).store
        val live = RecordService.Live
        if (live.recording) {
            v.setTextViewText(R.id.widget_title, ctx.getString(if (live.paused) R.string.notif_paused else R.string.notif_recording))
            v.setTextViewText(R.id.widget_sub, ctx.getString(R.string.widget_tap_stop))
            v.setViewVisibility(R.id.widget_timer, View.VISIBLE)
            v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() - live.elapsedMs, null, !live.paused)
            v.setTextViewText(R.id.widget_button, "■")
        } else {
            val last = store.recordings.value.firstOrNull { it.durationMs > 0 }
            v.setTextViewText(R.id.widget_title, ctx.getString(R.string.widget_record))
            v.setTextViewText(R.id.widget_sub, if (last == null) ctx.getString(R.string.app_title) else last.title + " · " + RecordService.clock(last.durationMs))
            v.setViewVisibility(R.id.widget_timer, View.GONE)
            v.setTextViewText(R.id.widget_button, "●")
        }
        val toggle = PendingIntent.getForegroundService(ctx, 1, RecordService.intent(ctx, RecordService.ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.widget_button, toggle)
        v.setOnClickPendingIntent(R.id.widget_timer, toggle)
        v.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(ctx, Intent(ctx, MainActivity::class.java), 2))
        mgr.updateAppWidget(id, v)
    }

    fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, RecordWidget::class.java)).forEach { render(ctx, mgr, it) }
    }
}

class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { RecordWidgets.render(context, mgr, it) } }
}
