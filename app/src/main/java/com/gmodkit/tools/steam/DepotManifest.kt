package com.gmodkit.tools.steam

import com.gmodkit.tools.core.u32le
import com.gmodkit.tools.network.WorkshopException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class DepotChunk(
    val sha1: ByteArray,
    val checksum: Int,
    val offset: Long,
    val originalSize: Int,
    val compressedSize: Int
)

internal data class DepotFile(
    val name: String,
    val size: Long,
    val flags: Long,
    val chunks: List<DepotChunk>
) {
    val isDirectory: Boolean get() = flags and DIRECTORY_FLAG != 0L

    companion object {
        private const val DIRECTORY_FLAG = 1L
    }
}

/**
 * The depot manifest: a list of files and, for each one, the content-addressed
 * chunks its bytes are built from. Chunks are shared between addons, which is
 * why the same file always arrives as the same set of chunk ids.
 */
internal class DepotManifest(
    val files: List<DepotFile>,
    val gid: Long,
    val depotId: Int,
    val totalUncompressed: Long
) {
    companion object {
        private const val PAYLOAD_MAGIC = 0x71F617D0L
        private const val METADATA_MAGIC = 0x1F4812BEL
        private const val SIGNATURE_MAGIC = 0x1B81B817L
        private const val END_MAGIC = 0x32C415ABL
        private const val MAX_SECTION = 64 * 1024 * 1024

        fun parse(bytes: ByteArray, depotKey: ByteArray): DepotManifest {
            val content = if (bytes.size > 4 && isZip(bytes)) inflateOnlyEntry(bytes) else bytes

            var payload = ByteArray(0)
            var metadata = ByteArray(0)
            var position = 0
            while (position + 8 <= content.size) {
                val magic = content.u32le(position)
                val length = content.u32le(position + 4).toInt()
                position += 8
                if (magic == END_MAGIC) break
                if (length < 0 || length > MAX_SECTION || position + length > content.size) {
                    throw WorkshopException("The addon manifest is damaged.")
                }
                val section = content.copyOfRange(position, position + length)
                position += length
                when (magic) {
                    PAYLOAD_MAGIC -> payload = section
                    METADATA_MAGIC -> metadata = section
                    SIGNATURE_MAGIC -> Unit
                    else -> throw WorkshopException("The addon manifest uses an unknown format.")
                }
            }
            if (payload.isEmpty() || metadata.isEmpty()) {
                throw WorkshopException("The addon manifest could not be read.")
            }

            val namesEncrypted = ProtoReader.varint(metadata, 4) != 0L
            val files = ProtoReader.messages(payload, 1).map { mapping ->
                val rawName = ProtoReader.bytes(mapping, 1)
                DepotFile(
                    name = if (namesEncrypted) decryptName(rawName, depotKey) else cleanName(rawName),
                    size = ProtoReader.varint(mapping, 2),
                    flags = ProtoReader.varint(mapping, 3),
                    chunks = ProtoReader.messages(mapping, 6).map { chunk ->
                        val sha1 = ProtoReader.bytes(chunk, 1)
                        if (sha1.size != 20) {
                            throw WorkshopException("A chunk id in the manifest is not valid.")
                        }
                        DepotChunk(
                            sha1 = sha1,
                            checksum = ProtoReader.fixed32(chunk, 2),
                            offset = ProtoReader.varint(chunk, 3),
                            originalSize = ProtoReader.varint(chunk, 4).toInt(),
                            compressedSize = ProtoReader.varint(chunk, 5).toInt()
                        )
                    }
                )
            }

            return DepotManifest(
                files = files,
                gid = ProtoReader.varint(metadata, 2),
                depotId = ProtoReader.varint(metadata, 1).toInt(),
                totalUncompressed = ProtoReader.varint(metadata, 5)
            )
        }

        /** Picks the addon payload inside the manifest: the .gma, or the largest file. */
        fun pickContent(manifest: DepotManifest, preferred: String?): DepotFile {
            val candidates = manifest.files.filter { !it.isDirectory && it.chunks.isNotEmpty() }
            if (candidates.isEmpty()) {
                throw WorkshopException("This addon's manifest lists no downloadable content.")
            }
            val wanted = preferred?.substringAfterLast('/')?.lowercase(Locale.US)
            if (!wanted.isNullOrEmpty()) {
                candidates.firstOrNull { it.name.lowercase(Locale.US) == wanted }?.let { return it }
                candidates.firstOrNull { it.name.endsWith(wanted, ignoreCase = true) }?.let { return it }
            }
            return candidates.maxByOrNull { it.size } ?: candidates.first()
        }

        private fun isZip(data: ByteArray): Boolean =
            data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()

        private fun inflateOnlyEntry(data: ByteArray): ByteArray {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                if (zip.nextEntry == null) throw WorkshopException("The addon manifest is empty.")
                val out = ByteArrayOutputStream(64 * 1024)
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            }
        }

        private fun decryptName(value: ByteArray, depotKey: ByteArray): String {
            val encoded = String(value, Charsets.US_ASCII).trim()
            if (encoded.isEmpty()) return ""
            val plain = try {
                SteamCrypto.decryptPayload(decodeBase64(encoded), depotKey)
            } catch (_: Exception) {
                throw WorkshopException("The addon manifest names could not be decoded.")
            }
            return cleanName(plain)
        }

        /** Manifest names are padded with zeroes and use Windows separators. */
        private fun cleanName(value: ByteArray): String =
            String(value, Charsets.UTF_8).trimEnd('\u0000', ' ', '\t', '\n', '\r')
    }
}
