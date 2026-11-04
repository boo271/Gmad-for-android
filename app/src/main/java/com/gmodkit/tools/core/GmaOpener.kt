package com.gmodkit.tools.core

import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Opens a GMAD file and transparently handles the containers that Workshop
 * downloads are commonly wrapped in:
 *
 *  - plain .gma
 *  - .bin with LZMA-alone compression
 *  - .xz
 *  - .gz
 *  - .zip containing a .gma
 *
 * All temporary files are removed when the returned object is closed.
 */
class OpenedGma internal constructor(
    val archive: GmaArchive,
    private val temporaryFiles: List<File>
) : AutoCloseable {

    val metadata: GmaMetadata
        get() = archive.metadata

    fun extract(
        zip: java.util.zip.ZipOutputStream,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): ExtractionStats {
        return archive.extract(zip, progress, isActive)
    }

    override fun close() {
        try {
            archive.close()
        } finally {
            for (file in temporaryFiles) {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }
}

object GmaOpener {
    private const val MAX_UNPACKED = 8L * 1024 * 1024 * 1024
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    fun open(
        source: SeekableSource,
        cacheDir: File,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): OpenedGma {
        if (source.size <= 0) {
            throw GmaFormatException("The selected file is empty.")
        }

        val headLength = minOf(source.size, 13L).toInt()
        val head = source.readFully(0, headLength)

        if (startsWith(head, byteArrayOf('G'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte()))) {
            return OpenedGma(GmaArchive.open(source), emptyList())
        }

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw GmaFormatException("Could not create a temporary folder.")
        }

        val temporary = ArrayList<File>()
        try {
            when {
                isLzmaAlone(head) -> {
                    val output = createTemp(cacheDir, "gmad_lzma_", ".gma", temporary)
                    decompress(
                        LZMAInputStream(
                            ProgressInputStream(source.inputStreamAt(0), source.size, progress, isActive)
                        ),
                        output,
                        isActive
                    )
                    return openUnpacked(output, temporary)
                }

                isXz(head) -> {
                    val output = createTemp(cacheDir, "gmad_xz_", ".gma", temporary)
                    decompress(
                        XZInputStream(
                            ProgressInputStream(source.inputStreamAt(0), source.size, progress, isActive)
                        ),
                        output,
                        isActive
                    )
                    return openUnpacked(output, temporary)
                }

                isGzip(head) -> {
                    val output = createTemp(cacheDir, "gmad_gz_", ".gma", temporary)
                    decompress(
                        GZIPInputStream(
                            ProgressInputStream(source.inputStreamAt(0), source.size, progress, isActive)
                        ),
                        output,
                        isActive
                    )
                    return openUnpacked(output, temporary)
                }

                isZip(head) -> {
                    val zipCopy = createTemp(cacheDir, "gmad_zip_", ".zip", temporary)
                    checkSpace(cacheDir, source.size)
                    copyWithProgress(
                        ProgressInputStream(source.inputStreamAt(0), source.size, progress, isActive),
                        FileOutputStream(zipCopy),
                        source.size,
                        isActive
                    )
                    val output = extractGmaFromZip(zipCopy, cacheDir, temporary, progress, isActive)
                    return openUnpacked(output, temporary)
                }

                else -> throw GmaFormatException(
                    "This is not a GMAD file. If this is a compressed Workshop addon, " +
                        "extract it with a file archiver first, then pick the .gma file."
                )
            }
        } catch (e: GmaFormatException) {
            deleteAll(temporary)
            throw e
        } catch (e: CancellationException) {
            deleteAll(temporary)
            throw e
        } catch (e: IOException) {
            deleteAll(temporary)
            throw GmaFormatException("Could not unpack this addon: ${e.message}", e)
        } catch (e: Exception) {
            deleteAll(temporary)
            throw GmaFormatException("Could not unpack this addon: ${e.message}", e)
        }
    }

    private fun openUnpacked(file: File, temporary: MutableList<File>): OpenedGma {
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(4)
            val read = input.read(buffer)
            if (read == 4) buffer else ByteArray(0)
        }
        if (!startsWith(
                head,
                byteArrayOf('G'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte())
            )
        ) {
            throw GmaFormatException("The file was unpacked, but it is not a GMAD file.")
        }
        return OpenedGma(GmaArchive.open(FileSource(file)), temporary.toList())
    }

    private fun extractGmaFromZip(
        zipFile: File,
        cacheDir: File,
        temporary: MutableList<File>,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): File {
        ZipFile(zipFile).use { zip ->
            var best: java.util.zip.ZipEntry? = null
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                if (!entry.name.lowercase().endsWith(".gma")) continue
                if (best == null || entry.size > best!!.size) best = entry
            }
            val entry = best
                ?: throw GmaFormatException("This ZIP file does not contain a .gma file.")
            if (entry.size > MAX_UNPACKED) {
                throw GmaFormatException("The addon inside this ZIP is too large.")
            }
            if (entry.size > 0) checkSpace(cacheDir, entry.size)

            val output = createTemp(cacheDir, "gmad_from_zip_", ".gma", temporary)
            zip.getInputStream(entry).use { input ->
                val counting = ProgressInputStream(
                    input,
                    if (entry.size > 0) entry.size else 0,
                    progress,
                    isActive
                )
                FileOutputStream(output).use { out ->
                    copyWithProgress(counting, out, if (entry.size > 0) entry.size else MAX_UNPACKED, isActive)
                }
            }
            return output
        }
    }

    private fun decompress(input: InputStream, outputFile: File, isActive: () -> Boolean) {
        input.use { source ->
            FileOutputStream(outputFile).use { fileOut ->
                BufferedOutputStream(fileOut, 64 * 1024).use { out ->
                    copyWithProgress(source, out, MAX_UNPACKED, isActive)
                }
            }
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        isActive: () -> Boolean
    ) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        output.use { out ->
            while (true) {
                if (!isActive()) throw CancellationException("Cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) {
                    throw GmaFormatException("The unpacked addon is too large to handle safely.")
                }
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }

    private fun createTemp(
        cacheDir: File,
        prefix: String,
        suffix: String,
        temporary: MutableList<File>
    ): File {
        val file = File.createTempFile(prefix, suffix, cacheDir)
        temporary.add(file)
        return file
    }

    private fun checkSpace(cacheDir: File, needed: Long) {
        val usable = cacheDir.usableSpace
        if (usable > 0 && usable < needed + SPACE_MARGIN) {
            throw GmaFormatException(
                "Not enough free storage. This addon needs about " +
                    "${(needed + SPACE_MARGIN) / (1024 * 1024)} MB free."
            )
        }
    }

    private fun deleteAll(files: List<File>) {
        for (file in files) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    private fun isLzmaAlone(head: ByteArray): Boolean {
        // LZMA-alone starts with a 13-byte header: one properties byte,
        // a little-endian dictionary size, and a little-endian size that may
        // be unknown (all ones). Checking the dictionary size avoids treating
        // arbitrary files as LZMA data.
        if (head.size < 13) return false
        val properties = head[0].toInt() and 0xFF
        if (properties >= 225) return false
        val dictionarySize = head.u32le(1)
        if (!isValidDictionarySize(dictionarySize)) return false
        val unpackedSize = head.u64le(5)
        if (unpackedSize != -1L && (unpackedSize < 0 || unpackedSize > MAX_UNPACKED)) {
            return false
        }
        return true
    }

    private fun isValidDictionarySize(value: Long): Boolean {
        if (value < 4096L || value > 1536L * 1024L * 1024L) return false
        var remaining = value
        while (remaining and 1L == 0L) {
            remaining = remaining ushr 1
        }
        return remaining == 1L || remaining == 3L
    }

    private fun isXz(head: ByteArray): Boolean {
        return startsWith(
            head,
            byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
        )
    }

    private fun isGzip(head: ByteArray): Boolean {
        return startsWith(head, byteArrayOf(0x1F, 0x8B.toByte()))
    }

    private fun isZip(head: ByteArray): Boolean {
        return startsWith(head, byteArrayOf(0x50, 0x4B, 0x03, 0x04)) ||
            startsWith(head, byteArrayOf(0x50, 0x4B, 0x05, 0x06))
    }
}

private class ProgressInputStream(
    private val delegate: InputStream,
    private val total: Long,
    private val progress: ProgressSink,
    private val isActive: () -> Boolean
) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        if (!isActive()) throw CancellationException("Cancelled")
        val value = delegate.read()
        if (value >= 0) {
            count++
            progress.onProgress(count, total)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!isActive()) throw CancellationException("Cancelled")
        val read = delegate.read(b, off, len)
        if (read > 0) {
            count += read
            progress.onProgress(count, total)
        }
        return read
    }

    override fun close() {
        delegate.close()
    }
}
