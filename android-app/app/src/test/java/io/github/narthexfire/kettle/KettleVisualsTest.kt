package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.ui.components.KettleGeometry
import io.github.narthexfire.kettle.ui.components.KettleMode
import io.github.narthexfire.kettle.ui.components.KettleVisuals
import io.github.narthexfire.kettle.ui.theme.WaterRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "The color should be set by the temp, not whether the kettle is currently being boiled." */
class KettleVisualsTest {
    private val waterModes = listOf(KettleMode.Standby, KettleMode.Heating, KettleMode.Ready, KettleMode.KeepWarm)

    @Test fun colorDependsOnlyOnTemperature() {
        for (t in listOf(20, 52, 75, 95)) {
            val colors = waterModes.map { KettleVisuals.of(it, t, 912, 95).fillArgb }.toSet()
            assertEquals("one color for all states at $t C", 1, colors.size)
            assertEquals(WaterRamp.argb(t.toFloat(), 95), colors.first())
        }
        // The reported case: 52 C is the same blue-side color in Standby and while heating (not red).
        assertEquals(KettleVisuals.of(KettleMode.Standby, 52, 912, 95).fillArgb, KettleVisuals.of(KettleMode.Heating, 52, 912, 95).fillArgb)
        assertTrue(KettleVisuals.of(KettleMode.Heating, 52, 912, 95).fillArgb != WaterRamp.RED)
    }

    @Test fun waterAlwaysAtItsRealLevel() {
        val y = KettleGeometry.waterY(912)
        waterModes.forEach {
            val v = KettleVisuals.of(it, 64, 912, 95)
            assertTrue(v.showWater); assertEquals(y, v.waterTop, 0.001f)
        }
    }

    @Test fun heatingShownByBubblesSteamAndPulsingLight() {
        val h = KettleVisuals.of(KettleMode.Heating, 80, 912, 95)
        assertTrue(h.bubbles); assertEquals(3, h.steamWisps); assertTrue(h.ledOn); assertTrue(h.ledPulse)
        assertTrue(KettleVisuals.of(KettleMode.Heating, 40, 912, 95).bubbles) // bubbles even when cool
        listOf(KettleMode.Standby, KettleMode.Ready, KettleMode.KeepWarm).forEach { assertFalse(KettleVisuals.of(it, 80, 912, 95).bubbles) }
    }

    @Test fun keepWarmOneSteadyWispLightOnNoBubbles() {
        val k = KettleVisuals.of(KettleMode.KeepWarm, 95, 500, 95)
        assertEquals(1, k.steamWisps); assertTrue(k.ledOn); assertFalse(k.ledPulse); assertFalse(k.bubbles)
    }

    @Test fun offBaseShowsNoWater() {
        val o = KettleVisuals.of(KettleMode.OffBase, 40, 0, 95)
        assertFalse(o.showWater); assertFalse(o.bubbles); assertEquals(0, o.steamWisps); assertFalse(o.ledOn)
    }
}
