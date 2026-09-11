package com.freedomfighter.readersrecorder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.freedomfighter.readersrecorder.App
import com.freedomfighter.readersrecorder.MainActivity
import com.freedomfighter.readersrecorder.PlayerService
import com.freedomfighter.readersrecorder.R
import com.freedomfighter.readersrecorder.RecordService

/**
 * The listen widget for any launcher: one recording at a time, newest first. ▶ / ❚❚ plays and
 * pauses it; ‹ and › step to the more recent and the older ones (a standard widget cannot be
 * swiped, so the arrows stand in for the swipe of the launcher tile); the title opens it.
 */
object ListenWidgets {
    const val ACTION_STEP = "com.freedomfighter.readersrecorder.widget.LISTEN_STEP"
    private const val PKG = "com.freedomfighter.readersrecorder"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_listen)
        val (bg, fg, dim) = WidgetUi.colors(ctx)
        v.setInt(R.id.widget_root, "setBackgroundColor", bg)
        listOf(R.id.listen_prev, R.id.listen_next, R.id.listen_play, R.id.widget_title, R.id.listen_timer).forEach { v.setTextColor(it, fg) }
        v.setTextColor(R.id.widget_sub, dim)
        val list = (ctx.applicationContext as App).store.recordings.value.filter { it.durationMs > 0 }
        if (list.isEmpty()) {
            v.setTextViewText(R.id.widget_title, ctx.getString(R.string.listen_empty))
            v.setTextViewText(R.id.widget_sub, ctx.getString(R.string.app_title))
            listOf(R.id.listen_prev, R.id.listen_next, R.id.listen_play, R.id.listen_timer).forEach { v.setViewVisibility(it, View.GONE) }
            v.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(ctx, Intent(ctx, MainActivity::class.java), 400 + widgetId))
            mgr.updateAppWidget(widgetId, v); return
        }
        val storedId = sp(ctx).getString("listen_$widgetId", null)
        val i = list.indexOfFirst { it.id == storedId }.takeIf { it >= 0 } ?: 0
        val r = list[i]
        val live = PlayerService.Live
        val isThis = live.id == r.id
        val playing = isThis && live.playing
        v.setTextViewText(R.id.widget_title, r.title)
        val datePart = if (r.title.trim() == r.whenLabel) "" else r.whenLabel + " · "
        v.setTextViewText(R.id.widget_sub, (if (list.size > 1) "${i + 1}/${list.size} · " else "") + datePart + RecordService.clock(r.durationMs))
        v.setViewVisibility(R.id.listen_play, View.VISIBLE)
        v.setTextViewText(R.id.listen_play, if (playing) "❚❚" else "▶")
        if (isThis) {
            val pos = live.positionMs + (if (playing) System.currentTimeMillis() - live.at else 0L)
            v.setViewVisibility(R.id.listen_timer, View.VISIBLE)
            v.setChronometer(R.id.listen_timer, SystemClock.elapsedRealtime() - pos, null, playing)
        } else v.setViewVisibility(R.id.listen_timer, View.GONE)
        v.setViewVisibility(R.id.listen_prev, if (i > 0) View.VISIBLE else View.INVISIBLE)
        v.setViewVisibility(R.id.listen_next, if (i < list.size - 1) View.VISIBLE else View.INVISIBLE)
        // PendingIntent equality ignores extras: a distinct data URI per widget and per button.
        val play = PendingIntent.getForegroundService(ctx, 100 + widgetId,
            PlayerService.intent(ctx, PlayerService.ACTION_TOGGLE, r.id).setData(Uri.parse("recorder://listen/$widgetId/play")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.listen_play, play)
        v.setOnClickPendingIntent(R.id.listen_timer, play)
        v.setOnClickPendingIntent(R.id.listen_prev, step(ctx, widgetId, -1, 200))
        v.setOnClickPendingIntent(R.id.listen_next, step(ctx, widgetId, +1, 300))
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("content://$PKG/recordings/${r.id}")).setClassName(PKG, "$PKG.MainActivity")
        v.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(ctx, open, 400 + widgetId))
        mgr.updateAppWidget(widgetId, v)
    }

    private fun step(ctx: Context, widgetId: Int, delta: Int, base: Int): PendingIntent = PendingIntent.getBroadcast(ctx, base + widgetId,
        Intent(ctx, ListenReceiver::class.java).setAction(ACTION_STEP).setData(Uri.parse("recorder://listen/$widgetId/$delta"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra("delta", delta),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Newer (−1) or older (+1) recording in this widget; remembered by id so a new recording does not shift it. */
    fun step(ctx: Context, widgetId: Int, delta: Int) {
        val list = (ctx.applicationContext as App).store.recordings.value.filter { it.durationMs > 0 }
        if (list.isEmpty()) return
        val storedId = sp(ctx).getString("listen_$widgetId", null)
        val i = list.indexOfFirst { it.id == storedId }.takeIf { it >= 0 } ?: 0
        val next = (i + delta).coerceIn(0, list.size - 1)
        sp(ctx).edit().putString("listen_$widgetId", list[next].id).apply()
        render(ctx, AppWidgetManager.getInstance(ctx), widgetId)
    }

    fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, ListenWidget::class.java)).forEach { render(ctx, mgr, it) }
    }
}

class ListenWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { ListenWidgets.render(context, mgr, it) } }
    override fun onDeleted(context: Context, ids: IntArray) {
        val e = context.getSharedPreferences("widgets", Context.MODE_PRIVATE).edit(); ids.forEach { e.remove("listen_$it") }; e.apply()
    }
}

class ListenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ListenWidgets.ACTION_STEP) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (id >= 0) ListenWidgets.step(context, id, intent.getIntExtra("delta", 0))
    }
}
