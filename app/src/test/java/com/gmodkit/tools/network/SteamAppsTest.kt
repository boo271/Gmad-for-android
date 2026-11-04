package com.gmodkit.tools.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAppsTest {

    @Test
    fun namesTheGamesPeoplePasteItemsFrom() {
        assertEquals("Garry's Mod", SteamApps.name(4000))
        assertEquals("Half-Life 2", SteamApps.name(220))
        assertEquals("Team Fortress 2", SteamApps.name(440))
        assertEquals("Portal 2", SteamApps.name(620))
    }

    @Test
    fun marksTheAppsAnAnonymousSessionCanFetch() {
        assertTrue(SteamApps.canDownload(4000) == true)
        assertTrue(SteamApps.canDownload(440) == true)
        assertTrue(SteamApps.canDownload(730) == true)
    }

    @Test
    fun marksTheAppsSteamRefusesWithoutALicence() {
        assertFalse(SteamApps.canDownload(220) == true)
        assertFalse(SteamApps.canDownload(320) == true)
        assertFalse(SteamApps.canDownload(620) == true)
        assertFalse(SteamApps.canDownload(107410) == true)
    }

    @Test
    fun treatsTheDedicatedServersAsUnsupportedForWorkshopItems() {
        // These hand out depot keys, but no Workshop items are published to them.
        assertFalse(SteamApps.canDownload(232370) == true)
        assertFalse(SteamApps.canDownload(215) == true)
    }

    @Test
    fun leavesUnmeasuredAppsUndecided() {
        assertNull(SteamApps.canDownload(999999))
        assertNull(SteamApps.canDownload(0))
        assertNull(SteamApps.canDownload(-1))
        assertNull(SteamApps.name(999999))
    }

    @Test
    fun labelsUnknownAppsByNumber() {
        assertEquals("Garry's Mod (4000)", SteamApps.label(4000))
        assertEquals("app 999999", SteamApps.label(999999))
    }
}
