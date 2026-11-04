package com.gmodkit.tools.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpPolicyTest {

    private val web = listOf(
        "https://example.com/addon.gma",
        "https://github.com/x/releases/download/v1/file.zip"
    )

    private val lan = listOf(
        "http://192.168.1.50:8477/file/160250458",
        "http://10.0.0.5:8477/resolve",
        "http://172.16.0.5:8477/",
        "http://localhost:8477/health",
        "http://127.0.0.1:8477/health",
        "http://10.0.2.2:8477/resolve",
        "http://my-pc.local:8477/file/1"
    )

    private val rejected = listOf(
        "http://example.com/addon.gma",
        "http://8.8.8.8/addon.gma",
        "http://172.32.0.5:8477/",
        "http://192.169.1.1:8477/",
        "http://26.1.2.3:8477/",
        "ftp://example.com/addon.gma",
        "example.com/addon.gma",
        "",
        "https://",
        "http:///no-host"
    )

    @Test
    fun httpsIsAlwaysAllowed() {
        web.forEach {
            assertTrue(it, Http.isAllowedUrl(it))
            assertTrue(it, Http.isAllowedLocalUrl(it))
        }
    }

    @Test
    fun plainHttpIsOnlyAllowedOnTheLocalNetwork() {
        lan.forEach {
            assertFalse("$it must not pass the strict check", Http.isAllowedUrl(it))
            assertTrue(it, Http.isAllowedLocalUrl(it))
        }
    }

    @Test
    fun publicAndMalformedUrlsFailBothChecks() {
        rejected.forEach {
            assertFalse(it, Http.isAllowedUrl(it))
            assertFalse(it, Http.isAllowedLocalUrl(it))
        }
    }

    @Test
    fun updatePathStaysHttpsOnly() {
        // An APK served over plain HTTP could be swapped on the local network.
        assertFalse(Http.isAllowedUrl("http://192.168.1.50:8477/gmad.apk"))
    }
}
