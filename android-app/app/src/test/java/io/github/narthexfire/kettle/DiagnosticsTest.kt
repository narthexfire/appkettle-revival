package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.net.LinkInfo
import io.github.narthexfire.kettle.net.PhoneNet
import io.github.narthexfire.kettle.ui.Diagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val m = TestKettles.kitchen

    @Test fun kettleIdFirstAndInCopyText() {
        val s = Diagnostics.sections(m, null, PhoneNet(), 0L, "1.0", "")
        assertTrue(s[0].items[0].label == "Kettle ID" && s[0].items[0].value == "GD0-00000-00ab")
        val text = Diagnostics.asText(s, 0L)
        assertTrue(text.contains("Kettle ID: GD0-00000-00ab"))
        assertTrue(text.contains("AppKettle Ltd (UK, dissolved 2023)"))
        assertTrue(text.contains("MAC address: 00:26:29:00:00:01"))
        assertTrue(text.contains("Wi-Fi: 2.4 GHz only"))
    }

    @Test fun idMismatchWarns() {
        assertTrue(Diagnostics.idMismatch(m, LinkInfo(reportedImei = "GD0-99999-0000")))
        assertFalse(Diagnostics.idMismatch(m, LinkInfo(reportedImei = "GD0-00000-00ab")))
        assertFalse(Diagnostics.idMismatch(m, LinkInfo()))
    }

    @Test fun subnetWarning() {
        assertTrue(Diagnostics.subnetWarning(m, PhoneNet(onWifi = true, ipv4 = "10.0.0.12", prefixLength = 24)))
        assertFalse(Diagnostics.subnetWarning(m, PhoneNet(onWifi = true, ipv4 = "192.168.1.101", prefixLength = 24)))
        val text = Diagnostics.asText(
            Diagnostics.sections(m, null, PhoneNet(onWifi = true, ipv4 = "10.0.0.12", prefixLength = 24), 0L, "1.0", "x"), 0L,
        )
        assertTrue(text.contains("Your phone is on a different network from this kettle"))
    }
}
