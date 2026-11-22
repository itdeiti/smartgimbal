package dev.smartgimbal.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chunks are real notifications captured from FY_PocketV_3E on 2026-11-22. */
class FrameAssemblerTest {

    private fun FrameAssembler.feedHex(vararg chunks: String): List<FrameAssembler.Piece> =
        chunks.flatMap { feed(parseHex(it)) }

    private fun List<FrameAssembler.Piece>.names() = map {
        when (it) {
            is FrameAssembler.Piece.Valid -> Frame.decode(it.bytes)!!.name
            is FrameAssembler.Piece.Garbage -> "garbage:" + it.bytes.toHex()
        }
    }

    @Test
    fun `home reply split over two notifications`() {
        val pieces = FrameAssembler().feedHex(
            "A5 5A 02 03 33 60 00 01 00 02 E6 B8 A5 5A 02 03 61 00 00 01",
            "00 01 8D 6A",
        )
        assertEquals(listOf("Home-ACK", "Fahrt läuft?"), pieces.names())
    }

    @Test
    fun `three notifications with telemetry glued to the end`() {
        val assembler = FrameAssembler()
        val pieces = assembler.feedHex(
            "A5 5A 02 03 33 60 00 01 00 02 E6 B8 A5 5A 02 03 61 00 00 01",
            "00 01 8D 6A A5 5A 00 FF 1E 00 00 08 00 01 4F FF 0B 00 A5 EB",
            "4E E2 FF",
        )
        assertEquals(listOf("Home-ACK", "Fahrt läuft?", "Telemetrie"), pieces.names())
        // The payload contains A5 (part of C) without being a header.
        assertEquals(
            Telemetry(status = 1, a = -177, b = 11, c = -5211, d = 0x4E),
            Telemetry.parse(pieces.last().bytes),
        )
        assertEquals(0, assembler.pendingBytes)
    }

    @Test
    fun `telemetry split after the length field`() {
        val pieces = FrameAssembler().feedHex(
            "A5 5A 02 03 61 00 00 01 00 00 AC 7A A5 5A 00 FF 1E 00 00 08",
            "00 01 8C FF 1C 00 E3 0B 4E 44 8D",
        )
        assertEquals(listOf("Fahrt fertig?", "Telemetrie"), pieces.names())
    }

    @Test
    fun `byte by byte feeding yields one frame`() {
        val assembler = FrameAssembler()
        val pieces = FeiyuProtocol.home().flatMap { assembler.feed(byteArrayOf(it)) }
        assertEquals(listOf("Home"), pieces.names())
    }

    @Test
    fun `garbage and corrupted frames are skipped`() {
        val corrupted = FeiyuProtocol.home().also { it[9] = 0x07 }
        val assembler = FrameAssembler()
        val pieces = assembler.feed(parseHex("12 34") + corrupted + FeiyuProtocol.home())
        val names = pieces.names()
        assertTrue(names.first().startsWith("garbage:12 34 A5 5A"))
        assertEquals("Home", names.last())
        assertEquals(2, names.size)
    }

    @Test
    fun `operator move inverts both raw axes`() {
        assertEquals(FeiyuProtocol.joystick(-200, 0).toHex(), FeiyuProtocol.move(200, 0).toHex())
        assertEquals(FeiyuProtocol.joystick(0, -51).toHex(), FeiyuProtocol.move(0, 51).toHex())
    }

    @Test
    fun `telemetry pan and tilt follow operator directions`() {
        // Raw: pressing the old ▲ (+y) made A grow, so tilting up in move() terms makes A shrink.
        val t = Telemetry(status = 1, a = -2500, b = 5, c = 1200, d = 79)
        assertEquals(2500, t.tilt)
        assertEquals(1200, t.pan)
        assertEquals(5, t.roll)
    }
}
