package com.gmodkit.tools.steam

import com.gmodkit.tools.core.u32le
import com.gmodkit.tools.network.WorkshopException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

internal object SteamEm {
    const val MULTI = 1
    const val SERVICE_METHOD = 151
    const val SERVICE_METHOD_RESPONSE = 147
    const val CLIENT_LOG_ON_RESPONSE = 751
    const val CHANNEL_ENCRYPT_REQUEST = 1303
    const val CHANNEL_ENCRYPT_RESPONSE = 1304
    const val CHANNEL_ENCRYPT_RESULT = 1305
    const val CLIENT_LOGON = 5514
    const val CLIENT_GET_DEPOT_DECRYPTION_KEY = 5438
    const val CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE = 5439
}

private const val PROTO_HEADER_BIT = 0x80000000L
private const val LEGACY_HEADER_SIZE = 20
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 20_000
private const val REPLY_TIMEOUT_MS = 40_000
private const val MAX_FRAME = 64 * 1024 * 1024
private const val MAX_GUNZIP_OUTPUT = 8L * 1024 * 1024

/** EResult.AccessDenied: the depot exists, but its licence is not visible to us. */
private const val RESULT_ACCESS_DENIED = 15L

/**
 * Steam answers this for depots it only serves to accounts that own the game, and
 * no other connection manager will answer differently, so the caller must not retry.
 */
class DepotAccessDeniedException(val appId: Int) : Exception(
    "Steam only gives app $appId's files to accounts that own that game, and this app signs in " +
        "without an account. Try an addon published for Garry's Mod instead."
)

/**
 * Talks to one Steam connection manager just long enough to be allowed to read
 * a depot: anonymous logon, manifest request code, depot key, then hang up.
 *
 * Everything after that is plain HTTPS against a content server, so nothing is
 * kept and no account is involved.
 */
internal class SteamSession(
    private val host: String,
    private val port: Int,
    private val isActive: () -> Boolean = { true }
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var keys: ChannelKeys? = null
    private var jobCounter = 0L
    private var steamId = ANONYMOUS_STEAM_ID
    private var sessionId = 0L
    private val backlog = ArrayList<SteamMessage>()

    /** Cell id from the logon reply, used to ask for nearby content servers. */
    var cellId = 0
        private set

    fun open() {
        // Not an apply block: Socket exposes a read-only `port`, which would shadow
        // this session's port and silently connect to port 0.
        val connected = Socket()
        connected.tcpNoDelay = true
        connected.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        connected.soTimeout = READ_TIMEOUT_MS
        socket = connected
        input = DataInputStream(BufferedInputStream(connected.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(connected.getOutputStream()))
        secureChannel()
    }

    /** Logs on as an anonymous user, which is what SteamCMD does for public content. */
    fun logOnAnonymously() {
        val body = ProtoWriter()
            .varint(1, PROTOCOL_VERSION)
            .varint(5, CLIENT_PACKAGE_VERSION)
            .toByteArray()
        val header = ProtoWriter()
            .fixed64(1, ANONYMOUS_STEAM_ID)
            .toByteArray()
        sendMessage(SteamEm.CLIENT_LOGON, header, body)

        val response = await(SteamEm.CLIENT_LOG_ON_RESPONSE, 0L)
        val result = ProtoReader.varint(response.body, 1)
        if (result != 1L) {
            throw WorkshopException("Steam refused the anonymous logon (result $result).")
        }
        // The CM hands back the identity it gave this connection; every message
        // after the logon has to carry it, or the CM cannot route it to a session.
        steamId = ProtoReader.fixed64(response.header, 1)
        sessionId = ProtoReader.varint(response.header, 2)
        cellId = ProtoReader.varint(response.body, 7).toInt()
    }

    /**
     * The CDN rejects manifest reads without this code, but it keeps accepting
     * the code after the session that produced it has closed.
     */
    fun manifestRequestCode(appId: Int, depotId: Int, manifestGid: Long): Long {
        val job = nextJob()
        val header = messageHeader(job, "$MANIFEST_CODE_METHOD#1")
        val request = ProtoWriter()
            .varint(1, appId.toLong())
            .varint(2, depotId.toLong())
            .varint(3, manifestGid)
            .toByteArray()
        sendMessage(SteamEm.SERVICE_METHOD, header, request)

        val response = await(SteamEm.SERVICE_METHOD_RESPONSE, job)
        val result = ProtoReader.varint(response.header, 13)
        if (result == RESULT_ACCESS_DENIED) throw DepotAccessDeniedException(appId)
        if (result != 0L && result != 1L) {
            throw WorkshopException("Steam would not issue a manifest request code (result $result).")
        }
        val code = ProtoReader.varint(response.body, 1)
        if (code == 0L) {
            throw WorkshopException("Steam returned an empty manifest request code.")
        }
        return code
    }

    fun depotDecryptionKey(appId: Int, depotId: Int): ByteArray {
        val job = nextJob()
        val request = ProtoWriter()
            .varint(1, depotId.toLong())
            .varint(2, appId.toLong())
            .toByteArray()
        sendMessage(
            SteamEm.CLIENT_GET_DEPOT_DECRYPTION_KEY,
            messageHeader(job),
            request
        )

        val response = await(SteamEm.CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE, job)
        val result = ProtoReader.varint(response.body, 1)
        if (result == RESULT_ACCESS_DENIED) throw DepotAccessDeniedException(appId)
        if (result != 1L) {
            throw WorkshopException("Steam did not return a key for this depot (result $result).")
        }
        val key = ProtoReader.bytes(response.body, 3)
        if (key.size != 32) {
            throw WorkshopException("Steam returned a depot key of an unexpected length.")
        }
        return key
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
        keys = null
        backlog.clear()
    }

    private fun secureChannel() {
        val request = await(SteamEm.CHANNEL_ENCRYPT_REQUEST, 0L)
        val payload = request.body
        if (payload.size < 8) throw IOException("The channel handshake is too short.")
        if (payload.u32le(0) != 1L) throw IOException("Unsupported channel protocol version.")
        if (payload.u32le(4) != 1L) throw IOException("Unsupported Steam universe.")
        val challenge = if (payload.size > 8) payload.copyOfRange(8, payload.size) else ByteArray(0)

        val session = SteamCrypto.createChannelKeys(challenge)
        val encryptedKey = session.encryptedSessionKey
        if (encryptedKey.size != 128) {
            throw IOException("The session key does not fit the handshake.")
        }

        val body = le32(1) + le32(encryptedKey.size.toLong()) + encryptedKey +
            le32(SteamCrypto.crc32(encryptedKey)) + le32(0)
        writeFrame(legacyHeader(SteamEm.CHANNEL_ENCRYPT_RESPONSE) + body)

        // The result itself still arrives in plain text; the channel only becomes
        // encrypted once it has been accepted.
        val result = await(SteamEm.CHANNEL_ENCRYPT_RESULT, 0L)
        if (result.body.size < 4 || result.body.u32le(0) != 1L) {
            throw IOException("Steam would not secure the connection.")
        }
        keys = session
    }

    /** A message header, stamped with the identity the CM gave this connection. */
    private fun messageHeader(job: Long, methodName: String = ""): ByteArray = ProtoWriter()
        .fixed64(1, steamId)
        .varint(2, sessionId)
        .fixed64(10, job)
        .string(12, methodName)
        .toByteArray()

    private fun sendMessage(emsg: Int, header: ByteArray, body: ByteArray) {
        val payload = le32(emsg.toLong() or PROTO_HEADER_BIT) +
            le32(header.size.toLong()) + header + body
        val session = keys
        writeFrame(if (session == null) payload else SteamCrypto.encrypt(payload, session))
    }

    private fun writeFrame(payload: ByteArray) {
        val stream = output ?: throw IOException("The session is not connected.")
        stream.write(le32(payload.size.toLong()) + FRAME_MAGIC + payload)
        stream.flush()
    }

    private fun readFrame(): ByteArray {
        val stream = input ?: throw IOException("The session is not connected.")
        val header = ByteArray(8).also { stream.readFully(it) }
        if (!header.copyOfRange(4, 8).contentEquals(FRAME_MAGIC)) {
            throw IOException("The connection manager sent an unexpected frame.")
        }
        val length = header.u32le(0).toInt()
        if (length < 0 || length > MAX_FRAME) {
            throw IOException("The connection manager sent an oversized frame.")
        }
        val payload = ByteArray(length).also { stream.readFully(it) }
        val session = keys
        return if (session == null) payload else SteamCrypto.decrypt(payload, session)
    }

    /** Blocks until a message with this EMsg (and job, when given) shows up. */
    private fun await(emsg: Int, jobId: Long): SteamMessage {
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        while (true) {
            if (!isActive()) throw CancellationException("Cancelled")
            val index = backlog.indexOfFirst { it.matches(emsg, jobId) }
            if (index >= 0) return backlog.removeAt(index)

            val payload = try {
                readFrame()
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() > deadline) {
                    throw WorkshopException("Steam did not answer in time.")
                }
                continue
            }
            unpack(decodeMessage(payload), backlog)
        }
    }

    private fun decodeMessage(data: ByteArray): SteamMessage {
        if (data.size < 8) throw IOException("Message is too short.")
        val raw = data.u32le(0)
        if (raw and PROTO_HEADER_BIT != 0L) {
            val headerLength = data.u32le(4).toInt()
            if (headerLength < 0 || 8 + headerLength > data.size) {
                throw IOException("Message header length is out of range.")
            }
            return SteamMessage(
                emsg = (raw and 0x7FFFFFFFL).toInt(),
                header = data.copyOfRange(8, 8 + headerLength),
                body = data.copyOfRange(8 + headerLength, data.size)
            )
        }
        if (data.size < LEGACY_HEADER_SIZE) throw IOException("Legacy message header is truncated.")
        return SteamMessage(
            emsg = raw.toInt(),
            header = data.copyOfRange(0, LEGACY_HEADER_SIZE),
            body = data.copyOfRange(LEGACY_HEADER_SIZE, data.size)
        )
    }

    /** Flattens CMsgMulti batches, which is how CMs deliver anything in bulk. */
    private fun unpack(message: SteamMessage, into: ArrayList<SteamMessage>) {
        if (message.emsg != SteamEm.MULTI) {
            into.add(message)
            return
        }
        var sizeUnzipped = 0L
        var payload = ByteArray(0)
        val reader = ProtoReader(message.body)
        while (reader.hasNext()) {
            val field = reader.readField()
            if (field.number == 1 && field.wireType == ProtoReader.WIRE_VARINT) {
                sizeUnzipped = reader.readVarint()
            } else if (field.number == 2 && field.wireType == ProtoReader.WIRE_LENGTH) {
                payload = reader.readBytes()
            } else {
                reader.readValue(field)
            }
        }
        if (sizeUnzipped > 0) payload = gunzip(payload)

        var position = 0
        while (position + 4 <= payload.size) {
            val size = payload.u32le(position).toInt()
            position += 4
            if (size <= 0 || position + size > payload.size) break
            unpack(decodeMessage(payload.copyOfRange(position, position + size)), into)
            position += size
        }
    }

    private fun gunzip(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { stream ->
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_GUNZIP_OUTPUT) throw IOException("Steam sent an oversized batch.")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun nextJob(): Long {
        jobCounter += 1
        return jobCounter
    }

    private class SteamMessage(val emsg: Int, val header: ByteArray, val body: ByteArray) {
        fun matches(expected: Int, jobId: Long): Boolean {
            if (emsg != expected) return false
            if (jobId == 0L) return true
            return ProtoReader.fixed64(header, 11) == jobId
        }
    }

    companion object {
        private const val MANIFEST_CODE_METHOD = "ContentServerDirectory.GetManifestRequestCode"
        private const val PROTOCOL_VERSION = 65580L
        private const val CLIENT_PACKAGE_VERSION = 1561159470L
        private const val ANONYMOUS_STEAM_ID = 117093590311632896L
        private val FRAME_MAGIC = byteArrayOf(
            'V'.code.toByte(),
            'T'.code.toByte(),
            '0'.code.toByte(),
            '1'.code.toByte()
        )

        private fun le32(value: Long): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        )

        private fun le64(value: Long): ByteArray = ByteArray(8).also { bytes ->
            var remaining = value
            for (i in 0 until 8) {
                bytes[i] = (remaining and 0xFF).toByte()
                remaining = remaining ushr 8
            }
        }

        /** Old-style header: u32 emsg, i64 target job, i64 source job. */
        private fun legacyHeader(emsg: Int): ByteArray =
            le32(emsg.toLong()) + le64(-1L) + le64(-1L)
    }
}
