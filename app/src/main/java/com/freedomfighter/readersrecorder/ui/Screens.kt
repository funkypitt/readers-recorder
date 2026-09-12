package com.freedomfighter.readersrecorder.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.content.FileProvider
import com.freedomfighter.readersrecorder.App
import com.freedomfighter.readersrecorder.MainActivity
import com.freedomfighter.readersrecorder.R
import com.freedomfighter.readersrecorder.RecordService
import com.freedomfighter.readersrecorder.data.FontChoice
import com.freedomfighter.readersrecorder.data.Prefs
import com.freedomfighter.readersrecorder.data.Recording
import com.freedomfighter.readersrecorder.data.TextSize
import com.freedomfighter.readersrecorder.sync.Sync
import com.freedomfighter.readersrecorder.ProcessService
import com.freedomfighter.readersrecorder.PlayerService
import com.freedomfighter.readersrecorder.summary.SummaryModel
import com.freedomfighter.readersrecorder.whisper.Models
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

sealed class Screen {
    data object List : Screen()
    data object Record : Screen()
    data class Detail(val id: String) : Screen()
    data class Settings(val setup: Boolean = false) : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.List)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
}

@Composable
fun kindLabel(kind: String): String = stringResource(when (kind) { "lecture" -> R.string.kind_lecture; "conversation" -> R.string.kind_conversation; else -> R.string.kind_memo })

@Composable
fun statusLabel(r: Recording, configured: Boolean): String = (when {
    ProcessService.Live.id == r.id -> ProcessService.phaseLabel(LocalContext.current, ProcessService.Live.phase, ProcessService.Live.percent)
    r.error.isNotBlank() -> r.error
    r.transcribed -> stringResource(R.string.status_transcribed)
    r.cleaned -> stringResource(R.string.status_cleaned)
    r.uploaded -> stringResource(R.string.status_uploaded)
    configured -> stringResource(R.string.status_waiting)
    else -> stringResource(R.string.status_phone)
}) + (if (r.via.isNotBlank()) " · " + stringResource(if (r.via == "cloud") R.string.via_short_cloud else R.string.via_short_phone) else "")

/** A hairline, [fraction] of it in the foreground colour. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(colors.rule, topLeft = Offset(0f, size.height / 3), size = Size(size.width, size.height / 3))
        drawRect(colors.fg, size = Size(size.width * fraction, size.height))
    }
}

// ---------------------------------------------------------------------------------------------
// The list, and the one frequent action at the bottom: ● record
// ---------------------------------------------------------------------------------------------

@Composable
fun ListScreen(nav: Nav, app: App, activity: MainActivity) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val settings by app.prefs.settings.collectAsState()
    val all by app.store.recordings.collectAsState()
    val status by app.status.collectAsState()
    val live = RecordService.Live
    var menu by remember { mutableStateOf(false) }
    var viaMenu by remember { mutableStateOf(false) }
    // Long press on a row: its own menu; "select several" turns the rows into boxes for a bulk delete.
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = selecting) { selecting = false; selected.clear() }
    fun deleteMany(ids: List<String>) {
        val s = settings
        if (PlayerService.Live.id in ids) PlayerService.stop(context)
        val gone = ids.mapNotNull { app.store.get(it) }
        gone.forEach { app.store.delete(it.id) }
        scope.launch(Dispatchers.IO) { gone.forEach { runCatching { Sync.deleteRemote(it, s) } } }
        selecting = false; selected.clear()
    }
    val recordings = all.filter { it.durationMs > 0 || it.id == live.id }
    Page {
        Column(Modifier.fillMaxSize()) {
            if (selecting) ScreenTitle(stringResource(R.string.selected_n, selected.size), onBack = { selecting = false; selected.clear() })
            else ScreenTitle(stringResource(R.string.app_title), onBack = null, trailing = "⋯", onTrailing = { menu = true })
            LazyColumn(Modifier.weight(1f)) {
                if (recordings.isEmpty()) item { Small(stringResource(R.string.empty), Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 4) }
                items(recordings, key = { it.id }) { r ->
                    val isLive = r.id == live.id && live.recording
                    val checked = r.id in selected
                    Box(Modifier.fillMaxWidth().pressable(
                        onClick = {
                            when {
                                isLive -> nav.push(Screen.Record)
                                selecting -> if (checked) selected.remove(r.id) else selected.add(r.id)
                                else -> nav.push(Screen.Detail(r.id))
                            }
                        },
                        onLongPress = { if (!isLive) { if (selecting) { if (!checked) selected.add(r.id) } else rowMenu = r.id } }
                    )) {
                        TextRow(
                            (if (selecting && !isLive) (if (checked) "☑  " else "☐  ") else "") + r.title,
                            inverted = isLive,
                            secondary = if (isLive) stringResource(R.string.notif_recording) + " · " + RecordService.clock(live.elapsedMs)
                            else r.whenPrefix + RecordService.clock(r.durationMs) + " · " + kindLabel(r.kind) + " · " + statusLabel(r, settings.configured)
                        )
                    }
                }
            }
            Small(
                when {
                    // phase without an id: the summary model being fetched, which belongs to no recording
                    ProcessService.Live.id.isNotBlank() || ProcessService.Live.phase.isNotBlank() ->
                        ProcessService.phaseLabel(LocalContext.current, ProcessService.Live.phase, ProcessService.Live.percent)
                    settings.configured -> status.ifBlank { stringResource(R.string.synced_folder, settings.folder) }
                    else -> stringResource(R.string.local_only_phone)
                },
                Modifier.padding(horizontal = rowPadH).padding(bottom = 6.dp).noRippleClickable { if (settings.configured) app.sync() else nav.push(Screen.Settings()) }, maxLines = 2
            )
            Rule()
            // The one thing this app is for, one tap from the list, inverted so it cannot be missed.
            if (live.recording) TextRow("■  " + stringResource(R.string.notif_recording) + " · " + RecordService.clock(live.elapsedMs), inverted = true) { nav.push(Screen.Record) }
            // Tap records; a long press asks, for this one recording, who will transcribe it.
            else if (selecting) TextRow(stringResource(R.string.delete_selected, selected.size), inverted = selected.isNotEmpty()) { if (selected.isNotEmpty()) deleteMany(selected.toList()) }
            else Box(Modifier.fillMaxWidth().pressable(onClick = { activity.record() }, onLongPress = {
                // Choosing who transcribes only makes sense where the computer can: the private build.
                if (settings.configured && com.freedomfighter.readersrecorder.BuildConfig.PRIVATE) viaMenu = true else activity.record()
            })) {
                TextRow("●  " + stringResource(R.string.record), inverted = true)
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).background(if (live.recording || true) colors.fg else colors.bg).fillMaxWidth())
        }
        rowMenu?.let { id ->
            val r = all.firstOrNull { it.id == id }
            if (r == null) rowMenu = null else {
                val transcript = app.store.transcript(r)
                TextMenu(r.title, buildList {
                    add(MenuItem(stringResource(R.string.rename)) { renaming = r.id })
                    add(MenuItem(stringResource(R.string.share_audio)) { shareFile(context, app.store.playable(r), "audio/*", r.title) })
                    if (transcript.isNotBlank()) add(MenuItem(stringResource(R.string.share_transcript)) { shareText(context, transcript, r.title) })
                    add(MenuItem(stringResource(R.string.delete), secondary = if (r.uploaded) stringResource(R.string.delete_hint_server) else null) { deleteMany(listOf(r.id)) })
                    add(MenuItem(stringResource(R.string.select_several)) { selecting = true; selected.clear(); selected.add(r.id) })
                }, onDismiss = { rowMenu = null })
            }
        }
        renaming?.let { id ->
            val r = all.firstOrNull { it.id == id }
            if (r == null) renaming = null else TextPrompt(stringResource(R.string.rename), initial = r.title, onDone = { t ->
                val s = settings
                val new = app.store.rename(r, t)
                scope.launch(Dispatchers.IO) { runCatching { Sync.renameRemote(r, new, s) } }
                renaming = null
            }, onCancel = { renaming = null })
        }
        if (viaMenu) TextMenu(stringResource(R.string.via_title), listOf(
            MenuItem(stringResource(R.string.via_phone), secondary = if (settings.processing == "phone") stringResource(R.string.via_default) else null) { activity.record("phone") },
            MenuItem(stringResource(R.string.via_cloud), secondary = if (settings.processing == "cloud") stringResource(R.string.via_default) else null) { activity.record("cloud") }
        ), onDismiss = { viaMenu = false })
        if (menu) TextMenu(null, buildList {
            if (settings.configured) add(MenuItem(stringResource(R.string.sync_now)) { app.sync() })
            add(MenuItem(kindLabel(settings.kind), secondary = stringResource(R.string.next_kind)) { app.prefs.setKind(Prefs.KINDS[(Prefs.KINDS.indexOf(settings.kind) + 1) % Prefs.KINDS.size]) })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings()) }
        ))
    }
}

// ---------------------------------------------------------------------------------------------
// Recording: the running time, large; the kind; pause and stop
// ---------------------------------------------------------------------------------------------

@Composable
fun RecordScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val live = RecordService.Live
    val settings by app.prefs.settings.collectAsState()
    val tick = rememberTick()
    BackHandler { nav.pop() }
    // Once the recording has stopped (from the notification, say), fall back to the list.
    LaunchedEffect(live.recording) { if (!live.recording) { delay(300); if (!live.recording) nav.home() } }
    val current = app.store.get(live.id)
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(if (live.paused) R.string.notif_paused else R.string.notif_recording), onBack = { nav.pop() })
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = rowPadH), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                T(RecordService.clock(live.elapsedMs), size = typo.big, align = TextAlign.Start, maxLines = 1)
                // A level line: the loudest sample of the last quarter second, as a bar.
                Progress(if (live.paused) 0f else (live.amplitude / 20000f).coerceIn(0f, 1f), Modifier.padding(top = 10.dp, bottom = 18.dp))
                Small(current?.title ?: "", maxLines = 1)
                VSpace(18.dp)
                val kind = current?.kind ?: settings.kind
                TextRow(kindLabel(kind), secondary = stringResource(R.string.kind_hint), size = typo.title) {
                    val next = Prefs.KINDS[(Prefs.KINDS.indexOf(kind) + 1) % Prefs.KINDS.size]
                    app.prefs.setKind(next); current?.let { app.store.update(it.copy(kind = next)) }
                }
            }
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(if (live.paused) R.string.resume else R.string.pause)) { tick(); if (live.paused) RecordService.resume(context) else RecordService.pause(context) } }
                Box(Modifier.weight(1f)) { TextRow("■  " + stringResource(R.string.stop), inverted = true) { tick(); RecordService.stop(context); nav.home() } }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// One recording: play it, read the transcript, rename, share, delete
// ---------------------------------------------------------------------------------------------

@Composable
fun DetailScreen(nav: Nav, app: App, id: String) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val settings by app.prefs.settings.collectAsState()
    val all by app.store.recordings.collectAsState()
    val r = all.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // The shared player: what plays here also shows in the widget, the launcher tile and the notification.
    val pl = PlayerService.Live
    val isThis = pl.id == id
    val playing = isThis && pl.playing
    val position = if (isThis) pl.positionMs.toInt() else 0
    val duration = if (isThis && pl.durationMs > 0) pl.durationMs.toInt() else (r?.durationMs ?: 0L).toInt()
    BackHandler { nav.pop() }
    if (r == null) { LaunchedEffect(Unit) { nav.pop() }; return }
    val transcript = remember(r.transcribed, all) { app.store.transcript(r) }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(r.title, onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // ---- player: one line, tap to play or pause; the rule is the position, tap to seek ----
                Row(Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = rowPadV * 0.6f), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(RecordService.clock(position.toLong()) + " / " + RecordService.clock((if (duration > 0) duration else r.durationMs.toInt()).toLong()), maxLines = 1, align = TextAlign.Start)
                        Small(r.whenPrefix + kindLabel(r.kind) + " · " + statusLabel(r, settings.configured) + (if (r.cleaned && app.store.cleanAudio(r).exists()) " · " + stringResource(R.string.playing_cleaned) else ""), maxLines = 2)
                    }
                    Box(Modifier.padding(start = 16.dp).background(if (playing) colors.fg else Color.Transparent).noRippleClickable {
                        PlayerService.toggle(context, r.id)
                    }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                        T(if (playing) "❚❚" else "▶", size = typo.tile * 0.9f, color = if (playing) colors.bg else colors.fg, align = TextAlign.Center)
                    }
                }
                Box(Modifier.padding(horizontal = rowPadH).fillMaxWidth().height(24.dp).noRippleClickable { }, contentAlignment = Alignment.Center) {
                    var width by remember { mutableIntStateOf(1) }
                    Progress(if (duration > 0) position.toFloat() / duration else 0f, Modifier.onSizeChanged { width = it.width }
                        .pointerInput(duration, isThis) {
                            detectTapGestures { o: Offset -> if (duration > 0) { val p = (o.x / width * duration).toInt(); if (isThis) PlayerService.seek(context, p) else PlayerService.play(context, r.id, p) } }
                        })
                }
                Rule(Modifier.padding(top = 10.dp))
                // ---- transcript ----
                // The main points first, when the worker wrote them: they are what one comes back for.
                val points = remember(r.id, transcript) { app.store.summary(r) }
                if (points.isNotBlank()) {
                    Small(stringResource(R.string.summary_title), Modifier.padding(horizontal = rowPadH).padding(top = 16.dp))
                    T(points, Modifier.padding(horizontal = rowPadH, vertical = 8.dp), size = typo.title, align = TextAlign.Start, lineHeightMul = 1.4f)
                    Rule(Modifier.padding(vertical = 8.dp))
                }
                when {
                    transcript.isNotBlank() -> T(transcript, Modifier.padding(horizontal = rowPadH, vertical = 16.dp), size = typo.title, align = TextAlign.Start, lineHeightMul = 1.4f)
                    ProcessService.Live.id == r.id -> Small(ProcessService.phaseLabel(context, ProcessService.Live.phase, ProcessService.Live.percent), Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 3)
                    r.error.isNotBlank() -> Small(r.error, Modifier.padding(horizontal = rowPadH, vertical = 16.dp).noRippleClickable { app.store.update(r.copy(error = "")); ProcessService.kick(context) }, maxLines = 6)
                    r.mode(settings.processing) == "phone" -> Small(stringResource(R.string.transcript_phone_pending), Modifier.padding(horizontal = rowPadH, vertical = 16.dp).noRippleClickable { ProcessService.kick(context) }, maxLines = 4)
                    r.mode(settings.processing) == "off" -> Small(stringResource(R.string.transcript_off), Modifier.padding(horizontal = rowPadH, vertical = 16.dp).noRippleClickable { nav.push(Screen.Settings()) }, maxLines = 4)
                    settings.configured -> Small(stringResource(if (r.uploaded) R.string.transcript_pending else R.string.transcript_not_yet_uploaded), Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 4)
                    else -> Small(stringResource(R.string.transcript_needs_folder), Modifier.padding(horizontal = rowPadH, vertical = 16.dp).noRippleClickable { nav.push(Screen.Settings(setup = true)) }, maxLines = 5)
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(r.title, buildList {
            add(MenuItem(stringResource(R.string.rename)) { renaming = true })
            add(MenuItem(stringResource(R.string.share_audio)) { shareFile(context, app.store.playable(r), "audio/*", r.title) })
            if (transcript.isNotBlank()) add(MenuItem(stringResource(R.string.share_transcript)) { shareText(context, transcript, r.title) })
            if (settings.configured && !r.uploaded) add(MenuItem(stringResource(R.string.upload_now)) { app.sync() })
            if (settings.configured && r.uploaded && r.error.isNotBlank()) add(MenuItem(stringResource(R.string.retry)) { app.store.update(r.copy(error = "")); app.sync() })
            if (r.mode(settings.processing) == "phone" && !r.transcribed) add(MenuItem(stringResource(R.string.transcribe_now)) { app.store.update(r.copy(error = "")); ProcessService.kick(context) })
            // The points, asked for by hand: after two failed goes the queue leaves a recording
            // alone, and this is how it is told to try once more.
            if (settings.summaryOnPhone && r.transcribed && !app.store.summaryFile(r).exists() &&
                r.mode(settings.processing) == "phone" && SummaryModel.isDownloaded(context))
                add(MenuItem(stringResource(R.string.summarise_now)) { app.store.retrySummary(r); ProcessService.kick(context) })
            if (ProcessService.Live.id == r.id) add(MenuItem(stringResource(R.string.stop)) { ProcessService.cancel(context) })
            add(MenuItem(stringResource(R.string.delete), secondary = if (r.uploaded) stringResource(R.string.delete_hint_server) else null) {
                if (isThis) PlayerService.stop(context)
                val s = settings
                scope.launch(Dispatchers.IO) { Sync.deleteRemote(r, s) }
                app.store.delete(r.id); nav.pop()
            })
        }, onDismiss = { menu = false })
        if (renaming) TextPrompt(stringResource(R.string.rename), initial = r.title, onDone = { t ->
            val s = settings
            val new = app.store.rename(r, t)
            scope.launch(Dispatchers.IO) { runCatching { Sync.renameRemote(r, new, s) } }
            renaming = false
        }, onCancel = { renaming = false })
    }
}

fun shareFile(context: android.content.Context, file: java.io.File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(i, null)) }
}

fun shareText(context: android.content.Context, text: String, title: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
    runCatching { context.startActivity(Intent.createChooser(i, null)) }
}

// ---------------------------------------------------------------------------------------------
// Settings: the optional folder, the transcription language, the look
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(nav: Nav, app: App, setup: Boolean = false) {
    val context = LocalContext.current
    val s by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val status by app.status.collectAsState()
    val syncing by app.syncing.collectAsState()
    // The cloud folder is set up as one chain of three questions: server, username, password; then it is tried at once.
    var prompt by remember { mutableStateOf<String?>(if (setup && !s.configured) "server" else null) }
    var draft by remember { mutableStateOf(Triple(s.server, s.username, s.password)) }
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Small(stringResource(if (com.freedomfighter.readersrecorder.BuildConfig.PRIVATE) R.string.cloud_hint else R.string.cloud_hint_export), Modifier.padding(horizontal = rowPadH).padding(top = 16.dp, bottom = 4.dp), maxLines = 8)
                var guide by remember { mutableStateOf(false) }
                if (!s.configured) {
                    TextRow(stringResource(R.string.cloud_setup), secondary = stringResource(R.string.local_only_short)) { draft = Triple("", "", ""); prompt = "server" }
                    TextRow(stringResource(R.string.cloud_how), size = typo.title) { guide = !guide }
                    if (guide) Small(stringResource(if (com.freedomfighter.readersrecorder.BuildConfig.PRIVATE) R.string.cloud_guide else R.string.cloud_guide_export), Modifier.padding(horizontal = rowPadH).padding(bottom = 10.dp), maxLines = 40)
                } else {
                    TextRow(s.server.removePrefix("https://"), secondary = stringResource(R.string.server) + " · " + s.username) { draft = Triple(s.server, s.username, s.password); prompt = "server" }
                    TextRow(s.folder, secondary = stringResource(R.string.folder)) { prompt = "folder" }
                    TextRow(if (syncing) stringResource(R.string.syncing) else status.ifBlank { stringResource(R.string.sync_now) }, secondary = stringResource(R.string.sync_now)) { app.sync() }
                    if (com.freedomfighter.readersrecorder.BuildConfig.PRIVATE)
                        TextRow(if (s.fetchCleaned) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.fetch_cleaned)) { app.prefs.setFetchCleaned(!s.fetchCleaned) }
                    TextRow(stringResource(R.string.forget_server), size = typo.title) { app.prefs.setAccount("", s.folder, "", ""); app.prefs.setProcessing("phone") }
                    TextRow(stringResource(R.string.cloud_how), size = typo.title) { guide = !guide }
                    if (guide) Small(stringResource(if (com.freedomfighter.readersrecorder.BuildConfig.PRIVATE) R.string.cloud_guide else R.string.cloud_guide_export), Modifier.padding(horizontal = rowPadH).padding(bottom = 10.dp), maxLines = 40)
                }
                Rule(Modifier.padding(vertical = 8.dp))
                // Who does the work: the phone itself (whisper.cpp), the computer behind the cloud folder, or nobody.
                // "my computer" exists only in the private build; elsewhere the folder is an export.
                val modes = if (s.configured && com.freedomfighter.readersrecorder.BuildConfig.PRIVATE)
                    listOf("phone", "cloud", "off") else listOf("phone", "off")
                TextRow(stringResource(when (s.processing) { "cloud" -> R.string.processing_cloud; "off" -> R.string.processing_off; else -> R.string.processing_phone }), secondary = stringResource(R.string.processing)) {
                    val next = modes[(modes.indexOf(s.processing).coerceAtLeast(0) + 1) % modes.size]
                    app.prefs.setProcessing(next); if (next == "phone") ProcessService.kick(context)
                }
                if (s.processing == "phone") {
                    val m = Models.byKey(s.model)
                    val downloading by Models.downloading.collectAsState()
                    val state = when { Models.isDownloaded(context, m) -> ""; downloading >= 0 -> " · " + stringResource(R.string.phase_model, downloading); else -> " · " + stringResource(R.string.model_not_yet) }
                    TextRow(stringResource(if (m == Models.HIGH) R.string.quality_high else R.string.quality_normal), secondary = stringResource(R.string.quality) + " · " + m.mb + " MB" + state) {
                        app.prefs.setModel(if (m == Models.HIGH) Models.NORMAL.key else Models.HIGH.key)
                    }
                    TextRow(if (s.cleanOnPhone) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.clean_on_phone)) { app.prefs.setCleanOnPhone(!s.cleanOnPhone) }
                    // The summary needs a model of its own, two gigabytes of it: the row fetches it
                    // on the first tap, and only then can the setting be turned on. A phone too small
                    // to hold it is told so plainly rather than offered a switch that cannot work.
                    val roomy = remember { SummaryModel.phoneCanHoldIt(context) }
                    val fetching by SummaryModel.downloading.collectAsState()
                    val here = remember(fetching) { SummaryModel.isDownloaded(context) }
                    val part = remember(fetching) { SummaryModel.partPercent(context) }
                    val modelFailed by app.modelError.collectAsState()
                    if (!roomy) {
                        TextRow(stringResource(R.string.off), secondary = stringResource(R.string.summary_on_phone) + " · " +
                            stringResource(R.string.summary_needs_memory, SummaryModel.phoneMemoryGb(context)))
                    } else {
                        val summaryState = when {
                            here || fetching >= 0 || part > 0 -> ""
                            else -> " · " + stringResource(R.string.model_not_yet)
                        }
                        TextRow(
                            // While it downloads the progress takes the place of on/off: in the
                            // second line, after the label and the size, it was cut off the row.
                            when {
                                fetching >= 0 -> stringResource(R.string.phase_model, fetching)
                                s.summaryOnPhone && here -> stringResource(R.string.on)
                                else -> stringResource(R.string.off)
                            },
                            // A failed fetch says why, in the place of the label: it used to fail
                            // in silence, with nothing on the screen the user had just tapped.
                            // The failure, or what a cut download already got, takes the whole
                            // line: after the label and the size they were cut off the row.
                            secondary = when {
                                !here && modelFailed.isNotBlank() -> modelFailed
                                !here && fetching < 0 && part > 0 -> stringResource(R.string.model_resume, part)
                                else -> stringResource(R.string.summary_on_phone) + " · " + SummaryModel.MB + " MB" + summaryState
                            },
                        ) {
                            if (here) { app.prefs.setSummaryOnPhone(!s.summaryOnPhone); if (!s.summaryOnPhone) ProcessService.kick(context) }
                            else app.fetchSummaryModel()
                        }
                    }
                }
                val languages = Prefs.languages()
                TextRow(if (s.language.isBlank()) stringResource(R.string.language_auto) else java.util.Locale(s.language).getDisplayLanguage(java.util.Locale.getDefault()), secondary = stringResource(R.string.language)) {
                    app.prefs.setLanguage(languages[(languages.indexOf(s.language).coerceAtLeast(0) + 1) % languages.size])
                }
                TextRow(kindLabel(s.kind), secondary = stringResource(R.string.next_kind)) { app.prefs.setKind(Prefs.KINDS[(Prefs.KINDS.indexOf(s.kind) + 1) % Prefs.KINDS.size]) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(if (colors.isDark) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light), secondary = stringResource(R.string.colours)) { app.prefs.toggleTheme(colors.isDark) }
                TextRow(when (s.textSize) { TextSize.SMALL -> "S"; TextSize.MEDIUM -> "M"; TextSize.LARGE -> "L" }, secondary = stringResource(R.string.text_size)) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                TextRow(when (s.font) { FontChoice.SANS -> "sans-serif"; FontChoice.SERIF -> "serif"; FontChoice.MONO -> "mono" }, secondary = stringResource(R.string.font)) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                TextRow(if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.app_name), secondary = stringResource(R.string.about)) { }
                TextRow(stringResource(R.string.credits)) { }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        when (prompt) {
            "server" -> TextPrompt(stringResource(R.string.ask_server), initial = draft.first, confirm = stringResource(R.string.next),
                onDone = { v -> draft = Triple(v, draft.second, draft.third); prompt = "username" }, onCancel = { prompt = null })
            "username" -> TextPrompt(stringResource(R.string.ask_username), initial = draft.second, confirm = stringResource(R.string.next),
                onDone = { v -> draft = Triple(draft.first, v, draft.third); prompt = "password" }, onCancel = { prompt = null })
            "password" -> TextPrompt(stringResource(R.string.ask_password), initial = draft.third, password = true, confirm = stringResource(R.string.connect),
                onDone = { v -> app.prefs.setAccount(draft.first, s.folder, draft.second, v); app.prefs.setProcessing("cloud"); prompt = null; app.sync() }, onCancel = { prompt = null })
            "folder" -> TextPrompt(stringResource(R.string.folder), initial = s.folder, onDone = { v -> app.prefs.setAccount(s.server, v, s.username, s.password); prompt = null; app.sync() }, onCancel = { prompt = null })
        }
    }
}
