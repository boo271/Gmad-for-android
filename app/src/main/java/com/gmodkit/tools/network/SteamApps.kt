package com.gmodkit.tools.network

/**
 * What this app knows about the Steam app a Workshop item belongs to.
 *
 * The lists below were measured against Steam itself: an account-free session
 * asked each app for its depot key, which is the gate every Workshop download
 * passes through. Only the apps in [DOWNLOADABLE] answered with a key, so only
 * their items can be fetched here. An id that is not listed is simply
 * unmeasured, and the app still tries it.
 */
object SteamApps {

    private val NAMES = mapOf(
        4000 to "Garry's Mod",
        440 to "Team Fortress 2",
        480 to "Half-Life: Source",
        570 to "Dota 2",
        730 to "Counter-Strike 2",
        105600 to "Terraria",
        252490 to "Rust",
        232370 to "Half-Life 2: Deathmatch Dedicated Server",
        215 to "Source Dedicated Server",
        220 to "Half-Life 2",
        320 to "Half-Life 2: Deathmatch",
        340 to "Half-Life 2: Lost Coast",
        420 to "Half-Life 2: Episode Two",
        550 to "Left 4 Dead 2",
        620 to "Portal 2",
        107410 to "Arma 3",
        243750 to "Half-Life 2: Update",
        244310 to "Source SDK Base 2013 Dedicated Server"
    )

    /** Apps whose Workshop content an anonymous session can actually fetch. */
    private val DOWNLOADABLE = setOf(4000, 440, 480, 570, 730, 105600, 252490)

    /** Game name for [appId], or null when the id has never been seen. */
    fun name(appId: Int): String? = NAMES[appId]

    /**
     * True when the app was measured to work without an account, false when
     * Steam was measured to refuse it, and null when nothing is known, in which
     * case the caller should just try.
     */
    fun canDownload(appId: Int): Boolean? = when {
        appId <= 0 -> null
        appId in DOWNLOADABLE -> true
        appId in NAMES -> false
        else -> null
    }

    /** "Garry's Mod (4000)", or "app 999" when the id is unknown. */
    fun label(appId: Int): String {
        val name = name(appId)
        return if (name == null) "app $appId" else "$name ($appId)"
    }
}
