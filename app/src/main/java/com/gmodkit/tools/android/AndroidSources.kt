package com.gmodkit.tools.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.gmodkit.tools.core.FileSource
import com.gmodkit.tools.core.FormatException
import com.gmodkit.tools.core.ProgressSink
import com.gmodkit.tools.core.SeekableSource
import com.gmodkit.tools.core.VpkMissingArchiveException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException

data class DocumentInfo(val name: String, val size: Long)

object AndroidSources {

    fun query(context: Context, uri: Uri): DocumentInfo {
        if (uri.scheme == "file") {
            val file = try {
                File(uri.path ?: "")
            } catch (_: Exception) {
                null
            }
            if (file != null) {
                return DocumentInfo(
                    file.name.ifBlank { "file" },
                    if (file.isFile) file.length() else -1L
                )
            }
        }

        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return DocumentInfo(name.ifBlank { "file" }, size)
    }

    fun open(
        context: Context,
        uri: Uri,
        displayName: String,
        cacheDir: File,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): SeekableSource {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            if (file.isFile && file.canRead()) {
                return FileSource(file, displayName)
            }
            throw FormatException("The file could not be opened. It may have been moved or deleted.")
        }

        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (descriptor != null) {
                val source = DescriptorSource(descriptor, displayName)
                if (source.size > 0) {
                    // Verify that positional reads actually work on this provider.
                    val test = ByteArray(1)
                    val read = source.readAt(0, test, 0, 1)
                    if (read >= 0) return source
                }
                source.close()
            }
        } catch (_: SecurityException) {
            throw FormatException("The app does not have permission to read this file.")
        } catch (_: Exception) {
        }

        return copyToCache(context, uri, displayName, cacheDir, progress, isActive)
    }

    private fun copyToCache(
        context: Context,
        uri: Uri,
        displayName: String,
        cacheDir: File,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): SeekableSource {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw FormatException("Could not create a temporary folder.")
        }

        val info = query(context, uri)
        if (info.size > 0) {
            val usable = cacheDir.usableSpace
            if (usable > 0 && usable < info.size + 32L * 1024 * 1024) {
                throw FormatException("Not enough free storage to read this file.")
            }
        }

        val temp = File.createTempFile("gmad_input_", ".tmp", cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw FormatException("Could not open this file.")
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (!isActive()) throw CancellationException("Cancelled")
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 8L * 1024 * 1024 * 1024) {
                            throw FormatException("This file is too large to handle safely.")
                        }
                        output.write(buffer, 0, read)
                        if (info.size > 0) progress.onProgress(total, info.size)
                    }
                }
            }
            return TempFileSource(temp, displayName)
        } catch (e: Exception) {
            try {
                temp.delete()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    fun takeReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    fun takeWritePermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Finds a sibling of a document. This works for local storage providers
     * whose document id is a path. Providers that use opaque ids cannot be
     * resolved without folder access, so the caller falls back to asking the
     * user for the folder.
     */
    fun siblingUri(uri: Uri, fileName: String): Uri? {
        if (uri.scheme == "file") {
            val file = try {
                File(uri.path ?: "")
            } catch (_: Exception) {
                return null
            }
            val parent = file.parentFile ?: return null
            return Uri.fromFile(File(parent, fileName))
        }

        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val separator = documentId.lastIndexOf('/')
        if (separator < 0) return null
        val newId = documentId.substring(0, separator + 1) + fileName
        return try {
            DocumentsContract.buildDocumentUri(uri.authority ?: return null, newId)
        } catch (_: Exception) {
            null
        }
    }

    fun resolveVpkPart(
        context: Context,
        dirUri: Uri,
        folderUri: Uri?,
        fileName: String,
        cacheDir: File,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): SeekableSource {
        if (folderUri != null) {
            try {
                val folder = DocumentFile.fromTreeUri(context, folderUri)
                val file = folder?.findFile(fileName)
                if (file != null && file.exists()) {
                    return open(context, file.uri, file.name ?: fileName, cacheDir, progress, isActive)
                }
            } catch (_: Exception) {
            }
        }

        val sibling = siblingUri(dirUri, fileName)
        if (sibling != null) {
            try {
                return open(context, sibling, fileName, cacheDir, progress, isActive)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        throw VpkMissingArchiveException(fileName)
    }

    fun resolveVpkDirUri(
        context: Context,
        selectedUri: Uri,
        selectedName: String,
        folderUri: Uri?
    ): Pair<Uri, String>? {
        val index = com.gmodkit.tools.core.VpkArchive.numberedIndex(selectedName) ?: return null
        val base = selectedName.substringBeforeLast('_')
        val dirName = "${base}_dir.vpk"
        if (folderUri != null) {
            try {
                val folder = DocumentFile.fromTreeUri(context, folderUri)
                val file = folder?.findFile(dirName)
                if (file != null && file.exists()) {
                    return file.uri to (file.name ?: dirName)
                }
            } catch (_: Exception) {
            }
        }
        val sibling = siblingUri(selectedUri, dirName) ?: return null
        return try {
            // The URI is only used if it can actually be opened later.
            sibling to dirName
        } catch (_: Exception) {
            null
        }
    }
}

private class DescriptorSource(
    private val descriptor: ParcelFileDescriptor,
    override val name: String
) : SeekableSource {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val channel: FileChannel = input.channel

    override val size: Long
        get() = try {
            channel.size()
        } catch (_: IOException) {
            descriptor.statSize
        }

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
            input.close()
        } catch (_: Exception) {
        }
        try {
            descriptor.close()
        } catch (_: Exception) {
        }
    }
}

private class TempFileSource(
    private val temp: File,
    name: String
) : SeekableSource {
    private val delegate = FileSource(temp, name)

    override val size: Long
        get() = delegate.size

    override val name: String
        get() = delegate.name

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.readAt(position, buffer, offset, length)
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            try {
                temp.delete()
            } catch (_: Exception) {
            }
        }
    }
}
