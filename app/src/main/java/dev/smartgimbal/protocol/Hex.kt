package dev.smartgimbal.protocol

private val SEPARATORS = Regex("[\\s:,-]")
private val HEX_PAIRS = Regex("([0-9a-fA-F]{2})*")

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/** Parses hex like `A5 5A 03`, `a55a03` or `A5:5A:03`. */
fun parseHex(input: String): ByteArray {
    val clean = input.replace(SEPARATORS, "")
    require(clean.matches(HEX_PAIRS)) { "Ungültiger Hex-String: $input" }
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
