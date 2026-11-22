package dev.smartgimbal.protocol

/**
 * Rebuilds frames from the RX notification stream.
 *
 * The gimbal does not align notifications with frames: one notification can hold the end of one
 * frame plus the start of the next, and a frame can be split over two or three notifications.
 * The assembler buffers bytes, looks for `A5 5A`, waits for the length field and the CRC, and
 * resyncs byte by byte after garbage. Not thread-safe; feed it from one thread.
 */
class FrameAssembler(private val maxPayload: Int = 256) {

    sealed interface Piece {
        val bytes: ByteArray

        /** Complete frame with a valid CRC. */
        class Valid(override val bytes: ByteArray) : Piece

        /** Bytes that could not be part of any valid frame. */
        class Garbage(override val bytes: ByteArray) : Piece
    }

    private var buffer = ByteArray(0)

    val pendingBytes: Int get() = buffer.size

    fun feed(chunk: ByteArray): List<Piece> {
        buffer += chunk
        val out = mutableListOf<Piece>()
        var garbageStart = -1
        var pos = 0

        fun flushGarbage(end: Int) {
            if (garbageStart >= 0 && end > garbageStart) out += Piece.Garbage(buffer.copyOfRange(garbageStart, end))
            garbageStart = -1
        }

        while (pos < buffer.size) {
            if (!isHeaderAt(pos)) {
                // A lone A5 at the very end may be the start of the next header.
                if (pos == buffer.size - 1 && buffer[pos] == FeiyuProtocol.HEADER_0) break
                if (garbageStart < 0) garbageStart = pos
                pos++
                continue
            }
            if (buffer.size - pos < Frame.HEADER_SIZE) break
            val length = Frame.lengthField(buffer.copyOfRange(pos, pos + Frame.HEADER_SIZE))
            if (length > maxPayload) {
                if (garbageStart < 0) garbageStart = pos
                pos++
                continue
            }
            val total = Frame.HEADER_SIZE + length + Frame.CRC_SIZE
            if (buffer.size - pos < total) break
            val candidate = buffer.copyOfRange(pos, pos + total)
            if (FeiyuProtocol.isValidFrame(candidate)) {
                flushGarbage(pos)
                out += Piece.Valid(candidate)
                pos += total
            } else {
                if (garbageStart < 0) garbageStart = pos
                pos++
            }
        }
        flushGarbage(pos)
        buffer = buffer.copyOfRange(pos, buffer.size)
        return out
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    private fun isHeaderAt(i: Int) =
        i + 1 < buffer.size && buffer[i] == FeiyuProtocol.HEADER_0 && buffer[i + 1] == FeiyuProtocol.HEADER_1
}
