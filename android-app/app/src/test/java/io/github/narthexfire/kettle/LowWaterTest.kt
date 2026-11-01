package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.LowWater
import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.protocol.KettleStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowWaterTest {
    private fun st(state: KettleState, ml: Int) = KettleStatus(state, 20, 100, ml, 0, "")
    private val now = 1_000_000L

    @Test fun warnsBelowMinOnTheBaseWithFreshStatus() {
        assertTrue(LowWater.shouldWarn(st(KettleState.Standby, 249), now - 2_000, now))
        assertTrue(LowWater.shouldWarn(st(KettleState.Ready, 0), now - 2_000, now)) // empty on the base
        assertTrue(LowWater.shouldWarn(st(KettleState.Heating, 120), now - 2_000, now))
    }

    @Test fun noWarningAtOrAboveMin() {
        assertFalse(LowWater.shouldWarn(st(KettleState.Standby, 250), now - 2_000, now))
        assertFalse(LowWater.shouldWarn(st(KettleState.Standby, 912), now - 2_000, now))
    }

    @Test fun noWarningOffBaseOrWithoutRecentStatus() {
        assertFalse(LowWater.shouldWarn(st(KettleState.OffBase, 0), now - 2_000, now))
        assertFalse(LowWater.shouldWarn(st(KettleState.Unknown, 0), now - 2_000, now))
        assertFalse(LowWater.shouldWarn(null, now - 2_000, now))
        assertFalse(LowWater.shouldWarn(st(KettleState.Standby, 100), 0L, now)) // never received
        assertFalse(LowWater.shouldWarn(st(KettleState.Standby, 100), now - 61_000, now)) // stale (> 60 s)
    }

    @Test fun usesThePerKettleMinimum() {
        assertTrue(LowWater.shouldWarn(st(KettleState.Standby, 290), now - 1, now, minMl = 300))
    }
}
