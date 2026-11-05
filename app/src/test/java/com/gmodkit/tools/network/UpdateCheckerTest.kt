package com.gmodkit.tools.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerVersionsAreDetected() {
        assertTrue(UpdateChecker.isNewer("2.2", "2.1"))
        assertTrue(UpdateChecker.isNewer("v2.1.1", "2.1"))
        assertTrue(UpdateChecker.isNewer("2.10", "2.9"))
        assertTrue(UpdateChecker.isNewer("3.0", "2.9.9"))
        assertTrue(UpdateChecker.isNewer("2.2-beta", "2.1"))
    }

    @Test
    fun equalAndOlderVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewer("2.1", "2.1"))
        assertFalse(UpdateChecker.isNewer("v2.1", "2.1"))
        assertFalse(UpdateChecker.isNewer("2.1.0", "2.1"))
        assertFalse(UpdateChecker.isNewer("2.0", "2.1"))
        assertFalse(UpdateChecker.isNewer("1.99", "2.0"))
        assertEquals(0, UpdateChecker.compareVersions("v2.1.0", "2.1"))
    }

    @Test
    fun parsesReleaseWithApkAsset() {
        val release = UpdateChecker.parseRelease(
            """
            {
              "tag_name": "v2.2",
              "html_url": "https://github.com/boo271/Gmad-for-android/releases/tag/v2.2",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "checksums.txt", "browser_download_url": "https://example.com/checksums.txt"},
                {"name": "Gmad-2.2.apk", "browser_download_url": "https://example.com/Gmad-2.2.apk"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("2.2", release?.version)
        assertEquals("https://example.com/Gmad-2.2.apk", release?.downloadUrl)
        assertEquals(
            "https://github.com/boo271/Gmad-for-android/releases/tag/v2.2",
            release?.releaseUrl
        )
    }

    @Test
    fun fallsBackToTheReleasePageWithoutAnApk() {
        val release = UpdateChecker.parseRelease(
            """
            {
              "tag_name": "v2.2",
              "html_url": "https://github.com/boo271/Gmad-for-android/releases/tag/v2.2",
              "assets": []
            }
            """.trimIndent()
        )

        assertEquals("https://github.com/boo271/Gmad-for-android/releases/tag/v2.2", release?.downloadUrl)
    }

    @Test
    fun skipsDraftsAndPrereleases() {
        assertNull(
            UpdateChecker.parseRelease(
                """{"tag_name":"v2.2","draft":true,"html_url":"https://example.com"}"""
            )
        )
        assertNull(
            UpdateChecker.parseRelease(
                """{"tag_name":"v2.2","prerelease":true,"html_url":"https://example.com"}"""
            )
        )
    }

    @Test
    fun rejectsMalformedOrIncompleteReleases() {
        assertNull(UpdateChecker.parseRelease("not json"))
        assertNull(UpdateChecker.parseRelease("""{"html_url":"https://example.com"}"""))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v2.2"}"""))
    }
}
