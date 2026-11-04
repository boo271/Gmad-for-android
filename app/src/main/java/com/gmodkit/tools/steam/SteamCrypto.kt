package com.gmodkit.tools.steam

import android.annotation.SuppressLint
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.zip.CRC32
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ChannelKeys(
    val sessionKey: ByteArray,
    val encryptedSessionKey: ByteArray,
    /** Empty when the CM offered no challenge, leaving the channel unauthenticated. */
    val hmacSecret: ByteArray
) {
    val usesHmac: Boolean get() = hmacSecret.isNotEmpty()
}

/**
 * The cryptography of the Steam3 content network: the public universe key every
 * Steam client ships with, and the AES/HMAC framing of a secured CM channel.
 * Depot payloads use the same AES construction with a depot key instead.
 */
internal object SteamCrypto {
    private const val BLOCK = 16
    private const val SESSION_KEY_SIZE = 32
    private const val HMAC_SECRET_SIZE = 16
    private const val IV_PREFIX = 13
    private val random = SecureRandom()

    /**
     * Steam's RSA-1024 public universe key. Public by design (it is embedded in
     * the desktop client) and used only to hand a fresh random session key to
     * the server; no account data is involved.
     */
    private val universeKey: PublicKey by lazy {
        val der = decodeBase64(UNIVERSE_KEY_PUBLIC_B64)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    fun createChannelKeys(challenge: ByteArray): ChannelKeys {
        val sessionKey = ByteArray(SESSION_KEY_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, universeKey)
        val encrypted = cipher.doFinal(sessionKey + challenge)
        val hmacSecret = if (challenge.isEmpty()) {
            ByteArray(0)
        } else {
            // The desktop client derives the HMAC secret from the session key.
            sessionKey.copyOfRange(0, HMAC_SECRET_SIZE)
        }
        return ChannelKeys(sessionKey, encrypted, hmacSecret)
    }

    fun encrypt(message: ByteArray, keys: ChannelKeys): ByteArray {
        val iv = if (keys.usesHmac) {
            val prefix = ByteArray(3).also { random.nextBytes(it) }
            hmac(keys.hmacSecret, prefix + message).copyOfRange(0, IV_PREFIX) + prefix
        } else {
            ByteArray(BLOCK).also { random.nextBytes(it) }
        }
        return aesEcb(Cipher.ENCRYPT_MODE, keys.sessionKey).doFinal(iv) +
            aesCbc(Cipher.ENCRYPT_MODE, keys.sessionKey, iv).doFinal(pad(message))
    }

    fun decrypt(ciphertext: ByteArray, keys: ChannelKeys): ByteArray {
        val iv = aesEcb(Cipher.DECRYPT_MODE, keys.sessionKey).doFinal(ciphertext, 0, BLOCK)
        val plain = decryptBody(ciphertext, iv, keys.sessionKey)
        if (!keys.usesHmac) return plain
        val expected = hmac(keys.hmacSecret, iv.copyOfRange(IV_PREFIX, BLOCK) + plain)
        if (!iv.copyOfRange(0, IV_PREFIX).contentEquals(expected.copyOfRange(0, IV_PREFIX))) {
            throw BadPaddingException("Channel HMAC does not match")
        }
        return plain
    }

    /** Depot payloads carry no HMAC, so this stays separate from the channel path. */
    fun decryptPayload(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val iv = aesEcb(Cipher.DECRYPT_MODE, key).doFinal(ciphertext, 0, BLOCK)
        return decryptBody(ciphertext, iv, key)
    }

    private fun decryptBody(ciphertext: ByteArray, iv: ByteArray, key: ByteArray): ByteArray {
        if (ciphertext.size <= BLOCK || ciphertext.size % BLOCK != 0) {
            throw IllegalArgumentException("Ciphertext is not a whole number of AES blocks")
        }
        return unpad(aesCbc(Cipher.DECRYPT_MODE, key, iv).doFinal(ciphertext, BLOCK, ciphertext.size - BLOCK))
    }

    fun crc32(data: ByteArray): Long {
        val checksum = CRC32()
        checksum.update(data)
        return checksum.value
    }

    fun hmac(secret: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        return mac.doFinal(data)
    }

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    /**
     * Steam's framing uses one raw AES block to carry the IV, which is what
     * "ECB" means here: a single block, never a multi-block ECB stream.
     */
    @SuppressLint("GetInstance")
    private fun aesEcb(mode: Int, key: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher
    }

    private fun aesCbc(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun pad(data: ByteArray): ByteArray {
        val padding = BLOCK - data.size % BLOCK
        return data + ByteArray(padding) { padding.toByte() }
    }

    /** Strips block padding the way the desktop client does, without rejecting odd lengths. */
    private fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padding = data.last().toInt() and 0xFF
        if (padding == 0 || padding > BLOCK || padding > data.size) return data
        return data.copyOfRange(0, data.size - padding)
    }

    private const val UNIVERSE_KEY_PUBLIC_B64 =
        "MIGdMA0GCSqGSIb3DQEBAQUAA4GLADCBhwKBgQDf7BrWLBBmLBc1OhSwfFkRf53T" +
            "2Ct64+AVzRkeRuh7h3SiGEYxqQMUeYKO6UWiSRKpI2hzic9pobFhRr3Bvr/WARvY" +
            "gdTckPv+T1JzZsuVcNfFjrocejN1oWI0Rrtgt4Bo+hOneoo3S57G9F1fOpn5nsQ6" +
            "6WOiu4gZKODnFMBCiQIBEQ=="
}
