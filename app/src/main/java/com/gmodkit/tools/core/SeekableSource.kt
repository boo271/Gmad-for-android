package com.gmodkit.tools.core

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access view over an input file. Both regular files and Android
 * content URIs are exposed through this interface so the format parsers
 * stay platform independent and testable on the JVM.
 */
interface SeekableSource : Closeable {
    val size: Long
    val name: String

    /** Reads up to [length] bytes. Returns -1 at end of file. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Reads exactly [length] bytes or throws [EOFException]. */
    fun readFully(position: Long, length: Int): ByteArray {
        if (length < 0) throw IllegalArgumentException("length < 0")
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = readAt(position + read, out, read, length - read)
            if (n <= 0) throw EOFException("Unexpected end of $name")
            read += n
        }
        return out
    }

    /** Sequential stream that starts at [position]. Closing it does not close the source. */
    fun inputStreamAt(position: Long = 0L): InputStream = SourceInputStream(this, position)
}

class FileSource(
    private val file: File,
    override val name: String = file.name
) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel

    override val size: Long
        get() = channel.size()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || offset < 0 || length < 0 || offset + length > buffer.size) {
            throw IndexOutOfBoundsException("position=$position offset=$offset length=$length")
        }
        if (length == 0) return 0
        if (position >= size) return -1
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: IOException) {
        }
    }
}

private class SourceInputStream(
    private val source: SeekableSource,
    start: Long
) : InputStream() {
    private var position = start
    private val one = ByteArray(1)

    override fun read(): Int {
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = source.readAt(position, b, off, len)
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val remaining = source.size - position
        val skipped = if (remaining <= 0) 0 else minOf(n, remaining)
        position += skipped
        return skipped
    }

    override fun available(): Int {
        val remaining = source.size - position
        if (remaining <= 0) return 0
        return minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    }
}
