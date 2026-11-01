package io.github.narthexfire.kettle.data

/**
 * The Settings editor keeps only what the user changed (null = untouched). Saving applies
 * these to the CURRENT stored kettle, so a value that changed meanwhile (e.g. an IP found by the
 * automatic search) is never overwritten by a stale copy.
 */
data class KettleEdits(
    val name: String? = null, val device: String? = null, val ip: String? = null, val port: Int? = null,
    val imei: String? = null, val mac: String? = null, val wifiSsid: String? = null, val wifiPass: String? = null,
    val autoJoinWifi: Boolean? = null, val cupMl: Int? = null, val enabled: Boolean? = null,
    val presets: List<Preset>? = null,
) {
    fun applyTo(k: KettleConfig): KettleConfig = k.copy(
        name = name?.trim()?.ifBlank { null } ?: k.name,
        device = device?.trim()?.ifBlank { null } ?: k.device,
        ip = ip?.trim() ?: k.ip,
        port = port ?: k.port,
        imei = imei?.trim() ?: k.imei,
        mac = mac?.trim() ?: k.mac,
        wifiSsid = wifiSsid?.trim() ?: k.wifiSsid,
        wifiPass = wifiPass ?: k.wifiPass,
        autoJoinWifi = autoJoinWifi ?: k.autoJoinWifi,
        cupMl = cupMl?.coerceIn(Cups.RANGE) ?: k.cupMl,
        enabled = enabled ?: k.enabled,
        presets = presets?.let { PresetLogic.normalize(it) } ?: k.presets,
    )

    companion object {
        private const val F = '\u001F'
        private const val R = '\u001E'

        /** Presets as one string for rememberSaveable (survives rotation and process death). */
        fun presetsToState(list: List<Preset>): String =
            list.joinToString(R.toString()) { listOf(it.name, it.temp, it.keepWarm, it.icon).joinToString(F.toString()) }

        fun presetsFromState(s: String): List<Preset> = s.split(R).mapNotNull { row ->
            val c = row.split(F)
            if (c.size < 4) null else Preset(c[0], c[1].toIntOrNull() ?: return@mapNotNull null, c[2].toIntOrNull() ?: 0, c[3])
        }
    }
}
