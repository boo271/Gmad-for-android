package com.gmodkit.tools.steam

import com.gmodkit.tools.core.GmaArchive
import com.gmodkit.tools.core.FileSource
import com.gmodkit.tools.core.ProgressSink
import com.gmodkit.tools.network.WorkshopClient
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A public Workshop item downloaded the way a phone would do it: Steam's web
 * API, an anonymous session with a connection manager, then HTTPS chunks.
 *
 * Off by default because it needs the network. Run it with
 * `./gradlew :app:testDebugUnitTest -Pgmad.live=1`.
 */
class WorkshopLiveTest {

    @Test
    fun downloadsAPublicAddonAndOpensIt() = runBlocking {
        assumeTrue(System.getProperty("gmad.live") == "1")

        val info = WorkshopClient().fetch(ITEM)
        assertTrue("Steam listed no depot content for $ITEM", info.manifestGid > 0L)

        val destination = File.createTempFile("workshop_", ".gma")
        var lastDone = -1L
        var lastTotal = -1L
        try {
            val written = WorkshopDownloader().download(
                info,
                destination,
                ProgressSink { done, total ->
                    lastDone = done
                    lastTotal = total
                }
            ) { true }

            assertEquals(info.fileSize, written)
            assertEquals(written, destination.length())
            assertEquals(written, lastDone)
            assertEquals(written, lastTotal)

            FileSource(destination).use { source ->
                GmaArchive.open(source).use { archive ->
                    assertTrue("the addon holds no files", archive.metadata.files.isNotEmpty())
                    assertTrue(archive.metadata.addonName.isNotBlank())
                }
            }
        } finally {
            destination.delete()
        }
    }

    /**
     * Half-Life 2's depot is licence-gated: an anonymous session is refused the
     * request code and the depot key, and no other connection manager says
     * otherwise. The refusal has to arrive once, not after six logons.
     */
    @Test
    fun refusesAnItemPublishedForAGameThatNeedsAnAccount() = runBlocking {
        assumeTrue(System.getProperty("gmad.live") == "1")

        val info = WorkshopClient().fetch(LICENSED_ITEM)
        assumeTrue(
            "$LICENSED_ITEM is no longer a licensed-app item",
            info.appId == 220 && info.manifestGid > 0L
        )

        val destination = File.createTempFile("workshop_", ".gma")
        val started = System.currentTimeMillis()
        var refusal: DepotAccessDeniedException? = null
        try {
            WorkshopDownloader().download(
                info,
                destination,
                ProgressSink { _, _ -> }
            ) { true }
        } catch (e: DepotAccessDeniedException) {
            refusal = e
        } finally {
            destination.delete()
        }
        val elapsed = System.currentTimeMillis() - started

        assertNotNull("Steam did not refuse an item for a game we do not own", refusal)
        assertEquals(220, refusal!!.appId)
        assertTrue("the refusal named another app", refusal.message!!.contains("220"))
        assertTrue("the refusal was retried for ${elapsed}ms", elapsed < 45_000)
    }

    private companion object {
        const val ITEM = "160250458"

        /** Half-Life 2 workshop item; ownership of HL2 cannot be had without an account. */
        const val LICENSED_ITEM = "3829870024"
    }
}
