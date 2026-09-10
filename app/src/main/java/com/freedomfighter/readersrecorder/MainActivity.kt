package com.freedomfighter.readersrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freedomfighter.readersrecorder.ui.DetailScreen
import com.freedomfighter.readersrecorder.ui.ListScreen
import com.freedomfighter.readersrecorder.ui.LocalColors
import com.freedomfighter.readersrecorder.ui.Nav
import com.freedomfighter.readersrecorder.ui.ReaderTheme
import com.freedomfighter.readersrecorder.ui.RecordScreen
import com.freedomfighter.readersrecorder.ui.Screen
import com.freedomfighter.readersrecorder.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val nav = Nav()
    /** Set once the microphone is granted, so a tap that asked for it can go on. */
    private var pendingStart: String? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        val via = pendingStart
        if (g[Manifest.permission.RECORD_AUDIO] == true && via != null) { pendingStart = null; RecordService.start(this, via); nav.push(Screen.Record) }
    }

    fun micGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The frequent action: start recording now, asking for the microphone only the first time. */
    fun record(via: String = "") {
        if (micGranted()) { RecordService.start(this, via); nav.push(Screen.Record) }
        else { pendingStart = via; askPermissions() }
    }

    private fun askPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askMic.launch(wanted.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        askPermissions()
        handle(intent)
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) {
                Bars()
                when (val s = nav.current) {
                    Screen.List -> ListScreen(nav, app, this)
                    Screen.Record -> RecordScreen(nav, app)
                    is Screen.Detail -> DetailScreen(nav, app, s.id)
                    is Screen.Settings -> SettingsScreen(nav, app, s.setup)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onResume() {
        super.onResume()
        (application as App).sync()
        ProcessService.kick(this)
        // Opened while recording (from the notification or the widget): straight to the recording page.
        if (RecordService.Live.recording && nav.current == Screen.List) nav.push(Screen.Record)
    }

    /** content://…/recordings/<id> opens that recording; /record starts one. */
    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.authority == "com.freedomfighter.readersrecorder") {
            val app = application as App
            val id = data.lastPathSegment
            nav.home()
            if (id == "record") record() else if (id != null && app.store.get(id) != null) nav.push(Screen.Detail(id))
            intent.action = null
        }
    }

    @Composable
    private fun Bars() {
        val view = LocalView.current
        val colors = LocalColors.current
        LaunchedEffect(colors.isDark) {
            val c = WindowInsetsControllerCompat(window, view)
            c.isAppearanceLightStatusBars = !colors.isDark
            c.isAppearanceLightNavigationBars = !colors.isDark
        }
    }
}
