package io.github.narthexfire.kettle.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.narthexfire.kettle.R
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.net.PhoneNet
import io.github.narthexfire.kettle.ui.DiagItem
import io.github.narthexfire.kettle.ui.Diagnostics
import io.github.narthexfire.kettle.ui.KettleView
import io.github.narthexfire.kettle.ui.components.InfoRow
import io.github.narthexfire.kettle.ui.components.KIcons
import io.github.narthexfire.kettle.ui.components.SecondaryButton
import io.github.narthexfire.kettle.ui.components.ZoneCard
import io.github.narthexfire.kettle.ui.components.ZoneHeading
import io.github.narthexfire.kettle.ui.theme.K

/* ---------- Diagnostics ---------- */

@Composable
fun DiagnosticsScreen(
    view: KettleView, phone: PhoneNet, now: Long, version: String, buildDate: String,
    onBack: () -> Unit, onReconnect: () -> Unit, onCopy: (String) -> Unit, onGrantLocation: () -> Unit,
    lastCrash: String? = null, onClearCrash: () -> Unit = {},
    local: io.github.narthexfire.kettle.net.LocalWifiState = io.github.narthexfire.kettle.net.LocalWifiState.Idle, concurrency: Boolean? = null,
    onChangeAddress: () -> Unit = {},
    search: io.github.narthexfire.kettle.ui.SearchStatus? = null, onSearch: () -> Unit = {},
) {
    // This screen uses half-size text (14 sp minimum, raw frames 13 sp); other screens are unchanged.
    androidx.compose.runtime.CompositionLocalProvider(
        io.github.narthexfire.kettle.ui.theme.LocalKettleType provides io.github.narthexfire.kettle.ui.theme.KTypeCompact,
    ) { DiagnosticsContent(view, phone, now, version, buildDate, onBack, onReconnect, onCopy, onGrantLocation, lastCrash, onClearCrash, local, concurrency, onChangeAddress, search, onSearch) }
}

@Composable
private fun DiagnosticsContent(
    view: KettleView, phone: PhoneNet, now: Long, version: String, buildDate: String,
    onBack: () -> Unit, onReconnect: () -> Unit, onCopy: (String) -> Unit, onGrantLocation: () -> Unit,
    lastCrash: String?, onClearCrash: () -> Unit,
    local: io.github.narthexfire.kettle.net.LocalWifiState, concurrency: Boolean?,
    onChangeAddress: () -> Unit,
    search: io.github.narthexfire.kettle.ui.SearchStatus?, onSearch: () -> Unit,
) {
    val c = K.c
    val sections = Diagnostics.sections(view.config, view.link, phone, now, version, buildDate, lastCrash, local, concurrency, search)
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    SubScreen("${view.config.name} details", onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Reconnect now", Modifier.weight(1f), onClick = onReconnect)
            SecondaryButton(if (copied) "Copied \u2713" else "Copy details", Modifier.weight(1f)) { onCopy(Diagnostics.asText(sections, now)); copied = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Change address", Modifier.weight(1f), onClick = onChangeAddress)
            SecondaryButton(if (search?.running == true) "Searching\u2026" else "Search for kettle", Modifier.weight(1f),
                enabled = search?.running != true, onClick = onSearch)
        }
        sections.forEach { s ->
            ZoneCard(c.status) {
                ZoneHeading(if (s.title == "Kettle") KIcons.gauge else if (s.title == "Phone") KIcons.wifi else KIcons.pulse, s.title, c.status)
                s.items.forEach { item -> DiagRow(item) }
                if (s.title == "Last crash" && !lastCrash.isNullOrBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Copy crash report", Modifier.weight(1f)) { onCopy(lastCrash) }
                        SecondaryButton("Clear", Modifier.weight(1f), onClick = onClearCrash)
                    }
                }
                if (s.title == "Phone" && phone.ssidNeedsPermission) {
                    SecondaryButton("Allow Wi-Fi name (location)", Modifier.fillMaxWidth(), onClick = onGrantLocation)
                }
            }
        }
        Para("No button here sends commands to the kettle.")
    }
}

@Composable
private fun DiagRow(item: DiagItem) {
    val c = K.c
    if (item.warn) {
        Text("⚠ " + item.value, style = K.t.title, color = c.accentText)
    } else {
        if (item.lead != null && item.value.startsWith(item.lead)) {
            Column(Modifier.fillMaxWidth()) {
                Text(item.label, style = K.t.secondary, color = c.ink2)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = K.t.title.fontSize * 1.5f, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                        append(item.lead); pop()
                        append(item.value.removePrefix(item.lead))
                    },
                    style = K.t.title, color = c.ink,
                )
                if (item.note != null) Text(item.note, style = K.t.secondary, color = c.ink2)
            }
        } else {
            InfoRow(item.label, item.value, note = item.note, mono = item.mono)
        }
    }
}

/* ---------- About ---------- */

@Composable
fun AboutScreen(kettles: List<KettleConfig>, version: String, buildDate: String, onBack: () -> Unit, onLicenses: () -> Unit) {
    val c = K.c
    SubScreen("About", onBack) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            AppTitle(48.dp)
        }
        ZoneCard(c.status) {
            ZoneHeading(KIcons.info, "Kettle", c.status)
            Text("Kettle: the Android app for AppKettle smart kettles", style = K.t.title, color = c.ink)
            Para("Unofficial. Not affiliated with, endorsed by or supported by the kettle's maker. \"AppKettle\" is the maker's name, used here only to say which kettles this app works with.")
            Para("Provided as is, without warranty and without support. Free and open source (MIT license).")
            InfoRow("Version", version)
            Para("Controls your AppKettle kettle over your home Wi-Fi: no cloud, no account, no tracking.")
        }
        ZoneCard(c.temp) {
            ZoneHeading(KIcons.gauge, "Your kettles", c.temp)
            kettles.forEach { k ->
                InfoRow(
                    k.name,
                    if (k.isSetUp) "Kettle ID ${k.imei}\nIP ${k.ip}:${k.port}" else "Not set up",
                )
            }
        }
        ZoneCard(c.preset) {
            ZoneHeading(KIcons.star, "Privacy", c.preset)
            Para("No analytics, no internet; talks only to your kettles on your local network.")
        }
        ZoneCard(c.warm) {
            ZoneHeading(KIcons.flame, "Credits", c.warm)
            Para("Protocol knowledge from the public work of tinaught/appkettleapi, longmover/ha_addons (MIT) and Michael Hutchinson's appkettle (MIT). Thank you.")
            Para("Typeface: Manrope (SIL Open Font License 1.1).")
            SecondaryButton("Open-source licenses", Modifier.fillMaxWidth(), onClick = onLicenses)
        }
    }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ofl = remember { runCatching { ctx.resources.openRawResource(R.raw.manrope_ofl).bufferedReader().readText() }.getOrDefault("") }
    val c = K.c
    SubScreen("Licenses", onBack) {
        ZoneCard(c.status) {
            ZoneHeading(KIcons.info, "AndroidX, Jetpack Compose, Kotlin", c.status)
            Para("Copyright The Android Open Source Project and JetBrains s.r.o. Licensed under the Apache License, Version 2.0. You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0. Distributed on an \"AS IS\" basis, without warranties or conditions of any kind.")
        }
        ZoneCard(c.status) {
            ZoneHeading(KIcons.info, "Manrope typeface", c.status)
            SelectionContainer { Text(ofl, style = K.t.mono, color = c.ink) }
        }
    }
}

/* ---------- Help ---------- */

@Composable
fun HelpScreen(onBack: () -> Unit, onShowDetails: () -> Unit) {
    val c = K.c
    SubScreen("Help", onBack) {
        ZoneCard(c.status) {
            ZoneHeading(KIcons.wifi, "Getting connected", c.status)
            Para("The phone must be on the same network as the kettle.")
            Para("First time: put the phone on the same Wi-Fi as the kettle, open Settings, tap the kettle, then tap Search for kettle. The app finds the kettle, saves its address and Kettle ID, and connects; the dot by the kettle name turns green.")
            Para("If the kettle is not on any Wi-Fi yet (or its old network is gone), it has to be paired first. The app can't do that; see the project's re-pairing guide (docs/re-pairing.md).")
            Para("If the app says it can't reach the kettle, check your phone's Wi-Fi is on the kettle's network.")
            Para("Automatic: with \"Connect to the kettle's Wi-Fi automatically\" on (kettle Settings), the app joins the kettle's Wi-Fi by itself whenever the kettle can't be reached on the phone's current network. The first time, Android asks you to approve it. The connection is local-only and is released when you leave the app. Some phones can stay on their main Wi-Fi at the same time; others leave it while the app is open (Kettle details shows which).")
        }
        ZoneCard(c.temp) {
            ZoneHeading(KIcons.power, "Buttons explained", c.temp)
            Bullet("Boil / Heat: heats to the chosen temperature. 100° is \"Boil\"; the kettle stops by itself when the water boils (below 100 °C at altitude, for example about 95 °C at 1,500 m). You can choose anything from 40 to 100.")
            Bullet("Stop: switches the kettle off straight away.")
            Bullet("Temperature − and +: one degree per tap; hold to go faster. You can also drag the slider or use the chips in Options.")
            Bullet("Presets: tap to choose, long-press to edit, or set temperature and keep-warm in Options and tap Save as preset. Tapping a preset sets the temperature and keep warm; then press Heat (or Boil) to start. Each kettle has its own six presets, also editable in its Settings.")
            Bullet("Keep warm: holds the water hot for 5 to 30 minutes, or Off. Chosen before a boil, it starts when the water boils. After a boil (water 60° or more), tapping a time keeps the water warm straight away; while keeping warm, Off stops it and another time restarts the countdown.")
        }
        ZoneCard(c.preset) {
            ZoneHeading(KIcons.drop, "Reading the kettle picture", c.preset)
            Para("The water color shows its temperature: blue cold, red hot (bright red at the boil). Bubbles mean it's heating; steam and a glowing base light show it is hot or keeping warm.")
            Para("The picture always shows the real water level, blue when cold and red when hot (bright red at the boil), with MIN 250 ml and MAX 1.7 L marked. The amount is shown in ml and in cups (250 ml = 1 cup, to the nearest quarter; the cup size can be changed in the kettle's Settings).")
        }
        ZoneCard(c.warm) {
            ZoneHeading(KIcons.help, "Troubleshooting", c.warm)
            Bullet("Kettle offline: check it is on its base and powered, check the phone's Wi-Fi, then use the Reconnect button.")
            Bullet("Pull down to reconnect: on the main screen, pull the cards down. If the kettle is offline the app reconnects (and searches the network if needed); if it is connected it just checks the kettle answers.")
            Bullet("If the kettle's address changes (for example after a power cut), the app finds it automatically: it searches the kettle's network, checks the Kettle ID, and saves the new address. It keeps trying for about a minute, because the kettle may be dozing; if your phone has been given the kettle's old address, it starts searching straight away. The search only listens: it sends the kettle's keep-alive and a UDP discovery probe (not a kettle command) on the local network. You can also tap Search for kettle in Kettle details or Settings.")
            Bullet("If the kettle has a new address, tap Change address (on the \"Can't reach\" card or in Kettle details), enter the IP address and port, and use Test connection. The test only listens to the kettle; it never sends a command.")
            Bullet("No water, or it won't start: fill above the MIN mark (250 ml). The app warns when the kettle reports less, and asks before sending.")
            Bullet("Kettle display and Wi-Fi light off? If the kettle loses Wi-Fi for 20 minutes it goes into off mode. Press Power on the base to wake it; it reconnects by itself.")
            Bullet("Water reading wrong: recalibrate the scale. With the kettle off the base, hold + and Keep Warm until it beeps (the display shows \"000\"), then put it back.")
            Bullet("Show kettle details: the Kettle ID, MAC address and IP address, the connection state, the last reading, and the phone's own Wi-Fi network and IP address, with a warning if the phone is on a different network. \"Copy details\" copies it all as text to share.")
            SecondaryButton("Show kettle details", Modifier.fillMaxWidth(), onClick = onShowDetails)
        }
        ZoneCard(c.status) {
            ZoneHeading(KIcons.power, "Using the kettle's own buttons", c.status)
            Bullet("Power: press once to wake, press again to boil, press while heating to stop, and press and hold to turn off.")
            Bullet("+ / \u2212: set the temperature. Press both together to switch between \u00B0C and \u00B0F.")
            Bullet("Keep Warm: holds the temperature (only at 60 \u00B0C or above). The app sends keep warm only when the water (or the target) is 60\u00B0 or more; in Standby its keep-warm buttons just choose the time for your next boil.")
            Bullet("Wi-Fi button: only for pairing. To start pairing mode, press and hold the Wi-Fi button for about 5 seconds (or longer) until its light flashes fast, then release it. If nothing sends pairing data, the kettle returns to its saved network after about 50 seconds.")
            Bullet("Scale recalibration: with the kettle off the base, hold + and Keep Warm until it beeps (the display shows \"000\"), then put it back.")
        }
        ZoneCard(c.status) {
            ZoneHeading(KIcons.wifi, "Only hold the Wi-Fi button to re-pair", c.status)
            Para("Don't do this unless you intend to re-pair the kettle (see the re-pairing guide). To start pairing mode, press and hold the Wi-Fi button for about 5 seconds (or longer) until its light flashes fast, then release it. If nothing sends pairing data, the kettle returns to its saved network after about 50 seconds.")
        }
    }
}
