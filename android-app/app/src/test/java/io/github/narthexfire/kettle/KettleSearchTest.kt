package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.SettingsRepository
import io.github.narthexfire.kettle.net.AddressUpdate
import io.github.narthexfire.kettle.net.KettleSearch
import io.github.narthexfire.kettle.net.SearchPolicy
import io.github.narthexfire.kettle.protocol.Frames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KettleSearchTest {
    private val kitchenImei = "GD0-00000-00ab"

    /** A loopback-only fake kettle (no firewall prompt) that answers like the real one with [imei]. */
    private fun fakeKettle(imei: String, received: ByteArrayOutputStream? = null): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                while (!server.isClosed) {
                    server.accept().use { s ->
                        val buf = ByteArray(1024)
                        val n = s.getInputStream().read(buf)
                        if (n > 0) received?.write(buf, 0, n)
                        s.getOutputStream().write(("##000bKeepConnect&&" +
                            "##0083{\"wifi_cmd\":\"62\",\"imei\":\"$imei\",\"data3\":\"aa001803000000000000002a360000c800020000226401f400003f\",\"suc\":\"00\",\"seq\":\"54545\"}&&").toByteArray())
                        s.getOutputStream().flush()
                        Thread.sleep(200)
                    }
                }
            }
        }
        return server
    }

    @Test fun imeiMatchLogic() {
        assertTrue(KettleSearch.imeiMatches(kitchenImei, "GD0-00000-00ab"))
        assertTrue(KettleSearch.imeiMatches(kitchenImei, "gd0-00000-00AB "))
        assertFalse(KettleSearch.imeiMatches(kitchenImei, "GD0-99999-0000"))
        assertFalse(KettleSearch.imeiMatches(kitchenImei, null))
        assertFalse(KettleSearch.imeiMatches("", "GD0-00000-00ab"))
    }

    @Test fun probeAcceptsOnlyTheRightKettleAndSendsOnlyKeepAlive() {
        val got = ByteArrayOutputStream()
        val right = fakeKettle(kitchenImei, got)
        val wrong = fakeKettle("GD0-99999-0000")
        try {
            val r = KettleSearch.probe("127.0.0.1", right.localPort, kitchenImei)
            assertEquals(KettleSearch.Probe.Match("127.0.0.1", kitchenImei), r)
            assertEquals(Frames.KEEP_ALIVE, got.toString(Charsets.US_ASCII)) // nothing else is ever written
            val w = KettleSearch.probe("127.0.0.1", wrong.localPort, kitchenImei)
            assertTrue("wrong IMEI must be rejected: $w", w is KettleSearch.Probe.WrongKettle)
        } finally { right.close(); wrong.close() }
    }

    @Test fun sweepFindsTheKettleAmongCandidates() = runBlocking {
        val server = fakeKettle(kitchenImei)
        try {
            // 127.0.0.2 has nothing listening on that port; 127.0.0.1 is "the kettle at its new address".
            val m = KettleSearch.sweep(listOf("127.0.0.2", "127.0.0.1"), server.localPort, kitchenImei, { Socket() }, rounds = 1, gapMs = 0)
            assertEquals("127.0.0.1", m?.ip)
            val none = KettleSearch.sweep(listOf("127.0.0.2"), server.localPort, kitchenImei, { Socket() }, rounds = 2, gapMs = 10)
            assertNull(none)
        } finally { server.close() }
    }

    @Test fun subnetEnumeration() {
        val c = KettleSearch.candidates("192.168.1.100", setOf("192.168.1.1", null))
        assertEquals(252, c.size) // .1-.254 minus the phone (.100) and the gateway (.1)
        assertFalse("192.168.1.100" in c); assertFalse("192.168.1.1" in c)
        assertTrue("192.168.1.101" in c); assertTrue("192.168.1.254" in c)
        assertFalse(c.any { it.endsWith(".0") || it.endsWith(".255") })
        assertEquals("192.168.1.255", KettleSearch.broadcastOf("192.168.1.100"))
        assertTrue(KettleSearch.candidates("not.an.ip").isEmpty())
        assertTrue(KettleSearch.probeMessage(0L).matches(Regex("Probe#\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-2")))
    }

    @Test fun oldToNewAddressUpdateIsSavedAndLogged() {
        val m = TestKettles.kitchen
        val u = AddressUpdate.apply(m, "192.168.1.101", 0L)
        assertEquals("192.168.1.101", u.ip)
        assertTrue(u.addressLog.single().contains("192.168.1.100 → 192.168.1.101 (automatic)"))
        assertEquals("Found Kitchen-Kettle at 192.168.1.101 (was .100)", AddressUpdate.message(m.name, m.ip, u.ip))
        val back = SettingsRepository.decode(SettingsRepository.encode(listOf(u)))[0]
        assertEquals("192.168.1.101", back.ip); assertEquals(u.addressLog, back.addressLog)
        assertEquals(u, AddressUpdate.apply(u, "192.168.1.101", 1L)) // same address: unchanged
        var many = m
        repeat(7) { many = AddressUpdate.apply(many, "192.168.1.${110 + it}", it.toLong()) }
        assertEquals(5, many.addressLog.size)
    }

    @Test fun searchOncePerEpisodeOnlyWhenSweepable() {
        fun go(connected: Boolean = false, since: Long? = 0L, now: Long = 13_000L, sweepable: Boolean = true, searched: Boolean = false, running: Boolean = false, fg: Boolean = true) =
            SearchPolicy.shouldSearch(true, fg, true, connected, since, now, sweepable, searched, running)
        assertTrue(go())
        assertFalse(go(now = 5_000L)) // let the normal retries run first (~12 s)
        assertFalse(go(connected = true))
        assertFalse(go(sweepable = false)) // different subnet and no kettle-Wi-Fi path
        assertFalse(go(searched = true)) // once per failure episode
        assertFalse(go(running = true))
        assertFalse(go(fg = false))
        assertFalse(go(since = null))
    }
}
