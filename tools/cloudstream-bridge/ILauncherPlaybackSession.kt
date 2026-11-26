package com.lagradost.cloudstream3

import android.content.Intent

/** Pure policies shared by the entry activity and the player. */
internal object ILauncherPlaybackSession {
    fun mainTaskFlags(incoming: Int): Int {
        val incompatible = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return (incoming and incompatible.inv()) or
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    /** Prefer the show/movie poster, then the current episode's artwork.
     * Only public HTTP(S) image references belong in a platform media session.
     */
    fun artworkUrl(poster: String?, fallback: String?): String? =
        sequenceOf(poster, fallback).filterNotNull().map { it.trim() }.firstOrNull { url ->
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            uri != null && (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }

    data class Metadata(val title: String, val series: String?, val subtitle: String?)

    fun metadata(
        header: String?, name: String?, episodic: Boolean, season: Int?, episode: Int?,
    ): Metadata {
        val base = header?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: ""
        val coordinate = if (episodic && episode != null) {
            if (season != null) "S${season}:E${episode}" else "E$episode"
        } else null
        val title = listOfNotNull(base.takeIf { it.isNotBlank() }, coordinate).joinToString(" ")
        return Metadata(
            title = title,
            series = base.takeIf { episodic && it.isNotBlank() },
            subtitle = name?.takeIf { it.isNotBlank() && it != base },
        )
    }
}
