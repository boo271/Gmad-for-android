package com.gmodkit.tools.network

import com.gmodkit.tools.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

object Http {
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 25_000
    private const val MAX_REDIRECTS = 4

    /** URLs fetched from the internet: HTTPS only. */
    fun isAllowedUrl(url: String): Boolean {
        val parts = parse(url) ?: return false
        return parts.scheme == "https"
    }

    /**
     * URLs for the optional Workshop resolver, which normally runs on the user's
     * own machine: HTTPS, plus plain HTTP to loopback or a private network
     * address. Anything reachable from the internet still requires HTTPS.
     */
    fun isAllowedLocalUrl(url: String): Boolean {
        val parts = parse(url) ?: return false
        if (parts.scheme == "https") return true
        if (parts.scheme != "http") return false
        val host = parts.host ?: return false
        return host == "127.0.0.1" || host == "localhost" ||
            host == "::1" || host == "[::1]" || isPrivateHost(host)
    }

    /**
     * Matches the address ranges a resolver would use on a home network, so a
     * helper running on the user's own machine can be reached without HTTPS.
     */
    private fun isPrivateHost(host: String): Boolean {
        if (host.endsWith(".local")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    private data class UrlParts(val scheme: String?, val host: String?)

    private fun parse(url: String): UrlParts? {
        val uri = try {
            // java.net.URI rather than android.net.Uri so the policy is
            // unit-testable off-device.
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase(Locale.US) ?: return null
        return UrlParts(uri.scheme?.lowercase(Locale.US), host)
    }

    fun open(url: String, method: String = "GET"): HttpURLConnection =
        open(url, method, ::isAllowedUrl)

    fun openLocal(url: String, method: String = "GET"): HttpURLConnection =
        open(url, method, ::isAllowedLocalUrl)

    private fun open(url: String, method: String, allowed: (String) -> Boolean): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(current)) {
                throw IOException(
                    "This URL is not allowed. Use an HTTPS link, or a resolver on your own network."
                )
            }
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "Gmad/${BuildConfig.VERSION_NAME} (Android)")
            connection.setRequestProperty("Accept", "*/*")

            if (method == "GET" || method == "HEAD") {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) {
                        throw IOException("The server returned an unexpected redirect.")
                    }
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
            }
            return connection
        }
        throw IOException("Too many redirects.")
    }

    fun readText(connection: HttpURLConnection, maxBytes: Int = 512 * 1024): String {
        return connection.useConnection { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("The server response is too large.")
                output.write(buffer, 0, read)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }

    fun readBytes(connection: HttpURLConnection, maxBytes: Int = 4 * 1024 * 1024): ByteArray =
        connection.useConnection { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("The server sent more data than expected.")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }

    fun errorText(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: return ""
        return try {
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 64 * 1024) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    inline fun <T> HttpURLConnection.useConnection(block: (InputStream) -> T): T {
        try {
            if (responseCode !in 200..299) {
                throw IOException("Server returned HTTP $responseCode")
            }
            return inputStream.use(block)
        } finally {
            disconnect()
        }
    }
}
