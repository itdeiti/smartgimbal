package dev.smartgimbal.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeiyuProtocolTest {

    @Test
    fun `crc matches documented joystick example`() {
        assertEquals(0x45CC, FeiyuProtocol.crc16Xmodem(parseHex("03 00 0E 00 00 05 00 00 00 00 00 00")))
    }

    @Test
    fun `zero joystick packet matches documentation`() {
        assertArrayEquals(
            parseHex("A5 5A 03 00 0E 00 00 05 00 00 00 00 00 00 CC 45"),
            FeiyuProtocol.joystick(0, 0),
        )
    }

    @Test
    fun `home packet matches documentation`() {
        assertArrayEquals(parseHex("A5 5A 03 02 33 20 00 01 00 02 3C 56"), FeiyuProtocol.home())
    }

    @Test
    fun `joystick values are signed little endian`() {
        val positive = FeiyuProtocol.joystick(100, 0)
        assertEquals("64 00 00 00", positive.copyOfRange(10, 14).toHex())

        val negative = FeiyuProtocol.joystick(-100, 300)
        assertEquals("9C FF 2C 01", negative.copyOfRange(10, 14).toHex())

        assertTrue(FeiyuProtocol.isValidFrame(positive))
        assertTrue(FeiyuProtocol.isValidFrame(negative))
    }

    @Test
    fun `joystick values are clamped to int16`() {
        val packet = FeiyuProtocol.joystick(100_000, -100_000)
        assertEquals("FF 7F 00 80", packet.copyOfRange(10, 14).toHex())
    }

    @Test
    fun `withCrc appends crc to a raw frame`() {
        assertArrayEquals(
            FeiyuProtocol.home(),
            FeiyuProtocol.withCrc(parseHex("A5 5A 03 02 33 20 00 01 00 02")),
        )
    }

    @Test
    fun `corrupted frame is invalid`() {
        val packet = FeiyuProtocol.home()
        packet[5] = 0x21
        assertFalse(FeiyuProtocol.isValidFrame(packet))
        assertFalse(FeiyuProtocol.isValidFrame(parseHex("A5")))
    }

    @Test
    fun `parseHex accepts common separators`() {
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A, 0x03), parseHex("a5:5A-03"))
        assertEquals("A5 5A 03", parseHex("A55A03").toHex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseHex rejects odd length`() {
        parseHex("A5 5")
    }
}
