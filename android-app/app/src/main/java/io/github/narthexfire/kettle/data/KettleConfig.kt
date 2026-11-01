package io.github.narthexfire.kettle.data

/** One kettle as configured in Settings. Persisted as JSON in Preferences DataStore. */
data class KettleConfig(
    val id: String,
    val name: String,
    val ip: String = "",
    val port: Int = 6002,
    val imei: String = "",
    val mac: String = "",
    val wifiSsid: String = "",
    /** Passphrase of the kettle's own Wi-Fi (a low-value local network password). Never logged. */
    val wifiPass: String = "",
    /** Join the kettle's Wi-Fi (local-only) automatically when the kettle isn't reachable otherwise. */
    val autoJoinWifi: Boolean = false,
    /** Cup size for the "cups" display (AppKettle: 250 ml = 1 cup). */
    val cupMl: Int = Cups.DEFAULT_ML,
    /** "time · old → new (automatic)" lines, last 5. */
    val addressLog: List<String> = emptyList(),
    val device: String = DeviceFacts.DEFAULT_DEVICE,
    /** Kept in storage for compatibility; no longer used (the boil-point limit was removed). */
    val defaultBoilPoint: Int = 100,
    /** Kept in storage for compatibility; no longer used (the override was removed). */
    val boilPointOverride: Int? = null,
    val boilHistory: List<Int> = emptyList(),
    val enabled: Boolean = true,
    val lastTarget: Int = 100,
    val lastKeepWarm: Int = 0,
    val presets: List<Preset> = DEFAULT_PRESETS,
    /** One-time "Coffee first" migration done (true for new installs). */
    val presetOrderMigrated: Boolean = true,
) {
    val isSetUp: Boolean get() = ip.isNotBlank() && imei.isNotBlank() && port in 1..65535
    val active: Boolean get() = enabled && isSetUp

    /** The boil temperature the kettle itself reported (median of the last 5 real boils), or null. */
    val measuredBoilPoint: Int? get() = BoilPointLearner.median(boilHistory)

    /** Top of the color ramp and of the heating-progress sweep: the measured boil point, else 100. */
    val boilPoint: Int get() = measuredBoilPoint ?: 100

    companion object {
        /**
         * First run: one kettle that is not set up yet. The user opens Settings, taps the kettle and
         * either uses "Search for kettle" (finds any kettle on the phone's Wi-Fi and fills in its
         * address and Kettle ID) or types the IP address and uses "Test connection".
         */
        val DEFAULTS = listOf(KettleConfig(id = "kettle1", name = "My kettle"))

        /** The next free id ("kettle2", "kettle3", ...) for "Add another kettle". */
        fun newId(existing: Collection<String>): String =
            generateSequence(1) { it + 1 }.map { "kettle$it" }.first { it !in existing }
    }
}

/** Facts about the hardware, from the maker's published spec sheet and user manual. */
object DeviceFacts {
    const val DEFAULT_DEVICE = "AppKettle Wi-Fi smart kettle"
    const val MANUFACTURER = "AppKettle Ltd (UK, dissolved 2023)"
    // The manuals and spec sheet give no model number (AK000001 is only on the old website).
    const val MODEL = "model not confirmed"
    const val CAPACITY = "1.7 L (1,700 ml); minimum 250 ml"
    const val POWER = "2–2.4 kW, 220–240 V"
    const val WIFI = "2.4 GHz only (802.11 b/g/n)"
    const val FIRMWARE = "unknown"
    const val CAPACITY_ML = 1700
    const val MIN_ML = 250
}

val KEEP_WARM_OPTIONS = listOf(0, 5, 10, 15, 20, 30)

/** A user-editable preset. The icon travels with the preset, so presets can be reordered. */
data class Preset(val name: String, val temp: Int, val keepWarm: Int = 0, val icon: String = "")

/** Defaults, Coffee first. There is no "Boil" preset: the main Boil button does that. */
val DEFAULT_PRESETS = listOf(
    Preset("Coffee", 88, icon = "cup"),
    Preset("Green Tea", 75, icon = "leaf"),
    Preset("White Tea", 80, icon = "flower"),
    Preset("Oolong", 85, icon = "pot"),
    Preset("Hot Chocolate", 70, icon = "mug"),
    Preset("Warm", 60, icon = "flame"),
)

object PresetLogic {
    const val COUNT = 6
    /** Icons by slot  (when the icon was fixed by position); used to migrate old data. */
    val OLD_SLOT_ICONS = listOf("leaf", "flower", "pot", "cup", "mug", "flame")
    const val MAX_NAME = 24

    /** Stored names that still exactly match the old lower-case defaults become Title Case. */
    val OLD_DEFAULT_NAMES = mapOf("Green tea" to "Green Tea", "White tea" to "White Tea", "Hot chocolate" to "Hot Chocolate")

    fun migrateName(name: String): String = OLD_DEFAULT_NAMES[name] ?: name

    /** Presets range from 40 to 100 C (no boil-point limit). */
    @Suppress("UNUSED_PARAMETER")
    fun clamp(t: Int, boilPoint: Int = TempLogic.BOIL): Int = t.coerceIn(TempLogic.MIN, TempLogic.BOIL)

    fun plus(t: Int, boilPoint: Int = TempLogic.BOIL) = clamp(t + 1, boilPoint)
    fun minus(t: Int, boilPoint: Int = TempLogic.BOIL) = clamp(t - 1, boilPoint)

    /** Always exactly 6 presets (older 4-slot data gains the 2 new defaults); names trimmed and capped. */
    fun normalize(list: List<Preset>): List<Preset> = List(COUNT) { i ->
        val p = list.getOrNull(i) ?: DEFAULT_PRESETS[i]
        Preset(
            p.name.trim().take(MAX_NAME).ifBlank { DEFAULT_PRESETS[i].name }, p.temp.coerceIn(TempLogic.MIN, TempLogic.BOIL),
            // Keep warm is only valid in the list, and only for presets at 60 C or above.
            (p.keepWarm.takeIf { it in KEEP_WARM_OPTIONS } ?: 0).let { TempLogic.effectiveKeepWarm(p.temp, it) },
            p.icon.ifBlank { DEFAULT_PRESETS[i].icon },
        )
    }

    /** One-time migration: move the preset named "Coffee" (any case) to the first slot. */
    fun coffeeFirst(list: List<Preset>): List<Preset> {
        val i = list.indexOfFirst { it.name.trim().equals("Coffee", ignoreCase = true) }
        if (i <= 0) return list
        return listOf(list[i]) + list.filterIndexed { j, _ -> j != i }
    }


    /**
     * Which preset tile is highlighted, derived from the current choice (never stored by slot,
     * so it can't go stale after an edit): the first preset whose temperature AND keep-warm match.
     */
    fun matching(presets: List<Preset>, target: Int, keepWarm: Int): Int? =
        presets.indexOfFirst { clamp(it.temp) == target && it.keepWarm == keepWarm }.takeIf { it >= 0 }

    /** Index of the preset whose temperature is exactly [t], else null. */
    fun indexFor(t: Int, presets: List<Preset>, boilPoint: Int): Int? =
        presets.indexOfFirst { clamp(it.temp, boilPoint) == t }.takeIf { it >= 0 }

    fun tempLabel(p: Preset, boilPoint: Int): String =
        if (TempLogic.isBoil(clamp(p.temp, boilPoint), boilPoint)) "Boil" else "${clamp(p.temp, boilPoint)}°"

    /** Tile line: "85°", "85° · warm 10′" or "Boil · warm 5′". */
    fun label(p: Preset, boilPoint: Int): String =
        tempLabel(p, boilPoint) + if (p.keepWarm > 0) " · warm ${p.keepWarm}′" else ""

    /** "Save as preset" from the sheet: overwrite one slot with the current temperature and keep-warm. */
    fun saveTo(presets: List<Preset>, slot: Int, name: String, target: Int, keepWarm: Int, boilPoint: Int): List<Preset> {
        val list = normalize(presets).toMutableList()
        list[slot] = Preset(name.trim().ifBlank { list[slot].name }, clamp(target, boilPoint), keepWarm, list[slot].icon)
        return normalize(list)
    }
}
