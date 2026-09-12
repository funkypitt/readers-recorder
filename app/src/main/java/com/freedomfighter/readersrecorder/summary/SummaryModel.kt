package com.freedomfighter.readersrecorder.summary

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The language model that writes the summary, fetched once and kept in the app's files.
 *
 * Only one choice is offered, on purpose. Measured on 2026-09-12 on the same interview: a
 * 1.5-billion model misses the subject entirely — it turned the psychiatrist's critique of
 * prescribing into a description of it, and promoted the closing thanks to a main point — while
 * a 3-billion model got the thesis, the argument about the industry, and the lasting side
 * effects. An 8-billion one would be three times slower and hold five gigabytes, which a phone
 * cannot spare. Offering a model that produces a plausible but wrong summary would be worse
 * than offering none, so this is the only size on the menu.
 */
object SummaryModel {
    const val FILE = "qwen2.5-3b-instruct-q4_k_m.gguf"
    const val URL_STR = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/$FILE"
    const val MB = 1930                     // 1.93 GB as the server reports it
    private const val NEEDED_BYTES = 2_100_000_000L

    /**
     * A phone small enough that loading two gigabytes of weights would get the application killed
     * mid-answer is refused the option outright, rather than offered a feature that cannot work.
     * Measured on the emulator: three gigabytes of system memory is not enough — the model loads,
     * then the process dies and the queue starts again.
     */
    private const val MIN_TOTAL_RAM = 5_000_000_000L    // a "6 GB" phone reports about 5.6
    private const val MIN_FREE_RAM = 900_000_000L       // right before loading

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context): File = File(dir(ctx), FILE)
    fun isDownloaded(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 1_500_000_000L }

    private fun memory(ctx: Context): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }

    /** Whether this phone can hold the model at all. Checked before the setting is even offered. */
    fun phoneCanHoldIt(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return !am.isLowRamDevice && memory(ctx).totalMem >= MIN_TOTAL_RAM
    }

    /** Total system memory in whole gigabytes, for the sentence that explains a refusal. */
    fun phoneMemoryGb(ctx: Context): Int = ((memory(ctx).totalMem + 500_000_000L) / 1_000_000_000L).toInt()

    /** Whether there is room right now: another application may have taken it since. */
    fun roomRightNow(ctx: Context): Boolean = memory(ctx).let { !it.lowMemory && it.availMem >= MIN_FREE_RAM }

    /** 0–100 while the download runs, −1 otherwise. */
    val downloading = MutableStateFlow(-1)

    /** Delete it to get the space back; the summary setting then simply offers to fetch it again. */
    fun remove(ctx: Context): Boolean = file(ctx).delete()

    /**
     * Fetch the model. A cut download is thrown away rather than kept half-written — a truncated
     * GGUF loads for a while and then fails deep inside the native code, where the error means
     * nothing to anyone.
     */
    fun download(ctx: Context, onProgress: (Int) -> Unit = {}) {
        val target = file(ctx)
        if (isDownloaded(ctx)) return
        if (dir(ctx).usableSpace < NEEDED_BYTES)
            throw IllegalStateException("not enough space: ${NEEDED_BYTES / 1_000_000} MB needed")

        val tmp = File(target.parentFile, "$FILE.part")
        downloading.value = 0
        try {
            val c = URL(URL_STR).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "readers-recorder")
            if (c.responseCode >= 400) throw IllegalStateException("summary model: HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(512 * 1024); var last = -1
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; downloading.value = pct; onProgress(pct) }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("summary model: download cut short")
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } finally { downloading.value = -1; tmp.delete() }
    }
}
