package io.github.narthexfire.kettle.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.Socket

data class PhoneNet(
    val onWifi: Boolean = false,
    val ssid: String? = null,
    val ssidNeedsPermission: Boolean = false,
    val ipv4: String? = null,
    val prefixLength: Int? = null,
    val gateway: String? = null,
)

/**
 * Tracks the phone's Wi-Fi network. Kettle sockets are bound to it, because a Wi-Fi with no
 * internet (such as a dedicated kettle network) is otherwise often bypassed by Android in favor of mobile
 * data, and a LAN address can't be reached over cellular data.
 */
class WifiTracker(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var wifi: Network? = null
    private val _phone = MutableStateFlow(PhoneNet())
    val phone: StateFlow<PhoneNet> = _phone.asStateFlow()

    private fun callback() = if (Build.VERSION.SDK_INT >= 31) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = onUp(network)
            override fun onLost(network: Network) = onDown(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(network, caps)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = onLp(network, lp)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onUp(network)
            override fun onLost(network: Network) = onDown(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(network, caps)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = onLp(network, lp)
        }
    }

    private var registered: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (registered != null) return
        val cb = callback()
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(req, cb) }.onSuccess { registered = cb }
    }

    fun stop() {
        registered?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        registered = null
    }

    /** Re-read the SSID (after the location permission is granted). */
    fun refresh() {
        stop(); start()
    }

    fun newSocket(): Socket = wifi?.socketFactory?.createSocket() ?: Socket()

    /** A UDP socket on the phone's Wi-Fi (for the discovery probe). */
    fun newDatagram(): java.net.DatagramSocket = java.net.DatagramSocket().also { s -> runCatching { wifi?.bindSocket(s) } }

    private fun onUp(n: Network) {
        wifi = n
        _phone.update { it.copy(onWifi = true) }
        cm.getLinkProperties(n)?.let { onLp(n, it) }
    }

    private fun onDown(n: Network) {
        if (wifi == n) {
            wifi = null
            _phone.value = PhoneNet(onWifi = false, ssidNeedsPermission = !hasLocation())
        }
    }

    private fun onLp(n: Network, lp: LinkProperties) {
        val a = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        _phone.update { it.copy(onWifi = true, ipv4 = a?.address?.hostAddress, prefixLength = a?.prefixLength, gateway = gw) }
    }

    @SuppressLint("MissingPermission")
    private fun onCaps(n: Network, caps: NetworkCapabilities) {
        val granted = hasLocation()
        var ssid: String? = null
        if (granted) {
            ssid = if (Build.VERSION.SDK_INT >= 31) {
                (caps.transportInfo as? WifiInfo)?.ssid
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
            }
            ssid = ssid?.trim('"')?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID.trim('"', '<', '>') || it.contains("unknown ssid") }
        }
        _phone.update { it.copy(onWifi = true, ssid = ssid, ssidNeedsPermission = !granted) }
    }

    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    companion object {
        /** True when [ip] lies in the IPv4 subnet ipv4/prefix. */
        fun sameSubnet(phoneIp: String?, prefix: Int?, ip: String): Boolean? {
            if (phoneIp == null || prefix == null) return null
            val a = ipToInt(phoneIp) ?: return null
            val b = ipToInt(ip) ?: return null
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            return (a and mask) == (b and mask)
        }

        fun ipToInt(ip: String): Int? {
            val p = ip.trim().split('.')
            if (p.size != 4) return null
            var r = 0
            for (s in p) {
                val v = s.toIntOrNull() ?: return null
                if (v !in 0..255) return null
                r = (r shl 8) or v
            }
            return r
        }

        fun maskString(prefix: Int): String {
            val m = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            return listOf(24, 16, 8, 0).joinToString(".") { ((m ushr it) and 0xFF).toString() }
        }
    }
}
