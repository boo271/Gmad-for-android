package com.gmodkit.tools.network

import org.json.JSONObject
import java.io.IOException

internal data class CmServer(val host: String, val port: Int)

/**
 * Valve's public directory endpoints. They need no key, no account and no
 * cookie: the same calls any browser can make, used here to find a connection
 * manager and the content servers nearest to this device.
 */
internal object SteamDirectory {
    private const val CM_LIST =
        "https://api.steampowered.com/ISteamDirectory/GetCMList/v1/?cellid="
    private const val CONTENT_SERVERS =
        "https://api.steampowered.com/IContentServerDirectoryService/GetServersForSteamPipe/v1/" +
            "?cells="
    private const val CDN_SUFFIX = ".steamcontent.com"

    fun cmServers(cellId: Int): List<CmServer> {
        val response = json(CM_LIST + cellId).optJSONObject("response")
            ?: throw IOException("Steam's server list is not in the expected shape.")
        val list = response.optJSONArray("serverlist") ?: return emptyList()
        val servers = ArrayList<CmServer>(list.length())
        for (i in 0 until list.length()) {
            val entry = list.optString(i)
            val split = entry.lastIndexOf(':')
            val host = if (split > 0) entry.substring(0, split) else entry
            val port = if (split > 0) entry.substring(split + 1).toIntOrNull() ?: 27017 else 27017
            if (host.isNotEmpty()) servers.add(CmServer(host, port))
        }
        return servers
    }

    /** HTTPS-only content servers, in the order Valve suggests. */
    fun cdnHosts(cellId: Int): List<String> {
        val response = json(CONTENT_SERVERS + cellId + "&version=1").optJSONObject("response")
            ?: throw IOException("Steam's content server list is not in the expected shape.")
        val servers = response.optJSONArray("servers") ?: return emptyList()
        val hosts = ArrayList<String>()
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val host = server.optString("host").trim().lowercase(java.util.Locale.US)
            val https = server.optString("https_support")
            if (host.isEmpty() || https == "disabled") continue
            // Only Valve's own CDN hosts, and only over HTTPS.
            if (!host.endsWith(CDN_SUFFIX)) continue
            if (!hosts.contains(host)) hosts.add(host)
        }
        return hosts
    }

    private fun json(url: String): JSONObject {
        val connection = Http.open(url, "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Steam's directory service returned HTTP $code.")
            return JSONObject(Http.readText(connection, 512 * 1024))
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
