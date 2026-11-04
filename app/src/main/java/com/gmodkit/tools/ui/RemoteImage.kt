package com.gmodkit.tools.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.gmodkit.tools.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

private const val CACHE_BYTES = 12 * 1024 * 1024

private val imageCache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * Shows the Workshop item's preview picture.
 *
 * Steam's URL policy is the one the rest of the app already uses, so a preview
 * can never reach a host the downloader would refuse. A picture is decoration,
 * so every failure here stays silent rather than replacing an error message
 * that already explains the download.
 */
@Composable
fun RemotePreviewImage(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val httpsUrl = remember(url) { toHttps(url) }
    if (httpsUrl == null || !Http.isAllowedUrl(httpsUrl)) return

    var widthPx by remember(url) { mutableIntStateOf(0) }
    var heightPx by remember(url) { mutableIntStateOf(0) }
    var bitmap by remember(url) { mutableStateOf(imageCache.get(httpsUrl)) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, widthPx, heightPx) {
        if (bitmap != null || failed || widthPx <= 0) return@LaunchedEffect
        val decoded = loadBitmap(httpsUrl, widthPx, heightPx)
        if (decoded == null) {
            failed = true
        } else {
            imageCache.put(httpsUrl, decoded)
            bitmap = decoded
        }
    }

    if (failed) return

    val shape = RoundedCornerShape(10.dp)
    val boxModifier = modifier
        .aspectRatio(16f / 9f)
        .clip(shape)
        .onSizeChanged {
            widthPx = it.width
            heightPx = it.height
        }
    val loaded = bitmap
    if (loaded == null) {
        Box(
            modifier = boxModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp
            )
        }
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = boxModifier
        )
    }
}

private fun toHttps(url: String): String? {
    val trimmed = url.trim()
    return when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        // Steam still hands out a few plain-http preview links. Upgrading them
        // keeps the HTTPS-only rule instead of breaking on those items.
        trimmed.startsWith("http://", ignoreCase = true) ->
            "https://" + trimmed.substring("http://".length)
        else -> null
    }
}

private suspend fun loadBitmap(url: String, maxWidthPx: Int, maxHeightPx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val bytes = Http.readBytes(Http.open(url), MAX_IMAGE_BYTES)
            decodeSampled(bytes, maxWidthPx, maxHeightPx)
        } catch (_: Exception) {
            null
        }
    }

/**
 * Decodes at no more than the box will draw. Sampling by width alone would
 * keep a tall picture at full height, and one oversized bitmap is enough to
 * end the app on a low-memory phone.
 */
private fun decodeSampled(bytes: ByteArray, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val maxPx = if (maxHeightPx > 0) maxHeightPx else maxWidthPx * 9 / 16
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidthPx ||
        bounds.outHeight / (sample * 2) >= maxPx
    ) {
        sample *= 2
    }

    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
    )
}
