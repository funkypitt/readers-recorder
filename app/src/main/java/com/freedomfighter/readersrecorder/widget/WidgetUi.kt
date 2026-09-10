package com.freedomfighter.readersrecorder.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import com.freedomfighter.readersrecorder.R

/** Shared by the standard widgets: the app's two colours, the launch intents, the refresh. */
object WidgetUi {
    /** (background, foreground, dim) following the app's theme setting. */
    fun colors(context: Context): Triple<Int, Int, Int> {
        val theme = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "DARK")
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (theme) { "LIGHT" -> false; "SYSTEM" -> systemDark; else -> true }
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        val dim = if (dark) Color.argb(140, 255, 255, 255) else Color.argb(140, 0, 0, 0)
        return Triple(bg, fg, dim)
    }


    fun activity(context: Context, intent: Intent, code: Int = 0): PendingIntent =
        PendingIntent.getActivity(context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun broadcast(context: Context, intent: Intent, code: Int = 0, mutable: Boolean = false): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, code, intent, flags)
    }

    /** The hairline colour: the foreground at a quarter. */
    fun rule(fg: Int): Int = Color.argb(64, Color.red(fg), Color.green(fg), Color.blue(fg))
}
