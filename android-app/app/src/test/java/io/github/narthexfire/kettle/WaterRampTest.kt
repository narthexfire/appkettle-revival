package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.ui.theme.WaterRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Blue when cold. Red when hot." */
class WaterRampTest {
    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    @Test fun solidBlueUpTo40() {
        listOf(5f, 15f, 20f, 30f, 40f).forEach { assertEquals("at $it", "#4A90D9", hex(WaterRamp.argb(it, 95))) }
    }

    @Test fun softRedAt75BrightRedAtBoil() {
        assertEquals("#D2605A", hex(WaterRamp.argb(75f, 95)))
        assertEquals("#E53935", hex(WaterRamp.argb(95f, 95))) // a measured boil point below 100 (altitude)
        assertEquals("#E53935", hex(WaterRamp.argb(100f, 95)))
        assertEquals("#E53935", hex(WaterRamp.argb(100f, 100))) // nothing measured: bright red at 100
        assertTrue(hex(WaterRamp.argb(95f, 100)) != "#E53935") // not yet bright at 95 when the boil is 100
        // Red gets more saturated (brighter) from 75 to the boil.
        var lastC = 0.0
        for (t in 75..95) {
            val c = WaterRamp.toOklch(WaterRamp.argb(t.toFloat(), 95))[1]
            assertTrue("chroma at $t", c >= lastC - 1e-6); lastC = c
        }
    }

    @Test fun blendIsBlueToPaleToRedNeverPurple() {
        val mid = WaterRamp.toOklch(WaterRamp.argb(57.5f, 95))
        assertTrue("midpoint is pale (light, low chroma): $mid", mid[0] > 0.9 && mid[1] < 0.02)
        for (t in 41..74) {
            val lch = WaterRamp.toOklch(WaterRamp.argb(t.toFloat(), 95))
            // Purple/magenta lives around OKLCH hue 290-350; any noticeable color must be blue- or red-ish.
            if (lch[1] > 0.03) assertTrue("no purple at $t: hue ${lch[2]}", lch[2] !in 280.0..355.0)
        }
    }
}
