package com.gmodkit.tools.steam

import com.gmodkit.tools.core.u32le
import com.gmodkit.tools.core.u64le
import com.gmodkit.tools.network.WorkshopException
import com.gmodkit.tools.steam.zstd.ZstdDecompressor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.tukaani.xz.LZMAInputStream

/**
 * Turns one encrypted CDN chunk container into the bytes the manifest expects.
 *
 * Steam has used three containers over the years. All of them sit inside the
 * same AES wrapper, and every chunk is checked against the SHA-1 the manifest
 * carries for it, so a corrupt or substituted chunk can never be written out.
 */
internal object ChunkDecoder {
    private const val MAX_CHUNK = 8L * 1024 * 1024
    private const val ZSTD_HEADER_SIZE = 8
    private const val ZSTD_FOOTER_SIZE = 15
    private const val LZMA_HEADER_SIZE = 12
    private const val LZMA_TRAILER_SIZE = 9

    fun decode(container: ByteArray, depotKey: ByteArray, expectedSha1: ByteArray): ByteArray {
        val plain = SteamCrypto.decryptPayload(container, depotKey)
        val data = when {
            matches(plain, "VSZa") -> fromZstd(plain)
            matches(plain, "VZa") -> fromLzma(plain)
            matches(plain, "PK") -> fromZip(plain)
            else -> throw WorkshopException(
                "This addon uses a chunk format that this app does not understand."
            )
        }
        if (!SteamCrypto.sha1(data).contentEquals(expectedSha1)) {
            throw WorkshopException(
                "Steam sent a chunk that does not match the manifest. Try the download again."
            )
        }
        return data
    }

    /** VSZa: zstd frame with a 4-byte header checksum and a 15-byte trailer. */
    private fun fromZstd(container: ByteArray): ByteArray {
        if (container.size <= ZSTD_HEADER_SIZE + ZSTD_FOOTER_SIZE) {
            throw WorkshopException("A compressed chunk is truncated.")
        }
        val size = container.u64le(container.size - ZSTD_FOOTER_SIZE + 4)
        if (size <= 0 || size > MAX_CHUNK) throw WorkshopException("A compressed chunk claims a bad size.")
        val out = ByteArray(size.toInt())
        val written = ZstdDecompressor().decompress(
            container,
            ZSTD_HEADER_SIZE,
            container.size - ZSTD_HEADER_SIZE - ZSTD_FOOTER_SIZE,
            out,
            0,
            out.size
        )
        if (written.toLong() != size) {
            throw WorkshopException("A compressed chunk is smaller than the manifest says.")
        }
        return out
    }

    /** VZa: raw LZMA1 stream with a CRC32 and an uncompressed size in the trailer. */
    private fun fromLzma(container: ByteArray): ByteArray {
        if (container.size <= LZMA_HEADER_SIZE + LZMA_TRAILER_SIZE) {
            throw WorkshopException("A compressed chunk is truncated.")
        }
        val properties = container[7].toInt() and 0xFF
        val lc = properties % 9
        val remainder = properties / 9
        val lp = remainder % 5
        val pb = remainder / 5
        if (lc + lp > 4 || pb > 4) throw WorkshopException("A compressed chunk uses unsupported LZMA settings.")

        val dictSize = container.u32le(8).toInt()
        val crc = container.u32le(container.size - LZMA_TRAILER_SIZE - 1).toInt()
        val size = container.u32le(container.size - LZMA_TRAILER_SIZE + 3).toInt()
        if (size <= 0 || size.toLong() > MAX_CHUNK) throw WorkshopException("A compressed chunk claims a bad size.")

        val body = container.copyOfRange(LZMA_HEADER_SIZE, container.size - LZMA_TRAILER_SIZE)
        val out = ByteArray(size)
        LZMAInputStream(
            ByteArrayInputStream(body),
            -1L,
            lc,
            lp,
            pb,
            dictSize,
            null
        ).use { stream ->
            // The container's trailer is trimmed off by a byte or two on purpose,
            // because the desktop client does the same to its LZMA stream.
            stream.enableRelaxedEndCondition()
            readFully(stream, out)
        }
        if (SteamCrypto.crc32(out) != (crc.toLong() and 0xFFFFFFFFL)) {
            throw WorkshopException("A compressed chunk failed its CRC check.")
        }
        return out
    }

    private fun fromZip(container: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(container)).use { zip ->
            if (zip.nextEntry == null) throw WorkshopException("A stored chunk is empty.")
            val out = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_CHUNK) throw WorkshopException("A stored chunk is too large.")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun matches(data: ByteArray, magic: String): Boolean {
        if (data.size < magic.length) return false
        for (i in magic.indices) {
            if (data[i] != magic[i].code.toByte()) return false
        }
        return true
    }

    private fun readFully(stream: InputStream, out: ByteArray) {
        var done = 0
        while (done < out.size) {
            val read = stream.read(out, done, out.size - done)
            if (read < 0) throw IOException("A compressed chunk ended too early.")
            done += read
        }
    }
}
