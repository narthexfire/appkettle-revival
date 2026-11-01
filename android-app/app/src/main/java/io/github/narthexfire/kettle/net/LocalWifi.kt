package io.github.narthexfire.kettle.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.Socket

/** State of the app's local-only connection to a kettle's own Wi-Fi. */
sealed interface LocalWifiState {
    data object Idle : LocalWifiState
    data class Requesting(val kettleId: String, val ssid: String, val mainSsid: String?) : LocalWifiState
    data class Connected(val kettleId: String, val ssid: String, val ipv4: String?, val prefix: Int?, val mainSsid: String?) : LocalWifiState
    data class Unavailable(val kettleId: String, val ssid: String) : LocalWifiState
}

/**
 * Joins a kettle's Wi-Fi as a LOCAL-ONLY network (WifiNetworkSpecifier, INTERNET capability removed),
 * so kettle sockets can be bound to it while the phone keeps (or, without STA concurrency, briefly
 * leaves) its main Wi-Fi. Android shows a one-time approval dialog. The passphrase is never logged.
 */
class LocalWifi(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow<LocalWifiState>(LocalWifiState.Idle)
    val state: StateFlow<LocalWifiState> = _state.asStateFlow()

    @Volatile private var network: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    val sdkSupportsSpecifier: Boolean get() = Build.VERSION.SDK_INT >= 29

    /** Android 12+: can the phone stay on its main Wi-Fi while joined to a local-only one? Null = unknown. */
    val concurrencySupported: Boolean?
        get() = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { app.getSystemService(WifiManager::class.java)?.isStaConcurrencyForLocalOnlyConnectionsSupported }.getOrNull()
        } else null

    val requestedFor: String?
        get() = when (val s = _state.value) {
            is LocalWifiState.Requesting -> s.kettleId
            is LocalWifiState.Connected -> s.kettleId
            else -> null
        }

    /** A socket on the kettle Wi-Fi when it is connected, else null. */
    fun socketOrNull(): Socket? = network?.socketFactory?.createSocket()

    fun datagramOrNull(): java.net.DatagramSocket? =
        network?.let { n -> java.net.DatagramSocket().also { s -> runCatching { n.bindSocket(s) } } }

    /** Fingerprint of the passphrase the current request was made with (the passphrase itself isn't kept). */
    @Volatile var requestedPassKey: Int? = null
        private set

    val requestedSsid: String?
        get() = when (val s = _state.value) {
            is LocalWifiState.Requesting -> s.ssid
            is LocalWifiState.Connected -> s.ssid
            else -> null
        }

    val failedSsid: String? get() = (_state.value as? LocalWifiState.Unavailable)?.ssid

    fun request(kettleId: String, ssid: String, passphrase: String, mainSsid: String?, onConnected: () -> Unit) {
        if (Build.VERSION.SDK_INT < 29) return
        release()
        _state.value = LocalWifiState.Idle // a re-request clears any old "Couldn't join" state
        requestedPassKey = passKey(passphrase)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(passphrase).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                val lp = cm.getLinkProperties(n)
                _state.value = connected(kettleId, ssid, lp, mainSsid)
                onConnected()
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (n == network) _state.value = connected(kettleId, ssid, lp, mainSsid)
            }

            override fun onLost(n: Network) {
                if (n == network) {
                    network = null
                    _state.value = LocalWifiState.Requesting(kettleId, ssid, mainSsid)
                }
            }

            override fun onUnavailable() {
                network = null
                callback = null
                _state.value = LocalWifiState.Unavailable(kettleId, ssid)
            }
        }
        _state.value = LocalWifiState.Requesting(kettleId, ssid, mainSsid)
        callback = cb
        runCatching { cm.requestNetwork(req, cb, TIMEOUT_MS) }.onFailure {
            callback = null
            _state.value = LocalWifiState.Unavailable(kettleId, ssid)
        }
    }

    /** Give the kettle Wi-Fi back (app in background, other kettle, feature off). */
    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        if (_state.value !is LocalWifiState.Unavailable) _state.value = LocalWifiState.Idle
    }

    /** "Try again": forget the failure so the policy may request again. */
    fun clearFailure() {
        if (_state.value is LocalWifiState.Unavailable) _state.value = LocalWifiState.Idle
    }

    private fun connected(id: String, ssid: String, lp: LinkProperties?, mainSsid: String?): LocalWifiState.Connected {
        val a = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        return LocalWifiState.Connected(id, ssid, a?.address?.hostAddress, a?.prefixLength, mainSsid)
    }

    companion object {
        const val TIMEOUT_MS = 30_000

        fun passKey(passphrase: String): Int = passphrase.hashCode()
    }
}
