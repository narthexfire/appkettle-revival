package io.github.narthexfire.kettle.protocol

enum class KettleState(val label: String) {
    OffBase("Off base"), Unknown1("Unknown"), Standby("Standby"), Ready("Ready"), Heating("Heating"),
    KeepWarm("Keep warm"), Unknown("Unknown");

    val isActive get() = this == Heating || this == KeepWarm

    companion object {
        fun fromByte(b: Int): KettleState = when (b) {
            0 -> OffBase; 1 -> Unknown1; 2 -> Standby; 3 -> Ready; 4 -> Heating; 5 -> KeepWarm
            else -> Unknown
        }
    }
}

data class KettleStatus(
    val state: KettleState,
    val temperatureC: Int,
    val targetC: Int,
    val waterMl: Int,
    val keepWarmSecondsLeft: Int,
    val rawContent: String,
)

object StatusParser {
    /**
     * Status content (API 0x36 and the 0xA4 push), 11 bytes:
     *   [0] lead byte (C8 on 0x36; a varying value such as 25 / 89 / B5 on 0xA4)
     *   [1] pad  [2] state  [3..4] keep-warm seconds left (big-endian)  [5] temp  [6] target
     *   [7..8] water ml (big-endian)  [9..10] pad
     * The lead byte is skipped whatever its value (the reference Python only stripped C8, so it
     * misread A4 pushes as "Not on base"). A 10-byte form without the lead byte is also accepted.
     */
    fun parse(content: String): KettleStatus? {
        val h = content.uppercase()
        if (h.length % 2 != 0) return null
        val off = when {
            h.length >= 22 -> 2
            h.length >= 16 -> 0
            else -> return null
        }
        fun byte(i: Int) = h.substring(off + i * 2, off + i * 2 + 2).toInt(16)
        return try {
            KettleStatus(
                state = KettleState.fromByte(byte(1)),
                keepWarmSecondsLeft = (byte(2) shl 8) or byte(3),
                temperatureC = byte(4),
                targetC = byte(5),
                waterMl = (byte(6) shl 8) or byte(7),
                rawContent = h,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun fromReply(r: AcrossReply): KettleStatus? =
        if (r.api == Api.GET_STATUS || r.api == Api.STATUS_PUSH) parse(r.content) else null
}
