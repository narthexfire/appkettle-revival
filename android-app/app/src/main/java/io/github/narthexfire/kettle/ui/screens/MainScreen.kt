package io.github.narthexfire.kettle.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.TempLogic
import io.github.narthexfire.kettle.net.LinkInfo
import io.github.narthexfire.kettle.net.LinkPhase
import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.ui.Choice
import io.github.narthexfire.kettle.ui.KettleView
import io.github.narthexfire.kettle.ui.UiState
import io.github.narthexfire.kettle.ui.components.KIcons
import io.github.narthexfire.kettle.ui.components.KeepWarmChips
import io.github.narthexfire.kettle.ui.components.KettleIllustration
import io.github.narthexfire.kettle.ui.components.KettleMode
import io.github.narthexfire.kettle.ui.components.PresetGrid
import io.github.narthexfire.kettle.ui.components.PrimaryButton
import io.github.narthexfire.kettle.ui.components.SecondaryButton
import io.github.narthexfire.kettle.ui.components.StatusDot
import io.github.narthexfire.kettle.ui.components.TempChips
import io.github.narthexfire.kettle.ui.components.TempControl
import io.github.narthexfire.kettle.ui.components.ZoneCard
import io.github.narthexfire.kettle.ui.components.ZoneHeading
import io.github.narthexfire.kettle.ui.theme.K
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActions(
    val select: (String) -> Unit = {},
    val setTarget: (Int) -> Unit = {},
    val preset: (Int) -> Unit = {},
    val keepWarm: (Int) -> Unit = {},
    val boil: () -> Unit = {},
    val stop: () -> Unit = {},
    val reconnect: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val openHelp: () -> Unit = {},
    val openAbout: () -> Unit = {},
    val openDiagnostics: () -> Unit = {},
    val editPreset: (Int, io.github.narthexfire.kettle.data.Preset) -> Unit = { _, _ -> },
    val saveAsPreset: (Int, String) -> Unit = { _, _ -> },
    val retryKettleWifi: () -> Unit = {},
    val changeAddress: () -> Unit = {},
    /** Send Boil / Heat even with too little water (after the user confirms). */
    val boilAnyway: () -> Unit = {},
    /** "Search again" after an unsuccessful kettle search. */
    val searchAgain: () -> Unit = {},
    /** Pull down on the main screen (reconnect when offline; keep-alive when connected). */
    val pullRefresh: () -> Unit = {},
)

/* ---------- derived text ---------- */

fun modeOf(link: LinkInfo?): KettleMode = when (link?.status?.state) {
    KettleState.Standby -> KettleMode.Standby
    KettleState.Heating -> KettleMode.Heating
    KettleState.Ready -> KettleMode.Ready
    KettleState.KeepWarm -> KettleMode.KeepWarm
    KettleState.OffBase -> KettleMode.OffBase
    else -> KettleMode.Unknown
}

fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

fun ago(now: Long, t: Long): String {
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }
}

fun clock(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

fun pickerSub(k: KettleView, now: Long): Pair<String, Boolean> {
    val c = k.config
    val l = k.link
    return when {
        !c.isSetUp -> "Not set up" to false
        !c.enabled -> "Turned off in Settings" to false
        l == null -> "Offline" to false
        l.phase == LinkPhase.Connected -> "Online · " + (l.status?.state?.label ?: "waiting for status") to true
        l.lastSeenAt > 0 -> "Offline · seen ${clock(l.lastSeenAt)}" to false
        l.phase == LinkPhase.Connecting -> "Connecting…" to false
        else -> "Offline" to false
    }
}

fun connectionLine(k: KettleView, now: Long): String {
    val c = k.config
    val l = k.link
    return when {
        !c.isSetUp -> "Not set up"
        !c.enabled -> "Turned off in Settings"
        l == null -> "Offline"
        l.phase == LinkPhase.Connected && l.lastStatusAt > 0 -> "Connected · updated ${ago(now, l.lastStatusAt)} ago"
        l.phase == LinkPhase.Connected -> "Connected · waiting for status"
        l.phase == LinkPhase.Connecting -> "Connecting to ${c.ip}…"
        else -> "Offline · retrying"
    }
}

fun fmtWater(ml: Int, cupMl: Int = io.github.narthexfire.kettle.data.Cups.DEFAULT_ML) =
    io.github.narthexfire.kettle.data.Cups.format(ml, cupMl) + " · " + fmtMlOnly(ml)

fun fmtMlOnly(ml: Int) = if (ml >= 1000) "≈ %.1f L".format(ml / 1000f) else "≈ $ml ml"

/** Cups first and 50 % larger (semibold), then the ml. */
fun waterAnnotated(ml: Int, cupMl: Int, baseSize: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        // "Empty" at 0 ml; "MAX+ (over 1.7 L)" above capacity.
        if (ml <= 0 || ml > io.github.narthexfire.kettle.data.DeviceFacts.CAPACITY_ML) { append(io.github.narthexfire.kettle.data.Cups.waterText(ml, cupMl)); return@buildAnnotatedString }
        pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = baseSize * 1.5f, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        append(io.github.narthexfire.kettle.data.Cups.format(ml, cupMl))
        pop()
        append(" · " + fmtMlOnly(ml))
    }

/* ---------- screen ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState, reduceMotion: Boolean, actions: MainActions, sheetOpenInitially: Boolean = false,
    local: io.github.narthexfire.kettle.net.LocalWifiState = io.github.narthexfire.kettle.net.LocalWifiState.Idle, concurrency: Boolean? = null,
) {
    val c = K.c
    var sheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(sheetOpenInitially) }
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    // Inline two-tap confirm instead of a dialog.
    var lowWaterArmedAt by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(lowWaterArmedAt) {
        if (lowWaterArmedAt > 0) { kotlinx.coroutines.delay(io.github.narthexfire.kettle.data.LowWater.WINDOW_MS); lowWaterArmedAt = 0 }
    }
    var barHeight by remember { mutableStateOf(124.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lowWater = io.github.narthexfire.kettle.data.LowWater.shouldWarn(state.selected.link?.status, state.selected.link?.lastStatusAt ?: 0L, state.now)
    val armed = lowWater && lowWaterArmedAt > 0
    val requestBoil: () -> Unit = {
        val now = System.currentTimeMillis()
        when (io.github.narthexfire.kettle.data.LowWater.onBoilTap(lowWater, lowWaterArmedAt, now)) {
            io.github.narthexfire.kettle.data.LowWater.Tap.Send -> actions.boil()
            io.github.narthexfire.kettle.data.LowWater.Tap.Arm -> lowWaterArmedAt = now
            io.github.narthexfire.kettle.data.LowWater.Tap.SendAnyway -> { lowWaterArmedAt = 0; actions.boilAnyway() }
        }
    }
    val boilActions = MainActions(
        select = actions.select, setTarget = actions.setTarget, preset = actions.preset, keepWarm = actions.keepWarm,
        boil = requestBoil, stop = actions.stop, reconnect = actions.reconnect, openSettings = actions.openSettings,
        openHelp = actions.openHelp, openAbout = actions.openAbout, openDiagnostics = actions.openDiagnostics,
        editPreset = actions.editPreset, saveAsPreset = actions.saveAsPreset, retryKettleWifi = actions.retryKettleWifi,
        changeAddress = actions.changeAddress, boilAnyway = actions.boilAnyway, searchAgain = actions.searchAgain,
        pullRefresh = actions.pullRefresh,
    )
    val k = state.selected
    val cfg = k.config
    val link = k.link
    val st = link?.status
    val boilPt = cfg.boilPoint
    val ch = state.choice
    val online = link?.phase == LinkPhase.Connected

    Box(Modifier.fillMaxSize().background(c.surface)) {
      Column(Modifier.fillMaxSize()) {
        // Fixed header: the title at the very top, then the kettle picker and connection line.
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 6.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppTitle(30.dp) }
            AppBar(state, actions)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                Icon(KIcons.wifi, null, tint = if (online) c.ok else c.ink3, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(connectionLine(k, state.now), style = K.t.secondary, color = c.ink2)
            }
        }
        // Only the cards scroll and refresh; the title header above and the Boil/Stop bar stay put.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.refreshing, onRefresh = actions.pullRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = barHeight + 16.dp), // clear the measured bar + insets
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KettleWifiNotice(cfg, local, concurrency, actions, connected = link?.phase == LinkPhase.Connected)
            if (!cfg.isSetUp || !cfg.enabled || (link != null && link.phase != LinkPhase.Connected)) {
                ProblemCard(cfg, link, actions, state.search[cfg.id])
            }
            StatusCard(cfg, link, ch, reduceMotion, lowWater, armed, state.notices[cfg.id]?.takeIf {
                io.github.narthexfire.kettle.ui.Notice.visible(it, link?.phase == LinkPhase.Connected, link?.lastStatusAt ?: 0L, state.now)
            })
            ZoneCard(c.temp, padH = 16.dp, padV = 12.dp, gap = 8.dp) {
                ZoneHeading(KIcons.therm, "Temperature", c.temp) {
                    Box(
                        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable { sheet = true }.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Options ›", style = K.t.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.temp.fg) }
                }
                TempControl(ch.target, boilPt, actions.setTarget, showEnds = true, measuredBoil = cfg.measuredBoilPoint)
            }
            ZoneCard(c.preset, padH = 16.dp, padV = 12.dp, gap = 8.dp) {
                ZoneHeading(KIcons.star, "Presets", c.preset)
                PresetGrid(cfg.presets, ch.preset, boilPt, actions.preset, onLongPress = { editing = it })
            }
            ZoneCard(c.warm, padH = 16.dp, padV = 12.dp, gap = 8.dp) {
                ZoneHeading(KIcons.flame, "Keep warm (minutes)", c.warm)
                val sub = when {
                    st?.state == KettleState.KeepWarm -> "Keep warm · ${mmss(st.keepWarmSecondsLeft)} left"
                    !keepWarmChipsEnabled(ch.target, st) || ch.keepWarm == 0 ->
                        if (TempLogic.keepWarmAllowed(ch.target)) "Off after boiling" else "Off: keep warm needs a target of 60° or more"
                    !TempLogic.keepWarmAllowed(ch.target) -> "Keeps the water warm now (it's hot); heating to ${ch.target}° won't keep warm"
                    else -> "Holds the water hot after boiling"
                }
                Text(sub, style = K.t.secondary, color = c.ink2)
                KeepWarmChips(ch.keepWarm, actions.keepWarm, enabled = keepWarmChipsEnabled(ch.target, st))
                when {
                    st?.state == KettleState.Standby && ch.keepWarm > 0 ->
                        Text(io.github.narthexfire.kettle.data.KeepWarmAction.STANDBY_HINT, style = K.t.secondary, color = c.ink2)
                    st?.state == KettleState.KeepWarm ->
                        Text("Tap Off to stop keeping warm, or another time to restart the countdown.", style = K.t.secondary, color = c.ink2)
                    st?.state == KettleState.Heating && st.temperatureC < 60 ->
                        Text("A change is sent once the water passes 60°; otherwise it applies to the next boil.", style = K.t.secondary, color = c.ink2)
                    st?.state == KettleState.Ready && st.temperatureC >= 60 ->
                        Text("Tap a time to keep this water warm now.", style = K.t.secondary, color = c.ink2)
                }
            }
        }
        }
      }
        ActionBar(
            state, Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeight = with(density) { it.height.toDp() } },
            boilActions, armed,
        )
    }

    editing?.let { i ->
        PresetDialog(cfg.presets[i], boilPt, onDismiss = { editing = null }) { p -> actions.editPreset(i, p); editing = null }
    }

    if (sheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheet = false }, sheetState = sheetState,
            containerColor = c.card, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            scrimColor = if (c.dark) Color.Black.copy(alpha = .6f) else Color(0xFF181410).copy(alpha = .42f),
        ) {
            SheetContent(cfg, ch, boilActions, st, state.notices[cfg.id]?.takeIf { it.kind == io.github.narthexfire.kettle.ui.Notice.Kind.Preset }) { sheet = false }
        }
    }
}

@Composable
private fun AppBar(state: UiState, actions: MainActions) {
    val c = K.c
    val k = state.selected
    var picker by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val online = k.link?.phase == LinkPhase.Connected
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { picker = true }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 6.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Choose kettle. Current: ${k.config.name}"; heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (online) c.ok else c.off, pulse = false, reduceMotion = true, size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(k.config.name, style = K.t.appTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Icon(KIcons.chevDown, null, tint = c.ink2, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(
                expanded = picker, onDismissRequest = { picker = false },
                shape = RoundedCornerShape(20.dp), containerColor = c.card, modifier = Modifier.width(340.dp),
            ) {
                state.kettles.forEach { kv ->
                    val current = kv.config.id == state.selectedId
                    val (sub, on) = pickerSub(kv, state.now)
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = 76.dp).background(if (current) c.accentSoft else Color.Transparent),
                        leadingIcon = { StatusDot(if (on) c.ok else c.off, false, true, 12.dp) },
                        text = {
                            Column {
                                Text(kv.config.name, style = K.t.title, color = c.ink)
                                Text(sub, style = K.t.secondary, color = c.ink2)
                            }
                        },
                        trailingIcon = { if (current) Icon(KIcons.tick, "Selected", tint = c.accentText, modifier = Modifier.size(24.dp)) },
                        onClick = { picker = false; actions.select(kv.config.id) },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { overflow = true }, modifier = Modifier.size(56.dp)) {
                Icon(KIcons.more, "More options", tint = c.ink, modifier = Modifier.size(26.dp))
            }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }, shape = RoundedCornerShape(20.dp), containerColor = c.card) {
                listOf(
                    Triple("Settings", KIcons.gear, actions.openSettings),
                    Triple("Help", KIcons.help, actions.openHelp),
                    Triple("Kettle details", KIcons.pulse, actions.openDiagnostics),
                    Triple("About", KIcons.info, actions.openAbout),
                ).forEach { (label, icon, go) ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = 64.dp),
                        leadingIcon = { Icon(icon, null, tint = c.ink2, modifier = Modifier.size(24.dp)) },
                        text = { Text(label, style = K.t.title, color = c.ink) },
                        onClick = { overflow = false; go() },
                    )
                }
                // The installed version, so it can be confirmed at a glance.
                Text(
                    "Kettle ${io.github.narthexfire.kettle.BuildConfig.VERSION_NAME} (${io.github.narthexfire.kettle.BuildConfig.VERSION_CODE})",
                    style = K.t.secondary, color = c.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ProblemCard(cfg: KettleConfig, link: LinkInfo?, actions: MainActions, search: io.github.narthexfire.kettle.ui.SearchStatus? = null) {
    val c = K.c
    ZoneCard(c.status, padH = 16.dp, padV = 12.dp, gap = 8.dp) {
        val (title, body) = when {
            !cfg.isSetUp -> "Not set up" to "Connect this phone to the same Wi-Fi as the kettle, tap Set up, then Search for kettle. The app finds the kettle and fills in its address and Kettle ID."
            !cfg.enabled -> "Turned off" to "This kettle is turned off in Settings."
            link?.phase == LinkPhase.Connecting && (link.lastError == null) -> "Connecting…" to "Talking to ${cfg.ip}:${cfg.port}."
            else -> "Can't reach ${cfg.name}" to ((link?.lastError ?: "No answer.") +
                "\nCheck the kettle is on its base and powered, and that the phone is on ${cfg.wifiSsid.ifBlank { "the kettle's Wi-Fi" }}." +
                "\nTip: if the kettle's display is off, press Power on the base to wake it.")
        }
        Text(title, style = K.t.title, color = if (cfg.isSetUp && cfg.enabled) c.accentText else c.ink)
        Text(body, style = K.t.secondary, color = c.ink2)
        if (search?.ownIpCollision == true && search.running) {
            Text("This address now belongs to your phone \u2014 searching for the kettle\u2026",
                style = K.t.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.ink)
        }
        when {
            search?.running == true -> Text(
                "Searching\u2026 round ${search.round}/${search.rounds}", style = K.t.secondary, color = c.status.fg,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            )
            search?.result != null && !search.found -> {
                Text(search.result, style = K.t.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.ink)
                SecondaryButton("Search again", Modifier.fillMaxWidth(), onClick = actions.searchAgain)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cfg.isSetUp && cfg.enabled) SecondaryButton("Reconnect", Modifier.weight(1f), onClick = actions.reconnect)
            SecondaryButton(if (cfg.isSetUp) "Details" else "Set up", Modifier.weight(1f), onClick = if (cfg.isSetUp) actions.openDiagnostics else actions.openSettings)
        }
        if (cfg.isSetUp && cfg.enabled && link?.phase != LinkPhase.Connecting) {
            SecondaryButton("Change address", Modifier.fillMaxWidth(), onClick = actions.changeAddress)
        }
    }
}

@Composable
private fun StatusCard(cfg: KettleConfig, link: LinkInfo?, ch: Choice, reduceMotion: Boolean, lowWater: Boolean = false, lowWaterArmed: Boolean = false, notice: io.github.narthexfire.kettle.ui.Notice? = null) {
    val c = K.c
    val st = link?.status
    val boilPt = cfg.boilPoint
    val mode = modeOf(link)
    val stateWord = when (mode) {
        KettleMode.Heating -> "heating to " + if (TempLogic.isBoil(ch.target, boilPt)) "boil" else "${ch.target} degrees"
        KettleMode.KeepWarm -> "keeping warm"
        KettleMode.OffBase -> "off its base"
        KettleMode.Unknown -> "no reading"
        else -> mode.name.lowercase()
    }
    val desc = if (st == null) "${cfg.name}, no reading yet" else
        "${cfg.name}, ${st.temperatureC} degrees, $stateWord, about ${st.waterMl} milliliters"
    ZoneCard(c.status, padH = 16.dp, padV = 12.dp, gap = 8.dp) {
        ZoneHeading(KIcons.gauge, "Status", c.status)
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc }, horizontalAlignment = Alignment.CenterHorizontally) {
            KettleIllustration(
                mode = mode, temperature = st?.temperatureC ?: 15, waterMl = st?.waterMl ?: 0, boilPoint = boilPt, cupMl = cfg.cupMl,
                reduceMotion = reduceMotion, modifier = Modifier.size(226.dp, 176.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.Top) {
                    AnimatedContent(
                        targetState = st?.temperatureC,
                        transitionSpec = {
                            val up = (targetState ?: 0) > (initialState ?: 0)
                            (slideInVertically(tween(180)) { if (up) it else -it }) togetherWith (slideOutVertically(tween(180)) { if (up) -it else it })
                        }, label = "temp",
                    ) { t -> Text(t?.toString() ?: "--", style = K.t.display, color = c.ink) }
                    Text("°C", style = K.t.displayUnit, color = c.ink2, modifier = Modifier.padding(top = 5.dp))
                }
                Spacer(Modifier.width(24.dp))
                Column {
                    Text("TARGET", style = K.t.overline, color = c.status.fg)
                    Row {
                        Text("→ ", style = K.t.title, color = c.accentText)
                        Text(TempLogic.label(ch.target, boilPt), style = K.t.title, color = c.ink)
                    }
                }
            }
            Spacer(Modifier.size(2.dp))
            StateLine(link, reduceMotion)
            if (notice != null) {
                // Static feedback (replaced by the next notice), never a floating message.
                Text(
                    notice.text + " \u00B7 " + clock(notice.at),
                    style = K.t.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = if (notice.kind == io.github.narthexfire.kettle.ui.Notice.Kind.Error) c.accentText else c.status.fg,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
            }
            if (lowWater) {
                Text(
                    if (lowWaterArmed) io.github.narthexfire.kettle.data.LowWater.ARMED else io.github.narthexfire.kettle.data.LowWater.WARNING,
                    style = K.t.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = if (lowWaterArmed) c.accentText else c.warn, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
            }
            if (st != null && mode != KettleMode.OffBase && mode != KettleMode.Unknown) { // hidden like the picture's water
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(KIcons.drop, null, tint = c.status.fg, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(waterAnnotated(st.waterMl, cfg.cupMl, K.t.secondary.fontSize), style = K.t.secondary, color = c.ink2)
                }
            }
        }
    }
}

@Composable
private fun StateLine(link: LinkInfo?, reduceMotion: Boolean) {
    val c = K.c
    val st = link?.status
    val (dot, pulse, text, color) = when (st?.state) {
        KettleState.Heating -> Quad(c.accent, true, "Heating", c.accentText)
        KettleState.Ready -> Quad(c.ok, false, "Ready", c.ink)
        KettleState.KeepWarm -> Quad(c.accent, true, "Keep warm · ${mmss(st.keepWarmSecondsLeft)} left", c.accentText)
        KettleState.OffBase -> Quad(c.warn, false, "Off base", c.ink)
        KettleState.Standby -> Quad(c.off, false, "Standby", c.ink)
        null -> Quad(c.off, false, if (link?.phase == LinkPhase.Connected) "Waiting for status" else "Offline", c.ink)
        else -> Quad(c.off, false, "Unknown state", c.ink)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dot, pulse, reduceMotion)
        Spacer(Modifier.width(12.dp))
        Text(text, style = K.t.state, color = color)
    }
    if (st?.state == KettleState.OffBase) {
        Text("Put the kettle back on its base", style = K.t.secondary, color = c.ink2)
    }
}

private data class Quad(val a: Color, val b: Boolean, val c: String, val d: Color)

@Composable
private fun ActionBar(state: UiState, modifier: Modifier, actions: MainActions, lowWaterArmed: Boolean = false) {
    val c = K.c
    val k = state.selected
    val st = k.link?.status
    val boilPt = k.config.boilPoint
    val ch = state.choice
    val boilChoice = TempLogic.isBoil(ch.target, boilPt)
    val heating = st?.state == KettleState.Heating
    val progress = if (heating) {
        val start = state.heatStart ?: st.temperatureC
        val tgt = if (st.targetC >= 100 || st.targetC >= boilPt) boilPt else st.targetC
        if (tgt > start) (st.temperatureC - start).toFloat() / (tgt - start) else 0f
    } else null
    val label = when {
        state.busy -> "Sending…"
        lowWaterArmed -> "Boil anyway"
        heating -> "Heating… ${((progress ?: 0f).coerceIn(0f, 1f) * 100).toInt()}%"
        st?.state == KettleState.KeepWarm -> if (boilChoice) "Boil again" else "Heat to ${ch.target}°"
        boilChoice -> "Boil"
        else -> "Heat to ${ch.target}°"
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to c.surface.copy(alpha = 0f), .3f to c.surface, 1f to c.surface))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                label, Modifier.weight(1.8f), icon = if (heating) null else KIcons.power,
                enabled = !state.busy && !heating, dimWhenDisabled = !heating, progress = progress, onClick = actions.boil,
            )
            SecondaryButton(
                "Stop", Modifier.weight(1f), icon = KIcons.stop,
                enabled = !state.busy && st?.state?.isActive == true, onClick = actions.stop,
            )
        }
    }
}

@Composable
private fun SheetContent(
    cfg: KettleConfig, ch: Choice, actions: MainActions, status: io.github.narthexfire.kettle.protocol.KettleStatus? = null,
    presetNotice: io.github.narthexfire.kettle.ui.Notice? = null, close: () -> Unit,
) {
    val c = K.c
    val boilPt = cfg.boilPoint
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Temperature & keep warm", style = K.t.sheetTitle, color = c.ink, modifier = Modifier.semantics { heading() })
        ZoneHeading(KIcons.star, "Presets", c.preset)
        PresetGrid(cfg.presets, ch.preset, boilPt, actions.preset, compact = true)
        ZoneHeading(KIcons.therm, "Temperature", c.temp)
        TempControl(ch.target, boilPt, actions.setTarget, showEnds = true, measuredBoil = cfg.measuredBoilPoint)
        TempChips(ch.target, boilPt, actions.setTarget)
        ZoneHeading(KIcons.flame, "Keep warm (minutes)", c.warm)
        KeepWarmChips(ch.keepWarm, actions.keepWarm, enabled = keepWarmChipsEnabled(ch.target, status))
        val what = if (TempLogic.isBoil(ch.target, boilPt)) "Boil" else "Boil to ${ch.target}°"
        val kwEff = TempLogic.effectiveKeepWarm(ch.target, ch.keepWarm)
        val warm = if (kwEff == 0) "" else " · warm $kwEff min"
        SaveAsPresetButton(cfg, actions)
        if (presetNotice != null) Text(presetNotice.text, style = K.t.secondary, color = c.preset.fg)
        PrimaryButton(what + warm, Modifier.fillMaxWidth()) { actions.boil(); close() }
    }
}

@Composable
private fun SaveAsPresetButton(cfg: KettleConfig, actions: MainActions) {
    val c = K.c
    val z = c.preset
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(z.soft)
            .border(1.5.dp, z.fg, shape)
            .clickable { open = true }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KIcons.star, null, tint = z.fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save as preset…", style = K.t.button, color = z.fg)
        }
    }
    if (open) SaveAsPresetDialog(cfg, onDismiss = { open = false }) { slot, name -> actions.saveAsPreset(slot, name); open = false }
}

@Composable
private fun SaveAsPresetDialog(cfg: KettleConfig, onDismiss: () -> Unit, onSave: (Int, String) -> Unit) {
    val c = K.c
    val z = c.preset
    var slot by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(cfg.presets[0].name) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Save as preset", style = K.t.sheetTitle, color = c.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a slot to overwrite with the current temperature and keep warm.", style = K.t.secondary, color = c.ink2)
                cfg.presets.forEachIndexed { i, p ->
                    val sel = i == slot
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) z.soft else Color.Transparent)
                            .border(if (sel) 2.dp else 1.dp, if (sel) z.fg else z.line, RoundedCornerShape(16.dp))
                            .clickable { slot = i; name = p.name }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.name, style = K.t.presetName, color = c.ink, modifier = Modifier.weight(1f))
                        Text(io.github.narthexfire.kettle.data.PresetLogic.label(p, cfg.boilPoint), style = K.t.secondary, color = c.ink2)
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it.take(io.github.narthexfire.kettle.data.PresetLogic.MAX_NAME) }, singleLine = true,
                    label = { Text("Name (optional rename)", style = K.t.secondary) }, textStyle = K.t.title.copy(color = c.ink),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = z.fg, unfocusedBorderColor = z.line, focusedLabelColor = z.fg,
                        unfocusedLabelColor = c.ink2, cursorColor = z.fg,
                    ),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(slot, name) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Save", style = K.t.button, color = c.accentText)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel", style = K.t.button, color = c.ink2)
            }
        },
    )
}


@Composable
private fun PresetDialog(initial: io.github.narthexfire.kettle.data.Preset, boilPoint: Int, onDismiss: () -> Unit, onSave: (io.github.narthexfire.kettle.data.Preset) -> Unit) {
    val c = K.c
    var p by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Edit preset", style = K.t.sheetTitle, color = c.ink) },
        text = { io.github.narthexfire.kettle.ui.components.PresetEditorRow(p, boilPoint) { p = it } },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(p) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Save", style = K.t.button, color = c.accentText)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel", style = K.t.button, color = c.ink2)
            }
        },
    )
}

/** Kettle Wi-Fi (local-only) status: joining, couldn't join (with Try again), or "left main Wi-Fi" note. */
@Composable
private fun KettleWifiNotice(cfg: KettleConfig, local: io.github.narthexfire.kettle.net.LocalWifiState, concurrency: Boolean?, actions: MainActions, connected: Boolean = false) {
    val c = K.c
    when {
        local is io.github.narthexfire.kettle.net.LocalWifiState.Requesting && local.kettleId == cfg.id -> ZoneCard(c.status, padH = 16.dp, padV = 12.dp, gap = 6.dp) {
            Text("Joining ${local.ssid}\u2026", style = K.t.title, color = c.ink)
            Text("If Android asks, approve the connection to the kettle's Wi-Fi.", style = K.t.secondary, color = c.ink2)
        }
        // Hide a failure for an old SSID, and any failure once the kettle is connected.
        local is io.github.narthexfire.kettle.net.LocalWifiState.Unavailable && local.kettleId == cfg.id && local.ssid == cfg.wifiSsid.trim() && !connected -> ZoneCard(c.status, padH = 16.dp, padV = 12.dp, gap = 8.dp) {
            Text("Couldn't join ${local.ssid}", style = K.t.title, color = c.accentText)
            Text("Check the kettle network is in range and the passphrase in Settings is right, then try again.", style = K.t.secondary, color = c.ink2)
            SecondaryButton("Try again", Modifier.fillMaxWidth(), onClick = actions.retryKettleWifi)
        }
        local is io.github.narthexfire.kettle.net.LocalWifiState.Connected && local.kettleId == cfg.id && concurrency == false -> ZoneCard(c.status, padH = 16.dp, padV = 12.dp, gap = 6.dp) {
            Text(
                "Using the kettle's Wi-Fi \u2014 your phone has left ${local.mainSsid ?: "its main Wi-Fi"} while the app is open",
                style = K.t.secondary, color = c.ink2,
            )
        }
        else -> Unit
    }
}

/** Keep-warm chips work when the target allows it, or when the kettle's water is hot / keeping warm now. */
internal fun keepWarmChipsEnabled(target: Int, status: io.github.narthexfire.kettle.protocol.KettleStatus?): Boolean =
    TempLogic.keepWarmAllowed(target) || status?.state == KettleState.KeepWarm ||
        (status != null && status.state != KettleState.OffBase && status.temperatureC >= TempLogic.KEEP_WARM_MIN)
