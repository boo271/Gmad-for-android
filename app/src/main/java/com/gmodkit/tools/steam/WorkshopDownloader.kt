package com.gmodkit.tools.steam

import com.gmodkit.tools.core.ProgressSink
import com.gmodkit.tools.network.Http
import com.gmodkit.tools.network.SteamDirectory
import com.gmodkit.tools.network.WorkshopException
import com.gmodkit.tools.network.WorkshopInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GMOD_APP_ID = 4000
private const val MAX_ADDON_BYTES = 4L * 1024 * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
private const val MAX_CHUNK_CONTAINER = 16L * 1024 * 1024
private const val CHUNK_ATTEMPTS = 3
private const val MANIFEST_ATTEMPTS = 4

/**
 * Downloads a Workshop addon with nothing but the phone's internet connection.
 *
 * The route is the one SteamCMD uses for public content: an anonymous session
 * with a connection manager buys a manifest request code and a depot key, then
 * the bytes themselves come over ordinary HTTPS from Valve's content servers.
 * Every chunk is checked against the SHA-1 written in the manifest, so the
 * assembled file is exactly what Steam holds or the download fails.
 */
class WorkshopDownloader {

    suspend fun download(
        info: WorkshopInfo,
        destination: File,
        progress: ProgressSink,
        isActive: () -> Boolean = { true }
    ): Long = withContext(Dispatchers.IO) {
        if (info.manifestGid <= 0L) {
            throw WorkshopException(
                "Steam does not list any depot content for this item, so it cannot be downloaded here."
            )
        }
        // GMod keeps addon content in the depot that shares the app's id.
        val appId = if (info.appId > 0) info.appId else GMOD_APP_ID
        val depotId = appId

        val access = negotiate(info.manifestGid, appId, depotId, isActive)
        val hosts = cdnHosts(access.cellId)
        val manifestBytes = fetchManifest(hosts, depotId, info.manifestGid, access.code, isActive)
        val manifest = DepotManifest.parse(manifestBytes, access.depotKey)
        val file = DepotManifest.pickContent(manifest, info.fileName)
        if (file.size <= 0L || file.size > MAX_ADDON_BYTES) {
            throw WorkshopException("This addon claims a size that cannot be downloaded safely.")
        }

        RandomAccessFile(destination, "rw").use { target ->
            var done = 0L
            val chunks = file.chunks.sortedBy { it.offset }
            for ((number, chunk) in chunks.withIndex()) {
                if (!isActive()) throw CancellationException("Cancelled")
                val data = fetchChunk(hosts, depotId, chunk, access.depotKey, number, isActive)
                if (chunk.offset + data.size > file.size) {
                    throw WorkshopException("A chunk falls outside the file it belongs to.")
                }
                target.seek(chunk.offset)
                target.write(data)
                done += data.size
                progress.onProgress(done, file.size)
            }
            if (done != file.size) {
                throw WorkshopException(
                    "The addon came apart short of its full size ($done of ${file.size} bytes)."
                )
            }
        }
        file.size
    }

    /** Anonymous logon, request code and depot key, trying managers until one answers. */
    private fun negotiate(
        gid: Long,
        appId: Int,
        depotId: Int,
        isActive: () -> Boolean
    ): DepotAccess {
        val servers = try {
            SteamDirectory.cmServers(0)
        } catch (e: Exception) {
            throw WorkshopException("Could not reach Steam: ${e.message}", e)
        }
        if (servers.isEmpty()) throw WorkshopException("Steam returned no servers to talk to.")

        var lastFailure: Exception? = null
        for (server in servers.take(6)) {
            if (!isActive()) throw CancellationException("Cancelled")
            val session = SteamSession(server.host, server.port, isActive)
            try {
                session.open()
                session.logOnAnonymously()
                return DepotAccess(
                    code = session.manifestRequestCode(appId, depotId, gid),
                    depotKey = session.depotDecryptionKey(appId, depotId),
                    cellId = session.cellId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DepotAccessDeniedException) {
                // Every manager answers the same way for a game we do not own, so
                // retrying would only make the user wait for the same refusal.
                throw e
            } catch (e: Exception) {
                lastFailure = e
            } finally {
                session.close()
            }
        }
        val detail = lastFailure?.message?.let { ": $it" } ?: "."
        throw WorkshopException("Steam's content network did not accept the anonymous session$detail")
    }

    private fun cdnHosts(cellId: Int): List<String> {
        val nearby = try {
            SteamDirectory.cdnHosts(cellId)
        } catch (_: Exception) {
            emptyList()
        }
        val hosts = if (nearby.isNotEmpty()) nearby else try {
            SteamDirectory.cdnHosts(0)
        } catch (_: Exception) {
            emptyList()
        }
        if (hosts.isEmpty()) throw WorkshopException("Steam returned no content servers to download from.")
        return hosts
    }

    private fun fetchManifest(
        hosts: List<String>,
        depotId: Int,
        gid: Long,
        code: Long,
        isActive: () -> Boolean
    ): ByteArray {
        var lastStatus = 0
        for (host in hosts.take(MANIFEST_ATTEMPTS)) {
            if (!isActive()) throw CancellationException("Cancelled")
            val url = "https://$host/depot/$depotId/manifest/${unsignedToString(gid)}/5/${unsignedToString(code)}"
            try {
                val connection = Http.open(url, "GET")
                val status = connection.responseCode
                if (status in 200..299) {
                    return readAll(connection.inputStream, MAX_MANIFEST_BYTES)
                }
                lastStatus = status
                connection.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        if (lastStatus == 401 || lastStatus == 403) {
            throw WorkshopException(
                "Steam would not hand over this addon. It may be private, banned or age-restricted."
            )
        }
        val detail = if (lastStatus > 0) " (HTTP $lastStatus)." else "."
        throw WorkshopException("Could not fetch the addon's file list from Steam$detail")
    }

    private fun fetchChunk(
        hosts: List<String>,
        depotId: Int,
        chunk: DepotChunk,
        depotKey: ByteArray,
        number: Int,
        isActive: () -> Boolean
    ): ByteArray {
        val id = chunk.sha1.toHex()
        var failure: Exception? = null
        repeat(CHUNK_ATTEMPTS) { attempt ->
            if (!isActive()) throw CancellationException("Cancelled")
            val host = hosts[(number + attempt) % hosts.size]
            val url = "https://$host/depot/$depotId/chunk/$id"
            try {
                val connection = Http.open(url, "GET")
                val status = connection.responseCode
                if (status in 200..299) {
                    val container = readAll(connection.inputStream, MAX_CHUNK_CONTAINER)
                    return ChunkDecoder.decode(container, depotKey, chunk.sha1)
                }
                failure = IOException("HTTP $status")
                connection.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw WorkshopException("A chunk could not be downloaded from Steam: ${failure?.message}")
    }

    private fun readAll(input: InputStream, maxBytes: Long): ByteArray {
        input.use { stream ->
            val out = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("The server sent more data than expected.")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

private class DepotAccess(
    val code: Long,
    val depotKey: ByteArray,
    val cellId: Int
)

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value shr 4])
        out.append(digits[value and 0x0F])
    }
    return out.toString()
}
