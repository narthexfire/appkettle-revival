package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.SettingsRepository
import io.github.narthexfire.kettle.net.LocalWifi
import io.github.narthexfire.kettle.net.LocalWifiPolicy
import io.github.narthexfire.kettle.net.LocalWifiPolicy.Action
import io.github.narthexfire.kettle.ui.Notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiPolicyAndNoticeTest {
    private val base = LocalWifiPolicy.Input(
        foreground = true, sdkSupportsSpecifier = true, selectedId = "kitchen", autoJoin = true,
        kettleSsid = "KettleNet", hasPassphrase = true, kettleActive = true,
        linkConnected = false, linkFailed = false, phoneSsid = "HouseWiFi", sameSubnet = false,
        requestedFor = null, failedFor = null, passKey = LocalWifi.passKey("kettlepass1"),
    )

    @Test fun changedWifiDetailsReRequestImmediately() {
        // Held request was for the old network: release and request again with the new values.
        val held = base.copy(requestedFor = "kitchen", heldSsid = "OldKettleNet", heldPassKey = LocalWifi.passKey("oldpass123"))
        assertEquals(Action.Rerequest, LocalWifiPolicy.decide(held))
        assertEquals(Action.Rerequest, LocalWifiPolicy.decide(held.copy(heldSsid = "KettleNet"))) // passphrase changed only
        assertEquals(Action.None, LocalWifiPolicy.decide(held.copy(heldSsid = "KettleNet", heldPassKey = LocalWifi.passKey("kettlepass1"))))
        assertEquals(Action.Release, LocalWifiPolicy.decide(held.copy(foreground = false))) // still released in background
    }

    @Test fun anOldSsidFailureDoesNotBlockTheNewSsid() {
        val failedOld = base.copy(failedFor = "kitchen", failedSsid = "OldKettleNet")
        assertEquals(Action.Request, LocalWifiPolicy.decide(failedOld)) // new SSID tried at once, no "Try again" needed
        assertEquals(Action.None, LocalWifiPolicy.decide(base.copy(failedFor = "kitchen", failedSsid = "KettleNet"))) // same SSID: wait
    }

    @Test fun staleConnectionLinesClearOnceConnected() {
        val t = 1_000_000L
        val conn = Notice(Notice.Kind.Connection, "Not reachable at 192.168.1.100; searching the network", t)
        assertTrue(Notice.visible(conn, connected = false, lastStatusAt = t - 5_000, now = t + 1_000))
        assertFalse(Notice.visible(conn, connected = true, lastStatusAt = t - 5_000, now = t + 1_000)) // Connected
        assertFalse(Notice.visible(conn, connected = false, lastStatusAt = t + 500, now = t + 1_000)) // fresh status
        val found = Notice(Notice.Kind.Status, "Found Kitchen-Kettle at 192.168.1.101 (was .100)", t)
        assertTrue(Notice.visible(found, connected = true, lastStatusAt = t + 500, now = t + 30_000))
        assertFalse(Notice.visible(found, connected = true, lastStatusAt = t + 500, now = t + 61_000)) // <= 60 s
        assertTrue(Notice.visible(Notice(Notice.Kind.Error, "Kettle refused …", t), true, t + 500, t + 120_000))
        assertFalse(Notice.visible(Notice(Notice.Kind.Preset, "Saved to Oolong", t), true, 0, t))
    }

    @Test fun noBuiltInKettleNetworkAndStoredValuesAreKept() {
        KettleConfig.DEFAULTS.forEach {
            assertEquals("", it.wifiSsid); assertEquals("", it.wifiPass); assertFalse(it.autoJoinWifi)
        }
        val stored = """[{"id":"kitchen","name":"Kitchen-Kettle","wifiSsid":"KettleNet","wifiPass":"kettlepass1","autoJoinWifi":true},
            {"id":"office","name":"Office-Kettle","wifiSsid":"Other","wifiPass":"x","autoJoinWifi":false}]"""
        val d = SettingsRepository.decode(stored)
        assertEquals("KettleNet", d[0].wifiSsid)
        assertEquals("Other", d[1].wifiSsid); assertEquals("x", d[1].wifiPass); assertFalse(d[1].autoJoinWifi)
    }
}
