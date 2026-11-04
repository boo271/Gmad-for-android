package com.gmodkit.tools.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

data class TestGmaFile(
    val name: String,
    val data: ByteArray,
    val number: Long = 0L,
    val crcOverride: Long? = null
)

data class TestVpkFile(
    val path: String,
    val name: String,
    val extension: String,
    val data: ByteArray,
    val preload: ByteArray = ByteArray(0),
    val archiveIndex: Int = 0x7FFF,
    val crcOverride: Long? = null,
    val offsetOverride: Long? = null,
    val lengthOverride: Long? = null
)

object TestFiles {

    fun buildGma(
        version: Int = 3,
        requiredContent: List<String> = emptyList(),
        addonName: String = "Test Addon",
        addonDescription: String = "A test addon",
        addonAuthor: String = "Tester",
        addonVersion: Int = 1,
        files: List<TestGmaFile> = emptyList(),
        includeAddonCrc: Boolean = true
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('G'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte()))
        out.write(version)
        writeI64(out, 123456789L)
        writeI64(out, 1700000000L)

        if (version > 1) {
            for (item in requiredContent) writeCString(out, item)
            writeCString(out, "")
        }

        writeCString(out, addonName)
        writeCString(out, addonDescription)
        writeCString(out, addonAuthor)
        writeI32(out, addonVersion)

        files.forEachIndexed { index, file ->
            writeU32(out, if (file.number != 0L) file.number else (index + 1).toLong())
            writeCString(out, file.name)
            writeI64(out, file.data.size.toLong())
            writeU32(out, file.crcOverride ?: crc(file.data))
        }
        writeU32(out, 0)

        var all = ByteArray(0)
        for (file in files) {
            out.write(file.data)
            all += file.data
        }
        if (includeAddonCrc) {
            writeU32(out, crc(all))
        }
        return out.toByteArray()
    }

    fun buildVpk(
        version: Int = 2,
        files: List<TestVpkFile>,
        signed: Boolean = false,
        embeddedData: ByteArray? = null,
        fileDataSizeOverride: Long? = null,
        signatureBytes: ByteArray = ByteArray(16) { it.toByte() }
    ): ByteArray {
        val tree = ByteArrayOutputStream()
        val byExtension = LinkedHashMap<String, LinkedHashMap<String, MutableList<TestVpkFile>>>()
        for (file in files) {
            val paths = byExtension.getOrPut(file.extension) { LinkedHashMap() }
            val list = paths.getOrPut(file.path) { ArrayList() }
            list.add(file)
        }

        val data = ByteArrayOutputStream()
        if (embeddedData != null) {
            data.write(embeddedData)
        } else {
            for (file in files) {
                if (file.archiveIndex == 0x7FFF) data.write(file.data)
            }
        }
        val embedded = data.toByteArray()

        val embeddedOffsets = HashMap<TestVpkFile, Long>()
        var position = 0L
        for (file in files) {
            if (file.archiveIndex == 0x7FFF) {
                embeddedOffsets[file] = position
                position += file.data.size
            }
        }

        for ((extension, paths) in byExtension) {
            writeCString(tree, extension)
            for ((path, entries) in paths) {
                writeCString(tree, path)
                for (file in entries) {
                    writeCString(tree, file.name)
                    val full = file.preload + file.data
                    writeU32(tree, file.crcOverride ?: crc(full))
                    writeU16(tree, file.preload.size)
                    writeU16(tree, file.archiveIndex)
                    val offset = file.offsetOverride
                        ?: embeddedOffsets[file]
                        ?: 0L
                    writeU32(tree, offset)
                    writeU32(tree, file.lengthOverride ?: file.data.size.toLong())
                    writeU16(tree, 0xFFFF)
                    tree.write(file.preload)
                }
                writeCString(tree, "")
            }
            writeCString(tree, "")
        }
        writeCString(tree, "")

        val treeBytes = tree.toByteArray()
        val out = ByteArrayOutputStream()
        writeU32(out, 0x55AA1234L)
        writeU32(out, version.toLong())
        writeU32(out, treeBytes.size.toLong())
        if (version == 2) {
            writeU32(out, fileDataSizeOverride ?: embedded.size.toLong())
            writeU32(out, 0)
            writeU32(out, 0)
            writeU32(out, if (signed) signatureBytes.size.toLong() else 0L)
        }
        out.write(treeBytes)
        out.write(embedded)
        if (signed) out.write(signatureBytes)
        return out.toByteArray()
    }

    fun crc(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    fun writeI32(out: ByteArrayOutputStream, value: Int) {
        writeU32(out, value.toLong() and 0xFFFFFFFFL)
    }

    fun writeI64(out: ByteArrayOutputStream, value: Long) {
        for (i in 0 until 8) {
            out.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }

    fun writeCString(out: ByteArrayOutputStream, value: String) {
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write(0)
    }
}
