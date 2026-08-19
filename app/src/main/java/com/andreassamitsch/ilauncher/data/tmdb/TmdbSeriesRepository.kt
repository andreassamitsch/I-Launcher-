package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context
import android.util.Log
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.SeriesSeason
import com.andreassamitsch.ilauncher.model.SeriesSeasonContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SERIES_TAG = "TMDB_SERIES"

class TmdbSeriesRepository(
    context: Context,
    readAccessToken: String = BuildConfig.TMDB_READ_ACCESS_TOKEN,
) {
    private val appContext = context.applicationContext
    private val network = TmdbNetworkClient(readAccessToken)
    private val imageConfigurationStore = TmdbImageConfigurationStore(appContext)
    private val seasonsCache = LinkedHashMap<Int, CachedSeasons>()
    private val seasonContentCache = LinkedHashMap<String, CachedSeasonContent>()

    suspend fun loadSeasons(series: MediaItem): List<SeriesSeason> = withContext(Dispatchers.IO) {
        val seriesId = series.tmdbId ?: return@withContext emptyList()
        if (!network.isConfigured || series.type != MediaType.Series) return@withContext emptyList()
        val now = System.currentTimeMillis()
        seasonsCache[seriesId]
            ?.takeIf { now - it.updatedAtUtcMillis <= CACHE_MILLIS }
            ?.let { return@withContext it.items }

        val seasons = runCatching {
            val images = ensureImageConfiguration(now)
            network.api.tvDetails(
                seriesId = seriesId,
                language = LANGUAGE,
                appendToResponse = "",
            ).toSeriesSeasons(images)
        }.onFailure { throwable ->
            Log.w(SERIES_TAG, "TMDB season list failed (${throwable.javaClass.simpleName})")
        }.getOrDefault(emptyList())

        seasonsCache[seriesId] = CachedSeasons(seasons, now)
        trimCache(seasonsCache, MAX_SERIES_CACHE_ENTRIES)
        seasons
    }

    suspend fun loadSeason(series: MediaItem, seasonNumber: Int): SeriesSeasonContent? =
        withContext(Dispatchers.IO) {
            val seriesId = series.tmdbId ?: return@withContext null
            if (!network.isConfigured || series.type != MediaType.Series) return@withContext null
            val key = "$seriesId:$seasonNumber"
            val now = System.currentTimeMillis()
            seasonContentCache[key]
                ?.takeIf { now - it.updatedAtUtcMillis <= CACHE_MILLIS }
                ?.let { return@withContext it.content }

            val content = runCatching {
                val images = ensureImageConfiguration(now)
                network.api.seasonDetails(
                    seriesId = seriesId,
                    seasonNumber = seasonNumber,
                    language = LANGUAGE,
                ).toSeriesSeasonContent(series, images)
            }.onFailure { throwable ->
                Log.w(SERIES_TAG, "TMDB season details failed (${throwable.javaClass.simpleName})")
            }.getOrNull() ?: return@withContext null

            seasonContentCache[key] = CachedSeasonContent(content, now)
            trimCache(seasonContentCache, MAX_SEASON_CACHE_ENTRIES)
            content
        }

    private suspend fun ensureImageConfiguration(now: Long): TmdbImageConfiguration? {
        imageConfigurationStore.loadFresh(now)?.let { return it }
        return runCatching {
            imageConfigurationStore.save(network.api.configuration().images, now)
        }.onFailure { throwable ->
            Log.w(SERIES_TAG, "TMDB image configuration failed (${throwable.javaClass.simpleName})")
        }.getOrNull()
    }

    private fun <K, V> trimCache(cache: LinkedHashMap<K, V>, maxEntries: Int) {
        while (cache.size > maxEntries) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
    }

    private data class CachedSeasons(
        val items: List<SeriesSeason>,
        val updatedAtUtcMillis: Long,
    )

    private data class CachedSeasonContent(
        val content: SeriesSeasonContent,
        val updatedAtUtcMillis: Long,
    )

    private companion object {
        const val LANGUAGE = "de-DE"
        const val CACHE_MILLIS = 60L * 60L * 1_000L
        const val MAX_SERIES_CACHE_ENTRIES = 16
        const val MAX_SEASON_CACHE_ENTRIES = 32
    }
}

internal fun TmdbMediaDetailsDto.toSeriesSeasons(
    imageConfiguration: TmdbImageConfiguration?,
): List<SeriesSeason> = seasons
    .asSequence()
    .filter { it.episodeCount > 0 }
    .sortedBy { it.seasonNumber }
    .map { season ->
        SeriesSeason(
            seasonNumber = season.seasonNumber,
            title = season.name?.takeIf(String::isNotBlank)
                ?: if (season.seasonNumber == 0) "Specials" else "Staffel ${season.seasonNumber}",
            episodeCount = season.episodeCount,
            airYear = TmdbRepository.yearOf(season.airDate),
            posterUri = imageConfiguration?.url(TmdbImageKind.Poster, season.posterPath),
        )
    }
    .toList()

internal fun TmdbSeasonDetailsDto.toSeriesSeasonContent(
    series: MediaItem,
    imageConfiguration: TmdbImageConfiguration?,
): SeriesSeasonContent {
    val summary = SeriesSeason(
        seasonNumber = seasonNumber,
        title = name?.takeIf(String::isNotBlank)
            ?: if (seasonNumber == 0) "Specials" else "Staffel $seasonNumber",
        episodeCount = episodes.size,
        airYear = TmdbRepository.yearOf(airDate),
        posterUri = imageConfiguration?.url(TmdbImageKind.Poster, posterPath),
    )
    val mappedEpisodes = episodes
        .filter { it.episodeNumber > 0 }
        .sortedBy { it.episodeNumber }
        .map { episode ->
            val episodeTitle = episode.name?.takeIf(String::isNotBlank)
            MediaItem(
                id = "tmdb:Episode:${series.tmdbId}:${episode.seasonNumber}:${episode.episodeNumber}",
                type = MediaType.Episode,
                title = series.title,
                originalTitle = series.originalTitle,
                subtitle = buildString {
                    append("S${episode.seasonNumber} E${episode.episodeNumber}")
                    episodeTitle?.let { append(" · $it") }
                },
                overview = episode.overview,
                releaseYear = TmdbRepository.yearOf(episode.airDate),
                tmdbId = series.tmdbId,
                tmdbEpisodeId = episode.id.takeIf { it > 0 },
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                episodeTitle = episodeTitle,
                posterUri = series.posterUri,
                backdropUri = series.backdropUri,
                heroBackdropUri = series.heroBackdropUri,
                logoUri = series.logoUri,
                episodeStillUri = imageConfiguration?.url(TmdbImageKind.Still, episode.stillPath),
                durationMillis = episode.runtime?.times(60_000L),
                voteAverage = episode.voteAverage,
                imdbId = series.imdbId,
                tvdbId = series.tvdbId,
                wikidataId = series.wikidataId,
                source = MediaSource(
                    provider = "tmdb_series",
                    sourceId = "tmdb:${series.tmdbId}:s${episode.seasonNumber}:e${episode.episodeNumber}",
                ),
                resolverConfidence = 1f,
            )
        }
    return SeriesSeasonContent(summary, mappedEpisodes)
}
