package com.gmodkit.tools.core

import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class GmaFileEntry(
    val number: Long,
    val name: String,
    val size: Long,
    val crc: Long
)

data class GmaMetadata(
    val formatVersion: Int,
    val steamId: Long,
    val timestamp: Long,
    val requiredContent: List<String>,
    val addonName: String,
    val addonDescription: String,
    val addonAuthor: String,
    val addonVersion: Int,
    val files: List<GmaFileEntry>,
    val contentOffset: Long,
    val totalContentSize: Long,
    val addonCrc: Long?
) {
    val displayName: String
        get() = addonName.ifBlank { "Unnamed addon" }
}

/**
 * Reader for Garry's Mod GMAD addon files, versions 1 to 3.
 *
 * Format notes:
 *  - Version 1 has no required-content list.
 *  - Version 2 and 3 store a list of required-content strings terminated
 *    by an empty string.
 *  - File entries are terminated by a zero file number.
 *  - File contents follow the entry table in the same order.
 */
class GmaArchive private constructor(
    private val source: SeekableSource,
    val metadata: GmaMetadata
) : AutoCloseable {

    companion object {
        private const val MAX_FILES = 2_000_000
        private const val MAX_STRING = 4 * 1024 * 1024

        fun open(source: SeekableSource): GmaArchive {
            try {
                val reader = StreamReader(source.inputStreamAt(0))
                val magic = reader.bytes(4)
                if (!magic.contentEquals(byteArrayOf('G'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte()))) {
                    throw GmaFormatException("This is not a GMAD file.")
                }

                val version = reader.u8()
                if (version !in 1..3) {
                    throw GmaFormatException("Unsupported GMAD version $version.")
                }

                val steamId = reader.i64()
                val timestamp = reader.i64()

                val requiredContent = ArrayList<String>()
                if (version > 1) {
                    while (true) {
                        val item = reader.string(MAX_STRING)
                        if (item.isEmpty()) break
                        requiredContent.add(item)
                        if (requiredContent.size > 256) {
                            throw GmaFormatException("Too many required-content entries.")
                        }
                    }
                }

                val addonName = reader.string(MAX_STRING)
                val addonDescription = reader.string(MAX_STRING)
                val addonAuthor = reader.string(MAX_STRING)
                val addonVersion = reader.i32()

                val files = ArrayList<GmaFileEntry>()
                var totalSize = 0L
                while (true) {
                    val number = reader.u32()
                    if (number == 0L) break

                    val name = reader.string(MAX_STRING)
                    val size = reader.i64()
                    val crc = reader.u32()
                    if (size < 0) {
                        throw GmaFormatException("Invalid file size for '$name'.")
                    }
                    if (size > Long.MAX_VALUE - totalSize) {
                        throw GmaFormatException("GMA contents are too large.")
                    }
                    totalSize += size
                    files.add(GmaFileEntry(number, name, size, crc))
                    if (files.size > MAX_FILES) {
                        throw GmaFormatException("Too many files in this GMA.")
                    }
                }

                val contentOffset = reader.position
                val trailerStart = contentOffset + totalSize
                if (trailerStart > source.size) {
                    throw GmaFormatException(
                        "The GMA is truncated. Expected $totalSize bytes of file data."
                    )
                }

                val addonCrc = if (source.size - trailerStart >= 4) {
                    source.readFully(trailerStart, 4).u32le(0)
                } else {
                    null
                }

                val metadata = GmaMetadata(
                    formatVersion = version,
                    steamId = steamId,
                    timestamp = timestamp,
                    requiredContent = requiredContent,
                    addonName = addonName,
                    addonDescription = addonDescription,
                    addonAuthor = addonAuthor,
                    addonVersion = addonVersion,
                    files = files,
                    contentOffset = contentOffset,
                    totalContentSize = totalSize,
                    addonCrc = addonCrc
                )
                return GmaArchive(source, metadata)
            } catch (e: GmaFormatException) {
                throw e
            } catch (e: EOFException) {
                throw GmaFormatException("Invalid or truncated GMA file.", e)
            } catch (e: FormatException) {
                throw GmaFormatException("Invalid GMA file: ${e.message}", e)
            } catch (e: IOException) {
                throw GmaFormatException("Could not read the GMA file: ${e.message}", e)
            }
        }
    }

    fun extract(
        zip: ZipOutputStream,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): ExtractionStats {
        val total = metadata.totalContentSize
        val names = UniqueNames()
        val buffer = ByteArray(64 * 1024)
        var position = metadata.contentOffset
        var done = 0L
        var crcMismatches = 0

        for (entry in metadata.files) {
            if (!isActive()) throw CancellationException("Cancelled")

            val entryName = names.unique(entry.name)
            zip.putNextEntry(ZipEntry(entryName))
            val crc = CRC32()
            var remaining = entry.size
            while (remaining > 0) {
                if (!isActive()) throw CancellationException("Cancelled")
                val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                val read = source.readAt(position, buffer, 0, chunk)
                if (read <= 0) {
                    throw GmaFormatException(
                        "Unexpected end of file while extracting '${entry.name}'."
                    )
                }
                zip.write(buffer, 0, read)
                crc.update(buffer, 0, read)
                position += read
                remaining -= read
                done += read
                progress.onProgress(done, total)
            }
            zip.closeEntry()

            if (entry.crc != 0L && entry.crc != crc.value) {
                crcMismatches++
            }
        }

        if (total == 0L) {
            progress.onProgress(0, 0)
        }
        return ExtractionStats(
            entryCount = metadata.files.size,
            bytesWritten = done,
            crcMismatches = crcMismatches
        )
    }

    override fun close() {
        source.close()
    }
}
