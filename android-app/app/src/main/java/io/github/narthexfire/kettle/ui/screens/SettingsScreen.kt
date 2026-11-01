package io.github.narthexfire.kettle.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.narthexfire.kettle.data.BoilPointLearner
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.net.WifiTracker
import io.github.narthexfire.kettle.ui.KettleView
import io.github.narthexfire.kettle.ui.components.KIcons
import io.github.narthexfire.kettle.ui.components.PrimaryButton
import io.github.narthexfire.kettle.ui.components.SecondaryButton
import io.github.narthexfire.kettle.ui.components.StatusDot
import io.github.narthexfire.kettle.ui.components.ZoneCard
import io.github.narthexfire.kettle.ui.components.ZoneHeading
import io.github.narthexfire.kettle.ui.theme.K

@Composable
fun SettingsScreen(kettles: List<KettleView>, now: Long, onBack: () -> Unit, onAdd: () -> Unit = {}, onEdit: (String) -> Unit) {
    val c = K.c
    SubScreen("Settings", onBack) {
        ZoneCard(c.status) {
            ZoneHeading(KIcons.gear, "Kettles", c.status)
            kettles.forEach { k ->
                val (sub, on) = pickerSub(k, now)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onEdit(k.config.id) }.heightIn(min = 76.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(if (on) c.ok else c.off, false, true, 12.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.config.name, style = K.t.title, color = c.ink)
                        Text(sub, style = K.t.secondary, color = c.ink2)
                    }
                    Icon(KIcons.chevRight, "Edit", tint = c.ink2, modifier = Modifier.size(24.dp))
                }
            }
        }
        Para("To set up a kettle, open one marked \"Not set up\". With the phone on the same Wi-Fi as the kettle, tap Search for kettle: the app finds it and fills in its address and Kettle ID. Or type the kettle's IP address and tap Test connection.")
        SecondaryButton("Add another kettle", Modifier.fillMaxWidth(), onClick = onAdd)
    }
}

@Composable
fun KettleEditor(
    view: KettleView, onBack: () -> Unit, onSave: (KettleConfig) -> Unit,
    onDetails: () -> Unit,
    onSavePresets: (List<io.github.narthexfire.kettle.data.Preset>) -> Unit = {},
    /** Saves only the edited fields, applied to the current stored kettle. */
    onSaveEdits: ((io.github.narthexfire.kettle.data.KettleEdits) -> Unit)? = null,
    focusAddress: Boolean = false,
    onSearch: () -> Unit = {}, searching: Boolean = false, searchResult: String? = null,
    /** Save only the IP and port (then the app reconnects). */
    onSaveAddress: (ip: String, port: Int) -> Unit = { _, _ -> },
    onTestConnection: suspend (ip: String, port: Int) -> io.github.narthexfire.kettle.net.ConnectionTest.Result = { _, _ ->
        io.github.narthexfire.kettle.net.ConnectionTest.Result(false, "Not available")
    },
    /** Shown only when there is more than one kettle. */
    onRemove: (() -> Unit)? = null,
) {
    val c = K.c
    val k = view.config
    val ipFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var testing by androidx.compose.runtime.remember { mutableStateOf(false) }
    var testResult by androidx.compose.runtime.remember { mutableStateOf<io.github.narthexfire.kettle.net.ConnectionTest.Result?>(null) }
    var addressSaved by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (focusAddress) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { ipFocus.requestFocus() } }
    // Each field holds only the user's edit (null = untouched) in saveable state, so
    // untouched fields always show the current stored value (e.g. an IP found automatically).
    var nameEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var deviceEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var ipEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var portEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var imeiEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var macEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var ssidEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var passEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var showPass by rememberSaveable(k.id) { mutableStateOf(false) }
    var autoJoinEdit by rememberSaveable(k.id) { mutableStateOf<Boolean?>(null) }
    var cupEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    var enabledEdit by rememberSaveable(k.id) { mutableStateOf<Boolean?>(null) }
    var presetsEdit by rememberSaveable(k.id) { mutableStateOf<String?>(null) }
    val name = nameEdit ?: k.name
    val device = deviceEdit ?: k.device
    val ip = ipEdit ?: k.ip
    val port = portEdit ?: k.port.toString()
    val imei = imeiEdit ?: k.imei
    val mac = macEdit ?: k.mac
    val ssid = ssidEdit ?: k.wifiSsid
    val pass = passEdit ?: k.wifiPass
    val autoJoin = autoJoinEdit ?: k.autoJoinWifi
    val cup = cupEdit ?: k.cupMl.toString()
    val enabled = enabledEdit ?: k.enabled
    val presets = presetsEdit?.let { io.github.narthexfire.kettle.data.KettleEdits.presetsFromState(it) } ?: k.presets
    val cupOk = cup.toIntOrNull()?.let { it in io.github.narthexfire.kettle.data.Cups.RANGE } == true

    val ipOk = ip.isBlank() || WifiTracker.ipToInt(ip) != null
    val portOk = port.toIntOrNull()?.let { it in 1..65535 } == true

    SubScreen(k.name, onBack) {
        ZoneCard(c.status) {
            ZoneHeading(KIcons.gauge, "Kettle", c.status)
            Field("Name", name) { nameEdit = it }
            Field("Device", device) { deviceEdit = it }
            Field("Kettle ID", imei, note = "The kettle's built-in device ID (it starts with GD). Search for kettle or Test connection fills it in for you.") { imeiEdit = it }
            Field(
                "IP address", ip, error = if (!ipOk) "Enter an IPv4 address such as 192.168.1.50" else null,
                keyboard = KeyboardType.Uri, focusRequester = ipFocus,
            ) { ipEdit = it; testResult = null; addressSaved = false }
            Field("Port", port, error = if (!portOk) "1 to 65535 (normally 6002)" else null, keyboard = KeyboardType.Number) { portEdit = it; testResult = null; addressSaved = false }
            // Read-only check: connect, keep-alive, status. Never sends a command.
            val addressChanged = ip.trim() != k.ip || port.trim() != k.port.toString()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    if (testing) "Testing\u2026" else "Test connection", Modifier.weight(1f),
                    enabled = !testing && ip.isNotBlank() && ipOk && portOk,
                ) {
                    testing = true; testResult = null
                    scope.launch {
                        testResult = onTestConnection(ip.trim(), port.toInt())
                        testing = false
                    }
                }
                // Saves only IP + port, right here (the full Save is at the bottom of the page).
                PrimaryButton(
                    "Save address", Modifier.weight(1f), icon = null,
                    enabled = addressChanged && ip.isNotBlank() && ipOk && portOk,
                ) { onSaveAddress(ip.trim(), port.trim().toInt()); ipEdit = null; portEdit = null; addressSaved = true }
            }
            if (addressSaved && !addressChanged) Text("Saved \u2713 ${k.ip}:${k.port} (reconnecting)", style = K.t.secondary, color = c.ok)
            SecondaryButton(if (searching) "Searching\u2026" else "Search for kettle", Modifier.fillMaxWidth(), enabled = !searching, onClick = onSearch)
            if (!searching && searchResult != null) Text(searchResult, style = K.t.secondary, color = c.ink2)
            testResult?.let { r ->
                Text((if (r.ok) "\u2713 " else "\u26A0 ") + r.message, style = K.t.secondary, color = if (r.ok) c.ok else c.accentText)
            }
            Field("MAC address (optional)", mac) { macEdit = it }
            Field(
                "Cup size (ml)", cup, keyboard = KeyboardType.Number,
                error = if (!cupOk) "50 to 1000" else null,
                note = "For showing water in cups. AppKettle counts 250 ml as 1 cup; change it to match your cups.",
            ) { cupEdit = it }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Enabled", style = K.t.title, color = c.ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled, onCheckedChange = { enabledEdit = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.card),
                )
            }
        }
        ZoneCard(c.status) {
            ZoneHeading(KIcons.wifi, "Kettle Wi-Fi", c.status)
            Field("Wi-Fi network (SSID)", ssid, note = "Optional. The Wi-Fi network the kettle is on, if the phone is not normally on it.") { ssidEdit = it }
            Column {
                OutlinedTextField(
                    value = pass, onValueChange = { passEdit = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Passphrase", style = K.t.secondary) }, textStyle = K.t.title.copy(color = c.ink),
                    visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = { showPass = !showPass }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (showPass) "Hide" else "Show", style = K.t.secondary, color = c.status.fg)
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.status.fg, unfocusedBorderColor = c.status.line,
                        focusedLabelColor = c.status.fg, unfocusedLabelColor = c.ink2, cursorColor = c.status.fg,
                        focusedContainerColor = c.tile, unfocusedContainerColor = c.tile,
                    ),
                )
                Text("The kettle network's own password (a low-value, local-only password). Stored on this phone only.", style = K.t.secondary, color = c.ink2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Connect to the kettle's Wi-Fi automatically", style = K.t.title, color = c.ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoJoin, onCheckedChange = { autoJoinEdit = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.ok, checkedThumbColor = c.card),
                )
            }
            Para("When the kettle can't be reached on the phone's current network, the app joins this Wi-Fi as a local-only connection while the app is open. Android asks you to approve it the first time.")
        }
        ZoneCard(c.temp) {
            ZoneHeading(KIcons.therm, "Boil point", c.temp)
            Text(
                "Measured boil point: " + (k.measuredBoilPoint?.let { "$it °C" } ?: "not measured yet"),
                style = K.t.title, color = c.ink,
            )
            Para(
                "You can choose any temperature from 40 to 100 °C; the kettle stops by itself when the water boils. " +
                    "The measured value is what the kettle reported at its last full boils" +
                    (if (k.boilHistory.isNotEmpty()) " (${k.boilHistory.joinToString(", ")} °C)." else ".") +
                    " It only sets the colors.",
            )
        }
        ZoneCard(c.preset) {
            ZoneHeading(KIcons.star, "Presets", c.preset)
            Para("Tap a preset on the main screen to choose it; long-press it to edit. Range 40 to 100 °C.")
            presets.forEachIndexed { i, p ->
                io.github.narthexfire.kettle.ui.components.PresetEditorRow(p, k.boilPoint) { np ->
                    presetsEdit = io.github.narthexfire.kettle.data.KettleEdits.presetsToState(presets.toMutableList().also { it[i] = np })
                }
            }
            SecondaryButton("Reset to defaults", Modifier.fillMaxWidth()) { presetsEdit = io.github.narthexfire.kettle.data.KettleEdits.presetsToState(io.github.narthexfire.kettle.data.DEFAULT_PRESETS) }
        }
        PrimaryButton("Save", Modifier.fillMaxWidth(), icon = KIcons.tick, enabled = ipOk && portOk && cupOk && name.isNotBlank()) {
            val edits = io.github.narthexfire.kettle.data.KettleEdits(
                name = nameEdit, device = deviceEdit, ip = ipEdit, port = portEdit?.toIntOrNull(), imei = imeiEdit, mac = macEdit,
                wifiSsid = ssidEdit, wifiPass = passEdit, autoJoinWifi = autoJoinEdit, cupMl = cupEdit?.toIntOrNull(),
                enabled = enabledEdit, presets = presetsEdit?.let { io.github.narthexfire.kettle.data.KettleEdits.presetsFromState(it) },
            )
            if (onSaveEdits != null) onSaveEdits(edits) else onSave(edits.applyTo(k))
            onBack()
        }
        SecondaryButton("Show kettle details", Modifier.fillMaxWidth(), onClick = onDetails)
        if (onRemove != null) {
            var confirm by androidx.compose.runtime.remember { mutableStateOf(false) }
            SecondaryButton(if (confirm) "Tap again to remove ${k.name}" else "Remove this kettle", Modifier.fillMaxWidth()) {
                if (confirm) onRemove() else confirm = true
            }
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, note: String? = null, error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null, onChange: (String) -> Unit,
) {
    val c = K.c
    Column {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            ),
            label = { Text(label, style = K.t.secondary) },
            textStyle = K.t.title.copy(color = c.ink),
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.status.fg, unfocusedBorderColor = c.status.line,
                focusedLabelColor = c.status.fg, unfocusedLabelColor = c.ink2, cursorColor = c.status.fg,
                focusedContainerColor = c.tile, unfocusedContainerColor = c.tile,
            ),
        )
        val sub = error ?: note
        if (sub != null) Text(sub, style = K.t.secondary, color = if (error != null) c.accentText else c.ink2)
    }
}
