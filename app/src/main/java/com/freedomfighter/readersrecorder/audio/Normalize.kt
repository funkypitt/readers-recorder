package com.freedomfighter.readersrecorder.audio

import android.content.Context
import com.freedomfighter.readers.speech.audio.Decoder
import com.freedomfighter.readers.speech.audio.Pcm
import com.freedomfighter.readers.speech.audio.Resample
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The phone's own light-touch cleaning: a 60 Hz high-pass (rumble, handling noise), the voices
 * brought closer together when some are far from the phone ([Leveler]: one gain per turn, held
 * flat over the words and moved only in the pauses), then a constant gain to −19 LUFS (ITU
 * BS.1770 integrated loudness with its two gates), reduced if the sample peak would pass
 * −1 dBFS. No compressor, no denoiser.
 *
 * Streamed in two passes over the file, one minute at a time, so a two-hour lecture never
 * sits in memory: the first measures, the second filters, applies the gains and encodes.
 */
object Normalize {
    const val TARGET_LUFS = -19.0
    const val PEAK_DB = -1.0
    private const val PIECE_SECONDS = 60

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

    /** BS.1770 K-weighting stage 1: the high shelf, +4 dB above ~1.7 kHz, for any rate. */
    private fun shelf(rate: Int): Biquad {
        val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(PI * f0 / rate); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
        val a0 = 1 + k / q + k * k
        return Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    }

    /** BS.1770 K-weighting stage 2: the RLB high-pass at 38 Hz (b = 1, −2, 1 as in the standard). */
    private fun rlb(rate: Int): Biquad {
        val f0 = 38.13547087602444; val q = 0.5003270373238773
        val k = tan(PI * f0 / rate); val a0 = 1 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    }

    /** Pass one: the high-passed signal's loudness and peak, fed one piece at a time. */
    private class Meter(val rate: Int) {
        private val hp = highPass(rate, 60.0)
        private val s1 = shelf(rate)
        private val s2 = rlb(rate)
        private val sub = rate / 10                 // 100 ms: four of them make a 400 ms gating block
        private val energies = ArrayList<Double>()
        private val peaks = ArrayList<Double>()     // the peak of each 100 ms frame
        private var acc = 0.0
        private var framePeak = 0.0
        private var n = 0
        val frame get() = sub

        /** The mean K-weighted power of each 100 ms frame: what [Leveler] reads. */
        fun frames() = DoubleArray(energies.size) { energies[it] / sub }

        /** The sample peak once each frame has its gain ([gainsDb] null: as recorded). */
        fun peak(gainsDb: DoubleArray?): Double {
            var p = framePeak * lin(gainsDb, peaks.size - 1)      // the unfinished last frame
            // between two frame centres the gain is on its way to the neighbour's: take the highest
            for (i in peaks.indices) p = maxOf(p, peaks[i] * maxOf(lin(gainsDb, i - 1), lin(gainsDb, i), lin(gainsDb, i + 1)))
            return p
        }

        private fun lin(gainsDb: DoubleArray?, i: Int) =
            if (gainsDb == null || gainsDb.isEmpty()) 1.0 else 10.0.pow(gainsDb[i.coerceIn(0, gainsDb.size - 1)] / 20)

        fun add(x: FloatArray) {
            for (v in x) {
                val f = hp.run(v.toDouble())
                val a = abs(f); if (a > framePeak) framePeak = a
                val k = s2.run(s1.run(f))
                acc += k * k
                if (++n == sub) { energies.add(acc); peaks.add(framePeak); acc = 0.0; framePeak = 0.0; n = 0 }
            }
        }

        /**
         * Integrated loudness: 400 ms blocks with 75 % overlap, absolute gate −70 LUFS, relative
         * gate −10 LU — of the recording once each frame has its gain ([gainsDb] null: as recorded).
         */
        fun lufs(gainsDb: DoubleArray?): Double {
            if (energies.size < 4) return -70.0
            val e = DoubleArray(energies.size) { val l = lin(gainsDb, it); energies[it] * l * l }
            val powers = DoubleArray(e.size - 3) { i -> (e[i] + e[i + 1] + e[i + 2] + e[i + 3]) / (4.0 * sub) }
            fun lk(p: Double) = -0.691 + 10 * log10(p.coerceAtLeast(1e-12))
            val above = powers.filter { lk(it) > -70 }
            if (above.isEmpty()) return -70.0
            val rel = lk(above.average()) - 10
            val kept = above.filter { lk(it) > rel }
            return if (kept.isEmpty()) -70.0 else lk(kept.average())
        }
    }

    /** Mono PCM to AAC 128 kb/s in an .m4a, fed one piece at a time. */
    private class AacWriter(private val out: File, private val rate: Int) {
        private val tmp = File(out.parentFile, out.name + ".part")
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val muxer: MediaMuxer
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var started = false
        private var fed = 0L

        init {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        fun write(x: FloatArray) {
            var pos = 0
            while (pos < x.size) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    buf.clear(); buf.order(ByteOrder.nativeOrder())
                    val n = minOf(buf.remaining() / 2, x.size - pos)
                    val sb = buf.asShortBuffer()
                    for (k in 0 until n) sb.put((x[pos + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    codec.queueInputBuffer(i, 0, n * 2, fed * 1_000_000L / rate, 0)
                    pos += n; fed += n
                }
                drain(waitForEnd = false)
            }
        }

        /** Move encoded frames to the muxer; at the end, wait (bounded) for the end-of-stream frame. */
        private fun drain(waitForEnd: Boolean) {
            var idle = 0
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (waitForEnd) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!waitForEnd || ++idle > 500) return }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.size > 0 && started && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finish() {
            var sent = false
            var tries = 0
            while (!sent && tries++ < 200) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) { codec.queueInputBuffer(i, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sent = true }
                else drain(waitForEnd = false)
            }
            drain(waitForEnd = true)
            runCatching { codec.stop() }; codec.release()
            runCatching { muxer.stop() }; muxer.release()
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        }

        fun abort() {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { muxer.release() }; tmp.delete()
        }
    }

    /**
     * The listening copy of [src] into [out]. [onProgress] gets 0–100 across both passes; true when
     * the copy was written, false when cancelled.
     */
    fun cleanCopy(ctx: Context, src: Uri, out: File, totalMs: Long, onProgress: (Int) -> Unit, cancelled: () -> Boolean): Boolean {
        val total = totalMs.coerceAtLeast(1)
        // pass one: measure
        var meter: Meter? = null
        Decoder.chunks(ctx, src, PIECE_SECONDS) { x, rate, startMs ->
            if (cancelled()) return@chunks false
            (meter ?: Meter(rate).also { meter = it }).add(x)
            onProgress(((startMs + x.size * 1000L / rate) * 50 / total).toInt().coerceIn(0, 50))
            true
        }
        val m = meter ?: return false
        if (cancelled()) return false
        val turns = Leveler.gains(m.frames())           // null: one level throughout
        var gainDb = TARGET_LUFS - m.lufs(turns)
        val peakDb = 20 * log10(m.peak(turns).coerceAtLeast(1e-6))
        if (peakDb + gainDb > PEAK_DB) gainDb = PEAK_DB - peakDb
        val g = 10.0.pow(gainDb / 20)
        // each frame's whole gain, read at the frame's centre and joined by straight lines
        val lin = turns?.let { t -> DoubleArray(t.size) { g * 10.0.pow(t[it] / 20) } }
        val frame = m.frame.toDouble()
        var at = 0L
        // pass two: the same high-pass from a fresh state, the gains, the encoder
        val hp = highPass(m.rate, 60.0)
        val writer = AacWriter(out, m.rate)
        var ok = true
        try {
            Decoder.chunks(ctx, src, PIECE_SECONDS) { x, rate, startMs ->
                if (cancelled()) { ok = false; return@chunks false }
                for (i in x.indices) {
                    var k = g
                    if (lin != null) {
                        val t = (at + i) / frame - 0.5
                        val f = kotlin.math.floor(t).toInt()
                        val a = lin[f.coerceIn(0, lin.size - 1)]
                        val b = lin[(f + 1).coerceIn(0, lin.size - 1)]
                        k = a + (b - a) * (t - f)
                    }
                    x[i] = (hp.run(x[i].toDouble()) * k).toFloat().coerceIn(-1f, 1f)
                }
                at += x.size
                writer.write(x)
                onProgress(50 + ((startMs + x.size * 1000L / rate) * 50 / total).toInt().coerceIn(0, 50))
                true
            }
        } catch (e: Exception) { writer.abort(); throw e }
        if (!ok) { writer.abort(); return false }
        writer.finish()
        return true
    }
}
