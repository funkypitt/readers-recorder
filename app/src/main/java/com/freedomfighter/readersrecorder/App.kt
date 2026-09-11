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
    val syncing = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate(); prefs; store
        Thread { runCatching { com.freedomfighter.readersrecorder.whisper.Models.cleanup(this) } }.start()
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
