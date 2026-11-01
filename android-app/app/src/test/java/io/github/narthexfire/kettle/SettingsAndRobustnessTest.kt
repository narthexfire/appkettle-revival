package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.Cups
import io.github.narthexfire.kettle.data.KeepWarmAction
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.KettleEdits
import io.github.narthexfire.kettle.data.LowWater
import io.github.narthexfire.kettle.data.Preset
import io.github.narthexfire.kettle.data.PresetLogic
import io.github.narthexfire.kettle.data.SettingsRepository
import io.github.narthexfire.kettle.net.Endpoint
import io.github.narthexfire.kettle.net.KettleConnection
import io.github.narthexfire.kettle.net.KettleException
import io.github.narthexfire.kettle.net.KettleSearch
import io.github.narthexfire.kettle.net.LinkPhase
import io.github.narthexfire.kettle.net.SearchEpisodes
import io.github.narthexfire.kettle.protocol.Frames
import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.protocol.KettleStatus
import io.github.narthexfire.kettle.ui.components.KettleGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAndRobustnessTest {
    private val imei = "GD0-00000-00ab"
    private val status36 = "##0083{\"wifi_cmd\":\"62\",\"imei\":\"$imei\",\"data3\":\"aa001803000000000000002a360000c800020000226401f400003f\",\"suc\":\"00\",\"seq\":\"1\"}&&"

    /* ---------- storage ---------- */

    @Test fun emptyOrCorruptStorageNeverCrashesAndFallsBackToDefaults() {
        assertEquals(KettleConfig.DEFAULTS, SettingsRepository.decodeStored("[]").kettles)
        assertFalse(SettingsRepository.decodeStored("[]").readOnly) // empty list: defaults, writable
        assertEquals(KettleConfig.DEFAULTS, SettingsRepository.decodeStored(null).kettles)
        val garbage = SettingsRepository.decodeStored("{not json")
        assertEquals(KettleConfig.DEFAULTS, garbage.kettles)
        assertTrue(garbage.readOnly)
        assertNotNull(garbage.problem)
    }

    @Test fun lenientDecodeKeepsGoodKettlesAndLeavesBadDataUntouched() {
        val raw = """[{"id":"kitchen","name":"Kitchen-Kettle","ip":"192.168.1.101","imei":"$imei","port":"not a number","cupMl":"x"},
            "not an object", {"id":"office","name":"Office-Kettle"}]"""
        val s = SettingsRepository.decodeStored(raw)
        assertEquals(listOf("kitchen", "office"), s.kettles.map { it.id }) // good entries survive
        assertEquals("192.168.1.101", s.kettles[0].ip)
        assertEquals(6002, s.kettles[0].port) // bad field -> its default only
        assertEquals(Cups.DEFAULT_ML, s.kettles[0].cupMl)
        assertTrue(s.readOnly) // one entry was unreadable
        // Writes are refused, so the raw data (including the unreadable entry) stays as it was.
        assertNull(SettingsRepository.applyUpdate(raw, "kitchen") { it.copy(lastKeepWarm = 5) })
        // A clean store is writable.
        val clean = SettingsRepository.encode(KettleConfig.DEFAULTS)
        assertNotNull(SettingsRepository.applyUpdate(clean, "kitchen") { it.copy(lastKeepWarm = 5) })
    }

    /* ---------- editor saves only edited fields ---------- */

    @Test fun editorNeverOverwritesAnAutoFoundIp() {
        val stored = KettleConfig.DEFAULTS[0].copy(ip = "192.168.1.102") // found by the search meanwhile
        val edits = KettleEdits(name = "Kitchen kettle") // the user only renamed it
        val saved = edits.applyTo(stored)
        assertEquals("192.168.1.102", saved.ip)
        assertEquals("Kitchen kettle", saved.name)
        assertEquals("192.168.1.105", KettleEdits(ip = " 192.168.1.105 ").applyTo(stored).ip) // an edited IP is saved
        val p = listOf(Preset("Sencha", 72, 10, "leaf"))
        assertEquals(p, KettleEdits.presetsFromState(KettleEdits.presetsToState(p))) // saveable codec
    }

    /* ---------- preset highlight derived ---------- */

    @Test fun presetHighlightFollowsTargetAndKeepWarm() {
        val presets = listOf(Preset("Coffee", 88), Preset("Oolong", 85, 10), Preset("Oolong hot", 85))
        assertEquals(0, PresetLogic.matching(presets, 88, 0))
        assertEquals(1, PresetLogic.matching(presets, 85, 10))
        assertEquals(2, PresetLogic.matching(presets, 85, 0))
        assertNull(PresetLogic.matching(presets, 88, 5)) // keep warm differs: no highlight
        // After editing the Coffee preset to 90, choosing 88 no longer highlights it.
        assertNull(PresetLogic.matching(presets.toMutableList().also { it[0] = Preset("Coffee", 90) }, 88, 0))
    }

    /* ---------- low water ---------- */

    @Test fun lowWaterTwoTapConfirmAndOneRule() {
        assertEquals(LowWater.Tap.Send, LowWater.onBoilTap(false, 0, 1_000))
        assertEquals(LowWater.Tap.Arm, LowWater.onBoilTap(true, 0, 1_000))
        assertEquals(LowWater.Tap.SendAnyway, LowWater.onBoilTap(true, 1_000, 4_000))
        assertEquals(LowWater.Tap.Arm, LowWater.onBoilTap(true, 1_000, 7_000)) // window expired
        val low = KettleStatus(KettleState.Standby, 20, 100, 120, 0, "")
        val now = 1_000_000L
        assertTrue(KettleConnection.lowWaterBlocks(low, now - 2_000, now, false))
        assertFalse(KettleConnection.lowWaterBlocks(low, now - 2_000, now, true)) // "Boil anyway"
        assertFalse(KettleConnection.lowWaterBlocks(low, now - 120_000, now, false)) // stale: same rule as the UI
        assertFalse(KettleConnection.lowWaterBlocks(low.copy(state = KettleState.OffBase), now - 2_000, now, false))
    }

    /* ---------- water text ---------- */

    @Test fun waterEdgeCases() {
        assertEquals("Empty", Cups.waterText(0))
        assertEquals("Empty", Cups.format(0))
        assertEquals("MAX+ (over 1.7 L)", Cups.waterText(1_760))
        assertEquals("2 cups · ≈ 500 ml", Cups.waterText(500))
        assertEquals("Empty", KettleGeometry.fmtMl(0))
        assertEquals("MAX+", KettleGeometry.fmtMl(1_760))
        assertEquals("≈500 ml", KettleGeometry.fmtMl(500))
    }

    /* ---------- backoff ---------- */

    @Test fun backoffRestartsAfterAGoodSession() {
        assertEquals(1, KettleConnection.nextAttempt(5, reachedConnected = true))
        assertEquals(6, KettleConnection.nextAttempt(5, reachedConnected = false))
    }

    /* ---------- episodes ---------- */

    @Test fun episodesResetOnForegroundAndAreTimedOnlyInForeground() {
        val e = SearchEpisodes()
        e.observe("m", connected = false, hasLink = true, foreground = false, now = 1_000)
        assertNull(e.offlineSince("m")) // background: not timed
        e.observe("m", connected = false, hasLink = true, foreground = true, now = 2_000)
        assertEquals(2_000L, e.offlineSince("m"))
        e.markSearched("m")
        e.onForeground()
        assertNull(e.offlineSince("m")); assertFalse(e.searched("m")) // fresh on return
        e.observe("m", connected = true, hasLink = true, foreground = true, now = 3_000)
        assertNull(e.offlineSince("m"))
    }

    /* ---------- search subnet ---------- */

    @Test fun searchUsesTheRealPrefixCappedAt22() {
        assertEquals(253, KettleSearch.candidatesFor("192.168.1.101", 24, "192.168.1.100").size)
        assertEquals(509, KettleSearch.candidatesFor("192.168.1.101", 23, "192.168.1.100").size) // 510 hosts - ours
        assertEquals(1021, KettleSearch.candidatesFor("10.0.1.5", 22, "10.0.1.9").size) // the cap
        val wide = KettleSearch.candidatesFor("10.0.5.20", 16, "10.0.9.7") // larger: our /24 + old /24
        assertEquals(253 + 254, wide.size)
        assertTrue("10.0.9.1" in wide && "10.0.5.254" in wide && "10.0.5.20" !in wide)
        assertEquals("192.168.1.255", KettleSearch.broadcastFor("192.168.1.101", 23))
        assertEquals("10.0.5.255", KettleSearch.broadcastFor("10.0.5.20", 16))
    }

    /* ---------- queued Off ---------- */

    @Test fun keepWarmOffWhileHeatingBelow60IsQueued() {
        assertEquals(KeepWarmAction.QueueOff, KeepWarmAction.decide(KettleState.Heating, 45, 0))
        assertEquals(KeepWarmAction.SendKeepWarm(0), KeepWarmAction.decide(KettleState.Heating, 72, 0))
        assertNull(KeepWarmAction.resolveQueuedOff(KettleState.Heating, 50)) // keep waiting
        assertEquals(KeepWarmAction.SendKeepWarm(0), KeepWarmAction.resolveQueuedOff(KettleState.Heating, 60))
        assertEquals(KeepWarmAction.SendOff, KeepWarmAction.resolveQueuedOff(KettleState.KeepWarm, 95))
        assertEquals(KeepWarmAction.NextBoil(null), KeepWarmAction.resolveQueuedOff(KettleState.Ready, 95))
    }

    /* ---------- loopback fake kettle (127.0.0.1 only: no firewall prompt) ---------- */

    private class Fake(val reply: (String, OutputStream) -> Unit) {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        val buf = ByteArray(2048)
                        while (true) {
                            val n = s.getInputStream().read(buf); if (n < 0) break
                            val t = String(buf, 0, n); received += t
                            reply(t, s.getOutputStream()); s.getOutputStream().flush()
                        }
                    }
                }
            }
        }
        fun close() = server.close()
    }

    private fun withLink(fake: Fake, imeiSaved: String = imei, block: suspend (KettleConnection) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = KettleConnection(Endpoint("127.0.0.1", fake.server.localPort, imeiSaved), scope)
        try {
            link.start()
            withTimeout(5_000) { link.info.first { it.phase == LinkPhase.Connected } }
            block(link)
        } finally { link.disconnect(); scope.cancel(); fake.close() }
    }

    @Test fun differentKettleIdRefusesCommands() {
        val other = status36.replace(imei, "GD0-99999-0000")
        val fake = Fake { t, out -> if (t.contains("KeepConnect")) out.write(("##000bKeepConnect&&$other").toByteArray()) }
        withLink(fake) { link ->
            withTimeout(5_000) { link.info.first { it.imeiMismatch != null } }
            assertEquals("GD0-99999-0000", link.info.value.imeiMismatch)
            try { link.stopBoil(); fail("must refuse") } catch (e: KettleException) {
                assertTrue(e.message!!.startsWith("This address now answers as a different kettle"))
            }
            delay(200)
            assertTrue(fake.received.joinToString("").replace(Frames.KEEP_ALIVE, "").isEmpty()) // no command left the phone
        }
    }

    @Test fun staleAckIsIgnoredAndTheRightSerialCompletes() {
        val fake = Fake { t, out ->
            if (t.contains("KeepConnect")) out.write(("##000bKeepConnect&&$status36").toByteArray())
            val m = Regex("\"data2\":\"([0-9A-F]+)\"").find(t) ?: return@Fake
            val serial = Frames.parseAcross(m.groupValues[1])!!.serial
            // First an ack with a stale serial, then the real one.
            val stale = Frames.buildAcross(0x3A, (serial + 200) % 255 + 1, "C8")
            val good = Frames.buildAcross(0x3A, serial, "C8")
            out.write(Frames.wire("{\"wifi_cmd\":\"62\",\"imei\":\"$imei\",\"data3\":\"$stale\"}").toByteArray())
            out.write(Frames.wire("{\"wifi_cmd\":\"62\",\"imei\":\"$imei\",\"data3\":\"$good\"}").toByteArray())
        }
        withLink(fake) { link ->
            link.stopBoil() // completes on the matching serial
            assertTrue(link.info.value.commandLog.any { it.contains("IGNORED stale ack 3A") })
        }
    }

    @Test fun framesWithBadChecksumAreDroppedAndCounted() {
        val bad = status36.replace("f400003f", "f400003e")
        val fake = Fake { t, out -> if (t.contains("KeepConnect")) out.write(("##000bKeepConnect&&$bad$status36").toByteArray()) }
        withLink(fake) { link ->
            withTimeout(5_000) { link.info.first { it.status != null } }
            assertEquals(1, link.info.value.badFrames)
            assertEquals(500, link.info.value.status!!.waterMl) // the good frame was used
        }
    }

    @Test fun intentionalDisconnectRecordsNoError() = runBlocking {
        val fake = Fake { t, out -> if (t.contains("KeepConnect")) out.write("##000bKeepConnect&&".toByteArray()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = KettleConnection(Endpoint("127.0.0.1", fake.server.localPort, imei), scope)
        link.start()
        withTimeout(5_000) { link.info.first { it.phase == LinkPhase.Connected } }
        link.disconnect()
        delay(500)
        assertNull(link.info.value.lastError)
        scope.cancel(); fake.close()
    }

    @Test fun searchJobCanBeCanceled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.async { KettleSearch.sweep(listOf("127.0.0.2"), 1, imei, { java.net.Socket() }, rounds = 6, gapMs = 10_000) }
        delay(300)
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        scope.cancel()
    }
}
