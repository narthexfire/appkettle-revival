package io.github.narthexfire.kettle.protocol

/**
 * Wire format (TCP 6002, ASCII):
 *   ##<4-hex length><json>&&
 *   json   = {"app_cmd":"62","imei":"...","SubDev":"","data2":"<across hex>"}
 *   across = AA + stuff(len(2) + prefix(2) + unique(6) + serial(1) + api(1) + retain(2) + content) + stuff(sign)
 * Replies carry "data3" instead of "data2".
 */
object Frames {
    const val KEEP_ALIVE = "##000bKeepConnect&&"
    const val TERMINATOR = "&&"
    const val PREFIX_OPERATIONAL = "0000"
    private const val MSG_UNIQUE = "000000000000"
    private const val RETAIN = "0000"

    fun buildAcross(api: Int, serial: Int, content: String = "", prefix: String = PREFIX_OPERATIONAL): String {
        val msg = prefix + MSG_UNIQUE + Hex.u8(serial) + Hex.u8(api) + RETAIN + content.uppercase()
        val len = Hex.u16(msg.length / 2 + 1)
        val stuffed = Hex.stuff(len + msg)
        return ("AA" + stuffed + Hex.stuff(Hex.sign(stuffed))).uppercase()
    }

    fun wrapJson(imei: String, data2: String): String =
        "{\"app_cmd\":\"62\",\"imei\":\"" + escape(imei) + "\",\"SubDev\":\"\",\"data2\":\"" + data2 + "\"}"

    fun wire(json: String): String = "##" + Hex.u16(json.length) + json + TERMINATOR

    fun buildWire(imei: String, api: Int, serial: Int, content: String = ""): String =
        wire(wrapJson(imei, buildAcross(api, serial, content)))

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    val DATA2 = Regex("\"data2\":\"([0-9a-fA-F]+)\"")
    private val DATA3 = Regex("\"data3\":\"([0-9a-fA-F]+)\"")
    fun extractData3(wire: String): String? = DATA3.find(wire)?.groupValues?.get(1)

    fun parseAcross(data3: String): AcrossReply? {
        val u = Hex.unstuff(data3.uppercase())
        if (u.length < 32 || !u.startsWith("AA")) return null
        return try {
            val body = u.substring(2, u.length - 2)
            AcrossReply(
                frameLen = u.substring(2, 6).toInt(16),
                prefix = u.substring(6, 10),
                serial = u.substring(22, 24).toInt(16),
                api = u.substring(24, 26).toInt(16),
                content = u.substring(30, u.length - 2),
                sign = u.substring(u.length - 2),
                checksumOk = Hex.sign(Hex.stuff(body)) == u.substring(u.length - 2),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Parse one complete wire frame (up to and including &&). Null for keep-alive echoes and junk. */
    fun parseWire(wire: String): AcrossReply? = extractData3(wire)?.let { parseAcross(it) }
}

data class AcrossReply(
    val frameLen: Int,
    val prefix: String,
    val serial: Int,
    val api: Int,
    val content: String,
    val sign: String,
    val checksumOk: Boolean,
) {
    /** First content byte C8 means success for command replies. */
    val isAck: Boolean get() = content.startsWith("C8", ignoreCase = true)
}

/** Splits an incoming ASCII byte stream into frames ending with "&&". */
class FrameSplitter(private val maxBuffer: Int = 64 * 1024) {
    private val buf = StringBuilder()
    fun feed(chunk: String): List<String> {
        buf.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val i = buf.indexOf(Frames.TERMINATOR)
            if (i < 0) break
            out += buf.substring(0, i + 2)
            buf.delete(0, i + 2)
        }
        if (buf.length > maxBuffer) buf.setLength(0)
        return out
    }
}
