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
    val error: String = ""
) {
    /** Server-side stem: date and time, then the title, safe for any file system. */
    val base: String get() {
        val d = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val untitled = title.isBlank() || title.trim() == defaultTitle(createdAt)
        val t = title.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
        return if (untitled) "${d}_$kind" else "${d}_$t"
    }
    val status: String get() = when { transcribed -> "transcribed"; cleaned -> "cleaned"; uploaded -> "uploaded"; else -> "phone" }

    companion object {
        fun defaultTitle(createdAt: Long): String =
            Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")).lowercase()
    }
}

class Store(context: Context) {
    val dir: File = File(context.filesDir, "recordings").apply { mkdirs() }
    private val index = File(context.filesDir, "recordings.json")
    private val _recordings = MutableStateFlow(load())
    /** Newest first. */
    val recordings: StateFlow<List<Recording>> = _recordings
    var onChange: (() -> Unit)? = null

    private fun load(): List<Recording> = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Recording(o.getString("id"), o.optString("title"), o.getLong("createdAt"), o.optLong("durationMs"), o.optString("kind", "memo"),
                o.optBoolean("uploaded"), o.optBoolean("cleaned"), o.optBoolean("transcribed"), o.optString("error"))
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<Recording>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("title", r.title).put("createdAt", r.createdAt).put("durationMs", r.durationMs).put("kind", r.kind)
                .put("uploaded", r.uploaded).put("cleaned", r.cleaned).put("transcribed", r.transcribed).put("error", r.error))
        }
        val tmp = File(index.parentFile, "recordings.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) { index.writeText(arr.toString()); tmp.delete() }
        _recordings.value = list.sortedByDescending { it.createdAt }
        onChange?.invoke()
    }

    fun get(id: String): Recording? = _recordings.value.firstOrNull { it.id == id }
    fun audio(r: Recording): File = File(dir, "${r.id}.m4a")
    fun cleanAudio(r: Recording): File = File(dir, "${r.id}.clean.mp3")
    /** What plays: the cleaned copy when it is here, else the original. */
    fun playable(r: Recording): File = cleanAudio(r).takeIf { r.cleaned && it.exists() } ?: audio(r)
    fun transcriptFile(r: Recording): File = File(dir, "${r.id}.txt")
    fun transcript(r: Recording): String = transcriptFile(r).takeIf { it.exists() }?.readText() ?: ""

    fun add(r: Recording) = save(_recordings.value.filterNot { it.id == r.id } + r)
    fun update(r: Recording) = save(_recordings.value.map { if (it.id == r.id) r else it })
    fun update(id: String, f: (Recording) -> Recording) { get(id)?.let { update(f(it)) } }
    fun delete(id: String) {
        get(id)?.let { r -> audio(r).delete(); cleanAudio(r).delete(); transcriptFile(r).delete() }
        save(_recordings.value.filterNot { it.id == id })
    }
    fun setTranscript(r: Recording, text: String) { transcriptFile(r).writeText(text); update(r.copy(transcribed = true, error = "")) }
}
