package com.freedomfighter.readersrecorder.whisper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The ggml models the phone can run, from whisper.cpp's Hugging Face repository (quantised, multilingual). */
data class Model(val key: String, val label: String, val file: String, val mb: Int) {
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
}

object Models {
    val ALL = listOf(
        Model("base", "base", "ggml-base-q5_1.bin", 57),
        Model("small", "small", "ggml-small-q5_1.bin", 190),
        Model("medium", "medium", "ggml-medium-q5_0.bin", 539)
    )
    const val DEFAULT = "small"
    fun byKey(key: String?): Model = ALL.firstOrNull { it.key == key } ?: ALL.first { it.key == DEFAULT }
    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: Model): File = File(dir(ctx), m.file)
    fun isDownloaded(ctx: Context, m: Model): Boolean = file(ctx, m).let { it.exists() && it.length() > 1_000_000 }

    /** 0–100 while a download runs, −1 otherwise. */
    val downloading = MutableStateFlow(-1)

    /** Fetch the model into the app's files; resumable by re-running (the .part is restarted). */
    fun download(ctx: Context, m: Model, onProgress: (Int) -> Unit = {}) {
        val target = file(ctx, m)
        if (isDownloaded(ctx, m)) return
        val tmp = File(target.parentFile, target.name + ".part")
        downloading.value = 0
        try {
            val c = URL(m.url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "readers-recorder")
            if (c.responseCode >= 400) throw IllegalStateException("model download: HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(256 * 1024); var done = 0L; var last = -1
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; downloading.value = pct; onProgress(pct) }
                }
            } }
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } finally { downloading.value = -1; tmp.delete() }
    }
}
