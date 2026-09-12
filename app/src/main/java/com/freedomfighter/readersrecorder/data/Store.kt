package com.freedomfighter.readersrecorder.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One recording. The audio lives in `files/recordings/<id>.m4a`; once the workstation
 * has cleaned it, `<id>.clean.mp3` sits beside it and is what plays. The transcript is
 * `<id>.txt`. On the server everything shares [base]: `<base>.m4a`, `<base>.json` (what the
 * phone knows), `<base>_nettoye.mp3` and `<base>.txt` (what the worker adds).
 */
data class Recording(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val kind: String,
    val uploaded: Boolean,
    val cleaned: Boolean,
    val transcribed: Boolean,
    /** Last sync error for this recording, if any. */
    val error: String = "",
    /** Who transcribes THIS one: "phone", "cloud", or "" = whatever the settings say. */
    val via: String = "",
    /** True once the user typed a title; until then the title is automatic (date, then the transcript's first words). */
    val named: Boolean = false,
    /**
     * How many times the phone has begun to write the main points of this recording. Counted
     * before the attempt, never after: a summary that ends by killing the application would
     * otherwise be started again for ever — which is exactly what happened the first time this
     * was built. Past [MAX_SUMMARY_TRIES] the recording is simply left alone.
     */
    val summaryTries: Int = 0
) {
    /** The date and time, shown small under the title. */
    val whenLabel: String get() = defaultTitle(createdAt)
    /** The date line's prefix — nothing while the title itself is still the date. */
    val whenPrefix: String get() = if (title.trim() == whenLabel) "" else "$whenLabel · "
    /** The effective transcriber, given the settings' default. */
    fun mode(default: String): String = via.ifBlank { default }
    /** Server-side stem: date and time, then the title, safe for any file system. */
    val base: String get() {
        val d = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        // Only a title typed by hand goes into the file name: automatic ones change when the transcript arrives.
        val t = title.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
        return if (!named || t.isEmpty()) "${d}_$kind" else "${d}_$t"
    }
    val status: String get() = when { transcribed -> "transcribed"; cleaned -> "cleaned"; uploaded -> "uploaded"; else -> "phone" }

    companion object {
        /** Two goes at the summary, then never again unless it is asked for by hand. */
        const val MAX_SUMMARY_TRIES = 2
        fun defaultTitle(createdAt: Long): String =
            Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")).lowercase()

        /** The transcript's first words as a title (about 40 characters, cut at a word), or null when there is nothing usable. */
        fun titleFrom(transcript: String): String? {
            val text = transcript.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty() || text.startsWith("(")) return null
            if (text.length <= 44) return text.trimEnd('.', ',', ';', ':', '!', '?', ' ')
            val cut = text.take(44)
            val atWord = cut.lastIndexOf(' ').takeIf { it > 20 } ?: 44
            return cut.take(atWord).trimEnd('.', ',', ';', ':', '!', '?', ' ', '-', '—') + "…"
        }
    }
}

class Store(context: Context) {
    val dir: File = File(context.filesDir, "recordings").apply { mkdirs() }
    private val index = File(context.filesDir, "recordings.json")
    private val _recordings = MutableStateFlow(load().map { r ->
        if (!r.named && r.transcribed && r.title == Recording.defaultTitle(r.createdAt)) r.copy(title = Recording.titleFrom(File(dir, "${r.id}.txt").takeIf { it.exists() }?.readText() ?: "") ?: r.title) else r
    })
    /** Newest first. */
    val recordings: StateFlow<List<Recording>> = _recordings
    var onChange: (() -> Unit)? = null

    private fun load(): List<Recording> = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Recording(o.getString("id"), o.optString("title"), o.getLong("createdAt"), o.optLong("durationMs"), o.optString("kind", "memo"),
                o.optBoolean("uploaded"), o.optBoolean("cleaned"), o.optBoolean("transcribed"), o.optString("error"), o.optString("via"),
                // older entries: a title that is not the date was typed by hand
                o.optBoolean("named", o.optString("title").let { it.isNotBlank() && it != Recording.defaultTitle(o.getLong("createdAt")) }),
                o.optInt("summaryTries"))
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<Recording>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("title", r.title).put("createdAt", r.createdAt).put("durationMs", r.durationMs).put("kind", r.kind)
                .put("uploaded", r.uploaded).put("cleaned", r.cleaned).put("transcribed", r.transcribed).put("error", r.error).put("via", r.via).put("named", r.named).put("summaryTries", r.summaryTries))
        }
        val tmp = File(index.parentFile, "recordings.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) { index.writeText(arr.toString()); tmp.delete() }
        _recordings.value = list.sortedByDescending { it.createdAt }
        onChange?.invoke()
    }

    fun get(id: String): Recording? = _recordings.value.firstOrNull { it.id == id }
    fun audio(r: Recording): File = File(dir, "${r.id}.m4a")
    fun cleanAudio(r: Recording): File = listOf("m4a", "mp3").map { File(dir, "${r.id}.clean.$it") }.firstOrNull { it.exists() } ?: File(dir, "${r.id}.clean.mp3")
    fun cleanTarget(r: Recording, ext: String): File = File(dir, "${r.id}.clean.$ext")
    fun segmentsFile(r: Recording): File = File(dir, "${r.id}.segments.json")
    fun writeSegments(r: Recording, segments: List<com.freedomfighter.readersrecorder.whisper.Segment>, language: String) {
        val arr = JSONArray(); segments.forEach { arr.put(JSONObject().put("start", it.startMs / 1000.0).put("end", it.endMs / 1000.0).put("text", it.text)) }
        segmentsFile(r).writeText(JSONObject().put("language", language).put("segments", arr).toString())
    }
    /** What plays: the cleaned copy when it is here, else the original. */
    fun playable(r: Recording): File = cleanAudio(r).takeIf { r.cleaned && it.exists() } ?: audio(r)
    fun transcriptFile(r: Recording): File = File(dir, "${r.id}.txt")
    fun transcript(r: Recording): String = transcriptFile(r).takeIf { it.exists() }?.readText() ?: ""
    /** The summary lives beside the transcript; its presence is the only record that it exists,
     *  so an older index needs no migration and a deleted file simply means "no summary". */
    fun summaryFile(r: Recording): File = File(dir, "${r.id}.resume.txt")
    fun summary(r: Recording): String = summaryFile(r).takeIf { it.exists() }?.readText() ?: ""
    fun setSummary(r: Recording, text: String) { summaryFile(r).writeText(text) }
    /** Count the attempt before making it, so a summary that kills the application still counts. */
    fun countSummaryTry(r: Recording) = update(r.id) { it.copy(summaryTries = it.summaryTries + 1) }
    /** Ask for the points again on a recording that has used up its goes. */
    fun retrySummary(r: Recording) = update(r.id) { it.copy(summaryTries = 0) }

    fun add(r: Recording) = save(_recordings.value.filterNot { it.id == r.id } + r)
    fun update(r: Recording) = save(_recordings.value.map { if (it.id == r.id) r else it })
    fun update(id: String, f: (Recording) -> Recording) { get(id)?.let { update(f(it)) } }
    fun delete(id: String) {
        get(id)?.let { r -> audio(r).delete(); cleanTarget(r, "m4a").delete(); cleanTarget(r, "mp3").delete(); transcriptFile(r).delete(); segmentsFile(r).delete(); summaryFile(r).delete() }
        save(_recordings.value.filterNot { it.id == id })
    }
    /** Save the transcript; an automatic title becomes its first words. */
    fun setTranscript(r: Recording, text: String) {
        transcriptFile(r).writeText(text)
        val auto = if (r.named) r.title else Recording.titleFrom(text) ?: Recording.defaultTitle(r.createdAt)
        update(r.copy(transcribed = true, error = "", title = auto))
    }

    /** A title typed by hand; blank goes back to automatic. */
    fun rename(r: Recording, title: String): Recording {
        val t = title.trim()
        val new = if (t.isEmpty()) r.copy(named = false, title = Recording.titleFrom(transcript(r)) ?: Recording.defaultTitle(r.createdAt)) else r.copy(named = true, title = t)
        update(new); return new
    }
}
