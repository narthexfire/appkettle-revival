package io.github.narthexfire.kettle.ui

import io.github.narthexfire.kettle.data.DeviceFacts
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.net.LinkInfo
import io.github.narthexfire.kettle.net.LinkPhase
import io.github.narthexfire.kettle.net.PhoneNet
import io.github.narthexfire.kettle.net.WifiTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One labeled line of the Diagnostics card, also used for "Copy details". */
data class DiagItem(
    val label: String, val value: String, val note: String? = null, val warn: Boolean = false, val mono: Boolean = false,
    /** Leading part of [value] shown 50 % larger (the cups in "3¾ cups · 912 ml"). */
    val lead: String? = null,
)

data class DiagSection(val title: String, val items: List<DiagItem>)

object Diagnostics {
    private fun time(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))

    private fun ago(now: Long, t: Long): String {
        val s = ((now - t) / 1000).coerceAtLeast(0)
        return if (s < 120) "$s s ago" else if (s < 7200) "${s / 60} min ago" else "${s / 3600} h ago"
    }

    fun idMismatch(cfg: KettleConfig, link: LinkInfo?): Boolean =
        link?.reportedImei != null && !link.reportedImei.equals(cfg.imei.trim(), ignoreCase = true)

    fun subnetWarning(cfg: KettleConfig, phone: PhoneNet): Boolean =
        WifiTracker.sameSubnet(phone.ipv4, phone.prefixLength, cfg.ip) == false

    fun sections(
        cfg: KettleConfig, link: LinkInfo?, phone: PhoneNet, now: Long, version: String, buildDate: String,
        lastCrash: String? = null,
        local: io.github.narthexfire.kettle.net.LocalWifiState = io.github.narthexfire.kettle.net.LocalWifiState.Idle,
        concurrency: Boolean? = null,
        search: io.github.narthexfire.kettle.ui.SearchStatus? = null,
    ): List<DiagSection> {
        val kettle = buildList {
            add(DiagItem("Kettle ID", cfg.imei.ifBlank { "not set" }, "(the kettle's built-in device ID, also called IMEI)"))
            if (idMismatch(cfg, link)) add(
                DiagItem("Warning", "The kettle reports ID ${link!!.reportedImei}, but Settings has ${cfg.imei}.", warn = true),
            )
            add(DiagItem("Name", cfg.name))
            add(DiagItem("Device", cfg.device))
            add(DiagItem("Manufacturer", DeviceFacts.MANUFACTURER))
            add(DiagItem("Model", DeviceFacts.MODEL))
            add(DiagItem("Capacity", DeviceFacts.CAPACITY))
            add(DiagItem("Wi-Fi", DeviceFacts.WIFI))
            add(DiagItem("Firmware", DeviceFacts.FIRMWARE))
            add(DiagItem("MAC address", cfg.mac.ifBlank { "not set" }))
            add(DiagItem("IP address and port", if (cfg.ip.isBlank()) "not set" else "${cfg.ip}:${cfg.port}"))
            add(DiagItem("Expected Wi-Fi network", cfg.wifiSsid.ifBlank { "not set" }))
            add(DiagItem("Measured boil point", cfg.measuredBoilPoint?.let { "$it °C" } ?: "not measured yet",
                if (cfg.boilHistory.isNotEmpty()) "Last boils: ${cfg.boilHistory.joinToString(", ")} °C" else null))
        }
        val conn = buildList {
            val phase = when {
                !cfg.isSetUp -> "Not set up"
                !cfg.enabled -> "Turned off in Settings"
                link == null -> "Offline"
                link.phase == LinkPhase.Connected -> "Connected"
                link.phase == LinkPhase.Connecting -> "Connecting"
                else -> "Offline"
            }
            add(DiagItem("State", phase))
            add(DiagItem("Connected since", link?.connectedSince?.takeIf { it > 0 }?.let(::time) ?: "—"))
            add(DiagItem("Last status received", link?.lastStatusAt?.takeIf { it > 0 }?.let { ago(now, it) } ?: "never"))
            add(DiagItem("Reconnect count", (link?.reconnectCount ?: 0).toString()))
            add(DiagItem("Last error", link?.lastError ?: "none"))
            add(DiagItem("Kettle search", when {
                search?.running == true -> "Searching the network… round ${search.round}/${search.rounds}"
                search?.result != null -> search.result + " (" + time(search.at) + ")"
                else -> "Not needed so far"
            }))
            if (cfg.addressLog.isNotEmpty()) add(DiagItem("Address updated automatically", cfg.addressLog.reversed().joinToString("\n")))
            add(DiagItem("Keep-alive round trip", link?.keepAliveRttMs?.let { "$it ms" } ?: "—"))
            add(DiagItem("Kettle ID reported by the kettle", link?.reportedImei ?: "—"))
        }
        val st = link?.status
        val status = if (st == null) listOf(DiagItem("Status", "No status received yet")) else listOf(
            DiagItem("State", st.state.label),
            DiagItem("Temperature", "${st.temperatureC} °C"),
            DiagItem("Target", "${st.targetC} °C"),
            io.github.narthexfire.kettle.data.Cups.format(st.waterMl, cfg.cupMl).let { cups ->
                DiagItem("Water", "$cups · ${st.waterMl} ml", note = "1 cup = ${cfg.cupMl} ml", lead = cups)
            },
            DiagItem("Keep-warm seconds left", st.keepWarmSecondsLeft.toString()),
            DiagItem("Raw status content", st.rawContent, mono = true),
            DiagItem("Raw last status frame", link.lastRawFrame ?: "—", mono = true),
        )
        val ph = buildList {
            add(DiagItem("On Wi-Fi", if (phone.onWifi) "Yes" else "No"))
            add(DiagItem("Wi-Fi network", when {
                phone.ssid != null -> phone.ssid
                phone.ssidNeedsPermission -> "Hidden: Android needs the location permission to show it"
                !phone.onWifi -> "Not on Wi-Fi"
                else -> "Unknown (turn on Location to see it)"
            }))
            add(DiagItem("IP address", phone.ipv4 ?: "—"))
            add(DiagItem("Subnet", phone.prefixLength?.let { "/$it (${WifiTracker.maskString(it)})" } ?: "—"))
            val onKettleWifi = local is io.github.narthexfire.kettle.net.LocalWifiState.Connected && local.kettleId == cfg.id
            add(DiagItem("Path in use", if (onKettleWifi) "Kettle Wi-Fi (local-only)" else "Main Wi-Fi"))
            if (onKettleWifi) {
                val l = local as io.github.narthexfire.kettle.net.LocalWifiState.Connected
                add(DiagItem("Phone IP on that path", (l.ipv4 ?: "—") + (l.prefix?.let { " /$it" } ?: "")))
            }
            add(DiagItem("Kettle Wi-Fi auto-join", when {
                !cfg.autoJoinWifi -> "Off"
                local is io.github.narthexfire.kettle.net.LocalWifiState.Requesting && local.kettleId == cfg.id -> "Joining ${local.ssid}…"
                local is io.github.narthexfire.kettle.net.LocalWifiState.Unavailable && local.kettleId == cfg.id -> "Couldn't join ${local.ssid}"
                onKettleWifi -> "Joined ${cfg.wifiSsid}"
                else -> "On (used when the kettle isn't reachable on the main Wi-Fi)"
            }))
            add(DiagItem("Phone supports staying on main Wi-Fi at the same time", when (concurrency) {
                true -> "Yes"; false -> "No"; null -> "Unknown (needs Android 12)"
            }))
            if (cfg.ip.isNotBlank() && subnetWarning(cfg, phone)) add(
                DiagItem("Warning", "Your phone is on a different network from this kettle (phone ${phone.ipv4}, kettle ${cfg.ip}).", warn = true),
            )
            if (cfg.wifiSsid.isNotBlank() && phone.ssid != null && phone.ssid != cfg.wifiSsid) add(
                DiagItem("Warning", "This kettle expects Wi-Fi \"${cfg.wifiSsid}\"; the phone is on \"${phone.ssid}\".", warn = true),
            )
        }
        val cmds = listOf(DiagItem("Commands and acks", link?.commandLog?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "none yet", mono = true))
        val app = listOfNotNull(DiagItem("Version", version), buildDate.takeIf { it.isNotBlank() }?.let { DiagItem("Build date", it) })
        return listOf(
            DiagSection("Kettle", kettle), DiagSection("Connection", conn), DiagSection("Last status", status),
            DiagSection("Command log", cmds), DiagSection("Phone", ph), DiagSection("App", app),
            DiagSection("Last crash", if (lastCrash.isNullOrBlank()) listOf(DiagItem("Last crash", "none recorded"))
                else listOf(DiagItem("Crash report", lastCrash.trim(), mono = true))),
        )
    }

    fun asText(sections: List<DiagSection>, now: Long): String = buildString {
        appendLine("Kettle app diagnostics, ${time(now)}")
        sections.forEach { s ->
            appendLine()
            appendLine(s.title.uppercase())
            s.items.forEach { appendLine("${it.label}: ${it.value}" + (it.note?.let { n -> " $n" } ?: "")) }
        }
    }
}
