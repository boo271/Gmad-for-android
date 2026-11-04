package com.gmodkit.tools.network

import com.gmodkit.tools.core.ProgressSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Locale
import java.util.concurrent.CancellationException

data class WorkshopInfo(
    val id: String,
    val title: String,
    val creator: String,
    val fileSize: Long,
    val updatedAt: Long,
    val description: String,
    val previewUrl: String,
    val directUrl: String?,
    val fileName: String?,
    val workshopUrl: String,
    /** Depot manifest id of the addon file, or 0 when Steam lists no content. */
    val manifestGid: Long,
    /** Game the item belongs to; GMod's addon content sits in this app's depot. */
    val appId: Int
)

class WorkshopException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WorkshopClient {

    suspend fun fetch(input: String): WorkshopInfo = withContext(Dispatchers.IO) {
        val id = parseId(input)
        val body = "itemcount=1&publishedfileids%5B0%5D=$id"
        val connection = Http.open(STEAM_API, "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = Http.errorText(connection).take(300)
                throw WorkshopException(
                    "Steam returned HTTP $code.${if (detail.isBlank()) "" else "\n$detail"}"
                )
            }

            val text = Http.readText(connection, 1_000_000)
            val root = JSONObject(text)
            val response = root.optJSONObject("response")
                ?: throw WorkshopException("Steam returned an unexpected response.")
            val details = response.optJSONArray("publishedfiledetails")
                ?: throw WorkshopException("Steam returned no item details.")
            if (details.length() == 0) {
                throw WorkshopException("Steam could not find that Workshop item.")
            }

            val item = details.getJSONObject(0)
            val result = item.optInt("result", 0)
            if (result != 1) {
                throw WorkshopException(
                    "Steam could not return this item (result $result). It may be private or removed."
                )
            }

            val rawFileUrl = item.optString("file_url").trim()
            val directUrl = if (rawFileUrl.isNotEmpty() && Http.isAllowedUrl(rawFileUrl)) {
                rawFileUrl
            } else {
                null
            }

            WorkshopInfo(
                id = id,
                title = item.optString("title").ifBlank { "Workshop item $id" },
                creator = item.optString("creator"),
                fileSize = item.optLong("file_size", 0L),
                updatedAt = item.optLong("time_updated", 0L) * 1000L,
                description = cleanWorkshopDescription(item.optString("description")),
                previewUrl = item.optString("preview_url"),
                directUrl = directUrl,
                fileName = item.optString("filename").ifBlank { null },
                workshopUrl = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id",
                manifestGid = item.optString("hcontent_file").trim().toLongOrNull() ?: 0L,
                appId = item.optInt("consumer_app_id", 0)
            )
        } catch (e: WorkshopException) {
            throw e
        } catch (e: Exception) {
            throw WorkshopException("Could not reach Steam: ${e.message}", e)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Optional resolver contract:
     *
     *   POST {base}/resolve
     *   Content-Type: application/json
     *   {"url":"https://steamcommunity.com/sharedfiles/filedetails/?id=123"}
     *
     *   Response: {"download_url":"https://.../addon.gma"}
     *
     * The response may also wrap the URL in a "data" object. The app never
     * sends Steam credentials and never receives them.
     */
    suspend fun resolve(apiBase: String, workshopUrl: String): String? = withContext(Dispatchers.IO) {
        val base = apiBase.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext null
        if (!Http.isAllowedLocalUrl(base)) {
            throw WorkshopException("The resolver URL must use HTTPS or a private network address.")
        }

        val endpoint = "$base/resolve"
        if (!Http.isAllowedLocalUrl(endpoint)) {
            throw WorkshopException("The resolver URL must use HTTPS or a private network address.")
        }

        val connection = Http.openLocal(endpoint, "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val body = JSONObject().put("url", workshopUrl).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = Http.errorText(connection).take(300)
                throw WorkshopException(
                    "The resolver returned HTTP $code.${if (detail.isBlank()) "" else "\n$detail"}"
                )
            }

            val text = Http.readText(connection, 64 * 1024)
            val root = JSONObject(text)
            val data = root.optJSONObject("data")
            val url = root.optString("download_url").ifBlank {
                root.optString("url").ifBlank {
                    data?.optString("download_url").orEmpty()
                }
            }.trim()

            if (url.isEmpty()) {
                throw WorkshopException("The resolver did not return a download URL.")
            }
            if (!Http.isAllowedLocalUrl(url)) {
                throw WorkshopException("The resolver returned a URL that is not allowed.")
            }
            url
        } catch (e: WorkshopException) {
            throw e
        } catch (e: Exception) {
            throw WorkshopException("Could not contact the resolver: ${e.message}", e)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun download(
        url: String,
        destination: File,
        progress: ProgressSink,
        isActive: () -> Boolean
    ): Long = withContext(Dispatchers.IO) {
        // Accepts HTTPS everywhere, plus plain HTTP only for a resolver on the
        // user's own network (see Http.isAllowedLocalUrl).
        if (!Http.isAllowedLocalUrl(url)) {
            throw WorkshopException("The download URL must use HTTPS.")
        }

        val connection = Http.openLocal(url, "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw WorkshopException("The download server returned HTTP $code.")
            }

            val contentType = connection.contentType.orEmpty().lowercase(Locale.US)
            if (contentType.startsWith("text/html")) {
                throw WorkshopException(
                    "The download server returned a web page instead of an addon."
                )
            }

            val expected = connection.contentLengthLong
            if (expected > MAX_DOWNLOAD) {
                throw WorkshopException("This addon is too large to download safely.")
            }

            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!isActive()) throw CancellationException("Cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD) {
                            throw WorkshopException("This addon is too large to download safely.")
                        }
                        output.write(buffer, 0, read)
                        if (expected > 0) progress.onProgress(total, expected)
                    }
                }
            }
            total
        } catch (e: WorkshopException) {
            try {
                destination.delete()
            } catch (_: Exception) {
            }
            throw e
        } catch (e: CancellationException) {
            try {
                destination.delete()
            } catch (_: Exception) {
            }
            throw e
        } catch (e: IOException) {
            try {
                destination.delete()
            } catch (_: Exception) {
            }
            throw WorkshopException("The download failed: ${e.message}", e)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseId(input: String): String {
        val value = input.trim()
        if (value.matches(Regex("\\d{3,20}"))) return value
        val match = Regex("[?&]id=(\\d{3,20})").find(value)
        if (match != null) return match.groupValues[1]
        throw WorkshopException("Enter a Steam Workshop item URL or numeric ID.")
    }

    companion object {
        private const val STEAM_API =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
        private const val MAX_DOWNLOAD = 4L * 1024 * 1024 * 1024
    }
}

private val LINK_WITH_LABEL = Regex("\\[url=(.*?)](.*?)\\[/url]", RegexOption.IGNORE_CASE)
private val LINK_WITHOUT_LABEL = Regex("\\[url=([^]]*)]", RegexOption.IGNORE_CASE)
private val BARE_LINK_TAG = Regex("\\[/?(?:url|a)(?:=[^]]*)?]", RegexOption.IGNORE_CASE)
private val OTHER_TAGS = Regex("\\[[^\\]\\n]{1,30}]")
private val NUMERIC_ENTITY = Regex("&#(\\d{1,6});")
private val BLANK_LINES = Regex("\n{3,}")
private val TRAILING_SPACES = Regex("[ \\t]+(?=\n)")

/**
 * Steam ships Workshop descriptions as forum BBCode with HTML entities, which
 * reads as noise in a plain text label. Links keep their target because the
 * text is not clickable here.
 */
internal fun cleanWorkshopDescription(raw: String): String {
    var text = LINK_WITH_LABEL.replace(raw) { match ->
        val target = match.groupValues[1].trim()
        val label = match.groupValues[2].trim()
        if (label.isEmpty() || label == target) target else "$label ($target)"
    }
    text = LINK_WITHOUT_LABEL.replace(text) { match -> " ${match.groupValues[1].trim()} " }
    text = BARE_LINK_TAG.replace(text, " ")
    text = OTHER_TAGS.replace(text, "")
    text = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
    text = NUMERIC_ENTITY.replace(text) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code == null || !Character.isDefined(code)) match.value else String(Character.toChars(code))
    }
    text = TRAILING_SPACES.replace(text, "")
    return BLANK_LINES.replace(text, "\n\n").trim()
}

