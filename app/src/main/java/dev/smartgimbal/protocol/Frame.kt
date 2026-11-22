package dev.smartgimbal.protocol

/**
 * Decoded frame. Layout inferred from the documented commands and from captured traffic:
 *
 * ```
 * A5 5A | route (2) | command (1) | flags (2) | length (2, LE) | payload (length) | CRC (2, LE)
 * ```
 *
 * Seen routes: `03 00` joystick and `03 02` request (phone to gimbal), `02 03` reply and
 * `00 FF` broadcast (gimbal to phone). `flags` is not understood yet (`00 00`, `20 00`, `60 00`).
 */
class Frame(
    val route: Int,
    val command: Int,
    val flags: Int,
    val payload: ByteArray,
) {
    /** Human-readable name; a trailing `?` marks a guess. */
    val name: String
        get() = when {
            command == CMD_JOYSTICK -> "Joystick"
            command == Telemetry.COMMAND -> "Telemetrie"
            command == CMD_HOME && route == ROUTE_REPLY -> "Home-ACK"
            command == CMD_HOME -> "Home"
            command == CMD_MOTION && payload.size == 1 && payload[0].toInt() == 1 -> "Fahrt läuft?"
            command == CMD_MOTION && payload.size == 1 && payload[0].toInt() == 0 -> "Fahrt fertig?"
            else -> "unbekannt"
        }

    override fun toString(): String =
        "%s · cmd=%02X route=%04X flags=%04X payload=[%s]".format(name, command, route, flags, payload.toHex())

    companion object {
        const val HEADER_SIZE = 9
        const val CRC_SIZE = 2

        const val CMD_JOYSTICK = 0x0E
        const val CMD_HOME = 0x33
        /** Sent by the gimbal around an automatic move such as Home: payload 01, later 00. */
        const val CMD_MOTION = 0x61
        const val ROUTE_REPLY = 0x0203

        /** Returns null for frames with bad header, CRC or length field. */
        fun decode(bytes: ByteArray): Frame? {
            if (bytes.size < HEADER_SIZE + CRC_SIZE || !FeiyuProtocol.isValidFrame(bytes)) return null
            val length = lengthField(bytes)
            if (HEADER_SIZE + length + CRC_SIZE != bytes.size) return null
            return Frame(
                route = (u8(bytes, 2) shl 8) or u8(bytes, 3),
                command = u8(bytes, 4),
                flags = (u8(bytes, 5) shl 8) or u8(bytes, 6),
                payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + length),
            )
        }

        /** Payload length from bytes 7..8; the caller must make sure at least 9 bytes exist. */
        internal fun lengthField(bytes: ByteArray): Int = u8(bytes, 7) or (u8(bytes, 8) shl 8)

        private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF
    }
}

/**
 * Status broadcast by the gimbal about 2.4 times per second (command 0x1E, 8 byte payload):
 * `status | A int16 | B int16 | C int16 | D u8`.
 *
 * Confirmed 2026-11-22: A = tilt, B = roll, C = pan, all in 0.01°. D stays at 79/80
 * (battery or temperature?). Tilt stops at about +27° / -32° in raw A terms.
 */
data class Telemetry(
    val status: Int,
    val a: Int,
    val b: Int,
    val c: Int,
    val d: Int,
) {
    /** Pan in 0.01°; grows while turning right in [FeiyuProtocol.move] terms. */
    val pan: Int get() = c

    /** Tilt in 0.01°; grows while tilting up in [FeiyuProtocol.move] terms. */
    val tilt: Int get() = -a

    /** Roll in 0.01°; stays near 0 because the gimbal levels it. */
    val roll: Int get() = b

    companion object {
        const val COMMAND = 0x1E
        private const val PAYLOAD_SIZE = 8

        fun parse(bytes: ByteArray): Telemetry? {
            val frame = Frame.decode(bytes) ?: return null
            return fromFrame(frame)
        }

        fun fromFrame(frame: Frame): Telemetry? {
            if (frame.command != COMMAND || frame.payload.size != PAYLOAD_SIZE) return null
            val p = frame.payload
            return Telemetry(
                status = p[0].toInt() and 0xFF,
                a = int16Le(p, 1),
                b = int16Le(p, 3),
                c = int16Le(p, 5),
                d = p[7].toInt() and 0xFF,
            )
        }

        private fun int16Le(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}
