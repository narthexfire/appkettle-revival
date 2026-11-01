package io.github.narthexfire.kettle.protocol

/** Command codes. Only the ones this app uses are listed. */
object Api {
    const val GET_STATUS = 0x36
    const val SET_START_BOIL = 0x39
    const val SET_STOP_BOIL = 0x3A
    const val SET_KETTLE_ON = 0x41
    /** Keep warm: minutes as one byte, 0 = off. */
    const val SET_KEEP_WARM = 0x4A
    /** Unsolicited status push the kettle sends on connect. Same layout as 0x36. */
    const val STATUS_PUSH = 0xA4
}

/**
 * Builders for the only commands the app sends: WAKE, ON (start boil), OFF (stop) and,
 * 0x4A keep warm. Never 0x47 or 0x35.
 * Wi-Fi commands (0x3D / 0x3E) are deliberately absent.
 */
object Commands {
    fun wake(imei: String, serial: Int) = Frames.buildWire(imei, Api.SET_KETTLE_ON, serial)

    /** content = boilType, target C, keep-warm minutes, brew, delay. Target 100 = boil (kettle detects the real boil). */
    fun startBoil(imei: String, serial: Int, targetC: Int, keepWarmMin: Int): String {
        require(targetC in 40..100) { "target $targetC out of range" }
        require(keepWarmMin in 0..60) { "keep warm $keepWarmMin out of range" }
        val content = Hex.u8(0) + Hex.u8(targetC) + Hex.u8(keepWarmMin) + Hex.u8(0) + Hex.u8(0)
        return Frames.buildWire(imei, Api.SET_START_BOIL, serial, content)
    }

    fun stop(imei: String, serial: Int) = Frames.buildWire(imei, Api.SET_STOP_BOIL, serial)

    /**
     * 0x4A setKeepWarm, standalone (separate from ON). Content = minutes as one byte (0 = off).
     * Sent with no WAKE first, and only when the water is at least 60 C (behavior observed in the
     * original app and confirmed on a kettle).
     */
    fun keepWarm(imei: String, serial: Int, minutes: Int): String {
        require(minutes in 0..60) { "keep warm $minutes out of range" }
        return Frames.buildWire(imei, Api.SET_KEEP_WARM, serial, Hex.u8(minutes))
    }
}
