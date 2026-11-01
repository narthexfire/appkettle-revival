package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.Cups
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.SettingsRepository
import io.github.narthexfire.kettle.net.ConnectionTest
import io.github.narthexfire.kettle.net.LocalWifiPolicy
import io.github.narthexfire.kettle.net.LocalWifiPolicy.Action
import io.github.narthexfire.kettle.protocol.Frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CupsAndKettleWifiTest {

    /* ---------- cups ---------- */

    @Test fun cupsToNearestQuarterWithGlyphs() {
        assertEquals("Empty", Cups.format(0)) 
        assertEquals("<¼ cup", Cups.format(31)) // < 1/8 cup
        assertEquals("¼ cup", Cups.format(32))
        assertEquals("½ cup", Cups.format(125))
        assertEquals("¾ cup", Cups.format(190))
        assertEquals("1 cup", Cups.format(250))
        assertEquals("1¼ cups", Cups.format(310))
        assertEquals("2 cups", Cups.format(500)) // exactly 2 at 250 ml per cup
        assertEquals("2 cups", Cups.format(480)) // 1.92 rounds to the nearest quarter: 2
        assertEquals("2¼ cups", Cups.format(560))
        assertEquals("2½ cups", Cups.format(625))
        assertEquals("2¾ cups", Cups.format(690))
        assertEquals("6¼ cups", Cups.format(1580))
        assertEquals("6¾ cups", Cups.format(1700)) // full kettle at 250 ml/cup
        assertEquals("2 cups", Cups.format(640, 320)) // the manual's example: 640 ml = 2 cups at 320 ml
    }

    @Test fun cupSizePersistsAndDefaults() {
        val list = TestKettles.list.toMutableList().also { it[0] = it[0].copy(cupMl = 240) }
        val back = SettingsRepository.decode(SettingsRepository.encode(list))
        assertEquals(240, back[0].cupMl)
        assertEquals(Cups.DEFAULT_ML, back[1].cupMl)
        assertEquals(Cups.DEFAULT_ML, SettingsRepository.decode("""[{"id":"kitchen","name":"M"}]""")[0].cupMl)
    }

    /* ---------- kettle Wi-Fi settings ---------- */

    @Test fun kettleWifiDefaultsAndMigration() {
        // A fresh install ships no Wi-Fi name or password, and auto-join is off.
        KettleConfig.DEFAULTS.forEach { assertEquals("", it.wifiSsid); assertEquals("", it.wifiPass); assertFalse(it.autoJoinWifi) }
        // Data stored without wifiPass/autoJoinWifi takes those defaults; a stored SSID is kept.
        val olderData = """[{"id":"kitchen","name":"Kitchen-Kettle","wifiSsid":"OldKettleNet"},{"id":"office","name":"Office-Kettle"}]"""
        val d = SettingsRepository.decode(olderData)
        assertEquals("", d[0].wifiPass); assertFalse(d[0].autoJoinWifi)
        assertEquals("", d[1].wifiPass); assertFalse(d[1].autoJoinWifi)
        assertEquals("OldKettleNet", d[0].wifiSsid) // a stored SSID is never overwritten
        // Explicit user choices are kept.
        val off = SettingsRepository.decode("""[{"id":"kitchen","name":"M","wifiPass":"x","autoJoinWifi":false}]""")[0]
        assertEquals("x", off.wifiPass); assertFalse(off.autoJoinWifi)
    }

    /* ---------- auto-join decision ---------- */

    private val base = LocalWifiPolicy.Input(
        foreground = true, sdkSupportsSpecifier = true, selectedId = "kitchen", autoJoin = true,
        kettleSsid = "OldKettleNet", hasPassphrase = true, kettleActive = true,
        linkConnected = false, linkFailed = false, phoneSsid = "HouseWiFi", sameSubnet = false,
        requestedFor = null, failedFor = null,
    )

    @Test fun requestsWhenPhoneIsOnAnotherNetwork() {
        assertEquals(Action.Request, LocalWifiPolicy.decide(base)) // phone on the house Wi-Fi 10.0.0.x, kettle 192.168.1.x
        assertEquals(Action.Request, LocalWifiPolicy.decide(base.copy(sameSubnet = null, linkFailed = true)))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(sameSubnet = null, linkFailed = false))) // try main path first
    }

    @Test fun doesNotRequestWhenNotNeededOrNotAllowed() {
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(linkConnected = true)))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(phoneSsid = "OldKettleNet")))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(sameSubnet = true, linkFailed = true)))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(autoJoin = false)))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(hasPassphrase = false)))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(kettleSsid = " ")))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(kettleActive = false)))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(sdkSupportsSpecifier = false))) // Android 9 and older
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(foreground = false)))
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(failedFor = "kitchen"))) // wait for Try again
    }

    @Test fun keepsOrReleasesAHeldRequest() {
        val held = base.copy(requestedFor = "kitchen")
        assertEquals(Action.None, LocalWifiPolicy.decide(held))
        assertEquals(Action.None, LocalWifiPolicy.decide(held.copy(linkConnected = true))) // stays while in use
        assertEquals(Action.Release, LocalWifiPolicy.decide(held.copy(foreground = false))) // app to background
        assertEquals(Action.Release, LocalWifiPolicy.decide(held.copy(selectedId = "office"))) // switched kettle
        assertEquals(Action.Release, LocalWifiPolicy.decide(held.copy(autoJoin = false))) // turned off
        assertEquals(Action.Release, LocalWifiPolicy.decide(held.copy(kettleActive = false)))
    }

    /* ---------- read-only Test connection ---------- */

    @Test fun testConnectionSendsOnlyKeepAliveAndParsesStatus() {
        val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val received = ByteArrayOutputStream()
        val t = thread {
            server.accept().use { s ->
                val buf = ByteArray(1024)
                val n = s.getInputStream().read(buf)
                received.write(buf, 0, n)
                // Reply like a kettle: keep-alive echo, then a status frame (synthetic Kettle ID).
                s.getOutputStream().write(("##000bKeepConnect&&" +
                    "##0083{\"wifi_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"data3\":\"aa001803000000000000002a360000c800020000226401f400003f\",\"suc\":\"00\",\"seq\":\"54545\"}&&").toByteArray())
                s.getOutputStream().flush()
                Thread.sleep(300)
            }
        }
        val r = ConnectionTest.run("127.0.0.1", server.localPort, timeoutMs = 3000)
        t.join(); server.close()
        assertEquals(Frames.KEEP_ALIVE, received.toString(Charsets.US_ASCII)) // nothing but the keep-alive
        assertTrue(r.message, r.ok)
        assertTrue(r.message, r.message.contains("Standby") && r.message.contains("34 °C") && r.message.contains("GD0-00000-00ab"))
    }

    @Test fun testConnectionValidatesAndReportsErrors() {
        assertFalse(ConnectionTest.run("192.168.1", 6002).ok)
        assertEquals("Not a valid IPv4 address", ConnectionTest.run("abc", 6002).message)
        assertEquals("Port must be 1 to 65535", ConnectionTest.run("192.168.1.100", 70000).message)
        val closed = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).let { val p = it.localPort; it.close(); p }
        val r = ConnectionTest.run("127.0.0.1", closed, timeoutMs = 2000)
        assertFalse(r.ok)
        assertTrue(r.message, r.message.startsWith("Can't reach 127.0.0.1"))
    }
}
