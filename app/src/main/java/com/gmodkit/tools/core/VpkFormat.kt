package com.gmodkit.tools.core

import java.io.EOFException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class VpkEntry(
    val name: String,
    val crc: Long,
    val preload: ByteArray,
    val archiveIndex: Int,
    val offset: Long,
    val length: Long
) {
    val totalSize: Long
        get() = preload.size.toLong() + length
}

data class VpkMetadata(
    val version: Int,
    val treeSize: Long,
    val dataSectionSize: Long,
    val entries: List<VpkEntry>,
    val externalArchives: List<Int>,
    val totalSize: Long,
    val signed: Boolean
) {
    val fileCount: Int
        get() = entries.size
}

/**
 * Reader for Valve Pak (VPK) files, versions 1 and 2.
 *
 * Supported layouts:
 *  - v1 and v2 headers
 *  - embedded file data (archive index 0x7FFF)
 *  - multi-part archives (pak01_dir.vpk + pak01_000.vpk, ...)
 *  - preload bytes stored in the directory
 *  - signed v2 files (the signature is skipped)
 */
class VpkArchive private constructor(
    private val source: SeekableSource,
    private val dataOffset: Long,
    val baseName: String,
    val metadata: VpkMetadata
) : AutoCloseable {

    companion object {
        private const val MAGIC = 0x55AA1234L
        private const val EMBEDDED = 0x7FFF
        private const val MAX_TREE_SIZE = 64L * 1024 * 1024
        private const val MAX_ENTRIES = 2_000_000
        private const val MAX_STRING = 4096

        fun open(source: SeekableSource): VpkArchive {
            try {
                if (source.size < 12) {
                    throw VpkFormatException("This file is too small to be a VPK.")
                }

                val header = source.readFully(0, 12)
                if (header.u32le(0) != MAGIC) {
                    throw VpkFormatException("This is not a VPK file.")
                }

                val version = header.u32le(4).toInt()
                if (version != 1 && version != 2) {
                    throw VpkFormatException("Unsupported VPK version $version.")
                }

                val treeSize = header.u32le(8)
                if (treeSize > MAX_TREE_SIZE) {
                    throw VpkFormatException("The VPK directory is too large to read safely.")
                }

                var headerSize = 12L
                var dataSectionSize: Long
                var signed = false

                if (version == 2) {
                    val extra = source.readFully(12, 16)
                    val fileDataSize = extra.u32le(0)
                    val archiveMd5Size = extra.u32le(4)
                    val otherMd5Size = extra.u32le(8)
                    val signatureSize = extra.u32le(12)
                    headerSize = 28
                    dataSectionSize = fileDataSize
                    signed = signatureSize > 0

                    val total = headerSize + treeSize + fileDataSize +
                        archiveMd5Size + otherMd5Size + signatureSize
                    if (total > source.size) {
                        throw VpkFormatException("The VPK is truncated.")
                    }
                } else {
                    dataSectionSize = source.size - headerSize - treeSize
                    if (dataSectionSize < 0) {
                        throw VpkFormatException("The VPK is truncated.")
                    }
                }

                if (headerSize + treeSize > source.size) {
                    throw VpkFormatException("The VPK directory is outside the file.")
                }

                val tree = source.readFully(headerSize, treeSize.toInt())
                val reader = ByteReader(tree)
                val entries = ArrayList<VpkEntry>()
                var totalSize = 0L

                while (true) {
                    val extension = reader.cstring(MAX_STRING)
                    if (extension.isEmpty()) break

                    while (true) {
                        val path = reader.cstring(MAX_STRING)
                        if (path.isEmpty()) break

                        val directory = when {
                            path == " " || path == "." -> ""
                            path.endsWith('/') -> path
                            else -> "$path/"
                        }

                        while (true) {
                            val fileName = reader.cstring(MAX_STRING)
                            if (fileName.isEmpty()) break

                            val crc = reader.u32()
                            val preloadSize = reader.u16()
                            val archiveIndex = reader.u16()
                            val offset = reader.u32()
                            val length = reader.u32()
                            val terminator = reader.u16()
                            if (terminator != 0xFFFF) {
                                throw VpkFormatException("Invalid VPK directory entry.")
                            }
                            val preload = reader.bytes(preloadSize)

                            val name = buildString {
                                append(directory)
                                append(fileName)
                                if (extension.isNotEmpty()) {
                                    append('.')
                                    append(extension)
                                }
                            }

                            val entrySize = preload.size.toLong() + length
                            if (entrySize > Long.MAX_VALUE - totalSize) {
                                throw VpkFormatException("The VPK contents are too large.")
                            }
                            totalSize += entrySize

                            entries.add(
                                VpkEntry(
                                    name = name,
                                    crc = crc,
                                    preload = preload,
                                    archiveIndex = archiveIndex,
                                    offset = offset,
                                    length = length
                                )
                            )
                            if (entries.size > MAX_ENTRIES) {
                                throw VpkFormatException("Too many files in this VPK.")
                            }
                        }
                    }
                }

                val dataOffset = headerSize + treeSize
                val external = entries
                    .asSequence()
                    .filter { it.archiveIndex != EMBEDDED && it.length > 0 }
                    .map { it.archiveIndex }
                    .distinct()
                    .sorted()
                    .toList()

                for (entry in entries) {
                    if (entry.archiveIndex != EMBEDDED) continue
                    if (entry.offset + entry.length > dataSectionSize) {
                        throw VpkFormatException(
                            "VPK entry '${entry.name}' points outside the file data section."
                        )
                    }
                }

                val metadata = VpkMetadata(
                    version = version,
                    treeSize = treeSize,
                    dataSectionSize = dataSectionSize,
                    entries = entries,
                    externalArchives = external,
                    totalSize = totalSize,
                    signed = signed
                )
                return VpkArchive(source, dataOffset, baseNameFor(source.name), metadata)
            } catch (e: VpkFormatException) {
                throw e
            } catch (e: EOFException) {
                throw VpkFormatException("Invalid or truncated VPK file.", e)
            } catch (e: FormatException) {
                throw VpkFormatException("Invalid VPK directory: ${e.message}", e)
            } catch (e: IOException) {
                throw VpkFormatException("Could not read the VPK file: ${e.message}", e)
            }
        }

        fun baseNameFor(fileName: String): String {
            val file = fileName.substringAfterLast('/').substringAfterLast('\\')
            val stem = if (file.lowercase(Locale.US).endsWith(".vpk")) {
                file.dropLast(4)
            } else {
                file
            }
            val lower = stem.lowercase(Locale.US)
            if (lower.endsWith("_dir")) {
                return stem.dropLast(4)
            }
            val numbered = Regex("_\\d{3}$").find(stem)
            if (numbered != null) {
                return stem.substring(0, numbered.range.first)
            }
            return stem
        }

        fun archiveFileName(baseName: String, index: Int): String {
            return String.format(Locale.US, "%s_%03d.vpk", baseName, index)
        }

        fun dirFileName(baseName: String): String {
            return "${baseName}_dir.vpk"
        }

        fun numberedIndex(fileName: String): Int? {
            val match = Regex("_(\\d{3})\\.vpk$", RegexOption.IGNORE_CASE)
                .find(fileName.substringAfterLast('/').substringAfterLast('\\'))
                ?: return null
            return match.groupValues[1].toIntOrNull()
        }
    }

    fun extract(
        zip: ZipOutputStream,
        archives: Map<Int, SeekableSource>,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): ExtractionStats {
        val total = metadata.totalSize
        val names = UniqueNames()
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var crcMismatches = 0

        for (entry in metadata.entries) {
            if (!isActive()) throw CancellationException("Cancelled")

            val entryName = names.unique(entry.name)
            zip.putNextEntry(ZipEntry(entryName))
            val crc = CRC32()

            if (entry.preload.isNotEmpty()) {
                zip.write(entry.preload)
                crc.update(entry.preload)
                done += entry.preload.size
                progress.onProgress(done, total)
            }

            if (entry.length > 0) {
                val entrySource: SeekableSource
                val start: Long
                if (entry.archiveIndex == EMBEDDED) {
                    entrySource = source
                    start = dataOffset + entry.offset
                } else {
                    entrySource = archives[entry.archiveIndex]
                        ?: throw VpkMissingArchiveException(
                            archiveFileName(baseName, entry.archiveIndex)
                        )
                    start = entry.offset
                }

                if (start < 0 || entry.length > entrySource.size - start) {
                    throw VpkFormatException(
                        "VPK entry '${entry.name}' points outside its archive."
                    )
                }

                var position = start
                var remaining = entry.length
                while (remaining > 0) {
                    if (!isActive()) throw CancellationException("Cancelled")
                    val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = entrySource.readAt(position, buffer, 0, chunk)
                    if (read <= 0) {
                        throw VpkFormatException(
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
            entryCount = metadata.entries.size,
            bytesWritten = done,
            crcMismatches = crcMismatches
        )
    }

    override fun close() {
        source.close()
    }
}
