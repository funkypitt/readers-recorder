package com.freedomfighter.readersrecorder.summary

import android.os.Build
import com.freedomfighter.readersrecorder.whisper.preferredThreads
import java.io.File

/**
 * The JNI surface of the vendored llama.cpp. One model loaded at a time, one answer at a time
 * (the context is not thread-safe), and nothing to configure: the model is only ever asked for
 * a list of points, so there is no sampling setting to get wrong.
 */
object LlamaLib {
    init {
        // Same rule as whisper: on arm64 a second copy compiled with fp16 arithmetic is much
        // faster, and the loader falls back when the processor does not have it.
        val fp16 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
            runCatching { File("/proc/cpuinfo").readText().contains("fphp") }.getOrDefault(false)
        if (fp16) runCatching { System.loadLibrary("llama_v8fp16_va") }.onFailure { System.loadLibrary("llama") }
        else System.loadLibrary("llama")
    }

    @JvmStatic external fun initContext(modelPath: String, threads: Int, nCtx: Int): Long
    @JvmStatic external fun freeContext(ptr: Long)
    /** One prompt, one answer; "" when cancelled, when the prompt does not fit, or on failure. */
    @JvmStatic external fun generate(ptr: Long, prompt: String, maxTokens: Int): String
    @JvmStatic external fun cancel()
    @JvmStatic external fun progress(): Int
    /** Why the last call gave nothing, in one short sentence, or "" when nothing went wrong. */
    @JvmStatic external fun lastError(): String
}

/**
 * A model loaded once and used for every piece of a long transcript, then closed.
 * Failures are returned, never thrown: a recording must never lose its transcript because
 * the summary went wrong.
 */
class LlamaSession(model: File, private val nCtx: Int = CONTEXT) : AutoCloseable {
    private val ptr = LlamaLib.initContext(model.absolutePath, preferredThreads(), nCtx)

    val loaded: Boolean get() = ptr != 0L

    /** The answer, or null when cancelled or impossible. [onProgress] gets 0–100 while it runs. */
    fun run(prompt: String, maxTokens: Int = 512, onProgress: (Int) -> Unit = {}): String? {
        if (ptr == 0L) return null
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val poll = Thread {
            while (!done.get()) {
                onProgress(LlamaLib.progress())
                Thread.sleep(300)
            }
        }.apply { isDaemon = true; start() }
        return try {
            LlamaLib.generate(ptr, prompt, maxTokens).takeIf { it.isNotBlank() }
        } finally {
            done.set(true)
            runCatching { poll.join(500) }
        }
    }

    /** Stops the answer being written; the session stays usable for the next piece. */
    fun cancel() = LlamaLib.cancel()

    /** Why the last answer was empty, for the log — never shown to anyone as it is. */
    fun why(): String = runCatching { LlamaLib.lastError() }.getOrDefault("")

    override fun close() {
        if (ptr != 0L) LlamaLib.freeContext(ptr)
    }

    companion object {
        /** Enough for a piece of transcript and its answer, small enough to leave the phone alone. */
        const val CONTEXT = 4096
    }
}
