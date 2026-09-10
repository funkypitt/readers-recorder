package com.freedomfighter.readersrecorder.sync

import com.freedomfighter.readersrecorder.data.Recording
import com.freedomfighter.readersrecorder.data.Settings
import com.freedomfighter.readersrecorder.data.Store
import com.freedomfighter.readersrecorder.data.encodeSegment
import org.json.JSONObject

/**
 * The phone's side of the pipeline. Up: every finished recording goes to the WebDAV
 * folder as `<base>.m4a` with a `<base>.json` beside it (title, kind, language, duration).
 * Down: when the workstation's worker has left `<base>.txt` (the transcript) and
 * `<base>_nettoye.mp3` (the cleaned, loudness-normalised audio), they are fetched.
 * Nothing is ever deleted on the server by a sync; deleting a recording is explicit.
 */
object Sync {
    data class Result(val uploaded: Int, val transcripts: Int, val cleaned: Int)

    fun run(store: Store, s: Settings): Result {
        val dav = WebDav(s.username, s.password)
        val folder = s.folderUrl
        dav.mkcol(folder)
        var up = 0; var tr = 0; var cl = 0
        // ---- up ----
        for (r in store.recordings.value.filter { !it.uploaded && it.durationMs > 0 }) {
            val f = store.audio(r)
            if (!f.exists()) continue
            try {
                dav.putFile(folder + encodeSegment(r.base + ".m4a"), f, "audio/mp4")
                dav.put(folder + encodeSegment(r.base + ".json"), meta(r, s).toString(2))
                // Transcribed here already: send the text along, so the workstation leaves this one alone.
                if (r.transcribed) dav.put(folder + encodeSegment(r.base + ".txt"), store.transcript(r))
                store.update(r.copy(uploaded = true, error = "")); up++
            } catch (e: Exception) { store.update(r.copy(error = e.message ?: "upload failed")) }
        }
        // ---- down ----
        val waiting = store.recordings.value.filter { it.mode(s.processing) == "cloud" && it.uploaded && (!it.transcribed || (s.fetchCleaned && !it.cleaned)) }
        if (waiting.isNotEmpty()) {
            val names = dav.list(folder).map { it.name }.toSet()
            for (r in waiting) {
                try {
                    if (!r.transcribed && r.base + ".txt" in names) {
                        store.setTranscript(r, dav.get(folder + encodeSegment(r.base + ".txt"))); tr++
                    }
                    if (s.fetchCleaned && !r.cleaned && r.base + "_nettoye.mp3" in names) {
                        if (dav.download(folder + encodeSegment(r.base + "_nettoye.mp3"), store.cleanTarget(r, "mp3"))) { store.update(r.id) { it.copy(cleaned = true) }; cl++ }
                    }
                    if (!r.transcribed && r.base + ".error.txt" in names) {
                        store.update(r.id) { it.copy(error = dav.get(folder + encodeSegment(r.base + ".error.txt")).lines().firstOrNull()?.take(120) ?: "worker error") }
                    }
                } catch (e: Exception) { store.update(r.id) { it.copy(error = e.message ?: "sync failed") } }
            }
        }
        return Result(up, tr, cl)
    }

    fun meta(r: Recording, s: Settings): JSONObject = JSONObject()
        .put("title", r.title).put("kind", r.kind).put("createdAt", r.createdAt).put("durationMs", r.durationMs)
        .put("language", s.language).put("app", "readers-recorder")

    /** Remove the recording's files from the server (best effort). */
    fun deleteRemote(r: Recording, s: Settings) {
        if (!s.configured) return
        val dav = WebDav(s.username, s.password)
        for (suffix in listOf(".m4a", ".json", ".txt", "_nettoye.mp3", "_nettoyage.json", ".segments.json", ".error.txt")) runCatching { dav.delete(s.folderUrl + encodeSegment(r.base + suffix)) }
    }

    /** After a rename the server files must follow the new base. */
    fun renameRemote(old: Recording, new: Recording, s: Settings) {
        if (!s.configured || old.base == new.base || !old.uploaded) return
        val dav = WebDav(s.username, s.password)
        for (suffix in listOf(".m4a", ".json", ".txt", "_nettoye.mp3", "_nettoyage.json", ".segments.json")) runCatching { dav.move(s.folderUrl + encodeSegment(old.base + suffix), s.folderUrl + encodeSegment(new.base + suffix)) }
    }
}
