package io.github.narthexfire.kettle.net

import io.github.narthexfire.kettle.data.LowWater
import io.github.narthexfire.kettle.protocol.AcrossReply
import io.github.narthexfire.kettle.protocol.Api
import io.github.narthexfire.kettle.protocol.Commands
import io.github.narthexfire.kettle.protocol.FrameSplitter
import io.github.narthexfire.kettle.protocol.Hex
import io.github.narthexfire.kettle.protocol.Frames
import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.protocol.KettleStatus
import io.github.narthexfire.kettle.protocol.StatusParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

enum class LinkPhase { Connecting, Connected, Offline }

/** Everything the UI and the Diagnostics card need about one kettle link. */
data class LinkInfo(
    val phase: LinkPhase = LinkPhase.Connecting,
    val status: KettleStatus? = null,
    val lastStatusAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val connectedSince: Long = 0L,
    val reconnectCount: Int = 0,
    val lastError: String? = null,
    val keepAliveRttMs: Long? = null,
    val lastRawFrame: String? = null,
    val attempt: Int = 0,
    /** The device ID ("imei") the kettle puts in its own frames. */
    val reportedImei: String? = null,
    /** Commands sent, their acks, and the 10 s of status after a 0x4A (last 40 lines). */
    val commandLog: List<String> = emptyList(),
    /** Keep-alive echoes received (pull-to-refresh waits for the next one). */
    val keepAliveAcks: Int = 0,
    /** Frames dropped because their checksum didn't verify (as the original app does). */
    val badFrames: Int = 0,
    /** Set when this address answers with a different Kettle ID than the saved one. */
    val imeiMismatch: String? = null,
)

class KettleException(message: String) : Exception(message)

data class Endpoint(val ip: String, val port: Int, val imei: String)

/**
 * One persistent TCP link to one kettle. Connects, sends the keep-alive every 30 s, parses every
 * status frame into [info], and reconnects with backoff (the kettle power-saves and drops links).
 *
 * Commands are only sent through [boil], [stopBoil] and [keepWarm]; a link created with
 * allowCommands = false (the read-only live check) refuses them before anything is written, and
 * So does a link whose kettle reports a different Kettle ID.
 */
class KettleConnection(
    val endpoint: Endpoint,
    private val scope: CoroutineScope,
    private val socketFactory: () -> Socket = { Socket() },
    private val allowCommands: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onBoilObserved: (Int) -> Unit = {},
) {
    private val _info = MutableStateFlow(LinkInfo())
    val info: StateFlow<LinkInfo> = _info.asStateFlow()

    private var job: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()
    private val commandLock = Mutex()
    /** Pending acks keyed by api code AND serial (the kettle echoes the serial). */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<AcrossReply>>()
    @Volatile private var socket: Socket? = null
    @Volatile private var keepAliveSentAt = 0L
    /** True while we close the socket on purpose (no "Socket closed" error is recorded). */
    @Volatile private var closingOnPurpose = false
    @Volatile private var reachedConnected = false
    private var serial = 0
    private var sawHeatingToBoil = false

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun disconnect() {
        closingOnPurpose = true
        job?.cancel()
        job = null
        closeSocket()
        _info.update { it.copy(phase = LinkPhase.Offline, lastError = null) }
    }

    /** Skip the backoff wait and try again now (also resets the backoff). */
    fun reconnectNow() {
        closingOnPurpose = true
        closeSocket()
        kick.trySend(Unit)
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            _info.update { it.copy(phase = LinkPhase.Connecting, attempt = attempt + 1) }
            reachedConnected = false
            closingOnPurpose = false
            try {
                session()
                attempt = 0
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                attempt = nextAttempt(attempt, reachedConnected)
                val intentional = closingOnPurpose
                _info.update {
                    it.copy(phase = LinkPhase.Offline, connectedSince = 0L, lastError = if (intentional) it.lastError else describe(e))
                }
            } finally {
                closeSocket()
                pending.values.forEach { it.completeExceptionally(KettleException("Connection lost")) }
                pending.clear()
            }
            val wait = BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)]
            if (withTimeoutOrNull(wait) { kick.receive() } != null) attempt = 0
            _info.update { it.copy(reconnectCount = it.reconnectCount + 1) }
        }
    }

    private suspend fun session() = withContext(Dispatchers.IO) {
        val s = socketFactory()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(endpoint.ip, endpoint.port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_POLL_MS
        val input = s.getInputStream()
        sendKeepAlive()
        val splitter = FrameSplitter()
        val buf = ByteArray(4096)
        var lastRx = clock()
        val ka = launch {
            while (isActive) {
                delay(KEEP_ALIVE_MS)
                sendKeepAlive()
            }
        }
        try {
            while (isActive) {
                val n = try {
                    input.read(buf)
                } catch (e: SocketTimeoutException) {
                    if (clock() - lastRx > SILENCE_LIMIT_MS) throw KettleException("Kettle went quiet (no data for ${SILENCE_LIMIT_MS / 1000} s)")
                    continue
                }
                if (n < 0) throw KettleException("Kettle closed the connection")
                lastRx = clock()
                val now = clock()
                if (_info.value.phase != LinkPhase.Connected) {
                    reachedConnected = true // the backoff restarts from the beginning after a good session
                    _info.update { it.copy(phase = LinkPhase.Connected, connectedSince = now, lastError = null) }
                }
                _info.update { it.copy(lastSeenAt = now) }
                for (frame in splitter.feed(String(buf, 0, n, Charsets.US_ASCII))) handleFrame(frame, now)
            }
        } finally {
            ka.cancel()
        }
    }

    internal fun handleFrame(frame: String, now: Long) {
        if (frame.contains("KeepConnect")) {
            val sent = keepAliveSentAt
            if (sent > 0) _info.update { it.copy(keepAliveRttMs = now - sent, keepAliveAcks = it.keepAliveAcks + 1) }
            keepAliveSentAt = 0
            return
        }
        val reply = Frames.parseWire(frame) ?: return
        // Drop frames whose checksum doesn't verify, as the original app does
        if (!reply.checksumOk) {
            _info.update { it.copy(badFrames = it.badFrames + 1) }
            log("DROPPED frame api ${Hex.u8(reply.api)} (checksum)")
            return
        }
        IMEI.find(frame)?.groupValues?.get(1)?.let { id ->
            if (id != _info.value.reportedImei) _info.update { it.copy(reportedImei = id) }
            val expected = endpoint.imei.trim()
            val mismatch = if (expected.isNotEmpty() && !id.trim().equals(expected, ignoreCase = true)) id.trim() else null
            if (mismatch != _info.value.imeiMismatch) _info.update { it.copy(imeiMismatch = mismatch) }
        }
        val status = StatusParser.fromReply(reply)
        if (status != null) {
            observeBoil(status)
            _info.update { it.copy(status = status, lastStatusAt = now, lastRawFrame = frame) }
            if (now < traceUntil) {
                log("STATUS ${status.state.label} ${status.temperatureC}° target ${status.targetC}° kw ${status.keepWarmSecondsLeft}s water ${status.waterMl} ml raw ${status.rawContent}")
            }
            return
        }
        val waiter = pending.remove(ackKey(reply.api, reply.serial))
        if (waiter != null) waiter.complete(reply)
        else log("IGNORED stale ack ${Hex.u8(reply.api)} serial=${reply.serial} content=${reply.content}")
    }

    private fun observeBoil(s: KettleStatus) {
        if (s.state == KettleState.Heating) {
            if (s.targetC == 100) sawHeatingToBoil = true
            return
        }
        val done = s.state == KettleState.Ready || s.state == KettleState.KeepWarm
        val sample = io.github.narthexfire.kettle.data.BoilPointLearner.sample(sawHeatingToBoil, done, s.targetC, s.temperatureC)
        sawHeatingToBoil = false
        if (sample != null) onBoilObserved(sample)
    }

    /** Pull-to-refresh while connected: send ONLY the keep-alive and return its round trip (ms), or null. */
    suspend fun poke(timeoutMs: Long = 3_000): Long? {
        if (_info.value.phase != LinkPhase.Connected) return null
        val n0 = _info.value.keepAliveAcks
        runCatching { sendKeepAlive() }.onFailure { return null }
        return withTimeoutOrNull(timeoutMs) { info.first { it.keepAliveAcks > n0 } }?.keepAliveRttMs
    }

    private suspend fun sendKeepAlive() {
        keepAliveSentAt = clock()
        write(Frames.KEEP_ALIVE)
    }

    private suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val s = socket ?: throw KettleException("Not connected")
        writeLock.withLock {
            s.getOutputStream().apply { write(text.toByteArray(Charsets.US_ASCII)); flush() }
        }
    }

    private fun nextSerial(): Int {
        serial = if (serial >= 255) 1 else serial + 1
        return serial
    }

    @Volatile private var traceUntil = 0L

    private fun log(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(clock()))
        _info.update { it.copy(commandLog = (it.commandLog + "$t $line").takeLast(40)) }
    }

    private suspend fun command(api: Int, label: String, build: (Int) -> String): AcrossReply {
        if (!allowCommands) throw KettleException("Commands are disabled on this link")
        _info.value.imeiMismatch?.let { throw KettleException(mismatchMessage(it)) }
        if (_info.value.phase != LinkPhase.Connected) throw KettleException("Not connected to the kettle")
        return commandLock.withLock {
            val d = CompletableDeferred<AcrossReply>()
            val s = nextSerial()
            val key = ackKey(api, s)
            pending[key] = d
            try {
                val wire = build(s)
                log("SENT ${Hex.u8(api)} $label serial=$s data2=" + (Frames.DATA2.find(wire)?.groupValues?.get(1) ?: "?"))
                write(wire)
                val r = withTimeout(ACK_TIMEOUT_MS) { d.await() }
                log("ACK ${Hex.u8(r.api)} serial=${r.serial} content=${r.content}")
                if (!r.isAck) throw KettleException("Kettle refused $label (code ${r.content.take(2)})")
                r
            } catch (e: TimeoutCancellationException) {
                log("NO ACK ${Hex.u8(api)} serial=$s within ${ACK_TIMEOUT_MS / 1000} s")
                throw KettleException("No reply to $label within ${ACK_TIMEOUT_MS / 1000} s")
            } finally {
                pending.remove(key)
            }
        }
    }

    /**
     * 0x4A keep warm (standalone; no WAKE, as in the original app). Logs the frame, the ack and the next
     * 10 s of status frames into [LinkInfo.commandLog] for "Copy details".
     */
    suspend fun keepWarm(minutes: Int) {
        val st = _info.value.status
        if (st != null && st.state == KettleState.OffBase) throw KettleException("Put the kettle back on its base")
        try {
            command(Api.SET_KEEP_WARM, "keep warm $minutes min") { Commands.keepWarm(endpoint.imei, it, minutes) }
        } finally {
            traceUntil = clock() + 10_000
        }
    }

    /** WAKE (unless already heating / keeping warm), then ON with target and keep-warm minutes. */
    suspend fun boil(targetC: Int, keepWarmMin: Int, allowLowWater: Boolean = false) {
        val i = _info.value
        val st = i.status
        if (st != null && st.state == KettleState.OffBase) throw KettleException("Put the kettle back on its base")
        // The same rule as the UI's warning (fresh status, on the base, below MIN); the UI asks first.
        if (lowWaterBlocks(st, i.lastStatusAt, clock(), allowLowWater)) throw KettleException("Not enough water: fill above MIN (250 ml)")
        if (st == null || !st.state.isActive) {
            command(Api.SET_KETTLE_ON, "wake") { Commands.wake(endpoint.imei, it) }
        }
        command(Api.SET_START_BOIL, "boil") { Commands.startBoil(endpoint.imei, it, targetC, keepWarmMin) }
    }

    suspend fun stopBoil() {
        command(Api.SET_STOP_BOIL, "stop") { Commands.stop(endpoint.imei, it) }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun describe(e: Exception): String = when (e) {
        is KettleException -> e.message ?: "Error"
        is java.net.ConnectException -> "Can't reach ${endpoint.ip}:${endpoint.port} (${e.message})"
        is SocketTimeoutException -> "No answer from ${endpoint.ip}:${endpoint.port} (timed out)"
        is java.net.NoRouteToHostException -> "No route to ${endpoint.ip}: is the phone on the kettle's Wi-Fi?"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private val IMEI = Regex("\"imei\":\"([^\"]+)\"")
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_POLL_MS = 1_000
        const val KEEP_ALIVE_MS = 30_000L
        const val SILENCE_LIMIT_MS = 75_000L
        const val ACK_TIMEOUT_MS = 6_000L
        const val MIN_WATER_ML = 250
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

        fun ackKey(api: Int, serial: Int) = (api shl 8) or (serial and 0xFF)

        /** A session that reached Connected restarts the backoff from its first step. */
        fun nextAttempt(prev: Int, reachedConnected: Boolean) = if (reachedConnected) 1 else prev + 1

        /** One low-water rule for the UI warning and the connection guard. */
        fun lowWaterBlocks(status: KettleStatus?, lastStatusAt: Long, now: Long, allowLowWater: Boolean) =
            !allowLowWater && LowWater.shouldWarn(status, lastStatusAt, now)

        fun mismatchMessage(reported: String) = "This address now answers as a different kettle (ID $reported)"
    }
}
