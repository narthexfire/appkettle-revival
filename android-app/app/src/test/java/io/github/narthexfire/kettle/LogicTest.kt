package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.BoilPointLearner
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.TempLogic
import io.github.narthexfire.kettle.net.WifiTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val boilPt = 95

    @Test fun boilOnlyAt100AndNoLimit() {
        // No boil-point limit; only exactly 100 is "Boil"; targets are sent as chosen.
        assertFalse(TempLogic.isBoil(99))
        assertTrue(TempLogic.isBoil(100))
        assertEquals("Boil", TempLogic.label(100, boilPt))
        assertEquals("95\u00B0", TempLogic.label(95, boilPt))
        assertEquals("78\u00B0", TempLogic.label(78, boilPt))
        assertEquals(95, TempLogic.sendTarget(95, boilPt))
        assertEquals(92, TempLogic.sendTarget(92, boilPt))
        assertEquals(100, TempLogic.sendTarget(100, boilPt))
        assertEquals(40, TempLogic.sendTarget(30, boilPt))
        assertEquals("Water boils at ≈95° here; the kettle stops when it boils", TempLogic.stopsAtBoilHint(97, 95))
        assertEquals("100° · Boil", TempLogic.controlLabel(100))
        assertEquals("95°", TempLogic.controlLabel(95))
        assertNull(TempLogic.stopsAtBoilHint(95, 95))
        assertNull(TempLogic.stopsAtBoilHint(100, null))
    }

    @Test fun plusMinusSteps40To100() {
        assertEquals(79, TempLogic.plus(78, boilPt))
        assertEquals(90, TempLogic.plus(89, boilPt)) // no jump to Boil any more
        assertEquals(100, TempLogic.plus(99, boilPt))
        assertEquals(100, TempLogic.plus(100, boilPt))
        assertEquals(99, TempLogic.minus(100, boilPt))
        assertEquals(40, TempLogic.minus(40, boilPt))
        assertFalse(TempLogic.canPlus(100))
        assertFalse(TempLogic.canMinus(40))
        // Every value 40..100 is reachable one degree at a time.
        var t = 40; val seen = mutableListOf(t)
        while (TempLogic.canPlus(t)) { t = TempLogic.plus(t, boilPt); seen += t }
        assertEquals((40..100).toList(), seen)
    }


    @Test fun slider40To100() {
        assertEquals(40, TempLogic.fromSlider(0f, boilPt))
        assertEquals(100, TempLogic.fromSlider(1f, boilPt))
        assertEquals(70, TempLogic.fromSlider(0.5f, boilPt))
        assertEquals(1f, TempLogic.toSlider(100, boilPt), 0.0001f)
        assertEquals(0f, TempLogic.toSlider(40, boilPt), 0.0001f)
        assertEquals(95, TempLogic.fromSlider(TempLogic.toSlider(95, boilPt), boilPt))
    }

    @Test fun learnerTakesRealBoilsOnly() {
        assertEquals(95, BoilPointLearner.sample(true, true, 95, 95))
        assertNull(BoilPointLearner.sample(true, true, 100, 71)) // manual Stop at 71
        assertNull(BoilPointLearner.sample(false, true, 95, 95)) // never saw a boil
        assertNull(BoilPointLearner.sample(true, true, 60, 60)) // implausible
        assertNull(BoilPointLearner.sample(true, false, 95, 95))
    }

    @Test fun learnerMedianOfLastFive() {
        var h = emptyList<Int>()
        listOf(95, 92, 90, 95, 99, 95).forEach { h = BoilPointLearner.addSample(h, it) }
        assertEquals(listOf(92, 90, 95, 99, 95), h)
        assertEquals(95, BoilPointLearner.median(h))
        assertEquals(95, BoilPointLearner.effective(null, h, 100))
        assertEquals(95, BoilPointLearner.effective(95, h, 100))
        assertEquals(95, BoilPointLearner.effective(null, emptyList(), 95))
    }

    @Test fun firstRunHasOneKettleThatIsNotSetUp() {
        assertEquals(1, KettleConfig.DEFAULTS.size)
        val d = KettleConfig.DEFAULTS[0]
        assertFalse(d.isSetUp); assertEquals("", d.ip); assertEquals("", d.imei)
        assertEquals("kettle2", KettleConfig.newId(listOf("kettle1")))
        assertEquals("kettle1", KettleConfig.newId(listOf("kitchen")))
    }

    @Test fun configuredKettle() {
        val m = TestKettles.kitchen
        assertEquals("Kitchen-Kettle", m.name)
        assertEquals("192.168.1.100", m.ip)
        assertNull(m.measuredBoilPoint) // information only, learned from real boils
        assertEquals(100, m.boilPoint) // color-ramp top until a boil has been measured
        assertEquals(95, m.copy(boilHistory = listOf(95, 95, 90)).boilPoint)
        assertTrue(m.active)
        assertFalse(TestKettles.office.isSetUp)
    }

    @Test fun subnetCheck() {
        assertEquals(true, WifiTracker.sameSubnet("192.168.1.101", 24, "192.168.1.100"))
        assertEquals(false, WifiTracker.sameSubnet("10.0.0.12", 24, "192.168.1.100"))
        assertNull(WifiTracker.sameSubnet(null, null, "192.168.1.100"))
        assertEquals("255.255.255.0", WifiTracker.maskString(24))
    }
}
