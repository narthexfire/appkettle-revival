package io.github.narthexfire.kettle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.narthexfire.kettle.data.BoilPointLearner
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.Preset
import io.github.narthexfire.kettle.data.PresetLogic
import io.github.narthexfire.kettle.data.DEFAULT_PRESETS
import io.github.narthexfire.kettle.data.SettingsRepository
import io.github.narthexfire.kettle.data.TempLogic
import io.github.narthexfire.kettle.net.KettleManager
import io.github.narthexfire.kettle.net.LinkInfo
import io.github.narthexfire.kettle.net.PhoneNet
import io.github.narthexfire.kettle.net.WifiTracker
import io.github.narthexfire.kettle.protocol.KettleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KettleView(val config: KettleConfig, val link: LinkInfo?)

/** Local, not-yet-sent choices for one kettle. */
data class Choice(val target: Int, val keepWarm: Int, val preset: Int?)

/**
 * Feedback is static inline text (no toasts / snackbars). One notice per kettle, replaced by
 * the next one: [Kind.Status] and [Kind.Error] show in the Status card, [Kind.Preset] under the sheet's
 * "Save as preset" button.
 */
data class Notice(val kind: Kind, val text: String, val at: Long) {
    /** [Connection] lines (reachability / search) vanish once the kettle is connected again. */
    enum class Kind { Status, Error, Preset, Connection }

    companion object {
        const val INFO_MS = 60_000L

        /**
         * Whether a Status-card notice is still worth showing: connection lines only while not connected
         * (and not after a newer status); informational lines for at most [INFO_MS]; errors until replaced.
         */
        fun visible(n: Notice?, connected: Boolean, lastStatusAt: Long, now: Long): Boolean = when (n?.kind) {
            null, Kind.Preset -> false
            Kind.Connection -> !connected && lastStatusAt <= n.at
            Kind.Status -> now - n.at <= INFO_MS
            Kind.Error -> true
        }
    }
}

/** Automatic kettle search state, per kettle. */
data class SearchStatus(
    val running: Boolean = false, val result: String? = null, val at: Long = 0L,
    val round: Int = 0, val rounds: Int = io.github.narthexfire.kettle.net.KettleSearch.ROUNDS,
    /** The saved address turned out to be the phone's own. */
    val ownIpCollision: Boolean = false,
    val found: Boolean = false,
)

data class UiState(
    val kettles: List<KettleView> = KettleConfig.DEFAULTS.map { KettleView(it, null) },
    val selectedId: String = KettleConfig.DEFAULTS.first().id,
    val choice: Choice = Choice(100, 0, null),
    val busy: Boolean = false,
    val now: Long = System.currentTimeMillis(),
    val heatStart: Int? = null,
    val phone: PhoneNet = PhoneNet(),
    val search: Map<String, SearchStatus> = emptyMap(),
    val notices: Map<String, Notice> = emptyMap(),
    val refreshing: Boolean = false,
    /** Set when the stored settings couldn't be fully read (the app then never writes). */
    val settingsProblem: String? = null,
) {
    val selected: KettleView get() = kettles.firstOrNull { it.config.id == selectedId } ?: kettles.firstOrNull()
        ?: KettleView(KettleConfig.DEFAULTS.first(), null)
}

private data class Extra(
    val heat: Map<String, Int>, val phone: PhoneNet, val search: Map<String, SearchStatus>, val notices: Map<String, Notice>,
    val refreshing: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class KettleViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    val wifi = WifiTracker(app)
    val localWifi = io.github.narthexfire.kettle.net.LocalWifi(app)
    /** Kettle sockets use the kettle's own Wi-Fi when the app has joined it for that kettle. */
    private val manager = KettleManager(viewModelScope, { id -> socketFor(id) }) { id, t -> learnBoil(id, t) }
    private val foreground = MutableStateFlow(false)
    private val search = MutableStateFlow<Map<String, SearchStatus>>(emptyMap())
    private val episodes = io.github.narthexfire.kettle.net.SearchEpisodes()
    private val searchJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    /** Keep-warm Off queued until the water passes 60 during this boil. */
    private val queuedKwOff = mutableSetOf<String>()
    private var mismatchNoted: String? = null

    private fun socketFor(id: String): java.net.Socket =
        (if (localWifi.requestedFor == id) localWifi.socketOrNull() else null) ?: wifi.newSocket()

    private val choices = MutableStateFlow<Map<String, Choice>>(emptyMap())
    private val busy = MutableStateFlow(false)
    private val heatStart = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val notices = MutableStateFlow<Map<String, Notice>>(emptyMap())
    private val refreshing = MutableStateFlow(false)

    private fun note(id: String, kind: Notice.Kind, text: String) {
        notices.update { it + (id to Notice(kind, text, System.currentTimeMillis())) }
    }


    private val ticker: Flow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(1000) }
    }

    private val links: Flow<Map<String, LinkInfo>> = manager.connections.flatMapLatest { map ->
        if (map.isEmpty()) flowOf(emptyMap())
        else combine(map.map { (id, link) -> link.info.let { f -> f.map2(id) } }) { pairs -> pairs.toMap() }
    }

    private fun <T> StateFlow<T>.map2(id: String): Flow<Pair<String, T>> = flow { collect { emit(id to it) } }

    val state: StateFlow<UiState> = combine(
        combine(repo.stored, repo.selectedId, links) { k, s, l -> Triple(k, s, l) },
        choices, busy, ticker, combine(heatStart, wifi.phone, search, notices, refreshing) { h, p, sr, n, r -> Extra(h, p, sr, n, r) },
    ) { (stored, sel, linkMap), ch, b, now, (hs, phone, sr, nt, rf) ->
        val kettles = stored.kettles.ifEmpty { KettleConfig.DEFAULTS }
        val views = kettles.map { KettleView(it, linkMap[it.id]) }
        val selCfg = kettles.firstOrNull { it.id == sel } ?: kettles.first()
        val choice = ch[selCfg.id] ?: defaultChoice(selCfg)
        UiState(
            kettles = views, selectedId = selCfg.id,
            // The highlighted preset is derived from target + keep warm, never stored by slot.
            choice = choice.copy(preset = PresetLogic.matching(selCfg.presets, choice.target, choice.keepWarm)),
            busy = b, now = now, heatStart = hs[selCfg.id], phone = phone, search = sr, notices = nt, refreshing = rf,
            settingsProblem = stored.problem,
        )
    }.catch { emit(UiState()) }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch { repo.persistMigrations() }
        viewModelScope.launch { state.collect { evaluateLocalWifi(it); evaluateSearch(it); evaluateMismatch(it); resolveQueuedOff(it); pruneNotices(it) } }
        viewModelScope.launch { repo.kettles.collect { manager.sync(it) } }
        // Remember where heating started, for the Boil button's progress sweep.
        viewModelScope.launch {
            links.collect { map ->
                map.forEach { (id, info) ->
                    val st = info.status ?: return@forEach
                    val cur = heatStart.value[id]
                    if (st.state == KettleState.Heating && cur == null) heatStart.update { it + (id to st.temperatureC) }
                    if (st.state != KettleState.Heating && cur != null) heatStart.update { it - id }
                }
            }
        }
    }

    /** Every settings write goes through here; a read-only store is reported inline. */
    private suspend fun write(id: String, transform: (KettleConfig) -> KettleConfig): Boolean {
        val ok = repo.update(id, transform)
        if (!ok) note(id, Notice.Kind.Error, "Settings are read-only (the stored data couldn't be read); this change wasn't saved")
        return ok
    }

    private fun defaultChoice(c: KettleConfig): Choice {
        val t = c.lastTarget.coerceIn(TempLogic.MIN, TempLogic.BOIL)
        return Choice(t, c.lastKeepWarm, presetFor(t, c))
    }

    private fun presetFor(t: Int, c: KettleConfig): Int? = PresetLogic.indexFor(t, c.presets, c.boilPoint)

    private fun cfg(): KettleConfig = state.value.selected.config

    private fun editChoice(f: (Choice) -> Choice) {
        val c = cfg()
        choices.update { it + (c.id to f(it[c.id] ?: defaultChoice(c))) }
    }

    /* ---------- UI actions ---------- */

    fun setForeground(on: Boolean) {
        foreground.value = on
        if (on) {
            episodes.onForeground() // no spurious search on return
            wifi.start()
        } else {
            wifi.stop()
            cancelSearches()
            localWifi.release() // never hold the phone off its main Wi-Fi in the background
        }
        manager.setRunning(on)
    }

    private fun cancelSearches(only: String? = null) {
        searchJobs.filterKeys { only == null || it == only }.forEach { (id, j) ->
            j.cancel()
            search.update { m -> m + (id to SearchStatus(running = false)) } // stopped: not "not found"
        }
        if (only == null) searchJobs.clear() else searchJobs.remove(only)
    }

    /** Local-only join of the kettle's Wi-Fi, driven by [io.github.narthexfire.kettle.net.LocalWifiPolicy]. */
    private fun evaluateLocalWifi(s: UiState) {
        val cfg = s.selected.config
        val link = s.selected.link
        val lw = localWifi.state.value
        val input = io.github.narthexfire.kettle.net.LocalWifiPolicy.Input(
            foreground = foreground.value, sdkSupportsSpecifier = localWifi.sdkSupportsSpecifier,
            selectedId = cfg.id, autoJoin = cfg.autoJoinWifi, kettleSsid = cfg.wifiSsid.trim(),
            hasPassphrase = cfg.wifiPass.isNotEmpty(), kettleActive = cfg.active,
            linkConnected = link?.phase == io.github.narthexfire.kettle.net.LinkPhase.Connected,
            linkFailed = link?.phase == io.github.narthexfire.kettle.net.LinkPhase.Offline,
            phoneSsid = s.phone.ssid,
            sameSubnet = if (s.phone.onWifi) WifiTracker.sameSubnet(s.phone.ipv4, s.phone.prefixLength, cfg.ip) else false,
            requestedFor = localWifi.requestedFor,
            failedFor = (lw as? io.github.narthexfire.kettle.net.LocalWifiState.Unavailable)?.kettleId,
            heldSsid = localWifi.requestedSsid, heldPassKey = localWifi.requestedPassKey,
            passKey = io.github.narthexfire.kettle.net.LocalWifi.passKey(cfg.wifiPass), failedSsid = localWifi.failedSsid,
        )
        when (io.github.narthexfire.kettle.net.LocalWifiPolicy.decide(input)) {
            io.github.narthexfire.kettle.net.LocalWifiPolicy.Action.Request, io.github.narthexfire.kettle.net.LocalWifiPolicy.Action.Rerequest ->
                localWifi.request(cfg.id, cfg.wifiSsid.trim(), cfg.wifiPass, s.phone.ssid) { manager.get(cfg.id)?.reconnectNow() }
            io.github.narthexfire.kettle.net.LocalWifiPolicy.Action.Release -> {
                val heldFor = localWifi.requestedFor // reconnect THAT kettle
                localWifi.release()
                heldFor?.let { cancelSearches(it); manager.get(it)?.reconnectNow() }
            }
            io.github.narthexfire.kettle.net.LocalWifiPolicy.Action.None -> Unit
        }
    }

    /* ---------- automatic kettle search ---------- */

    /** The phone IP to sweep from: the kettle Wi-Fi path if joined for this kettle, else the main Wi-Fi if on the kettle's subnet. */
    private fun searchBase(cfg: KettleConfig, s: UiState): Pair<String, Int?>? {
        val lw = localWifi.state.value
        if (lw is io.github.narthexfire.kettle.net.LocalWifiState.Connected && lw.kettleId == cfg.id && lw.ipv4 != null) return lw.ipv4 to lw.prefix
        val ph = s.phone
        if (ph.onWifi && ph.ipv4 != null && WifiTracker.sameSubnet(ph.ipv4, ph.prefixLength, cfg.ip) == true) return ph.ipv4 to ph.prefixLength
        // First-time setup (no address saved yet): search the phone's own Wi-Fi network.
        if (cfg.ip.isBlank() && ph.onWifi && ph.ipv4 != null) return ph.ipv4 to ph.prefixLength
        return null
    }

    private fun evaluateSearch(s: UiState) {
        val now = System.currentTimeMillis()
        for (kv in s.kettles) {
            episodes.observe(kv.config.id, kv.link?.phase == io.github.narthexfire.kettle.net.LinkPhase.Connected, kv.link != null, foreground.value, now)
        }
        val cfg = s.selected.config
        val own = io.github.narthexfire.kettle.net.SearchPolicy.savedIpIsOwn(cfg.ip, ownIps(s))
        val go = io.github.narthexfire.kettle.net.SearchPolicy.shouldSearch(
            autoEnabled = cfg.imei.isNotBlank(), foreground = foreground.value, kettleActive = cfg.active,
            linkConnected = s.selected.link?.phase == io.github.narthexfire.kettle.net.LinkPhase.Connected,
            offlineSince = episodes.offlineSince(cfg.id), now = now, sameSubnetOrKettleWifi = searchBase(cfg, s) != null,
            searchedThisEpisode = episodes.searched(cfg.id), running = search.value[cfg.id]?.running == true,
            savedIpIsOwn = own,
        )
        if (go) startSearch(cfg.id, manual = false)
    }

    /** The address answers with another Kettle ID: say so inline and look for the right kettle. */
    private fun evaluateMismatch(s: UiState) {
        val cfg = s.selected.config
        val other = s.selected.link?.imeiMismatch
        if (other == null) { if (mismatchNoted != null) mismatchNoted = null; return }
        if (mismatchNoted == other) return
        mismatchNoted = other
        note(cfg.id, Notice.Kind.Connection, io.github.narthexfire.kettle.net.KettleConnection.mismatchMessage(other) + "; searching for ${cfg.name}")
        if (foreground.value && !episodes.searched(cfg.id)) startSearch(cfg.id, manual = false)
    }

    /** Send a queued keep-warm Off once the water passes 60 (or stop keep warm if it started). */
    private fun resolveQueuedOff(s: UiState) {
        for (id in queuedKwOff.toList()) {
            val kv = s.kettles.firstOrNull { it.config.id == id } ?: continue
            val st = kv.link?.status
            val a = io.github.narthexfire.kettle.data.KeepWarmAction.resolveQueuedOff(st?.state, st?.temperatureC) ?: continue
            queuedKwOff -= id
            val link = manager.get(id) ?: continue
            when (a) {
                is io.github.narthexfire.kettle.data.KeepWarmAction.SendKeepWarm -> viewModelScope.launch {
                    runCatching { link.keepWarm(0) }.onFailure { note(id, Notice.Kind.Error, it.message ?: "Keep warm off failed") }
                }
                io.github.narthexfire.kettle.data.KeepWarmAction.SendOff -> viewModelScope.launch {
                    runCatching { link.stopBoil() }.onFailure { note(id, Notice.Kind.Error, it.message ?: "Keep warm off failed") }
                }
                else -> Unit
            }
        }
    }

    /** Once a kettle is connected, its connection/search lines are cleared from state. */
    private fun pruneNotices(s: UiState) {
        val stale = s.kettles.filter { kv ->
            val n = s.notices[kv.config.id] ?: return@filter false
            n.kind == Notice.Kind.Connection && kv.link?.phase == io.github.narthexfire.kettle.net.LinkPhase.Connected
        }.map { it.config.id }
        if (stale.isNotEmpty()) notices.update { it - stale.toSet() }
    }

    private fun ownIps(s: UiState): List<String?> =
        listOf(s.phone.ipv4, (localWifi.state.value as? io.github.narthexfire.kettle.net.LocalWifiState.Connected)?.ipv4)

    /** Manual "Search for kettle" (Kettle details / Settings), or the automatic one. Read-only. */
    fun startSearch(id: String = cfg().id, manual: Boolean = true) {
        val s = state.value
        val cfg = s.kettles.firstOrNull { it.config.id == id }?.config ?: return
        if (search.value[id]?.running == true) return
        val base = searchBase(cfg, s)
        if (base == null) {
            val why = if (cfg.ip.isBlank()) "Can't search: connect the phone to the kettle's Wi-Fi network first"
                else "Can't search: the phone isn't on ${cfg.name}'s network (${cfg.ip.substringBeforeLast('.')}.x)"
            if (manual) search.update { it + (id to SearchStatus(false, why, System.currentTimeMillis())) }
            return
        }
        // No Kettle ID yet: only a manual search, which adopts the first kettle not already in the app (first-time setup).
        if (cfg.imei.isBlank() && !manual) return
        val skip = s.kettles.map { it.config }.filter { it.id != id }.map { it.imei }.filter { it.isNotBlank() }.toSet()
        episodes.markSearched(id)
        val own = io.github.narthexfire.kettle.net.SearchPolicy.savedIpIsOwn(cfg.ip, ownIps(s))
        search.update { it + (id to SearchStatus(running = true, at = System.currentTimeMillis(), round = 1, ownIpCollision = own)) }
        val (phoneIp, prefix) = base
        searchJobs[id] = viewModelScope.launch {
          try {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // The phone's real subnet (capped at /22) except our own IP; nearest to the old address first;
                // UDP discovery probe + ARP hints every round.
                val hosts = io.github.narthexfire.kettle.net.KettleSearch.ordered(
                    io.github.narthexfire.kettle.net.KettleSearch.candidatesFor(phoneIp, prefix, cfg.ip, ownIps(s).toSet()), cfg.ip,
                )
                io.github.narthexfire.kettle.net.KettleSearch.sweep(
                    hosts, cfg.port, cfg.imei, { socketFor(id) }, skipImeis = skip,
                    extraFirst = {
                        val dg = (if (localWifi.requestedFor == id) localWifi.datagramOrNull() else null) ?: wifi.newDatagram()
                        val udp = io.github.narthexfire.kettle.net.KettleSearch.broadcastFor(phoneIp, prefix)
                            ?.let { io.github.narthexfire.kettle.net.KettleSearch.udpDiscover(it, cfg.imei, dg, listenMs = 2_000) } ?: emptyList()
                        udp + io.github.narthexfire.kettle.net.KettleSearch.arpNeighbors(phoneIp)
                    },
                    onRound = { r -> search.update { m -> m + (id to (m[id] ?: SearchStatus()).copy(running = true, round = r)) } },
                )
            }
            val now = System.currentTimeMillis()
            if (found != null && cfg.imei.isBlank()) {
                // First-time setup: save the address and the Kettle ID the kettle reported.
                write(id) { io.github.narthexfire.kettle.net.AddressUpdate.apply(it, found.ip, now, automatic = false).copy(imei = found.imei) }
                note(id, Notice.Kind.Status, "Found a kettle at ${found.ip} (Kettle ID ${found.imei}); connecting")
                search.update { it + (id to SearchStatus(false, "Found a kettle at ${found.ip} (Kettle ID ${found.imei}) and saved it", now, found = true)) }
            } else if (found != null) {
                if (found.ip != cfg.ip) {
                    write(id) { io.github.narthexfire.kettle.net.AddressUpdate.apply(it, found.ip, now, automatic = !manual) }
                    note(id, Notice.Kind.Status, io.github.narthexfire.kettle.net.AddressUpdate.message(cfg.name, cfg.ip, found.ip))
                } else {
                    // Same address as the current link: don't kill it with a reconnect.
                    if (manual) note(id, Notice.Kind.Status, "${cfg.name} is at ${found.ip} (no change)")
                }
                search.update { it + (id to SearchStatus(false, "Found at ${found.ip}", now, found = true)) }
            } else {
                search.update { it + (id to SearchStatus(false, "Searched the network; kettle not found", now, ownIpCollision = own)) }
            }
          } catch (e: kotlinx.coroutines.CancellationException) {
            search.update { it + (id to SearchStatus(running = false)) } // stopped, not "not found"
            throw e
          } finally {
            searchJobs.remove(id)
          }
        }
    }

    /** "Try again" after Android could not join the kettle's Wi-Fi. */
    fun retryKettleWifi() {
        localWifi.clearFailure()
        reconnect()
    }

    fun select(id: String) = viewModelScope.launch { repo.select(id) }

    fun setTarget(t: Int) = editChoice { ch -> ch.copy(target = t, preset = presetFor(t, cfg())) }

    /** Tapping a preset only selects it (sets the target); the user then presses Heat / Boil. */
    fun choosePreset(i: Int) {
        val c = cfg()
        val t = PresetLogic.clamp(c.presets[i].temp, c.boilPoint)
        val kw = c.presets[i].keepWarm
        editChoice { it.copy(target = t, keepWarm = kw, preset = i) }
        viewModelScope.launch { write(c.id) { it.copy(lastKeepWarm = kw) } }
    }

    /** Sheet "Save as preset": store the current temperature and keep-warm in [slot]. */
    fun saveCurrentAsPreset(slot: Int, name: String) {
        val c = cfg()
        val ch = state.value.choice
        val newList = PresetLogic.saveTo(c.presets, slot, name, ch.target, ch.keepWarm, c.boilPoint)
        viewModelScope.launch {
            write(c.id) { it.copy(presets = newList) }
            editChoice { it.copy(preset = slot) }
            note(c.id, Notice.Kind.Preset, "Saved to ${newList[slot].name} \u2713")
        }
    }

    fun updatePreset(kettleId: String, index: Int, preset: Preset) = viewModelScope.launch {
        write(kettleId) { k ->
            k.copy(presets = PresetLogic.normalize(k.presets.toMutableList().also { it[index] = preset.copy(temp = PresetLogic.clamp(preset.temp, k.boilPoint)) }))
        }
    }

    fun savePresets(kettleId: String, presets: List<Preset>) = viewModelScope.launch {
        write(kettleId) { k -> k.copy(presets = PresetLogic.normalize(presets.map { it.copy(temp = PresetLogic.clamp(it.temp, k.boilPoint)) })) }
    }

    fun resetPresets(kettleId: String) = savePresets(kettleId, DEFAULT_PRESETS)

    fun setKeepWarm(m: Int) {
        editChoice { it.copy(keepWarm = m) }
        val c = cfg()
        val id = c.id
        viewModelScope.launch { write(id) { it.copy(lastKeepWarm = m) } }
        // Act on the kettle now when that makes sense (see KeepWarmAction).
        val link = manager.get(id) ?: return
        val st = state.value.selected.link?.status
        val a = io.github.narthexfire.kettle.data.KeepWarmAction.decide(st?.state, st?.temperatureC, m)
        when (a) {
            io.github.narthexfire.kettle.data.KeepWarmAction.SendOff -> launchCommand {
                link.stopBoil()
                note(id, Notice.Kind.Status, "Keep warm off")
            }
            is io.github.narthexfire.kettle.data.KeepWarmAction.SendKeepWarm -> launchCommand {
                try {
                    link.keepWarm(a.minutes)
                } catch (e: io.github.narthexfire.kettle.net.KettleException) {
                    // Refusal (e.g. ack 00): explain inline; the frame and ack are in the Command log.
                    note(id, Notice.Kind.Error, io.github.narthexfire.kettle.data.KeepWarmAction.REFUSED + " (" + (e.message ?: "") + ")")
                    return@launchCommand
                }
                note(id, Notice.Kind.Status, if (a.minutes == 0) "Keep warm off after this boil" else "Keep warm ${a.minutes} min")
            }
            is io.github.narthexfire.kettle.data.KeepWarmAction.NextBoil -> a.note?.let { note(id, Notice.Kind.Status, it) }
            io.github.narthexfire.kettle.data.KeepWarmAction.QueueOff -> {
                queuedKwOff += id
                note(id, Notice.Kind.Status, "Keep warm off after this boil")
            }
        }
        if (m != 0 || a !is io.github.narthexfire.kettle.data.KeepWarmAction.QueueOff) queuedKwOff -= id
    }

    fun boil(allowLowWater: Boolean = false) {
        val c = cfg()
        val link = manager.get(c.id)
        if (link == null) {
            note(c.id, Notice.Kind.Error, if (!c.isSetUp) "${c.name} is not set up yet. Add its IP and Kettle ID in Settings." else "${c.name} is turned off in Settings.")
            return
        }
        val ch = state.value.choice
        val send = TempLogic.sendTarget(ch.target, c.boilPoint)
        launchCommand {
            link.boil(send, TempLogic.effectiveKeepWarm(ch.target, ch.keepWarm), allowLowWater)
            write(c.id) { it.copy(lastTarget = ch.target, lastKeepWarm = ch.keepWarm) }
        }
    }

    fun stop() {
        val link = manager.get(cfg().id) ?: return
        launchCommand { link.stopBoil() }
    }

    private fun launchCommand(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        val id = cfg().id
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                note(id, Notice.Kind.Error, e.message ?: "The kettle didn't respond")
            } finally {
                busy.value = false
            }
        }
    }

    /** Settings "Save address": only IP and port; the link is rebuilt for the new endpoint (reconnect). */
    fun saveAddress(id: String, ip: String, port: Int) {
        if (WifiTracker.ipToInt(ip) == null || port !in 1..65535) return
        viewModelScope.launch {
            write(id) { it.copy(ip = ip.trim(), port = port) }
            manager.get(id)?.reconnectNow()
        }
    }

    /** Settings "Test connection": read-only (keep-alive + status only), 5 s timeout. */
    suspend fun testConnection(kettleId: String, ip: String, port: Int): io.github.narthexfire.kettle.net.ConnectionTest.Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val sock = (if (localWifi.requestedFor == kettleId) localWifi.socketOrNull() else null) ?: wifi.newSocket()
            val r = io.github.narthexfire.kettle.net.ConnectionTest.run(ip, port, sock)
            // First-time setup: fill in the Kettle ID the kettle reported, if none is saved yet.
            val cur = state.value.kettles.firstOrNull { it.config.id == kettleId }?.config
            val reported = r.imei?.trim()
            if (r.ok && !reported.isNullOrBlank() && cur != null && cur.imei.isBlank()) write(kettleId) { it.copy(imei = reported) }
            r
        }

    /** Settings "Add another kettle": a new, not-yet-set-up kettle; [then] gets its id (to open its editor). */
    fun addKettle(then: (String) -> Unit) = viewModelScope.launch {
        val id = repo.add("Kettle ${state.value.kettles.size + 1}")
        if (id != null) then(id) else note(cfg().id, Notice.Kind.Error, "Settings are read-only (the stored data couldn't be read); no kettle was added")
    }

    /** Settings "Remove this kettle" (never the last one). */
    fun removeKettle(id: String) = viewModelScope.launch { repo.remove(id) }

    /** Pull-to-refresh (see [io.github.narthexfire.kettle.data.RefreshAction]). Never sends a kettle command. */
    fun pullRefresh() {
        if (refreshing.value) return
        val c = cfg()
        val link = manager.get(c.id)
        val action = io.github.narthexfire.kettle.data.RefreshAction.decide(c.isSetUp, c.enabled && link != null,
            link?.info?.value?.phase == io.github.narthexfire.kettle.net.LinkPhase.Connected)
        refreshing.value = true
        viewModelScope.launch {
            try {
                when (action) {
                    io.github.narthexfire.kettle.data.RefreshAction.NotAvailable ->
                        note(c.id, Notice.Kind.Error, if (!c.isSetUp) "${c.name} is not set up yet." else "${c.name} is turned off in Settings.")
                    io.github.narthexfire.kettle.data.RefreshAction.KeepAlive -> {
                        val rtt = link!!.poke()
                        note(c.id, if (rtt != null) Notice.Kind.Status else Notice.Kind.Connection,
                            if (rtt != null) "Kettle answered in $rtt ms" else "No answer to the keep-alive; reconnecting")
                        if (rtt == null) link.reconnectNow()
                    }
                    io.github.narthexfire.kettle.data.RefreshAction.Reconnect -> {
                        link!!.reconnectNow()
                        val ok = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                            link.info.first { it.phase == io.github.narthexfire.kettle.net.LinkPhase.Connected }
                        } != null
                        if (ok) note(c.id, Notice.Kind.Status, "Reconnected")
                        else {
                            note(c.id, Notice.Kind.Connection, "Not reachable at ${c.ip}; searching the network")
                            episodes.allowAgain(c.id)
                            startSearch(c.id, manual = true)
                        }
                    }
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun reconnect(id: String = cfg().id) {
        val l = manager.get(id)
        if (l == null) note(id, Notice.Kind.Error, "This kettle is not set up or is turned off.") else l.reconnectNow()
    }

    fun saveKettle(updated: KettleConfig) = viewModelScope.launch { write(updated.id) { updated } }

    /** Apply only the edited fields to the CURRENT stored kettle (atomically). */
    fun saveEdits(id: String, edits: io.github.narthexfire.kettle.data.KettleEdits) = viewModelScope.launch { write(id) { edits.applyTo(it) } }


    private fun learnBoil(id: String, t: Int) {
        viewModelScope.launch { write(id) { it.copy(boilHistory = BoilPointLearner.addSample(it.boilHistory, t)) } }
    }

    fun refreshWifi() = wifi.refresh()


    override fun onCleared() {
        manager.setRunning(false)
        wifi.stop()
    }
}
