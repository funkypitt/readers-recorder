package com.freedomfighter.readersrecorder.whisper

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A ggml Whisper model from whisper.cpp's Hugging Face repository (quantised, multilingual). */
data class Model(val key: String, val file: String, val mb: Int) {
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
}

/**
 * Two qualities. Normal is Whisper small: quick, decent punctuation. High is large-v3-turbo:
 * clearly better punctuation and accuracy, but its encoder is as heavy as large-v3's, so it is
 * much slower on a phone.
 */
object Models {
    val NORMAL = Model("normal", "ggml-small-q5_1.bin", 190)
    val HIGH = Model("high", "ggml-large-v3-turbo-q5_0.bin", 574)
    val ALL = listOf(NORMAL, HIGH)
    const val DEFAULT = "normal"

    /** Older settings said base / small / medium: medium means high, the rest normal. */
    fun byKey(key: String?): Model = if (key == "high" || key == "medium") HIGH else NORMAL
    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: Model): File = File(dir(ctx), m.file)
    fun isDownloaded(ctx: Context, m: Model): Boolean = file(ctx, m).let { it.exists() && it.length() > 1_000_000 }

    /** 0–100 while a download runs, −1 otherwise. */
    val downloading = MutableStateFlow(-1)

    /** Fetch the model into the app's files; a cut download is thrown away, never kept half-written. */
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
            var done = 0L
            c.inputStream.use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(256 * 1024); var last = -1
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; downloading.value = pct; onProgress(pct) }
                }
            } }
            if (total > 0 && done != total) throw IllegalStateException("model download cut short")
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } finally { downloading.value = -1; tmp.delete() }
    }

    /** Remove model files neither quality uses any more (the old base and medium). */
    fun cleanup(ctx: Context) {
        val keep = ALL.map { it.file }.toSet()
        dir(ctx).listFiles()?.forEach { if (it.name.endsWith(".bin") && it.name !in keep) it.delete() }
    }
}
