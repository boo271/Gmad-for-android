package com.gmodkit.tools.steam

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Recorded traffic from a real anonymous SteamCMD-style session, captured once
 * for item 160250458 so the protocol port can be tested without a network.
 */
internal object SteamFixtures {

    fun bytes(name: String): ByteArray {
        val stream = SteamFixtures::class.java.getResourceAsStream("/steam/$name")
            ?: throw IllegalStateException("Missing Steam fixture: steam/$name")
        return stream.use { it.readBytes() }
    }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

    fun json(name: String): JSONObject = JSONObject(text(name))

    fun depotKey(): ByteArray = bytes("depotkey-4000.bin")

    fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(digits[value shr 4])
                append(digits[value and 0x0F])
            }
        }
    }

    fun fromHex(text: String): ByteArray =
        ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
