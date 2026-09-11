package com.freedomfighter.readersrecorder.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Decoded audio: mono float samples in −1..1 at [rate] Hz. */
class Pcm(val samples: FloatArray, val rate: Int) {
    val seconds: Float get() = samples.size.toFloat() / rate
}

object Decode {
    /** Any file MediaCodec can read → mono float PCM at its own rate. Stereo is averaged. */
    fun toPcm(file: File, onProgress: ((Float) -> Unit)? = null): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("no audio track")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = buf.asFloatBuffer(); val n = fb.remaining() / channels
                        for (k in 0 until n) { var s = 0f; for (c in 0 until channels) s += fb.get(); out.add(s / channels) }
                    } else {
                        val sb = buf.asShortBuffer(); val n = sb.remaining() / channels
                        for (k in 0 until n) { var s = 0f; for (c in 0 until channels) s += sb.get() / 32768f; out.add(s / channels) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (durationUs > 0 && info.presentationTimeUs > 0) onProgress?.invoke((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        return Pcm(out.toArray(), rate)
    }
}

/** A growable float array without boxing. */
class FloatList(initial: Int = 1 shl 20) {
    private var a = FloatArray(initial); var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray(): FloatArray = a.copyOf(size)
}

object Resample {
    /**
     * Rate change by a windowed-sinc low-pass at the lower Nyquist, evaluated at each output
     * instant — plain and exact enough for speech (16 taps a side, Hann window).
     */
    fun to(pcm: Pcm, rate: Int): Pcm {
        if (pcm.rate == rate) return pcm
        val ratio = pcm.rate.toDouble() / rate
        val cutoff = minOf(pcm.rate, rate) / 2.0 * 0.92 / pcm.rate   // cycles per input sample
        val taps = 16
        val n = (pcm.samples.size / ratio).toInt()
        val out = FloatArray(n)
        val x = pcm.samples
        for (i in 0 until n) {
            val center = i * ratio
            val c0 = center.toInt()
            var acc = 0.0; var wsum = 0.0
            for (k in c0 - taps..c0 + taps) {
                if (k < 0 || k >= x.size) continue
                val d = k - center
                val w = if (d == 0.0) 2 * cutoff else sin(2 * PI * cutoff * d) / (PI * d)
                val hann = 0.5 + 0.5 * kotlin.math.cos(PI * d / (taps + 1))
                acc += x[k] * w * hann; wsum += w * hann
            }
            out[i] = (if (wsum != 0.0) acc / wsum * 2 * cutoff / (2 * cutoff) else 0.0).toFloat()
        }
        // the kernel sums to ~1 by construction; guard the gain anyway
        var g = 0.0; for (k in -taps..taps) { val d = k.toDouble(); val w = if (d == 0.0) 2 * cutoff else sin(2 * PI * cutoff * d) / (PI * d); g += w * (0.5 + 0.5 * kotlin.math.cos(PI * d / (taps + 1))) }
        if (g != 0.0 && kotlin.math.abs(g - 1.0) > 1e-3) for (i in out.indices) out[i] = (out[i] / g).toFloat()
        return Pcm(out, rate)
    }
}


/**
 * Decode any audio file MediaCodec reads, mono, at its own rate, handed over in pieces of
 * about [seconds] with where each starts (ms). Memory stays flat whatever the length: a
 * two-hour lecture never sits in memory whole. Stops as soon as [onChunk] returns false.
 */
object Decoder {
    fun chunks(ctx: Context, uri: Uri, seconds: Int, onChunk: (FloatArray, Int, Long) -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        if (track == null) { ex.release(); error("no audio track") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        var acc = FloatList()
        var startMs = 0L
        var keepGoing = true
        fun flush() {
            if (acc.size == 0 || !keepGoing) return
            val ms = acc.size * 1000L / rate
            val out = acc.toArray()
            acc = FloatList()
            keepGoing = onChunk(out, rate, startMs)
            startMs += ms
        }
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && keepGoing) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = buf.asFloatBuffer(); val n = fb.remaining() / channels
                        for (k in 0 until n) { var v = 0f; for (c in 0 until channels) v += fb.get(); acc.add(v / channels) }
                    } else {
                        val sb = buf.asShortBuffer(); val n = sb.remaining() / channels
                        for (k in 0 until n) { var v = 0f; for (c in 0 until channels) v += sb.get() / 32768f; acc.add(v / channels) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (acc.size >= seconds * rate) flush()
                }
            }
            flush()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }
}

/** The same pieces, resampled to the 16 kHz Whisper wants. */
object Decode16k {
    fun chunks(ctx: Context, uri: Uri, seconds: Int, onChunk: (FloatArray, Long) -> Boolean) =
        Decoder.chunks(ctx, uri, seconds) { x, rate, startMs -> onChunk(Resample.to(Pcm(x, rate), 16_000).samples, startMs) }
}
