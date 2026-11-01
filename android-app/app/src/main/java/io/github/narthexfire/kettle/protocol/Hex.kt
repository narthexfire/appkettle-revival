package io.github.narthexfire.kettle.protocol

/** Hex helpers for the AppKettle "Across" protocol. All strings are upper-case hex, 2 chars per byte. */
object Hex {
    fun u8(n: Int): String = String.format("%02X", n and 0xFF)
    fun u16(n: Int): String = String.format("%04X", n and 0xFFFF)

    /** Checksum: (sum of bytes XOR 0xFF) & 0xFF over the stuffed length + message. */
    fun sign(hex: String): String {
        var total = 0
        for (i in 0 until hex.length - 1 step 2) total += hex.substring(i, i + 2).toInt(16)
        return u8((total xor 0xFF) and 0xFF)
    }

    /** Byte stuffing: every 0xAA byte becomes AA55. */
    fun stuff(hex: String): String {
        val sb = StringBuilder(hex.length + 8)
        for (i in 0 until hex.length - 1 step 2) {
            val b = hex.substring(i, i + 2).uppercase()
            sb.append(if (b == "AA") "AA55" else b)
        }
        return sb.toString()
    }

    /** Reverse of [stuff]: drops a 55 that directly follows an AA. */
    fun unstuff(hex: String): String {
        val sb = StringBuilder(hex.length)
        var head = false
        for (i in 0 until hex.length - 1 step 2) {
            val b = hex.substring(i, i + 2).uppercase()
            if (b == "AA") {
                sb.append("AA"); head = true
            } else {
                if (!(head && b == "55")) sb.append(b)
                head = false
            }
        }
        return sb.toString()
    }
}
