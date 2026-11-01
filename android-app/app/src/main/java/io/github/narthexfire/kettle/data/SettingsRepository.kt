package io.github.narthexfire.kettle.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * A corrupt preferences file is replaced with empty preferences (the app then uses the
 * defaults) instead of crashing.
 */
private val Context.dataStore by preferencesDataStore(
    name = "kettle_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * What was read from storage. [readOnly] is true when the stored kettle data could not be fully
 * decoded: the app then works from what it could read (or the defaults) but NEVER writes, so the
 * raw data is kept intact.
 */
data class Stored(val kettles: List<KettleConfig>, val readOnly: Boolean = false, val problem: String? = null)

class SettingsRepository(private val context: Context) {
    private val KETTLES = stringPreferencesKey("kettles_json")
    private val SELECTED = stringPreferencesKey("selected_kettle")

    private val prefs = context.dataStore.data.catch { emit(emptyPreferences()) }

    val stored: Flow<Stored> = prefs.map { p -> decodeStored(p[KETTLES]) }

    val kettles: Flow<List<KettleConfig>> = stored.map { it.kettles }

    val selectedId: Flow<String> = prefs.map { it[SELECTED] ?: KettleConfig.DEFAULTS.first().id }

    suspend fun select(id: String) {
        runCatching { context.dataStore.edit { it[SELECTED] = id } }
    }

    /** Applies [transform] to kettle [id]. Returns false (and writes nothing) when the stored data is unreadable. */
    suspend fun update(id: String, transform: (KettleConfig) -> KettleConfig): Boolean {
        var wrote = false
        runCatching {
            context.dataStore.edit { p ->
                val next = applyUpdate(p[KETTLES], id, transform) ?: return@edit
                p[KETTLES] = next
                wrote = true
            }
        }
        return wrote
    }

    suspend fun current(): List<KettleConfig> = kettles.first()

    /** "Add another kettle": appends a new, not-yet-set-up kettle and selects it. Returns its id, or null. */
    suspend fun add(name: String): String? {
        var newId: String? = null
        runCatching {
            context.dataStore.edit { p ->
                val s = decodeStored(p[KETTLES])
                if (s.readOnly) return@edit
                val id = KettleConfig.newId(s.kettles.map { it.id })
                p[KETTLES] = encode(s.kettles + KettleConfig(id = id, name = name))
                p[SELECTED] = id
                newId = id
            }
        }
        return newId
    }

    /** Removes kettle [id] (never the last one). Returns true when something was written. */
    suspend fun remove(id: String): Boolean {
        var wrote = false
        runCatching {
            context.dataStore.edit { p ->
                val s = decodeStored(p[KETTLES])
                if (s.readOnly || s.kettles.size <= 1 || s.kettles.none { it.id == id }) return@edit
                val rest = s.kettles.filter { it.id != id }
                p[KETTLES] = encode(rest)
                if (p[SELECTED] == id) p[SELECTED] = rest.first().id
                wrote = true
            }
        }
        return wrote
    }

    /** Persist one-time migrations (such as the "Coffee first" order) so they never run again. */
    suspend fun persistMigrations() {
        runCatching {
            context.dataStore.edit { p ->
                val raw = p[KETTLES] ?: return@edit
                val s = decodeStored(raw)
                if (s.readOnly) return@edit
                val again = encode(s.kettles)
                if (again != raw) p[KETTLES] = again
            }
        }
    }

    companion object {
        /**
         * Lenient decode. Missing or bad fields take their defaults; a kettle entry that can't
         * be read at all, or an unreadable list, marks the result read-only. An empty list falls back
         * to the defaults (and is writable).
         */
        fun decodeStored(raw: String?): Stored {
            if (raw.isNullOrBlank()) return Stored(KettleConfig.DEFAULTS)
            val arr = runCatching { JSONArray(raw) }.getOrNull()
                ?: return Stored(KettleConfig.DEFAULTS, readOnly = true, problem = "Stored kettle settings are unreadable")
            val out = mutableListOf<KettleConfig>()
            var bad = 0
            for (i in 0 until arr.length()) {
                val k = runCatching { decodeOne(arr.getJSONObject(i)) }.getOrNull()
                if (k == null) bad++ else out += k
            }
            if (out.isEmpty() && bad == 0) return Stored(KettleConfig.DEFAULTS)
            if (out.isEmpty()) return Stored(KettleConfig.DEFAULTS, readOnly = true, problem = "Stored kettle settings are unreadable")
            return if (bad > 0) Stored(out, readOnly = true, problem = "$bad stored kettle entr${if (bad == 1) "y" else "ies"} couldn't be read")
            else Stored(out)
        }

        /** The new raw JSON after updating kettle [id], or null when nothing may be written. */
        fun applyUpdate(raw: String?, id: String, transform: (KettleConfig) -> KettleConfig): String? {
            val s = decodeStored(raw)
            if (s.readOnly) return null
            return encode(s.kettles.map { if (it.id == id) transform(it) else it })
        }

        /** Kept for callers and tests: throws only if nothing at all can be read. */
        fun decode(s: String): List<KettleConfig> = decodeStored(s).kettles

        private inline fun <T> f(default: T, read: () -> T): T = runCatching(read).getOrDefault(default)

        private fun decodeOne(o: JSONObject): KettleConfig {
            val id = o.getString("id").also { require(it.isNotBlank()) }
            val def = KettleConfig.DEFAULTS.firstOrNull { it.id == id }
            val base = KettleConfig(id = id, name = def?.name ?: id)
            return KettleConfig(
                id = id,
                name = f(base.name) { o.optString("name").ifBlank { base.name } },
                ip = f("") { o.optString("ip") },
                port = f(6002) { o.optInt("port", 6002) },
                imei = f("") { o.optString("imei") },
                mac = f("") { o.optString("mac") },
                wifiSsid = f("") { o.optString("wifiSsid") },
                cupMl = f(Cups.DEFAULT_ML) { o.optInt("cupMl", Cups.DEFAULT_ML).coerceIn(Cups.RANGE) },
                addressLog = f(emptyList()) { o.optJSONArray("addressLog")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() },
                // Fields: older data without them takes the built-in defaults (none set: auto-join off).
                wifiPass = f(def?.wifiPass ?: "") { if (o.has("wifiPass")) o.optString("wifiPass") else def?.wifiPass ?: "" },
                autoJoinWifi = f(def?.autoJoinWifi ?: false) { if (o.has("autoJoinWifi")) o.getBoolean("autoJoinWifi") else def?.autoJoinWifi ?: false },
                device = f(DeviceFacts.DEFAULT_DEVICE) { o.optString("device").ifBlank { DeviceFacts.DEFAULT_DEVICE } },
                defaultBoilPoint = f(100) { o.optInt("defaultBoilPoint", 100) },
                boilPointOverride = f(null) { if (o.has("boilPointOverride")) o.getInt("boilPointOverride") else null },
                boilHistory = f(emptyList()) { o.optJSONArray("boilHistory")?.let { h -> (0 until h.length()).mapNotNull { runCatching { h.getInt(it) }.getOrNull() } } ?: emptyList() },
                enabled = f(true) { o.optBoolean("enabled", true) },
                lastTarget = f(100) { o.optInt("lastTarget", 100) },
                lastKeepWarm = f(0) { o.optInt("lastKeepWarm", 0) },
                presets = f(DEFAULT_PRESETS) { decodePresets(o) },
                presetOrderMigrated = true,
            )
        }

        private fun decodePresets(o: JSONObject): List<Preset> {
            val a = o.optJSONArray("presets") ?: return DEFAULT_PRESETS
            val stored = (0 until a.length()).mapNotNull { j ->
                runCatching {
                    val po = a.getJSONObject(j)
                    Preset(
                        PresetLogic.migrateName(po.optString("name")),
                        po.optInt("temp", DEFAULT_PRESETS.getOrElse(j) { DEFAULT_PRESETS[0] }.temp),
                        po.optInt("keepWarm", 0),
                        // In older data the icon was fixed by slot: keep each preset's old icon.
                        po.optString("icon").ifBlank { PresetLogic.OLD_SLOT_ICONS.getOrElse(j) { "drop" } },
                    )
                }.getOrNull()
            }
            // One-time migration (the flag is saved with the kettle, so it never runs again).
            val ordered = if (o.optBoolean("presetOrderMigrated", false)) stored else PresetLogic.coffeeFirst(stored)
            return PresetLogic.normalize(ordered)
        }

        fun encode(list: List<KettleConfig>): String = JSONArray().apply {
            list.forEach { k ->
                put(JSONObject().apply {
                    put("id", k.id); put("name", k.name); put("ip", k.ip); put("port", k.port)
                    put("imei", k.imei); put("mac", k.mac); put("wifiSsid", k.wifiSsid); put("device", k.device); put("wifiPass", k.wifiPass); put("autoJoinWifi", k.autoJoinWifi); put("cupMl", k.cupMl); put("addressLog", JSONArray(k.addressLog))
                    put("defaultBoilPoint", k.defaultBoilPoint)
                    k.boilPointOverride?.let { put("boilPointOverride", it) }
                    put("boilHistory", JSONArray(k.boilHistory))
                    put("enabled", k.enabled); put("lastTarget", k.lastTarget); put("lastKeepWarm", k.lastKeepWarm)
                    put("presetOrderMigrated", k.presetOrderMigrated)
                    put("presets", JSONArray().apply {
                        PresetLogic.normalize(k.presets).forEach { p -> put(JSONObject().apply { put("name", p.name); put("temp", p.temp); put("keepWarm", p.keepWarm); put("icon", p.icon) }) }
                    })
                })
            }
        }.toString()
    }
}
