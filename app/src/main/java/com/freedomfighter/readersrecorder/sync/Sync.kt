package com.freedomfighter.readersrecorder.sync

import com.freedomfighter.readersrecorder.BuildConfig
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
                putPhoneClean(dav, folder, store, r)
                store.update(r.copy(uploaded = true, error = "")); up++
            } catch (e: Exception) { store.update(r.copy(error = e.message ?: "upload failed")) }
        }
        // ---- the export catches up ----
        // A recording is usually uploaded before the phone has finished transcribing and cleaning it,
        // so what came later is sent on a following sync. The server listing decides, which keeps this
        // stateless: whatever is missing up there and ready down here goes up.
        val late = store.recordings.value.filter { it.uploaded && (it.transcribed || it.cleaned) }
        if (late.isNotEmpty()) {
            val names = runCatching { dav.list(folder).map { it.name }.toSet() }.getOrNull() ?: emptySet()
            for (r in late) runCatching {
                if (r.transcribed && r.base + ".txt" !in names) dav.put(folder + encodeSegment(r.base + ".txt"), store.transcript(r))
                if (r.base + "_nettoye.m4a" !in names) putPhoneClean(dav, folder, store, r)
            }
        }
        // ---- down ----
        // Only the private build expects anything back: the workstation's transcript, cleaned audio
        // and summary. The public build's folder is an export and nothing is ever fetched from it.
        // A summary is written after the transcript, and can appear long after it: a recording
        // stays on the list until its points are here, otherwise they would never be fetched.
        val waiting = if (!BuildConfig.PRIVATE) emptyList() else
            store.recordings.value.filter {
                it.mode(s.processing) == "cloud" && it.uploaded &&
                    (!it.transcribed || (s.fetchCleaned && !it.cleaned) || !store.summaryFile(it).exists())
            }
        if (waiting.isNotEmpty()) {
            val names = dav.list(folder).map { it.name }.toSet()
            for (r in waiting) {
                try {
                    if (!r.transcribed && r.base + ".txt" in names) {
                        store.setTranscript(r, dav.get(folder + encodeSegment(r.base + ".txt"))); tr++
                    }
                    if (!store.summaryFile(r).exists() && r.base + ".resume.txt" in names) {
                        store.setSummary(r, dav.get(folder + encodeSegment(r.base + ".resume.txt")))
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

    /** The listening copy the phone itself made, beside the original as `<base>_nettoye.m4a`. */
    private fun putPhoneClean(dav: WebDav, folder: String, store: Store, r: Recording) {
        val clean = store.cleanTarget(r, "m4a")
        if (clean.exists()) dav.putFile(folder + encodeSegment(r.base + "_nettoye.m4a"), clean, "audio/mp4")
    }

    fun meta(r: Recording, s: Settings): JSONObject = JSONObject()
        .put("title", r.title).put("kind", r.kind).put("createdAt", r.createdAt).put("durationMs", r.durationMs)
        .put("language", s.language).put("app", "readers-recorder")

    /** Remove the recording's files from the server (best effort). */
    fun deleteRemote(r: Recording, s: Settings) {
        if (!s.configured) return
        val dav = WebDav(s.username, s.password)
        for (suffix in listOf(".m4a", ".json", ".txt", "_nettoye.m4a", "_nettoye.mp3", "_nettoyage.json", ".segments.json", ".resume.txt", ".error.txt")) runCatching { dav.delete(s.folderUrl + encodeSegment(r.base + suffix)) }
    }

    /** After a rename the server files must follow the new base. */
    fun renameRemote(old: Recording, new: Recording, s: Settings) {
        if (!s.configured || old.base == new.base || !old.uploaded) return
        val dav = WebDav(s.username, s.password)
        for (suffix in listOf(".m4a", ".json", ".txt", "_nettoye.m4a", "_nettoye.mp3", "_nettoyage.json", ".segments.json", ".resume.txt")) runCatching { dav.move(s.folderUrl + encodeSegment(old.base + suffix), s.folderUrl + encodeSegment(new.base + suffix)) }
    }
}
