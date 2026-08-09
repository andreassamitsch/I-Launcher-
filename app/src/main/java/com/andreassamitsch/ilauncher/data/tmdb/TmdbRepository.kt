package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context
import android.util.Log
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.data.database.ILauncherDatabase
import com.andreassamitsch.ilauncher.data.database.TmdbEpisodeEntity
import com.andreassamitsch.ilauncher.data.database.TmdbMappingEntity
import com.andreassamitsch.ilauncher.data.database.TmdbMediaEntity
import com.andreassamitsch.ilauncher.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TMDB_RESOLVER"

class TmdbRepository(
    context: Context,
    readAccessToken: String = BuildConfig.TMDB_READ_ACCESS_TOKEN,
) {
    private val appContext = context.applicationContext
    private val dao = ILauncherDatabase.get(appContext).tmdbDao()
    private val network = TmdbNetworkClient(readAccessToken)
    private val imageConfigurationStore = TmdbImageConfigurationStore(appContext)
    private val cleanupStarted = AtomicBoolean(false)

    val isConfigured: Boolean
        get() = network.isConfigured

    suspend fun resolve(
        sourceKey: String,
        lookup: MediaLookup,
    ): TmdbMetadata? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cleanExpiredCacheOnce(now)
        val parsed = MediaTitleParser.parse(lookup)
        if (parsed.normalizedTitle.isBlank()) return@withContext null

        val cachedMapping = dao.mapping(sourceKey)
            ?.takeIf { now - it.updatedAtUtcMillis <= MAPPING_MAX_AGE_MILLIS }
            ?.takeIf { it.matches(parsed) }

        if (cachedMapping != null) {
            if (cachedMapping.tmdbId == null || cachedMapping.mediaType == null) {
                return@withContext null
            }

            val cached = cachedMetadata(cachedMapping, parsed, now)
            if (cached != null && now - cached.second <= REFRESH_AFTER_MILLIS) {
                return@withContext cached.first
            }
            if (!network.isConfigured) {
                return@withContext cached?.first
            }

            val refreshed = runCatching {
                refreshKnownMapping(cachedMapping, parsed, now)
            }.onFailure { throwable ->
                Log.w(TAG, "TMDB refresh failed (${throwable.javaClass.simpleName})")
            }.getOrNull()
            return@withContext refreshed ?: cached?.first
        }

        if (!network.isConfigured) return@withContext null

        runCatching {
            resolveFromNetwork(sourceKey, parsed, now)
        }.onFailure { throwable ->
            Log.w(TAG, "TMDB lookup failed (${throwable.javaClass.simpleName})")
        }.getOrNull()
    }

    private suspend fun resolveFromNetwork(
        sourceKey: String,
        parsed: ParsedMediaLookup,
        now: Long,
    ): TmdbMetadata? {
        val candidates = searchCandidates(parsed)
        val match = TmdbMatcher.bestMatch(parsed, candidates)
        if (match == null) {
            dao.upsertMapping(
                TmdbMappingEntity(
                    sourceKey = sourceKey,
                    normalizedTitle = parsed.normalizedTitle,
                    releaseYear = parsed.releaseYear,
                    seasonNumber = parsed.seasonNumber,
                    episodeNumber = parsed.episodeNumber,
                    tmdbId = null,
                    mediaType = null,
                    confidence = null,
                    updatedAtUtcMillis = now,
                ),
            )
            Log.d(TAG, "No confident TMDB match")
            return null
        }

        val mapping = TmdbMappingEntity(
            sourceKey = sourceKey,
            normalizedTitle = parsed.normalizedTitle,
            releaseYear = parsed.releaseYear,
            seasonNumber = parsed.seasonNumber,
            episodeNumber = parsed.episodeNumber,
            tmdbId = match.candidate.id,
            mediaType = match.candidate.type.name,
            confidence = match.confidence,
            updatedAtUtcMillis = now,
        )
        dao.upsertMapping(mapping)

        Log.d(
            TAG,
            "TMDB match accepted: id=${match.candidate.id}, type=${match.candidate.type}, confidence=${"%.2f".format(match.confidence)}",
        )
        return refreshKnownMapping(mapping, parsed, now)
    }

    private suspend fun refreshKnownMapping(
        mapping: TmdbMappingEntity,
        parsed: ParsedMediaLookup,
        now: Long,
    ): TmdbMetadata? {
        val tmdbId = mapping.tmdbId ?: return null
        val mediaType = mapping.mediaType.toMediaType()
        if (mediaType == MediaType.Unknown) return null

        val details = when (mediaType) {
            MediaType.Movie -> network.api.movieDetails(tmdbId, LANGUAGE)
            MediaType.Series -> network.api.tvDetails(tmdbId, LANGUAGE)
            MediaType.Episode,
            MediaType.Unknown,
            -> return null
        }

        val mediaEntity = details.toEntity(mediaType, now)
        dao.upsertMedia(mediaEntity)

        if (
            parsed.typeHint == MediaType.Episode &&
            parsed.seasonNumber != null &&
            parsed.episodeNumber != null &&
            mediaType == MediaType.Series
        ) {
            val episodeDto = network.api.episodeDetails(
                seriesId = tmdbId,
                seasonNumber = parsed.seasonNumber,
                episodeNumber = parsed.episodeNumber,
                language = LANGUAGE,
            )
            dao.upsertEpisode(
                episodeDto.toEntity(
                    seriesTmdbId = tmdbId,
                    seasonNumber = parsed.seasonNumber,
                    episodeNumber = parsed.episodeNumber,
                    now = now,
                ),
            )
        }

        val imageConfiguration = ensureImageConfiguration(now)
        return mediaEntity.toMetadata(
            confidence = mapping.confidence ?: 1f,
            episode = loadEpisode(tmdbId, parsed, imageConfiguration),
            imageConfiguration = imageConfiguration,
        )
    }

    private suspend fun cachedMetadata(
        mapping: TmdbMappingEntity,
        parsed: ParsedMediaLookup,
        now: Long,
    ): Pair<TmdbMetadata, Long>? {
        val tmdbId = mapping.tmdbId ?: return null
        val mediaType = mapping.mediaType.toMediaType()
        val media = dao.media(mediaKey(mediaType, tmdbId)) ?: return null
        if (now - media.updatedAtUtcMillis > CACHE_MAX_AGE_MILLIS) return null

        val imageConfiguration = imageConfigurationStore.loadFresh(now)
        val needsEpisodeDetails =
            parsed.typeHint == MediaType.Episode &&
                parsed.seasonNumber != null &&
                parsed.episodeNumber != null &&
                mediaType == MediaType.Series
        val episodeEntity = if (needsEpisodeDetails) {
            dao.episode(
                episodeKey(
                    seriesTmdbId = tmdbId,
                    season = parsed.seasonNumber!!,
                    episode = parsed.episodeNumber!!,
                ),
            )
        } else {
            null
        }
        val mediaTrailerPolicyFresh = media.updatedAtUtcMillis >= TRAILER_LANGUAGE_POLICY_CUTOFF_UTC_MILLIS
        val episodeTrailerPolicyFresh = !needsEpisodeDetails ||
            (episodeEntity?.updatedAtUtcMillis ?: 0L) >= TRAILER_LANGUAGE_POLICY_CUTOFF_UTC_MILLIS
        val videoLookupComplete = media.videoLookupComplete &&
            mediaTrailerPolicyFresh &&
            (!needsEpisodeDetails || episodeEntity?.videoLookupComplete == true) &&
            episodeTrailerPolicyFresh

        return media.toMetadata(
            confidence = mapping.confidence ?: 1f,
            episode = episodeEntity?.toMetadata(imageConfiguration),
            imageConfiguration = imageConfiguration,
        ) to if (videoLookupComplete) media.updatedAtUtcMillis else 0L
    }

    private suspend fun loadEpisode(
        seriesTmdbId: Int,
        parsed: ParsedMediaLookup,
        imageConfiguration: TmdbImageConfiguration?,
    ): TmdbEpisodeMetadata? {
        val season = parsed.seasonNumber ?: return null
        val episode = parsed.episodeNumber ?: return null
        return dao.episode(episodeKey(seriesTmdbId, season, episode))
            ?.toMetadata(imageConfiguration)
    }

    private suspend fun searchCandidates(parsed: ParsedMediaLookup): List<TmdbCandidate> {
        val response = when (parsed.typeHint) {
            MediaType.Movie -> network.api.searchMovies(
                query = parsed.title,
                language = LANGUAGE,
                releaseYear = parsed.releaseYear,
            )

            MediaType.Series,
            MediaType.Episode,
            -> network.api.searchTv(
                query = parsed.title,
                language = LANGUAGE,
                firstAirDateYear = parsed.releaseYear,
            )

            MediaType.Unknown -> network.api.searchMulti(
                query = parsed.title,
                language = LANGUAGE,
            )
        }

        return response.results.mapNotNull { dto ->
            val type = when {
                dto.mediaType == "movie" -> MediaType.Movie
                dto.mediaType == "tv" -> MediaType.Series
                parsed.typeHint == MediaType.Movie -> MediaType.Movie
                parsed.typeHint == MediaType.Series || parsed.typeHint == MediaType.Episode -> MediaType.Series
                else -> MediaType.Unknown
            }
            if (type == MediaType.Unknown) return@mapNotNull null

            val title = when (type) {
                MediaType.Movie -> dto.title
                MediaType.Series -> dto.name
                else -> null
            }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            TmdbCandidate(
                id = dto.id,
                type = type,
                title = title,
                originalTitle = when (type) {
                    MediaType.Movie -> dto.originalTitle
                    MediaType.Series -> dto.originalName
                    else -> null
                },
                releaseYear = yearOf(dto.releaseDate ?: dto.firstAirDate),
                popularity = dto.popularity,
            )
        }
    }

    private suspend fun ensureImageConfiguration(now: Long): TmdbImageConfiguration? {
        imageConfigurationStore.loadFresh(now)?.let { return it }
        if (!network.isConfigured) return null
        return runCatching {
            imageConfigurationStore.save(network.api.configuration().images, now)
        }.onFailure { throwable ->
            Log.w(TAG, "TMDB image configuration failed (${throwable.javaClass.simpleName})")
        }.getOrNull()
    }

    private suspend fun cleanExpiredCacheOnce(now: Long) {
        if (!cleanupStarted.compareAndSet(false, true)) return
        val cutoff = now - CACHE_MAX_AGE_MILLIS
        dao.deleteOldMappings(cutoff)
        dao.deleteOldMedia(cutoff)
        dao.deleteOldEpisodes(cutoff)
    }

    private fun TmdbMappingEntity.matches(parsed: ParsedMediaLookup): Boolean =
        normalizedTitle == parsed.normalizedTitle &&
            releaseYear == parsed.releaseYear &&
            seasonNumber == parsed.seasonNumber &&
            episodeNumber == parsed.episodeNumber

    private fun TmdbMediaDetailsDto.toEntity(mediaType: MediaType, now: Long): TmdbMediaEntity {
        val resolvedTitle = when (mediaType) {
            MediaType.Movie -> title
            MediaType.Series -> name
            else -> null
        }.orEmpty()
        val resolvedOriginalTitle = when (mediaType) {
            MediaType.Movie -> originalTitle
            MediaType.Series -> originalName
            else -> null
        }
        val preferredLogo = images?.logos
            .orEmpty()
            .sortedWith(
                compareBy<TmdbImageDto> { languageRank(it.language) }
                    .thenByDescending { it.voteAverage },
            )
            .firstOrNull()
            ?.filePath

        return TmdbMediaEntity(
            mediaKey = mediaKey(mediaType, id),
            tmdbId = id,
            mediaType = mediaType.name,
            title = resolvedTitle,
            originalTitle = resolvedOriginalTitle,
            overview = overview,
            releaseYear = yearOf(releaseDate ?: firstAirDate),
            runtimeMinutes = runtime ?: episodeRunTime?.firstOrNull(),
            posterPath = posterPath,
            backdropPath = backdropPath,
            logoPath = preferredLogo,
            voteAverage = voteAverage,
            imdbId = externalIds?.imdbId,
            tvdbId = externalIds?.tvdbId,
            wikidataId = externalIds?.wikidataId,
            trailerYoutubeId = TmdbTrailerSelector.preferredYouTubeId(videos?.results.orEmpty()),
            videoLookupComplete = true,
            updatedAtUtcMillis = now,
        )
    }

    private fun TmdbEpisodeDetailsDto.toEntity(
        seriesTmdbId: Int,
        seasonNumber: Int,
        episodeNumber: Int,
        now: Long,
    ) = TmdbEpisodeEntity(
        episodeKey = episodeKey(seriesTmdbId, seasonNumber, episodeNumber),
        seriesTmdbId = seriesTmdbId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        tmdbEpisodeId = id,
        title = name,
        overview = overview,
        airYear = yearOf(airDate),
        runtimeMinutes = runtime,
        stillPath = stillPath,
        voteAverage = voteAverage,
        trailerYoutubeId = TmdbTrailerSelector.preferredYouTubeId(videos?.results.orEmpty()),
        videoLookupComplete = true,
        updatedAtUtcMillis = now,
    )

    private fun TmdbMediaEntity.toMetadata(
        confidence: Float,
        episode: TmdbEpisodeMetadata?,
        imageConfiguration: TmdbImageConfiguration?,
    ) = TmdbMetadata(
        tmdbId = tmdbId,
        mediaType = mediaType.toMediaType(),
        title = title,
        originalTitle = originalTitle,
        overview = overview,
        releaseYear = releaseYear,
        runtimeMinutes = runtimeMinutes,
        posterUri = imageConfiguration?.url(TmdbImageKind.Poster, posterPath),
        backdropUri = imageConfiguration?.url(TmdbImageKind.Backdrop, backdropPath),
        logoUri = imageConfiguration?.url(TmdbImageKind.Logo, logoPath),
        voteAverage = voteAverage,
        imdbId = imdbId,
        tvdbId = tvdbId,
        wikidataId = wikidataId,
        trailerYoutubeId = trailerYoutubeId,
        episode = episode,
        confidence = confidence,
    )

    private fun TmdbEpisodeEntity.toMetadata(
        imageConfiguration: TmdbImageConfiguration?,
    ) = TmdbEpisodeMetadata(
        tmdbEpisodeId = tmdbEpisodeId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        title = title,
        overview = overview,
        airYear = airYear,
        runtimeMinutes = runtimeMinutes,
        stillUri = imageConfiguration?.url(TmdbImageKind.Still, stillPath),
        voteAverage = voteAverage,
        trailerYoutubeId = trailerYoutubeId,
    )

    private fun languageRank(language: String?): Int = when (language) {
        "de" -> 0
        "en" -> 1
        null -> 2
        else -> 3
    }

    private fun String?.toMediaType(): MediaType =
        runCatching { this?.let(MediaType::valueOf) ?: MediaType.Unknown }
            .getOrDefault(MediaType.Unknown)

    companion object {
        private const val LANGUAGE = "de-DE"
        private const val REFRESH_AFTER_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        private const val MAPPING_MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        private const val CACHE_MAX_AGE_MILLIS = 180L * 24L * 60L * 60L * 1_000L
        private const val TRAILER_LANGUAGE_POLICY_CUTOFF_UTC_MILLIS = 1_786_233_600_000L

        internal fun mediaKey(type: MediaType, tmdbId: Int): String = "${type.name}:$tmdbId"

        internal fun episodeKey(seriesTmdbId: Int, season: Int, episode: Int): String =
            "$seriesTmdbId:$season:$episode"

        internal fun yearOf(date: String?): Int? = date
            ?.take(4)
            ?.toIntOrNull()
    }
}
