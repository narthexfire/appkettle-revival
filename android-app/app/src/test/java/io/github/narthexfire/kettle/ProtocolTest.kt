package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.protocol.Api
import io.github.narthexfire.kettle.protocol.Commands
import io.github.narthexfire.kettle.protocol.FrameSplitter
import io.github.narthexfire.kettle.protocol.Frames
import io.github.narthexfire.kettle.protocol.Hex
import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.protocol.StatusParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values follow the frame format seen on real kettles; the Kettle ID is synthetic (GD0-00000-00ab). */
class ProtocolTest {
    private val imei = "GD0-00000-00ab"

    @Test fun onFrameMatchesCapture() {
        // Proven frame: ON 0x39, serial 2, 100 C, keep warm 0
        val wire = Commands.startBoil(imei, serial = 2, targetC = 100, keepWarmMin = 0)
        assertEquals(
            "##0069{\"app_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"SubDev\":\"\",\"data2\":\"AA001200000000000000000239000000640000004E\"}&&",
            wire,
        )
    }

    @Test fun onFrameWithKeepWarm5MatchesCapture() {
        // Proven frame: ON 0x39 100 C kw=5 min
        assertEquals("AA0012000000000000000002390000006405000049",
            Frames.buildAcross(Api.SET_START_BOIL, 2, "0064050000"))
        assertTrue(Commands.startBoil(imei, 2, 100, 5).contains("AA0012000000000000000002390000006405000049"))
    }

    @Test fun offFrameMatchesCapture() {
        assertEquals(
            "##005F{\"app_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"SubDev\":\"\",\"data2\":\"AA000D0000000000000000013A0000B7\"}&&",
            Commands.stop(imei, 1),
        )
    }

    @Test fun wakeFrameMatchesCapture() {
        assertTrue(Commands.wake(imei, 1).contains("\"data2\":\"AA000D000000000000000001410000B0\""))
        assertTrue(Commands.wake(imei, 1).startsWith("##005F"))
    }

    @Test fun wifiScanFrameMatchesCaptureForChecksumCoverage() {
        // kettle3d.py: setup prefix 0100 (built here only to check the checksum; the app never sends it)
        assertEquals("AA000D0100000000000000013D0000B3", Frames.buildAcross(0x3D, 1, prefix = "0100"))
    }

    @Test fun keepAliveIsExact() = assertEquals("##000bKeepConnect&&", Frames.KEEP_ALIVE)

    @Test fun checksum() {
        // sum of bytes of 000D0000000000000000013A0000 = 0x48 ; 0x48 xor 0xFF = 0xB7
        assertEquals("B7", Hex.sign("000D0000000000000000013A0000"))
    }

    @Test fun stuffingRoundTrip() {
        assertEquals("01AA5502", Hex.stuff("01AA02"))
        assertEquals("01AA02", Hex.unstuff("01AA5502"))
        assertEquals("AA55AA55", Hex.stuff("AAAA"))
        assertEquals("AAAA", Hex.unstuff("AA55AA55"))
        // A 55 that does not follow AA is kept.
        assertEquals("5501", Hex.unstuff("5501"))
    }

    @Test fun stuffedChecksumIsStuffed() {
        // Serial 0xAA forces stuffing inside the frame; it must parse back to serial 170 with a good checksum.
        val across = Frames.buildAcross(Api.SET_STOP_BOIL, 0xAA)
        assertTrue(across.contains("AA55"))
        val r = Frames.parseAcross(across)!!
        assertEquals(0xAA, r.serial)
        assertTrue(r.checksumOk)
    }

    @Test fun statusFrame36Standby() {
        // Observed frame (synthetic ID): api 36, Standby 34 C target 100, 500 ml
        val wire = "##0083{\"wifi_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"data3\":\"aa001803000000000000002a360000c800020000226401f400003f\",\"suc\":\"00\",\"seq\":\"54545\"}&&"
        val r = Frames.parseWire(wire)!!
        assertEquals(Api.GET_STATUS, r.api)
        assertEquals(42, r.serial)
        assertEquals("0300", r.prefix)
        val s = StatusParser.fromReply(r)!!
        assertEquals(KettleState.Standby, s.state)
        assertEquals(34, s.temperatureC)
        assertEquals(100, s.targetC)
        assertEquals(500, s.waterMl)
        assertEquals(0, s.keepWarmSecondsLeft)
    }

    @Test fun statusA4PushWithNonC8LeadByteIsNotOffBase() {
        // Observed frame (synthetic ID): api A4, content 2500020000226401F40000 (lead byte 0x25, not C8).
        // A parser that only strips a leading C8 reads this as "Not on base"; it is Standby, 34 C.
        val wire = "##0083{\"wifi_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"data3\":\"aa000d010000000000000028a400002500020000226401f4000083\",\"suc\":\"00\",\"seq\":\"54545\"}&&"
        val r = Frames.parseWire(wire)!!
        assertEquals(Api.STATUS_PUSH, r.api)
        val s = StatusParser.fromReply(r)!!
        assertEquals(KettleState.Standby, s.state)
        assertEquals(34, s.temperatureC)
        assertEquals(500, s.waterMl)
    }

    @Test fun statusA4OtherLeadBytes() {
        // A4 content with lead byte 0x89: Ready 100/100
        val ready = StatusParser.parse("8900030000646401F40000")!!
        assertEquals(KettleState.Ready, ready.state)
        assertEquals(100, ready.temperatureC)
        assertEquals(100, ready.targetC)
        // Observed A4 content 3E00020000206401F40000 = Standby 32
        assertEquals(KettleState.Standby, StatusParser.parse("3E00020000206401F40000")!!.state)
    }

    @Test fun statusHeatingAndKeepWarmCountdown() {
        val heat = StatusParser.parse("C80004012C5A6401F40000")!!
        assertEquals(KettleState.Heating, heat.state)
        assertEquals(300, heat.keepWarmSecondsLeft)
        assertEquals(90, heat.temperatureC)
        assertEquals(100, heat.targetC)
        val kw = StatusParser.parse("C80005012B646401F40000")!!
        assertEquals(KettleState.KeepWarm, kw.state)
        assertEquals(299, kw.keepWarmSecondsLeft)
        assertEquals(100, kw.targetC) // after the boil the kettle reports the temperature it boiled at (100 at sea level)
    }

    @Test fun ackReplies() {
        val ack = Frames.parseAcross(Frames.buildAcross(Api.SET_START_BOIL, 2, "C8"))!!
        assertTrue(ack.isAck)
        assertEquals(Api.SET_START_BOIL, ack.api)
        val nack = Frames.parseAcross(Frames.buildAcross(Api.SET_START_BOIL, 2, "00"))!!
        assertFalse(nack.isAck)
        assertNull(StatusParser.fromReply(ack))
    }

    @Test fun splitterHandlesFragmentsAndKeepAlive() {
        val sp = FrameSplitter()
        val a = "##000bKeepConnect&&##0083{\"data3\":\"aa00"
        assertEquals(listOf("##000bKeepConnect&&"), sp.feed(a))
        val frames = sp.feed("1803\"}&&")
        assertEquals(1, frames.size)
        assertNotNull(Frames.extractData3(frames[0]))
        assertNull(Frames.parseWire("##000bKeepConnect&&"))
    }

    @Test fun shortContentRejected() {
        assertNull(StatusParser.parse("C8"))
        assertNull(StatusParser.parse(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun targetOutOfRangeRejected() {
        Commands.startBoil(imei, 1, 120, 0)
    }
}
