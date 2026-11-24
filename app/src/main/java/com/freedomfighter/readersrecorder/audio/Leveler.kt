package com.freedomfighter.readersrecorder.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Brings the voices of one recording closer together: the person across the table is raised
 * towards the one beside the phone. For the listening copy only — the transcription reads the
 * original.
 *
 * It works on the loudness of 100 ms frames, never on the samples. Speech is what rises above
 * the room's floor; two frames of pause end a phrase; neighbouring phrases of similar level are
 * merged into turns, and each turn gets ONE gain, held flat from its first word to its last. The
 * gain only moves inside a pause, over half a second at most, so no word is ever faded in or out.
 * Differences up to [DEAD] dB are left alone (that is expression, not distance), and a turn is
 * raised by [UP] dB at most: raising a far voice raises the room behind it just as much.
 */
object Leveler {
    private const val OVER_FLOOR = 6.0     // dB above the room's floor: speech
    private const val BELOW_TOP = 35.0     // …and never further than this under the loud frames
    private const val MIN_GAP = 2          // frames of pause that end a phrase
    private const val SPLIT = 4.0          // dB between two neighbours that makes them two turns
    private const val FULL = 20.0          // frames from which a turn's level is trusted in full
    private const val OWN = 15             // frames of speech a turn needs to have a gain of its own
    private const val DEAD = 2.0
    private const val UP = 9.0
    private const val DOWN = -3.0
    private const val RAMP = 5             // frames, at most

    private fun db(p: Double) = 10 * log10(p + 1e-12)

    private fun centile(sorted: DoubleArray, q: Double): Double {
        val pos = q / 100 * (sorted.size - 1)
        val i = pos.toInt().coerceIn(0, sorted.size - 1)
        val j = min(i + 1, sorted.size - 1)
        return sorted[i] + (sorted[j] - sorted[i]) * (pos - i)
    }

    /** The level of a stretch of speech: its louder half — the vowels, not the tails. */
    private fun loudness(frames: DoubleArray): Double {
        val s = frames.sortedArray()
        var sum = 0.0
        for (i in s.size / 2 until s.size) sum += s[i]
        return db(sum / (s.size - s.size / 2))
    }

    private class Turn(val first: Int, val last: Int, val frames: DoubleArray) {
        val level = loudness(frames)
        operator fun plus(o: Turn) = Turn(first, o.last, frames + o.frames)
    }

    /**
     * [energy]: the mean K-weighted power of each 100 ms frame. Returns the gain of each frame in
     * dB, or null when the recording needs none (one level throughout, or too little speech).
     */
    fun gains(energy: DoubleArray): DoubleArray? {
        val n = energy.size
        if (n < 10) return null
        val level = DoubleArray(n) { db(energy[it]) }
        val sorted = level.sortedArray()
        val threshold = max(max(centile(sorted, 5.0) + OVER_FLOOR, centile(sorted, 95.0) - BELOW_TOP), -65.0)
        val speech = BooleanArray(n) { level[it] > threshold }

        // phrases: [start, end) in frames
        val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
        var i = 0
        while (i < n) {
            if (!speech[i]) { i++; continue }
            var j = i; var last = i
            while (j < n && (speech[j] || j - last < MIN_GAP)) { if (speech[j]) last = j; j++ }
            starts.add(i); ends.add(last + 1); i = last + 1
        }
        if (starts.size < 2) return null

        // turns: neighbouring phrases merged while their levels are close (short ones merge easily)
        val turns = ArrayList<Turn>(starts.size)
        for (p in starts.indices) {
            var count = 0
            for (f in starts[p] until ends[p]) if (speech[f]) count++
            val fr = DoubleArray(count); var k = 0
            for (f in starts[p] until ends[p]) if (speech[f]) fr[k++] = energy[f]
            turns.add(Turn(p, p, fr))
        }
        fun cost(k: Int): Double {
            val short = min(turns[k].frames.size, turns[k + 1].frames.size)
            return abs(turns[k].level - turns[k + 1].level) * min(1.0, sqrt(short / FULL))
        }
        val costs = ArrayList<Double>(turns.size)
        for (k in 0 until turns.size - 1) costs.add(cost(k))
        while (costs.isNotEmpty()) {
            var k = 0
            for (c in 1 until costs.size) if (costs[c] < costs[k]) k = c
            if (costs[k] >= SPLIT) break
            turns[k] = turns[k] + turns[k + 1]; turns.removeAt(k + 1); costs.removeAt(k)
            if (k < costs.size) costs[k] = cost(k)
            if (k > 0) costs[k - 1] = cost(k - 1)
        }
        // a turn too short to judge joins the neighbour nearest in level
        while (turns.size > 1) {
            val k = turns.indexOfFirst { it.frames.size < OWN }
            if (k < 0) break
            val j = if (k == turns.size - 1 || (k > 0 && abs(turns[k].level - turns[k - 1].level) <= abs(turns[k].level - turns[k + 1].level))) k - 1 else k
            turns[j] = turns[j] + turns[j + 1]; turns.removeAt(j + 1)
        }

        // the level to join: the 70th centile of the turns, weighted by their length
        val order = turns.indices.sortedBy { turns[it].level }
        val total = turns.sumOf { it.frames.size }
        var run = 0
        var target = turns[order.last()].level
        for (o in order) { run += turns[o].frames.size; if (run >= 0.7 * total) { target = turns[o].level; break } }

        val phraseGain = DoubleArray(starts.size)
        var any = false
        for (t in turns) {
            val d = target - t.level
            val g = (sign(d) * max(abs(d) - DEAD, 0.0)).coerceIn(DOWN, UP)
            if (abs(g) > 0.1) any = true
            for (p in t.first..t.last) phraseGain[p] = g
        }
        if (!any) return null

        // per frame: flat over a phrase, moving only inside the pause between two of them
        val out = DoubleArray(n)
        for (f in 0 until ends[0]) out[f] = phraseGain[0]
        for (p in 0 until starts.size - 1) {
            val a = ends[p]; val b = starts[p + 1]
            val g0 = phraseGain[p]; val g1 = phraseGain[p + 1]
            for (f in starts[p] until a) out[f] = g0
            val gap = b - a; val r = min(RAMP, gap); val s = a + (gap - r) / 2
            for (f in a until s) out[f] = g0
            for (f in s + r until b) out[f] = g1
            for (q in 0 until r) out[s + q] = if (r > 2) g0 + (g1 - g0) * (q + 1) / (r + 1) else if (q < r / 2) g0 else g1
        }
        for (f in starts.last() until n) out[f] = phraseGain.last()
        return out
    }
}
