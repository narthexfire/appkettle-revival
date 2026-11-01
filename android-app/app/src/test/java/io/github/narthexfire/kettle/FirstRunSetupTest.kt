package io.github.narthexfire.kettle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.narthexfire.kettle.net.ConnectionTest
import io.github.narthexfire.kettle.net.KettleSearch
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** First-time setup: find a kettle with no saved Kettle ID; Test connection reports the ID. Synthetic IDs only. */
class FirstRunSetupTest {
    private fun status(imei: String) =
        "##0083{\"wifi_cmd\":\"62\",\"imei\":\"$imei\",\"data3\":\"aa001803000000000000002a360000c800020000226401f400003f\",\"suc\":\"00\",\"seq\":\"54545\"}&&"

    /** A fake kettle: reads the keep-alive, answers with one status frame. Accepts [n] connections. */
    private fun fakeKettle(imei: String, n: Int = 4): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            repeat(n) {
                runCatching {
                    server.accept().use { s ->
                        s.getInputStream().read(ByteArray(64))
                        s.getOutputStream().write(status(imei).toByteArray()); s.getOutputStream().flush()
                        Thread.sleep(200)
                    }
                }
            }
        }
        return server
    }

    @Test fun acceptsNewSkipsKettlesAlreadyInTheApp() {
        assertTrue(KettleSearch.acceptsNew("GD0-00000-0001", emptySet()))
        assertFalse(KettleSearch.acceptsNew("GD0-00000-0001", setOf("gd0-00000-0001")))
        assertFalse(KettleSearch.acceptsNew(null, emptySet()))
        assertFalse(KettleSearch.acceptsNew(" ", emptySet()))
    }

    @Test fun blankExpectedIdAdoptsTheFirstKettleFound() {
        val server = fakeKettle("GD0-00000-0001")
        server.use {
            val r = KettleSearch.probe("127.0.0.1", server.localPort, "")
            assertEquals(KettleSearch.Probe.Match("127.0.0.1", "GD0-00000-0001"), r)
        }
    }

    @Test fun blankExpectedIdIgnoresAKettleAlreadySetUp() {
        val server = fakeKettle("GD0-00000-0001")
        server.use {
            val r = KettleSearch.probe("127.0.0.1", server.localPort, "", skipImeis = setOf("GD0-00000-0001"))
            assertTrue(r is KettleSearch.Probe.WrongKettle)
            val none = runBlocking {
                KettleSearch.sweep(listOf("127.0.0.1"), server.localPort, "", { Socket() }, rounds = 1, gapMs = 0,
                    skipImeis = setOf("GD0-00000-0001"))
            }
            assertNull(none)
        }
    }

    @Test fun connectionTestReportsTheKettleId() {
        val server = fakeKettle("GD0-00000-0002", n = 1)
        server.use {
            val r = ConnectionTest.run("127.0.0.1", server.localPort)
            assertTrue(r.message, r.ok)
            assertEquals("GD0-00000-0002", r.imei)
        }
    }
}
