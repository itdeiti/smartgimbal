package dev.smartgimbal.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTest {

    @Test
    fun `joystick frame decodes with 5 byte payload`() {
        val frame = Frame.decode(FeiyuProtocol.joystick(100, -100))
        assertNotNull(frame)
        assertEquals(0x0300, frame!!.route)
        assertEquals(0x0E, frame.command)
        assertEquals("00 64 00 9C FF", frame.payload.toHex())
    }

    @Test
    fun `home frame decodes with 1 byte payload`() {
        val frame = Frame.decode(FeiyuProtocol.home())!!
        assertEquals(0x0302, frame.route)
        assertEquals(0x33, frame.command)
        assertEquals(0x2000, frame.flags)
        assertEquals("02", frame.payload.toHex())
    }

    @Test
    fun `captured telemetry frame decodes`() {
        // Captured from FY_PocketV_3E on 2026-11-22.
        val bytes = parseHex("A5 5A 00 FF 1E 00 00 08 00 01 9D F0 05 00 C7 0A 4F 6D 12")
        val frame = Frame.decode(bytes)!!
        assertEquals(0x00FF, frame.route)
        assertEquals(Telemetry.COMMAND, frame.command)

        assertEquals(Telemetry(status = 1, a = -3939, b = 5, c = 2759, d = 79), Telemetry.parse(bytes))
    }

    @Test
    fun `telemetry with negative fields`() {
        val bytes = parseHex("A5 5A 00 FF 1E 00 00 08 00 01 F8 FF FB FF 7B D2 50 40 52")
        assertEquals(Telemetry(status = 1, a = -8, b = -5, c = -11653, d = 80), Telemetry.parse(bytes))
    }

    @Test
    fun `captured button frames decode to the kind of press`() {
        // Captured from FY_PocketV_3E on 2026-11-22: thumb button short, long, double.
        fun press(hex: String) = ButtonPress.fromFrame(Frame.decode(parseHex(hex))!!)
        assertEquals(ButtonPress.SHORT, press("A5 5A 04 03 50 00 00 03 00 00 00 00 C7 A5"))
        assertEquals(ButtonPress.LONG, press("A5 5A 04 03 50 00 00 03 00 01 00 00 F7 92"))
        assertEquals(ButtonPress.DOUBLE, press("A5 5A 04 03 50 00 00 03 00 02 00 00 A7 CB"))
        assertNull(ButtonPress.fromFrame(Frame.decode(FeiyuProtocol.home())!!))
    }

    @Test
    fun `telemetry carries the battery level`() {
        val bytes = parseHex("A5 5A 00 FF 1E 00 00 08 00 01 9D F0 05 00 C7 0A 4F 6D 12")
        assertEquals(79, Telemetry.parse(bytes)!!.batteryPercent)
    }

    @Test
    fun `status 2 means the trigger is held`() {
        val idle = Telemetry(status = 1, a = 0, b = 0, c = 0, d = 75)
        assertEquals(false, idle.triggerHeld)
        assertEquals(false, idle.copy(status = 0).triggerHeld)
        assertEquals(true, idle.copy(status = 2).triggerHeld)
    }

    @Test
    fun `non telemetry and broken frames are rejected`() {
        assertNull(Telemetry.parse(FeiyuProtocol.home()))
        // Length field says 9 but payload has 8 bytes.
        assertNull(Frame.decode(FeiyuProtocol.frame(parseHex("00 FF 1E 00 00 09 00 01 00 00 00 00 00 00 00"))))
        assertNull(Frame.decode(parseHex("A5 5A 00")))
    }
}
