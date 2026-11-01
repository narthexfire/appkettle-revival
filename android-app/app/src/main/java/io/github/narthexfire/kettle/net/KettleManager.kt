package io.github.narthexfire.kettle.net

import io.github.narthexfire.kettle.data.KettleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket

/** Owns one [KettleConnection] per active kettle and keeps them in step with the settings. */
class KettleManager(
    private val scope: CoroutineScope,
    private val socketFactory: (kettleId: String) -> Socket,
    private val onBoilObserved: (kettleId: String, temp: Int) -> Unit,
) {
    private val links = mutableMapOf<String, KettleConnection>()
    private val _connections = MutableStateFlow<Map<String, KettleConnection>>(emptyMap())
    val connections: StateFlow<Map<String, KettleConnection>> = _connections.asStateFlow()
    private var running = false

    @Synchronized
    fun sync(configs: List<KettleConfig>) {
        val wanted = configs.filter { it.active }.associateBy { it.id }
        // Drop links whose kettle was removed, disabled or re-addressed.
        links.entries.removeAll { (id, link) ->
            val c = wanted[id]
            val stale = c == null || link.endpoint != Endpoint(c.ip.trim(), c.port, c.imei.trim())
            if (stale) link.disconnect()
            stale
        }
        for ((id, c) in wanted) {
            if (id !in links) {
                val link = KettleConnection(
                    Endpoint(c.ip.trim(), c.port, c.imei.trim()), scope, { socketFactory(id) },
                    onBoilObserved = { t -> onBoilObserved(id, t) },
                )
                links[id] = link
                if (running) link.start()
            }
        }
        _connections.value = links.toMap()
    }

    @Synchronized
    fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        links.values.forEach { if (on) it.start() else it.disconnect() }
    }

    fun get(id: String): KettleConnection? = _connections.value[id]
}
