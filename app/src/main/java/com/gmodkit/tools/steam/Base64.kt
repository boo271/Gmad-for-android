package com.gmodkit.tools.steam

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
    ALPHABET.forEachIndexed { value, symbol -> table[symbol.code] = value }
}

/**
 * Standard-alphabet Base64 decoding.
 *
 * java.util.Base64 is Android API 26, and the Steam API encodes depot manifest
 * filenames and the universe key with it, so the app carries its own decoder.
 */
internal fun decodeBase64(text: String): ByteArray {
    val out = ByteArray(text.length / 4 * 3 + 3)
    var written = 0
    var buffer = 0
    var bits = 0
    for (symbol in text) {
        val code = if (symbol.code < DECODE_TABLE.size) DECODE_TABLE[symbol.code] else -1
        if (code < 0) continue
        buffer = buffer shl 6 or code
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[written++] = (buffer shr bits and 0xFF).toByte()
        }
    }
    return out.copyOf(written)
}
