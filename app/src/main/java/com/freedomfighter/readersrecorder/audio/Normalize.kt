package com.freedomfighter.readersrecorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The phone's own light-touch cleaning: a 60 Hz high-pass (rumble, handling noise), then a
 * pure constant gain to −19 LUFS (ITU BS.1770 integrated loudness with its two gates), the
 * gain reduced if the sample peak would pass −1 dBFS. No compression, no denoiser: the
 * dynamics stay as recorded, which is what the workstation's nettoyer.py does too.
 */
object Normalize {
    const val TARGET_LUFS = -19.0
    const val PEAK_DB = -1.0

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun run(x: Double): Double { val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y }
    }

    /** Second-order Butterworth high-pass. */
    private fun highPass(rate: Int, hz: Double): Biquad {
        val k = tan(PI * hz / rate); val q = 1 / sqrt(2.0)
        val norm = 1 / (1 + k / q + k * k)
        return Biquad(norm, -2 * norm, norm, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
    }

    /** BS.1770 K-weighting: the high shelf and the RLB high-pass, coefficients for any rate (Mansbridge/Finn design). */
    private fun kWeighting(rate: Int): Pair<Biquad, Biquad> {
        // stage 1: high shelf +4 dB above ~1.5 kHz
        run {
            val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
            val k = tan(PI * f0 / rate); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            val b0 = (vh + vb * k / q + k * k) / a0; val b1 = 2 * (k * k - vh) / a0; val b2 = (vh - vb * k / q + k * k) / a0
            val a1 = 2 * (k * k - 1) / a0; val a2 = (1 - k / q + k * k) / a0
            val shelf = Biquad(b0, b1, b2, a1, a2)
            // stage 2: RLB high-pass at 38 Hz
            val f1 = 38.13547087602444; val q1 = 0.5003270373238773
            val k1 = tan(PI * f1 / rate)
            val hp = Biquad(1.0, -2.0, 1.0, 2 * (k1 * k1 - 1) / (1 + k1 / q1 + k1 * k1), (1 - k1 / q1 + k1 * k1) / (1 + k1 / q1 + k1 * k1))
            // normalise the high-pass gain (its b coefficients above are un-normalised)
            val n = 1 / (1 + k1 / q1 + k1 * k1)
            return Biquad(shelf.b0, shelf.b1, shelf.b2, shelf.a1, shelf.a2) to Biquad(n, -2 * n, n, hp.a1, hp.a2)
        }
    }

    /** Integrated loudness in LUFS, absolute gate −70, relative gate −10 LU; mono treated as a centre channel (0 dB weight). */
    fun integratedLoudness(x: FloatArray, rate: Int): Double {
        val (s1, s2) = kWeighting(rate)
        val block = (0.4 * rate).toInt(); val hop = block / 4
        if (x.size < block) return -70.0
        val y = DoubleArray(x.size) { s2.run(s1.run(x[it].toDouble())) }
        val powers = ArrayList<Double>()
        var start = 0
        while (start + block <= y.size) {
            var acc = 0.0
            for (i in start until start + block) acc += y[i] * y[i]
            powers.add(acc / block); start += hop
        }
        fun lk(p: Double) = -0.691 + 10 * log10(p.coerceAtLeast(1e-12))
        val above = powers.filter { lk(it) > -70 }
        if (above.isEmpty()) return -70.0
        val rel = lk(above.average()) - 10
        val kept = above.filter { lk(it) > rel }
        if (kept.isEmpty()) return -70.0
        return lk(kept.average())
    }

    /** High-pass and gain, in place; returns (loudness before, gain in dB). */
    fun process(pcm: Pcm): Pair<Double, Double> {
        val hp = highPass(pcm.rate, 60.0)
        val x = pcm.samples
        for (i in x.indices) x[i] = hp.run(x[i].toDouble()).toFloat()
        val lufs = integratedLoudness(x, pcm.rate)
        var gainDb = TARGET_LUFS - lufs
        var peak = 0f; for (v in x) peak = maxOf(peak, abs(v))
        val peakDb = 20 * log10(peak.toDouble().coerceAtLeast(1e-6))
        if (peakDb + gainDb > PEAK_DB) gainDb = PEAK_DB - peakDb
        val g = 10.0.pow(gainDb / 20).toFloat()
        for (i in x.indices) x[i] = (x[i] * g).coerceIn(-1f, 1f)
        return lufs to gainDb
    }

    /** Mono PCM → AAC 128 kb/s in an .m4a. */
    fun encodeAac(pcm: Pcm, out: File, onProgress: ((Float) -> Unit)? = null) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val tmp = File(out.parentFile, out.name + ".part")
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        var pos = 0; var inputDone = false; var outputDone = false
        val x = pcm.samples
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!; buf.clear(); buf.order(ByteOrder.nativeOrder())
                    val n = minOf(buf.remaining() / 2, x.size - pos)
                    val sb = buf.asShortBuffer()
                    for (k in 0 until n) sb.put((x[pos + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    val ptsUs = pos * 1_000_000L / pcm.rate
                    if (n == 0) { codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(i, 0, n * 2, ptsUs, 0); pos += n; onProgress?.invoke(pos.toFloat() / x.size) }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.size > 0 && track >= 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release()
        runCatching { muxer.stop() }; muxer.release()
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
    }
}
