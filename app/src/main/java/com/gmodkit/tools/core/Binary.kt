package com.gmodkit.tools.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal fun ByteArray.u16le(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
}

internal fun ByteArray.u32le(offset: Int): Long {
    return (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)
}

internal fun ByteArray.i32le(offset: Int): Int = u32le(offset).toInt()

internal fun ByteArray.u64le(offset: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) {
        value = (value shl 8) or (this[offset + i].toLong() and 0xFFL)
    }
    return value
}

/** Little-endian reader over a byte array, used for the VPK tree. */
internal class ByteReader(
    private val data: ByteArray,
    private var position: Int = 0
) {
    fun remaining(): Int = data.size - position

    private fun require(count: Int) {
        if (count < 0 || position + count > data.size) {
            throw FormatException("Unexpected end of data")
        }
    }

    fun u8(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        require(2)
        val value = data.u16le(position)
        position += 2
        return value
    }

    fun u32(): Long {
        require(4)
        val value = data.u32le(position)
        position += 4
        return value
    }

    fun i32(): Int {
        require(4)
        val value = data.i32le(position)
        position += 4
        return value
    }

    fun bytes(count: Int): ByteArray {
        require(count)
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    /** Reads a null-terminated UTF-8 string. */
    fun cstring(maxBytes: Int = 65536): String {
        val start = position
        var end = start
        while (end < data.size && data[end] != 0.toByte()) {
            end++
            if (end - start > maxBytes) {
                throw FormatException("String is too long")
            }
        }
        if (end >= data.size) {
            throw FormatException("Unterminated string")
        }
        val value = String(data, start, end - start, StandardCharsets.UTF_8)
        position = end + 1
        return value
    }
}

/** Little-endian reader over a stream, used for GMA headers and entries. */
internal class StreamReader(private val input: InputStream) {
    var position: Long = 0L
        private set

    private val scratch = ByteArray(8)

    private fun readInto(count: Int) {
        var read = 0
        while (read < count) {
            val n = input.read(scratch, read, count - read)
            if (n <= 0) throw EOFException("Unexpected end of file")
            read += n
        }
        position += count
    }

    fun u8(): Int {
        readInto(1)
        return scratch[0].toInt() and 0xFF
    }

    fun u32(): Long {
        readInto(4)
        return scratch.u32le(0)
    }

    fun i32(): Int {
        readInto(4)
        return scratch.i32le(0)
    }

    fun i64(): Long {
        readInto(8)
        return scratch.u64le(0)
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0) throw IllegalArgumentException("count < 0")
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n <= 0) throw EOFException("Unexpected end of file")
            read += n
        }
        position += count
        return out
    }

    fun string(maxBytes: Int = 65536): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("Unterminated string")
            position++
            if (value == 0) break
            if (out.size() >= maxBytes) throw FormatException("String is too long")
            out.write(value)
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}

open class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GmaFormatException(message: String, cause: Throwable? = null) : FormatException(message, cause)

class VpkFormatException(message: String, cause: Throwable? = null) : FormatException(message, cause)

class VpkMissingArchiveException(val archiveName: String) : Exception(
    "A required VPK part is missing: $archiveName. Choose the folder that contains all VPK parts."
)

fun interface ProgressSink {
    fun onProgress(done: Long, total: Long)
}

data class ExtractionStats(
    val entryCount: Int,
    val bytesWritten: Long,
    val crcMismatches: Int
)
