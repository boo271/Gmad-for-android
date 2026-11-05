package com.gmodkit.tools.network

import com.gmodkit.tools.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

data class UpdateRelease(
    val version: String,
    val downloadUrl: String,
    val releaseUrl: String
)

object UpdateChecker {
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    fun fetchLatest(): UpdateRelease {
        val connection = Http.open(BuildConfig.UPDATE_API_URL)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP $code.")
            }
            val body = Http.readText(connection, MAX_RESPONSE_BYTES)
            return parseRelease(body)
                ?: throw IOException("The latest release does not include an APK.")
        } finally {
            connection.disconnect()
        }
    }

    fun parseRelease(json: String): UpdateRelease? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null

        val version = root.optString("tag_name").trim()
            .removePrefix("v")
            .removePrefix("V")
        if (version.isEmpty()) return null

        val releaseUrl = root.optString("html_url").trim()
        var apkUrl = ""
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").trim()
                if (apkUrl.isNotEmpty()) break
            }
        }

        val downloadUrl = apkUrl.ifEmpty { releaseUrl }
        if (downloadUrl.isEmpty()) return null
        return UpdateRelease(version, downloadUrl, releaseUrl)
    }

    fun isNewer(remote: String, current: String): Boolean =
        compareVersions(remote, current) > 0

    fun compareVersions(left: String, right: String): Int {
        val a = versionParts(left)
        val b = versionParts(right)
        val count = maxOf(a.size, b.size)
        for (i in 0 until count) {
            val l = a.getOrElse(i) { 0 }
            val r = b.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun versionParts(value: String): List<Int> {
        val clean = value.trim().removePrefix("v").removePrefix("V")
        if (clean.isEmpty()) return emptyList()
        return clean.split('.', '-', '+', '_').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
