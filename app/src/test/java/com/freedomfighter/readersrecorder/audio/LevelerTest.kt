package com.freedomfighter.readersrecorder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.pow

class LevelerTest {
    private val rnd = Random(7)
    private fun power(db: Double) = 10.0.pow((db + rnd.nextGaussian() * 2) / 10)

    /** Turns of [seconds] alternating between the given levels, 0.4 s of room (−55 dB) between them. */
    private fun talk(levels: List<Double>, turns: Int, seconds: Int = 6): Pair<DoubleArray, IntArray> {
        val e = ArrayList<Double>(); val who = ArrayList<Int>()
        repeat(turns) { t ->
            repeat(seconds * 10) { e.add(power(levels[t % levels.size])); who.add(t % levels.size) }
            repeat(4) { e.add(power(-55.0) / 1.5); who.add(-1) }
        }
        return e.toDoubleArray() to who.toIntArray()
    }

    @Test fun oneVoiceIsLeftAlone() {
        assertNull(Leveler.gains(talk(listOf(-22.0), 20).first))
    }

    @Test fun theFarVoiceIsRaisedAndTheNearOneIsNot() {
        val (e, who) = talk(listOf(-22.0, -34.0), 20)
        val g = Leveler.gains(e)
        assertNotNull(g); g!!
        for (i in g.indices) when (who[i]) {
            0 -> assertEquals("near voice at frame $i", 0.0, g[i], 0.5)
            1 -> assertTrue("far voice at frame $i: ${g[i]}", g[i] in 7.5..9.0)
        }
    }

    @Test fun theGainOnlyMovesInThePauses() {
        val (e, who) = talk(listOf(-22.0, -34.0), 20)
        val g = Leveler.gains(e)!!
        for (i in 1 until g.size) if (who[i] >= 0 && who[i - 1] == who[i]) assertEquals("frame $i", g[i - 1], g[i], 1e-9)
    }
}
