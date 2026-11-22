package dev.smartgimbal.protocol

import java.util.UUID

/**
 * Packet encoder for the FeiyuTech VLOG pocket BLE protocol.
 *
 * Source: https://github.com/Shalunizm/FeiyuTech-Pocket-Vlog-Protocol
 *
 * Frame layout: `A5 5A | body... | CRC16 (LE)`. The CRC is CRC-16/XMODEM over the body,
 * i.e. everything after the two header bytes.
 */
object FeiyuProtocol {
    /** Nordic UART Service characteristics used by the gimbal. */
    val TX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val RX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** Client Characteristic Configuration Descriptor, needed to enable notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertised name looks like `FY_PocketV_BA`. */
    const val DEVICE_NAME_PREFIX = "FY_PocketV"

    internal const val HEADER_0 = 0xA5.toByte()
    internal const val HEADER_1 = 0x5A.toByte()
    private val HEADER = byteArrayOf(HEADER_0, HEADER_1)

    /** Body of command 0x0E (joystick) without the X/Y values. Bytes 5..9 are not understood yet. */
    private val JOYSTICK_PREFIX = parseHex("03 00 0E 00 00 05 00 00")

    /** Body of command 0x33 (return to initial position). */
    private val HOME_BODY = parseHex("03 02 33 20 00 01 00 02")

    /**
     * Joystick command (0x0E). Behaves like a speed input: X = pan, Y = tilt.
     * Values are clamped to signed 16 bit; the useful range is not known yet.
     */
    fun joystick(x: Int, y: Int): ByteArray =
        frame(JOYSTICK_PREFIX + int16Le(x) + int16Le(y))

    /**
     * Joystick in operator terms: looking at the gimbal from the side with the clamp and the
     * buttons, positive [panRight] turns it to the right and positive [tiltUp] tilts it up.
     *
     * Checked on FY_PocketV_3E (2026-11-22): both raw joystick axes run the other way.
     * Speed is roughly linear at low values (about 0.22°/s per unit, so 200 ≈ 45°/s) and
     * levels off near 60°/s at 1000, where the gimbal also keeps moving briefly after a stop frame.
     */
    fun move(panRight: Int, tiltUp: Int): ByteArray = joystick(-panRight, -tiltUp)

    /** Return to initial position (0x33). */
    fun home(): ByteArray = frame(HOME_BODY)

    /** Wraps a body with header and CRC. */
    fun frame(body: ByteArray): ByteArray = HEADER + body + crcLe(body)

    /** Appends the CRC to a frame that already starts with `A5 5A`. */
    fun withCrc(frameWithoutCrc: ByteArray): ByteArray {
        require(frameWithoutCrc.size > HEADER.size && frameWithoutCrc.startsWithHeader()) {
            "Frame muss mit A5 5A beginnen"
        }
        return frameWithoutCrc + crcLe(frameWithoutCrc.copyOfRange(HEADER.size, frameWithoutCrc.size))
    }

    /** True when the frame has the `A5 5A` header and a matching CRC. */
    fun isValidFrame(frame: ByteArray): Boolean {
        if (frame.size < HEADER.size + 2 || !frame.startsWithHeader()) return false
        val body = frame.copyOfRange(HEADER.size, frame.size - 2)
        val expected = crcLe(body)
        return frame[frame.size - 2] == expected[0] && frame[frame.size - 1] == expected[1]
    }

    fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0x0000
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    private fun crcLe(body: ByteArray): ByteArray {
        val crc = crc16Xmodem(body)
        return byteArrayOf((crc and 0xFF).toByte(), (crc shr 8 and 0xFF).toByte())
    }

    private fun int16Le(value: Int): ByteArray {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
    }

    private fun ByteArray.startsWithHeader() = this[0] == HEADER[0] && this[1] == HEADER[1]
}
