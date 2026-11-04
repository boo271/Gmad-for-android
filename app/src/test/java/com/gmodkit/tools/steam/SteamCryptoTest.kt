package com.gmodkit.tools.steam

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.BadPaddingException

class SteamCryptoTest {

    @Test
    fun decryptsRecordedPayloads() {
        for (vector in vectors()) {
            val key = SteamFixtures.fromHex(vector.getString("key"))
            val cipher = SteamFixtures.fromHex(vector.getString("cipher"))
            val plain = SteamFixtures.fromHex(vector.getString("plain"))

            val decrypted = if (vector.getString("mode") == "aes-cbc-hmac") {
                SteamCrypto.decrypt(cipher, channelKeys(key, key.copyOfRange(0, 16)))
            } else {
                SteamCrypto.decryptPayload(cipher, key)
            }
            assertArrayEquals(vector.getString("name"), plain, decrypted)
        }
    }

    @Test
    fun roundTripsThroughTheSecuredChannel() {
        val sessionKey = ByteArray(32) { it.toByte() }
        for (secret in listOf(ByteArray(0), sessionKey.copyOfRange(0, 16))) {
            val message = ByteArray(1000) { (it % 251).toByte() }
            val cipher = SteamCrypto.encrypt(message, channelKeys(sessionKey, secret))
            assertArrayEquals(message, SteamCrypto.decrypt(cipher, channelKeys(sessionKey, secret)))
        }
    }

    @Test
    fun rejectsAChannelThatFailsItsHmac() {
        val sessionKey = ByteArray(32) { (it + 1).toByte() }
        val keys = channelKeys(sessionKey, sessionKey.copyOfRange(0, 16))
        val cipher = SteamCrypto.encrypt("ping".toByteArray(), keys)
        cipher[cipher.size - 1] = (cipher[cipher.size - 1] + 1).toByte()

        val failure = assertThrows(Exception::class.java) { SteamCrypto.decrypt(cipher, keys) }
        assertTrue(failure is BadPaddingException)
    }

    @Test
    fun buildsKeysThatFitTheWireFormat() {
        val withoutChallenge = SteamCrypto.createChannelKeys(ByteArray(0))
        assertEquals(32, withoutChallenge.sessionKey.size)
        assertEquals(128, withoutChallenge.encryptedSessionKey.size)
        assertEquals(0, withoutChallenge.hmacSecret.size)
        assertTrue(!withoutChallenge.usesHmac)

        val withChallenge = SteamCrypto.createChannelKeys(ByteArray(8))
        assertEquals(16, withChallenge.hmacSecret.size)
        assertArrayEquals(withChallenge.sessionKey.copyOfRange(0, 16), withChallenge.hmacSecret)
        assertTrue(withChallenge.usesHmac)
    }

    @Test
    fun decodesBase64TheSameWayAsThePlatform() {
        val random = java.util.Random(7)
        for (size in 0..64) {
            val data = ByteArray(size).also { random.nextBytes(it) }
            assertArrayEquals(data, decodeBase64(java.util.Base64.getEncoder().encodeToString(data)))
        }

        // A pasted key carries line breaks, which the platform MIME decoder skips.
        val data = ByteArray(48) { it.toByte() }
        val broken = java.util.Base64.getEncoder().encodeToString(data).chunked(11).joinToString("\n")
        assertArrayEquals(data, decodeBase64(broken))
    }

    @Test
    fun hashesMatchKnownValues() {
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            SteamFixtures.hex(SteamCrypto.sha1("abc".toByteArray()))
        )
        assertEquals(0xCBF43926L, SteamCrypto.crc32("123456789".toByteArray()))
    }

    private fun channelKeys(sessionKey: ByteArray, hmacSecret: ByteArray) =
        ChannelKeys(sessionKey, ByteArray(0), hmacSecret)

    private fun vectors(): List<JSONObject> {
        val all = SteamFixtures.json("crypto-vectors.json").getJSONArray("vectors")
        return (0 until all.length()).map { all.getJSONObject(it) }
    }
}
