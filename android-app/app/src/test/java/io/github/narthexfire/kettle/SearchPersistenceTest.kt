package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.net.KettleSearch
import io.github.narthexfire.kettle.net.SearchPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class SearchPersistenceTest {
    @Test fun savedIpEqualToOwnIpTriggersImmediateSearch() {
        assertTrue(SearchPolicy.savedIpIsOwn("192.168.1.101", listOf("192.168.1.101", null)))
        assertTrue(SearchPolicy.savedIpIsOwn("192.168.1.101", listOf(null, "192.168.1.101"))) // kettle-Wi-Fi path
        assertFalse(SearchPolicy.savedIpIsOwn("192.168.1.100", listOf("192.168.1.101")))
        assertFalse(SearchPolicy.savedIpIsOwn("", listOf("")))
        // Immediately (no 12 s wait, even before any offline time has been recorded) ...
        assertTrue(SearchPolicy.shouldSearch(true, true, true, false, null, 0L, true, false, false, savedIpIsOwn = true))
        assertTrue(SearchPolicy.shouldSearch(true, true, true, false, 1_000L, 1_500L, true, false, false, savedIpIsOwn = true))
        // ... but still once per episode, only in the foreground, never twice at once.
        assertFalse(SearchPolicy.shouldSearch(true, true, true, false, null, 0L, true, true, false, savedIpIsOwn = true))
        assertFalse(SearchPolicy.shouldSearch(true, false, true, false, null, 0L, true, false, false, savedIpIsOwn = true))
        assertFalse(SearchPolicy.shouldSearch(true, true, true, false, null, 0L, true, false, true, savedIpIsOwn = true))
        // Without the collision the normal ~12 s wait applies.
        assertFalse(SearchPolicy.shouldSearch(true, true, true, false, 1_000L, 1_500L, true, false, false))
    }

    @Test fun searchOrderNearOldAddressFirstWholeSubnetExceptOwnIp() {
        val c = KettleSearch.candidates("192.168.1.101") // phone took the kettle's old address
        assertEquals(253, c.size) // .1-.254 except our own .101 (the router is NOT skipped any more)
        assertTrue("192.168.1.1" in c)
        val o = KettleSearch.ordered(c, "192.168.1.101")
        assertEquals(listOf("192.168.1.100", "192.168.1.102", "192.168.1.99", "192.168.1.103"), o.take(4))
        assertEquals(10, o.takeWhile { kotlin.math.abs(it.substringAfterLast('.').toInt() - 101) <= 5 }.size) // ±5 minus own
        assertEquals(c.toSet(), o.toSet()) // nothing dropped
    }

    @Test fun retriesEveryCandidateSeveralTimesPerRoundAndAsksForHintsEachRound() = runBlocking {
        val sockets = AtomicInteger(); val hintCalls = AtomicInteger(); val rounds = mutableListOf<Int>()
        val m = KettleSearch.sweep(
            listOf("127.0.0.2"), 6002, "GD0-00000-00ab", { sockets.incrementAndGet(); Socket() },
            rounds = 6, gapMs = 5, tries = 2, connectTimeoutMs = 200,
            extraFirst = { hintCalls.incrementAndGet(); emptyList() }, onRound = { rounds += it },
        )
        assertNull(m)
        assertEquals(KettleSearch.ROUNDS, 6)
        assertEquals(12, sockets.get()) // 6 rounds x 2 tries for the one candidate
        assertEquals(6, hintCalls.get()) // UDP/ARP hints every round
        assertEquals(listOf(1, 2, 3, 4, 5, 6), rounds)
    }

    @Test fun hintsAreProbedFirstAndStopTheSearch() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        Thread {
            runCatching {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(64))
                    s.getOutputStream().write("##0083{\"wifi_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"data3\":\"aa001803000000000000002a360000c800020000226401f400003f\",\"suc\":\"00\",\"seq\":\"1\"}&&".toByteArray())
                    Thread.sleep(200)
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            val m = KettleSearch.sweep(emptyList(), server.localPort, "GD0-00000-00ab", { Socket() }, rounds = 3, gapMs = 5,
                extraFirst = { listOf("127.0.0.1") })
            assertEquals("127.0.0.1", m?.ip)
        } finally { server.close() }
    }

    @Test fun arpNeighborsParsedWhenReadableAndEmptyWhenDenied() {
        val table = listOf(
            "IP address       HW type     Flags       HW address            Mask     Device",
            "192.168.1.102   0x1         0x2         00:26:29:00:00:01     *        wlan0",
            "192.168.1.1     0x1         0x2         02:00:00:00:00:02     *        wlan0",
            "192.168.1.150   0x1         0x0         00:00:00:00:00:00     *        wlan0",
            "10.0.0.5      0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan1",
        )
        assertEquals(listOf("192.168.1.102", "192.168.1.1"), KettleSearch.arpNeighbors("192.168.1.101") { table })
        assertTrue(KettleSearch.arpNeighbors("192.168.1.101") { throw SecurityException("denied") }.isEmpty())
    }
}
