package com.gmodkit.tools.steam

import com.gmodkit.tools.core.u32le
import com.gmodkit.tools.core.u64le
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class ProtoField(val number: Int, val wireType: Int)

/**
 * Protobuf reader that tolerates fields it has never heard of, because the
 * Steam messages we parse grow new ones all the time.
 */
internal class ProtoReader(private val data: ByteArray) {
    private var position = 0

    fun hasNext(): Boolean = position < data.size

    fun readField(): ProtoField {
        val tag = readRawVarint()
        val number = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        if (number == 0 || wireType == 3 || wireType == 4) {
            throw IOException("Malformed protobuf field tag: $tag")
        }
        return ProtoField(number, wireType)
    }

    fun readVarint(): Long = readRawVarint()

    fun readFixed32(): Int = data.u32le(require(4)).toInt()

    fun readFixed64(): Long = data.u64le(require(8))

    fun readBytes(): ByteArray {
        val length = readRawVarint()
        if (length < 0 || length > Int.MAX_VALUE) throw IOException("Bad protobuf field length")
        val start = require(length.toInt())
        return data.copyOfRange(start, start + length.toInt())
    }

    /** Consumes the value that follows the tag and returns it, whatever the wire type. */
    fun readValue(field: ProtoField): Any = when (field.wireType) {
        WIRE_VARINT -> readVarint()
        WIRE_FIXED64 -> readFixed64()
        WIRE_FIXED32 -> readFixed32()
        else -> readBytes()
    }

    private fun require(count: Int): Int {
        if (count < 0 || position + count > data.size) {
            throw IOException("Truncated protobuf message")
        }
        val start = position
        position += count
        return start
    }

    private fun readRawVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= data.size) throw IOException("Truncated protobuf varint")
            val byte = data[position++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        // Tenth byte carries the sign bit only.
        if (position >= data.size) throw IOException("Truncated protobuf varint")
        val byte = data[position++].toInt() and 0xFF
        if (byte and 0x80 != 0) throw IOException("Protobuf varint is too long")
        return result or (byte.toLong() shl 56)
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5

        fun varint(data: ByteArray, number: Int): Long {
            var found = 0L
            forEach(data) { field, value ->
                if (field.number == number && field.wireType == WIRE_VARINT) found = value as Long
            }
            return found
        }

        fun fixed64(data: ByteArray, number: Int): Long {
            var found = 0L
            forEach(data) { field, value ->
                if (field.number == number && field.wireType == WIRE_FIXED64) found = value as Long
            }
            return found
        }

        fun fixed32(data: ByteArray, number: Int): Int {
            var found = 0
            forEach(data) { field, value ->
                if (field.number == number && field.wireType == WIRE_FIXED32) found = value as Int
            }
            return found
        }

        fun bytes(data: ByteArray, number: Int): ByteArray {
            var found = ByteArray(0)
            forEach(data) { field, value ->
                if (field.number == number && field.wireType == WIRE_LENGTH) found = value as ByteArray
            }
            return found
        }

        fun string(data: ByteArray, number: Int): String = String(bytes(data, number), Charsets.UTF_8)

        /** Collects every length-delimited field with this number, in wire order. */
        fun messages(data: ByteArray, number: Int): List<ByteArray> {
            val found = ArrayList<ByteArray>()
            forEach(data) { field, value ->
                if (field.number == number && field.wireType == WIRE_LENGTH) found.add(value as ByteArray)
            }
            return found
        }

        private fun forEach(data: ByteArray, block: (ProtoField, Any) -> Unit) {
            val reader = ProtoReader(data)
            while (reader.hasNext()) {
                val field = reader.readField()
                block(field, reader.readValue(field))
            }
        }
    }
}

/** Protobuf writer. Fields left at their default value are omitted, as proto3 requires. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream(64)

    fun varint(number: Int, value: Long): ProtoWriter {
        if (value != 0L) {
            writeTag(number, ProtoReader.WIRE_VARINT)
            var remaining = value
            while (true) {
                val byte = (remaining and 0x7F).toInt()
                remaining = remaining ushr 7
                if (remaining == 0L) {
                    out.write(byte)
                    break
                }
                out.write(byte or 0x80)
            }
        }
        return this
    }

    fun fixed64(number: Int, value: Long): ProtoWriter {
        if (value != 0L) {
            writeTag(number, ProtoReader.WIRE_FIXED64)
            writeLe(value, 8)
        }
        return this
    }

    fun bytes(number: Int, value: ByteArray?): ProtoWriter {
        if (value != null && value.isNotEmpty()) {
            writeTag(number, ProtoReader.WIRE_LENGTH)
            var length = value.size
            while (true) {
                val byte = length and 0x7F
                length = length ushr 7
                if (length == 0) {
                    out.write(byte)
                    break
                }
                out.write(byte or 0x80)
            }
            out.write(value)
        }
        return this
    }

    fun string(number: Int, value: String?): ProtoWriter = bytes(
        number,
        value?.toByteArray(Charsets.UTF_8)
    )

    fun message(number: Int, body: ByteArray): ProtoWriter = bytes(number, body)

    fun bool(number: Int, value: Boolean): ProtoWriter {
        if (value) varint(number, 1L)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeTag(number: Int, wireType: Int) {
        varintTag((number shl 3) or wireType)
    }

    private fun varintTag(value: Int) {
        var remaining = value
        while (true) {
            val byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.write(byte)
                break
            }
            out.write(byte or 0x80)
        }
    }

    private fun writeLe(value: Long, count: Int) {
        var remaining = value
        repeat(count) {
            out.write((remaining and 0xFF).toInt())
            remaining = remaining ushr 8
        }
    }
}

/**
 * Prints a protobuf uint64 the way Steam expects.
 *
 * Manifest ids and request codes are unsigned, so the top ones arrive as
 * negative Kotlin longs and would be sent to the CDN with a minus sign.
 * `Long.toUnsignedString` would do this, but it is Android API 26.
 */
internal fun unsignedToString(value: Long): String {
    if (value >= 0) return value.toString()
    // value / 10 == (value >>> 1) / 5 for every unsigned 64-bit number, and the
    // quotient always fits in a positive long, so only the last digit needs the wrap.
    val quotient = (value ushr 1) / 5L
    return quotient.toString() + (value - quotient * 10L)
}
