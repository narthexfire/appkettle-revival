package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.KeepWarmAction
import io.github.narthexfire.kettle.protocol.Api
import io.github.narthexfire.kettle.protocol.Commands
import io.github.narthexfire.kettle.protocol.Frames
import io.github.narthexfire.kettle.protocol.Hex
import io.github.narthexfire.kettle.protocol.KettleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0x4A keep warm: the exact frame proven on a real kettle, and the state-to-action rules. */
class KeepWarmCommandTest {
    @Test fun exactFrameFor5Minutes() {
        val across = Frames.buildAcross(Api.SET_KEEP_WARM, 1, Hex.u8(5))
        assertEquals("AA000E0000000000000000014A000005A1", across)
        assertEquals(
            "##0061{\"app_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"SubDev\":\"\",\"data2\":\"AA000E0000000000000000014A000005A1\"}&&",
            Commands.keepWarm("GD0-00000-00ab", 1, 5),
        )
        assertTrue(Frames.parseAcross(across)!!.checksumOk)
    }

    @Test fun stateToActionMapping() {
        // Keep warm running + Off -> OFF 0x3A (proven to cancel keep warm at once).
        assertEquals(KeepWarmAction.SendOff, KeepWarmAction.decide(KettleState.KeepWarm, 95, 0))
        assertEquals(KeepWarmAction.SendKeepWarm(10), KeepWarmAction.decide(KettleState.KeepWarm, 95, 10)) // restart with new time
        // Just boiled (Ready / Standby) and >= 60 C: 0x4A(minutes) now.
        assertEquals(KeepWarmAction.SendKeepWarm(5), KeepWarmAction.decide(KettleState.Ready, 95, 5))
        // Standby: select only (the kettle refuses 0x4A in Standby; the original app hid the button there).
        assertEquals(KeepWarmAction.NextBoil(KeepWarmAction.STANDBY_HINT), KeepWarmAction.decide(KettleState.Standby, 85, 5))
        assertEquals(KeepWarmAction.NextBoil(null), KeepWarmAction.decide(KettleState.Standby, 85, 0))
        // Below 60 C: nothing sent, the existing note.
        assertEquals(KeepWarmAction.NextBoil("Keep warm needs 60° or more"), KeepWarmAction.decide(KettleState.Ready, 59, 5))
        assertEquals(KeepWarmAction.NextBoil(null), KeepWarmAction.decide(KettleState.Ready, 95, 0)) // Off when idle: just the setting
        // Heating: as observed in the original app - 0x4A while >= 60 C, 0x4A(0) to cancel; never OFF.
        assertEquals(KeepWarmAction.SendKeepWarm(15), KeepWarmAction.decide(KettleState.Heating, 72, 15))
        assertEquals(KeepWarmAction.SendKeepWarm(0), KeepWarmAction.decide(KettleState.Heating, 72, 0))
        assertTrue(KeepWarmAction.decide(KettleState.Heating, 40, 15) is KeepWarmAction.NextBoil)
        // No status / off base: nothing sent.
        assertEquals(KeepWarmAction.NextBoil(null), KeepWarmAction.decide(null, null, 5))
        assertEquals(KeepWarmAction.NextBoil(null), KeepWarmAction.decide(KettleState.OffBase, 80, 5))
    }

    @Test fun neverBuilds0x47Or0x35() {
        val apis = listOf(Api.GET_STATUS, Api.SET_START_BOIL, Api.SET_STOP_BOIL, Api.SET_KETTLE_ON, Api.STATUS_PUSH, Api.SET_KEEP_WARM)
        assertTrue(0x47 !in apis && 0x35 !in apis)
    }
}
