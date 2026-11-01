package io.github.narthexfire.kettle.net

import io.github.narthexfire.kettle.protocol.FrameSplitter
import io.github.narthexfire.kettle.protocol.Frames
import io.github.narthexfire.kettle.protocol.StatusParser
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * READ-ONLY "Test connection" for the Settings address fields: connect, send the keep-alive, wait
 * for the kettle's reply or a status frame. The ONLY bytes ever written are [Frames.KEEP_ALIVE];
 * no command builder is reachable from here.
 */
object ConnectionTest {
    /** [imei] is the Kettle ID the kettle reported, if any (used to fill in a new kettle's ID). */
    data class Result(val ok: Boolean, val message: String, val imei: String? = null)

    fun run(ip: String, port: Int, socket: Socket = Socket(), timeoutMs: Int = 5_000, clock: () -> Long = System::currentTimeMillis): Result {
        if (WifiTracker.ipToInt(ip) == null) return Result(false, "Not a valid IPv4 address")
        if (port !in 1..65535) return Result(false, "Port must be 1 to 65535")
        val start = clock()
        return try {
            socket.use { s ->
                s.connect(InetSocketAddress(ip.trim(), port), timeoutMs)
                s.soTimeout = 500
                s.getOutputStream().apply { write(Frames.KEEP_ALIVE.toByteArray(Charsets.US_ASCII)); flush() }
                val input = s.getInputStream()
                val splitter = FrameSplitter()
                val buf = ByteArray(4096)
                var echo = false
                var imei: String? = null
                while (clock() - start < timeoutMs) {
                    val n = try { input.read(buf) } catch (e: SocketTimeoutException) { continue }
                    if (n < 0) break
                    for (f in splitter.feed(String(buf, 0, n, Charsets.US_ASCII))) {
                        if (f.contains("KeepConnect")) echo = true
                        Regex("\"imei\":\"([^\"]+)\"").find(f)?.let { imei = it.groupValues[1] }
                        val st = Frames.parseWire(f)?.let { StatusParser.fromReply(it) }
                        if (st != null) {
                            return Result(true, "OK · ${st.state.label}, ${st.temperatureC} °C, ${st.waterMl} ml" +
                                (imei?.let { " · Kettle ID $it" } ?: "") + " (${clock() - start} ms)", imei)
                        }
                    }
                }
                if (echo) Result(true, "OK · the kettle answered the keep-alive (no status yet)", imei)
                else Result(false, "Connected to $ip:$port, but nothing answered within ${timeoutMs / 1000} s")
            }
        } catch (e: SocketTimeoutException) {
            Result(false, "No answer from $ip:$port within ${timeoutMs / 1000} s")
        } catch (e: java.net.ConnectException) {
            Result(false, "Can't reach $ip:$port (${e.message})")
        } catch (e: java.net.NoRouteToHostException) {
            Result(false, "No route to $ip: is the phone on the kettle's Wi-Fi?")
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
