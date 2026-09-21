package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Joyn AT supplies the authoritative series identity, episode coordinates and pictures. A title
 * search alone is NOT an identity proof (e.g. the German and Austrian Bauer sucht Frau). Only add
 * the longer TMDB text if exactly one TMDB series matches the title, has AT as origin country and
 * actually contains the selected season and episode. Never copy TMDB title, logos or artwork.
 */
internal object JoynVerifiedEpisodeText {
    private val network = TmdbNetworkClient(BuildConfig.TMDB_READ_ACCESS_TOKEN)
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, CachedText>()

    suspend fun overview(media: MediaItem): String? {
        if (!network.isConfigured || media.type != MediaType.Episode ||
            media.source.packageName != "com.andreassamitsch.joyntv") return null
        val season = media.seasonNumber?.takeIf { it > 0 } ?: return null
        val episode = media.episodeNumber?.takeIf { it > 0 } ?: return null
        val title = media.title.trim().takeIf { it.length >= 3 } ?: return null
        val key = "${title.lowercase(Locale.ROOT)}:$season:$episode"
        return lock.withLock {
            val now = System.currentTimeMillis()
            cache[key]?.takeIf { now - it.timestamp < (if (it.value == null) FAILURE_TTL else SUCCESS_TTL) }
                ?.let { return@withLock it.value }
            val found = withContext(Dispatchers.IO) {
                runCatching { findVerifiedOverview(title, season, episode) }.getOrNull()
            }
            cache[key] = CachedText(found, now)
            if (cache.size > 100) cache.remove(cache.keys.first())
            found
        }
    }

    private suspend fun findVerifiedOverview(title: String, season: Int, episode: Int): String? {
        val candidates = network.api.searchTv(title, "de-DE").results
            .filter { it.id > 0 && (it.name.equals(title, ignoreCase = true) ||
                it.originalName.equals(title, ignoreCase = true)) }
            .filter { it.originCountry.isEmpty() || "AT" in it.originCountry }
            .take(MAX_CANDIDATES)

        val verified = candidates.mapNotNull { candidate ->
            val details = runCatching {
                network.api.tvDetails(candidate.id, "de-DE", appendToResponse = "")
            }.getOrNull() ?: return@mapNotNull null
            if ("AT" !in details.originCountry || details.seasons.none { it.seasonNumber == season }) {
                return@mapNotNull null
            }
            val entry = runCatching {
                network.api.episodeDetails(candidate.id, season, episode, "de-DE", appendToResponse = "")
            }.getOrNull() ?: return@mapNotNull null
            val text = entry.overview?.trim()?.takeIf { it.length >= 20 } ?: return@mapNotNull null
            candidate.id to text
        }
        // If TMDB contains several Austrian shows with identical names and episode coordinates,
        // don't guess which synopsis belongs to the Joyn asset.
        return verified.singleOrNull()?.second
    }

    private data class CachedText(val value: String?, val timestamp: Long)
    private const val MAX_CANDIDATES = 12
    private const val FAILURE_TTL = 60L * 60L * 1000L
    private const val SUCCESS_TTL = 12L * 60L * 60L * 1000L
}
