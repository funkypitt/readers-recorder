package com.freedomfighter.readersrecorder.whisper

import android.os.Build
import java.io.File

/** The JNI surface of the vendored whisper.cpp. One transcription at a time (the context is not thread-safe). */
object WhisperLib {
    init {
        // On arm64 a second copy compiled with fp16 arithmetic is much faster; use it when the CPU has it.
        val fp16 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && runCatching { File("/proc/cpuinfo").readText().contains("fphp") }.getOrDefault(false)
        if (fp16) runCatching { System.loadLibrary("whisper_v8fp16_va") }.onFailure { System.loadLibrary("whisper") } else System.loadLibrary("whisper")
    }
    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun fullTranscribe(ptr: Long, threads: Int, language: String?, prompt: String?, audio: FloatArray): Int
    @JvmStatic external fun cancel()
    @JvmStatic external fun progress(): Int
    @JvmStatic external fun segmentCount(ptr: Long): Int
    @JvmStatic external fun segmentText(ptr: Long, i: Int): String
    @JvmStatic external fun segmentT0(ptr: Long, i: Int): Long
    @JvmStatic external fun segmentT1(ptr: Long, i: Int): Long
    @JvmStatic external fun detectedLanguage(ptr: Long): String
    @JvmStatic external fun systemInfo(): String
}

data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** Threads: the big cores only, at least two. */
fun preferredThreads(): Int = runCatching {
    val freqs = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
        File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt()
    }
    val min = freqs.min()
    freqs.count { it > min }.takeIf { it >= 2 } ?: (freqs.size - 2).coerceAtLeast(2)
}.getOrDefault((Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2))

/** Run whisper over 16 kHz mono PCM; null when cancelled. */
fun transcribe(model: File, pcm16k: FloatArray, language: String?, prompt: String? = null): Pair<List<Segment>, String>? {
    val ptr = WhisperLib.initContext(model.absolutePath)
    require(ptr != 0L) { "cannot load ${model.name}" }
    try {
        val rc = WhisperLib.fullTranscribe(ptr, preferredThreads(), language, prompt, pcm16k)
        if (rc == 1) return null
        require(rc == 0) { "whisper failed" }
        val n = WhisperLib.segmentCount(ptr)
        val segs = (0 until n).map { Segment(WhisperLib.segmentT0(ptr, it) * 10, WhisperLib.segmentT1(ptr, it) * 10, WhisperLib.segmentText(ptr, it).trim()) }
        return segs to WhisperLib.detectedLanguage(ptr)
    } finally { WhisperLib.freeContext(ptr) }
}


/** A model loaded once and used for every piece of a long recording. One piece at a time. */
class WhisperSession(model: File) : AutoCloseable {
    private val ptr = WhisperLib.initContext(model.absolutePath).also { require(it != 0L) { "cannot load ${model.name}" } }

    /** Segments with times inside the piece; null when cancelled. [onProgress] gets 0–100 while it runs. */
    fun run(pcm16k: FloatArray, language: String?, prompt: String?, onProgress: (Int) -> Unit): List<Segment>? {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val poll = Thread {
            while (!done.get()) {
                onProgress(WhisperLib.progress())
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        try {
            val rc = WhisperLib.fullTranscribe(ptr, preferredThreads(), language, prompt, pcm16k)
            if (rc == 1) return null
            require(rc == 0) { "whisper failed" }
            val n = WhisperLib.segmentCount(ptr)
            return (0 until n).map { Segment(WhisperLib.segmentT0(ptr, it) * 10, WhisperLib.segmentT1(ptr, it) * 10, WhisperLib.segmentText(ptr, it).trim()) }
        } finally { done.set(true); poll.interrupt() }
    }

    /** The language whisper found in the last piece (useful when it was left to detection). */
    fun language(): String = WhisperLib.detectedLanguage(ptr)

    override fun close() = WhisperLib.freeContext(ptr)
}
