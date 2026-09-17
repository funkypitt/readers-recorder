package com.freedomfighter.readersrecorder

import android.app.Application
import com.freedomfighter.readersrecorder.data.Prefs
import com.freedomfighter.readersrecorder.data.Store
import com.freedomfighter.readersrecorder.provider.StateProvider
import com.freedomfighter.readersrecorder.sync.Sync
import com.freedomfighter.readersrecorder.widget.RecordWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class App : Application() {
    // lazy: the provider and the widget can run before Application.onCreate
    val prefs: Prefs by lazy { Prefs(this) }
    val store: Store by lazy { Store(this).also { s -> s.onChange = { RecordWidgets.refresh(this); com.freedomfighter.readersrecorder.widget.ListenWidgets.refresh(this); StateProvider.notify(this) } } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    /** One line for the status row: "syncing…", "synced 21:03", or the error. */
    val status = MutableStateFlow("")
    /** Empty, or why the model that writes the main points could not be fetched. */
    val modelError = MutableStateFlow("")
    val syncing = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate(); prefs; store
        com.freedomfighter.readersrecorder.data.CredentialsShare.cleanUp(this)   // a leftover export holds the password
        Thread { runCatching { com.freedomfighter.readers.speech.whisper.Models.cleanup(this) } }.start()
    }

    /**
     * Fetch the model that writes the main points, then turn the setting on. Here rather than in
     * the screen: it takes minutes, and it must survive the settings screen being left.
     */
    fun fetchSummaryModel() {
        if (com.freedomfighter.readers.speech.summary.SummaryModel.downloading.value >= 0) return
        // In the processing service, not here: two gigabytes take minutes, and a coroutine of the
        // application does not survive the user leaving the screen.
        ProcessService.fetchModel(this)
    }

    /** Upload what is new, fetch transcripts and cleaned audio that have appeared. */
    fun sync() {
        val s = prefs.settings.value
        if (!s.configured) { status.value = ""; return }
        scope.launch {
            if (syncLock.isLocked) return@launch
            syncLock.withLock {
                syncing.value = true; status.value = "syncing…"
                status.value = try {
                    val r = Sync.run(store, s)
                    "synced " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + listOf(r.uploaded to "↑", r.transcripts to "✎", r.cleaned to "♪").filter { it.first > 0 }.joinToString("") { " ${it.first}${it.second}" }
                } catch (e: Exception) { e.message ?: "sync failed" }
                syncing.value = false
            }
        }
    }
}
