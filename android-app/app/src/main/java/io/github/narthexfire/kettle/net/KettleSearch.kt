package io.github.narthexfire.kettle.net

import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.protocol.FrameSplitter
import io.github.narthexfire.kettle.protocol.Frames
import io.github.narthexfire.kettle.protocol.StatusParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Automatic kettle search (after a DHCP change moved the kettle to a new address).
 * READ-ONLY: every candidate gets only the keep-alive; a device is accepted only if the "imei" in
 * its own status frame matches the kettle's saved Kettle ID. No command is ever sent.
 * First-time setup: with no saved Kettle ID (expected ID blank), the first kettle found whose ID is
 * not in the skip list (kettles already set up in the app) is accepted.
 */
object KettleSearch {
    const val DISCOVERY_PORT = 15103
    const val CONNECT_TIMEOUT_MS = 700
    const val READ_TIMEOUT_MS = 2_000
    const val PARALLEL = 64
    /** Power-saving kettles need persistence: 6 rounds over ~60-90 s, 2 tries per candidate per round. */
    const val ROUNDS = 6
    const val ROUND_GAP_MS = 10_000L
    const val TRIES_PER_ROUND = 2
    const val NEAR = 5

    sealed interface Probe {
        data class Match(val ip: String, val imei: String) : Probe
        data class WrongKettle(val ip: String, val imei: String) : Probe
        data object NoKettle : Probe
    }

    fun imeiMatches(expected: String, reported: String?): Boolean =
        reported != null && expected.isNotBlank() && reported.trim().equals(expected.trim(), ignoreCase = true)

    /** First-time setup: any kettle ID that is not already used by another kettle in the app. */
    fun acceptsNew(reported: String?, skip: Set<String>): Boolean =
        !reported.isNullOrBlank() && skip.none { it.trim().equals(reported.trim(), ignoreCase = true) }

    private fun accepts(expected: String, reported: String?, skip: Set<String>): Boolean =
        if (expected.isBlank()) acceptsNew(reported, skip) else imeiMatches(expected, reported)

    /** .1-.254 of [phoneIp]'s /24, minus the phone itself (and anything in [exclude]). */
    fun candidates(phoneIp: String, exclude: Set<String?> = emptySet()): List<String> {
        val p = phoneIp.trim().split('.')
        if (p.size != 4 || WifiTracker.ipToInt(phoneIp) == null) return emptyList()
        val base = "${p[0]}.${p[1]}.${p[2]}."
        val skip = exclude.filterNotNull().map { it.trim() }.toSet() + phoneIp.trim()
        return (1..254).map { base + it }.filter { it !in skip }
    }

    /** Addresses within ±[NEAR] of the old one first (nearest first), then the rest in order. */
    fun ordered(hosts: List<String>, oldIp: String?): List<String> {
        val old = oldIp?.substringAfterLast('.')?.toIntOrNull() ?: return hosts
        val prefix = oldIp.substringBeforeLast('.')
        fun last(ip: String) = ip.substringAfterLast('.').toIntOrNull() ?: 999
        val (near, rest) = hosts.partition { it.substringBeforeLast('.') == prefix && kotlin.math.abs(last(it) - old) <= NEAR }
        return near.sortedWith(compareBy({ kotlin.math.abs(last(it) - old) }, { last(it) })) + rest
    }

    /** Neighbors from the ARP cache on the same /24, if the OS allows reading it (Android 10+ usually doesn't). */
    fun arpNeighbors(phoneIp: String, read: () -> List<String> = { java.io.File("/proc/net/arp").readLines() }): List<String> {
        val prefix = phoneIp.substringBeforeLast('.') + "."
        return runCatching {
            read().drop(1).mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                val ip = cols.getOrNull(0) ?: return@mapNotNull null
                val flags = cols.getOrNull(2)
                if (ip.startsWith(prefix) && ip != phoneIp && flags != "0x0") ip else null
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Hosts to sweep for the phone's real prefix. /22../30 are enumerated in full (a /22
     * is 1022 hosts, the cap); larger networks (prefix < 22) sweep the phone's /24 plus the old IP's
     * /24. The phone itself and anything in [exclude] are skipped.
     */
    fun candidatesFor(phoneIp: String, prefix: Int?, oldIp: String?, exclude: Set<String?> = emptySet()): List<String> {
        val ip = WifiTracker.ipToInt(phoneIp) ?: return emptyList()
        val p = prefix ?: 24
        val skip = exclude.filterNotNull().map { it.trim() }.toSet() + phoneIp.trim()
        if (p < 22 || p > 30) {
            // candidates() leaves out its own first argument; the old address itself must be tried too.
            val olds = oldIp?.trim()?.takeIf { WifiTracker.ipToInt(it) != null }
                ?.let { o -> candidates(o, skip) + listOf(o).filter { it !in skip } } ?: emptyList()
            return (candidates(phoneIp, skip) + olds).distinct()
        }
        val mask = -1 shl (32 - p)
        val net = ip and mask
        val size = 1 shl (32 - p)
        return (1 until size - 1).map { intToIp(net + it) }.filter { it !in skip }
    }

    fun intToIp(v: Int) = listOf(24, 16, 8, 0).joinToString(".") { ((v ushr it) and 0xFF).toString() }

    /** Directed broadcast for the phone's prefix (the /24 one for larger networks). */
    fun broadcastFor(phoneIp: String, prefix: Int?): String? {
        val ip = WifiTracker.ipToInt(phoneIp) ?: return null
        val p = prefix?.takeIf { it in 22..30 } ?: 24
        return intToIp(ip or ((1 shl (32 - p)) - 1))
    }

    fun broadcastOf(phoneIp: String): String? {
        val p = phoneIp.trim().split('.')
        return if (p.size == 4) "${p[0]}.${p[1]}.${p[2]}.255" else null
    }

    fun probeMessage(now: Long = System.currentTimeMillis()): String =
        "Probe#" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date(now)) + "-2"

    /** Connect, send ONLY the keep-alive, read the first status frame's "imei". */
    fun probe(ip: String, port: Int, expectedImei: String, socket: Socket = Socket(),
              connectTimeoutMs: Int = CONNECT_TIMEOUT_MS, readTimeoutMs: Int = READ_TIMEOUT_MS,
              skipImeis: Set<String> = emptySet()): Probe {
        return try {
            socket.use { s ->
                s.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                s.soTimeout = 300
                s.getOutputStream().apply { write(Frames.KEEP_ALIVE.toByteArray(Charsets.US_ASCII)); flush() }
                val input = s.getInputStream()
                val splitter = FrameSplitter()
                val buf = ByteArray(4096)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < readTimeoutMs) {
                    val n = try { input.read(buf) } catch (e: SocketTimeoutException) { continue }
                    if (n < 0) break
                    for (f in splitter.feed(String(buf, 0, n, Charsets.US_ASCII))) {
                        if (Frames.parseWire(f)?.let { StatusParser.fromReply(it) } == null) continue // status frames only
                        val imei = IMEI.find(f)?.groupValues?.get(1)
                        return if (accepts(expectedImei, imei, skipImeis)) Probe.Match(ip, imei!!) else Probe.WrongKettle(ip, imei ?: "?")
                    }
                }
                Probe.NoKettle
            }
        } catch (e: Exception) {
            Probe.NoKettle
        }
    }

    /** UDP discovery: broadcast the probe and collect senders whose reply mentions [expectedImei]. */
    fun udpDiscover(broadcast: String, expectedImei: String, socket: DatagramSocket, listenMs: Int = 3_000): List<String> {
        val found = linkedSetOf<String>()
        try {
            socket.use { s ->
                s.broadcast = true
                s.soTimeout = 300
                val msg = probeMessage().toByteArray(Charsets.US_ASCII)
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(broadcast), DISCOVERY_PORT))
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + listenMs
                while (System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (e: SocketTimeoutException) { continue }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    // Blank expected ID (first-time setup): any reply that looks like a kettle ("GD" IDs).
                    val ok = if (expectedImei.isBlank()) text.contains("GD") else text.contains(expectedImei, ignoreCase = true)
                    if (ok) pkt.address?.hostAddress?.let { found += it }
                }
            }
        } catch (_: Exception) {
        }
        return found.toList()
    }

    /**
     * Probe [hosts] [rounds] times, [gapMs] apart, [parallel] at a time, [tries] attempts per candidate
     * per round. Before each round [extraFirst] (UDP replies, ARP neighbors) is asked for addresses to
     * try first. [onRound] reports progress (1-based). Returns the first IMEI match, or null.
     */
    suspend fun sweep(
        hosts: List<String>, port: Int, expectedImei: String, socketFactory: () -> Socket,
        rounds: Int = ROUNDS, gapMs: Long = ROUND_GAP_MS, parallel: Int = PARALLEL,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS, tries: Int = TRIES_PER_ROUND,
        extraFirst: suspend () -> List<String> = { emptyList() },
        onRound: (Int) -> Unit = {},
        skipImeis: Set<String> = emptySet(),
    ): Probe.Match? = withContext(Dispatchers.IO) {
        val gate = Semaphore(parallel)
        repeat(rounds) { round ->
            if (round > 0) delay(gapMs)
            onRound(round + 1)
            val order = (runCatching { extraFirst() }.getOrDefault(emptyList()) + hosts).distinct()
            val match = coroutineScope {
                order.map { ip ->
                    async {
                        gate.withPermit {
                            var r: Probe = Probe.NoKettle
                            for (t in 0 until tries) {
                                r = probe(ip, port, expectedImei, socketFactory(), connectTimeoutMs, skipImeis = skipImeis)
                                if (r != Probe.NoKettle) break
                                if (t < tries - 1) delay(250)
                            }
                            r
                        }
                    }
                }.awaitAll().firstOrNull { it is Probe.Match } as Probe.Match?
            }
            if (match != null) return@withContext match
        }
        null
    }

    private val IMEI = Regex("\"imei\":\"([^\"]+)\"")
}

/** When to run an automatic search: once per failure episode, only when the subnet can be swept. */
object SearchPolicy {
    const val AFTER_OFFLINE_MS = 12_000L

    fun shouldSearch(
        autoEnabled: Boolean, foreground: Boolean, kettleActive: Boolean, linkConnected: Boolean,
        offlineSince: Long?, now: Long, sameSubnetOrKettleWifi: Boolean, searchedThisEpisode: Boolean, running: Boolean,
        /** The saved kettle IP is one of the phone's own addresses: stale, search at once. */
        savedIpIsOwn: Boolean = false,
    ): Boolean = autoEnabled && foreground && kettleActive && !linkConnected && !running && !searchedThisEpisode &&
        sameSubnetOrKettleWifi && (savedIpIsOwn || (offlineSince != null && now - offlineSince >= AFTER_OFFLINE_MS))

    fun savedIpIsOwn(savedIp: String, ownIps: Collection<String?>): Boolean =
        savedIp.isNotBlank() && ownIps.any { it != null && it.trim() == savedIp.trim() }
}

/** Applying a found address: new IP plus a log line kept with the kettle (last 5). */
object AddressUpdate {
    fun apply(cfg: KettleConfig, newIp: String, now: Long, automatic: Boolean = true): KettleConfig {
        if (newIp == cfg.ip) return cfg
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))
        val line = "$stamp · ${cfg.ip.ifBlank { "—" }} → $newIp" + if (automatic) " (automatic)" else " (search)"
        return cfg.copy(ip = newIp, addressLog = (cfg.addressLog + line).takeLast(5))
    }

    /** "Found Kitchen kettle at 192.168.1.101 (was .100)". */
    fun message(name: String, oldIp: String, newIp: String): String {
        val o = oldIp.split('.'); val n = newIp.split('.')
        val was = if (o.size == 4 && n.size == 4 && o.take(3) == n.take(3)) ".${o[3]}" else oldIp
        return "Found $name at $newIp (was $was)"
    }
}

/**
 * Failure episodes for the automatic search. Timed only while the app is in the
 * foreground; returning to the foreground starts fresh (no spurious search on resume).
 */
class SearchEpisodes {
    private val offlineSince = mutableMapOf<String, Long>()
    private val searched = mutableSetOf<String>()

    fun onForeground() { offlineSince.clear(); searched.clear() }

    fun observe(id: String, connected: Boolean, hasLink: Boolean, foreground: Boolean, now: Long) {
        if (connected) { offlineSince.remove(id); searched.remove(id) }
        else if (hasLink && foreground) offlineSince.putIfAbsent(id, now)
    }

    fun offlineSince(id: String): Long? = offlineSince[id]
    fun searched(id: String) = id in searched
    fun markSearched(id: String) { searched += id }
    fun allowAgain(id: String) { searched -= id }
}
