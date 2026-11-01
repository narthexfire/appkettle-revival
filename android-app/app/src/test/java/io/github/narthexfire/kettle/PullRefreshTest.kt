package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.RefreshAction
import io.github.narthexfire.kettle.net.Endpoint
import io.github.narthexfire.kettle.net.KettleConnection
import io.github.narthexfire.kettle.net.LinkPhase
import io.github.narthexfire.kettle.protocol.Frames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class PullRefreshTest {
    @Test fun actionMapping() {
        assertEquals(RefreshAction.Reconnect, RefreshAction.decide(isSetUp = true, enabled = true, connected = false))
        assertEquals(RefreshAction.KeepAlive, RefreshAction.decide(isSetUp = true, enabled = true, connected = true))
        assertEquals(RefreshAction.NotAvailable, RefreshAction.decide(isSetUp = false, enabled = true, connected = false))
        assertEquals(RefreshAction.NotAvailable, RefreshAction.decide(isSetUp = true, enabled = false, connected = false))
    }

    /** Connected pull-to-refresh sends ONLY keep-alives (loopback fake kettle, no firewall prompt). */
    @Test fun pokeSendsOnlyKeepAliveAndReturnsRoundTrip() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val written = Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val out = s.getOutputStream(); val inp = s.getInputStream(); val buf = ByteArray(256)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        val text = String(buf, 0, n)
                        written += text
                        // Echo each keep-alive like the real kettle.
                        repeat(Regex("KeepConnect").findAll(text).count()) { out.write(Frames.KEEP_ALIVE.toByteArray()); out.flush() }
                    }
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = KettleConnection(Endpoint("127.0.0.1", server.localPort, "GD0-00000-00ab"), scope)
        try {
            link.start()
            withTimeout(5_000) { link.info.first { it.phase == LinkPhase.Connected } }
            val rtt = link.poke()
            assertNotNull("keep-alive should be answered", rtt)
            assertTrue(written.joinToString("").replace(Frames.KEEP_ALIVE, "").isEmpty()) // nothing but keep-alives
        } finally {
            link.disconnect(); scope.cancel(); server.close()
        }
    }
}
