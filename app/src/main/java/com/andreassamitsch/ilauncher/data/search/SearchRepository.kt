package com.andreassamitsch.ilauncher.data.search

import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.data.tmdb.TmdbSearchRepository
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.SearchItem
import com.andreassamitsch.ilauncher.model.SearchResultKind
import java.text.Normalizer
import java.util.Locale

class SearchRepository(
    private val tmdbSearchRepository: TmdbSearchRepository? = null,
) {
    val isTmdbConfigured: Boolean
        get() = tmdbSearchRepository?.isConfigured == true

    fun searchLocal(
        query: String,
        apps: List<InstalledApp>,
        watchNextItems: List<EnrichedWatchNextItem>,
        previewChannels: List<AppContentChannel>,
        liveTvChannels: List<LiveTvChannel>,
        epgState: EpgState,
        nowUtcMillis: Long = System.currentTimeMillis(),
    ): List<SearchItem> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.length < MIN_LOCAL_QUERY_LENGTH) return emptyList()
        val includeLongMetadata = normalizedQuery.length >= MIN_METADATA_QUERY_LENGTH

        val appLabels = apps.associate { it.packageName to it.label }
        val channelsByRef = liveTvChannels.associateBy { it.serviceReference }
        var sequence = 0
        val matches = mutableListOf<ScoredSearchItem>()

        watchNextItems.forEach { enriched ->
            val media = enriched.media
            val source = enriched.sourceItem
            val mediaScore = scoreMedia(
                query = normalizedQuery,
                media = media,
                includeLongMetadata = includeLongMetadata,
            ) ?: 0
            val sourceIdentityScore = maxOf(
                scoreText(normalizedQuery, source.displayTitle),
                scoreText(normalizedQuery, source.episodeTitle),
                scoreText(normalizedQuery, source.displaySubtitle),
            )
            val sourceDescriptionScore = if (includeLongMetadata) {
                scoreText(normalizedQuery, source.shortDescription)
                    .takeIf { it > 0 }
                    ?.minus(220)
                    ?.coerceAtLeast(0)
                    ?: 0
            } else {
                0
            }
            val score = maxOf(mediaScore, sourceIdentityScore, sourceDescriptionScore)
            if (score > 0) {
                matches += ScoredSearchItem(
                    score = score,
                    priority = PRIORITY_WATCH_NEXT,
                    sequence = sequence++,
                    item = SearchItem(
                        id = "search:watch:${media.source.sourceId}",
                        kind = SearchResultKind.WatchNext,
                        title = media.title,
                        subtitle = media.subtitle ?: source.displaySubtitle,
                        artworkUri = media.preferredArtworkUri ?: source.artworkUri,
                        sourceLabel = media.source.packageName?.let { appLabels[it] ?: it },
                        media = media,
                        packageName = media.source.packageName,
                    ),
                )
            }
        }

        previewChannels.forEach { channel ->
            val channelSourceLabel = channel.packageName?.let { appLabels[it] ?: it }
            channel.programs.forEach { program ->
                val media = program.media
                val mediaScore = scoreMedia(
                    query = normalizedQuery,
                    media = media,
                    includeLongMetadata = includeLongMetadata,
                ) ?: 0
                val channelScore = maxOf(
                    scoreText(normalizedQuery, channel.title),
                    scoreText(normalizedQuery, channelSourceLabel),
                ).takeIf { it > 0 }?.minus(260)?.coerceAtLeast(0) ?: 0
                val score = maxOf(mediaScore, channelScore)
                if (score > 0) {
                    matches += ScoredSearchItem(
                        score = score,
                        priority = PRIORITY_PREVIEW,
                        sequence = sequence++,
                        item = SearchItem(
                            id = "search:preview:${channel.id}:${media.source.sourceId}",
                            kind = SearchResultKind.PreviewProgram,
                            title = media.title,
                            subtitle = media.subtitle,
                            artworkUri = media.preferredArtworkUri,
                            sourceLabel = listOfNotNull(channel.title, channelSourceLabel)
                                .distinct()
                                .joinToString(" · "),
                            media = media,
                            packageName = media.source.packageName,
                            previewChannelId = channel.id,
                        ),
                    )
                }
            }
        }

        val epgMatches = mutableListOf<ScoredSearchItem>()
        epgState.guideByServiceReference.forEach { (serviceReference, programs) ->
            val channel = channelsByRef[serviceReference]
            programs.forEach programLoop@{ program ->
                if (program.endUtcMillis < nowUtcMillis) return@programLoop
                val titleScore = scoreText(normalizedQuery, program.title)
                val metadataScore = if (includeLongMetadata) {
                    scoreText(
                        normalizedQuery,
                        listOfNotNull(
                            program.subtitle,
                            program.shortDescription,
                            program.longDescription,
                            program.categories?.joinToString(" "),
                            channel?.name,
                        ).joinToString(" "),
                    )
                } else {
                    scoreText(normalizedQuery, channel?.name)
                }
                val score = maxOf(titleScore, metadataScore.takeIf { it > 0 }?.minus(220) ?: 0)
                if (score <= 0) return@programLoop

                epgMatches += ScoredSearchItem(
                    score = score,
                    priority = PRIORITY_EPG,
                    sequence = sequence++,
                    item = SearchItem(
                        id = "search:epg:$serviceReference:${program.startUtcMillis}",
                        kind = SearchResultKind.EpgProgram,
                        title = program.title,
                        subtitle = program.subtitle,
                        artworkUri = program.preferredArtworkUri ?: channel?.piconUri,
                        sourceLabel = channel?.name ?: "TV",
                        serviceReference = serviceReference,
                        programStartUtcMillis = program.startUtcMillis,
                    ),
                )
            }
        }
        matches += epgMatches
            .sortedWith(scoredComparator)
            .distinctBy { it.item.id }
            .take(MAX_EPG_RESULTS)

        apps.forEach { app ->
            val score = maxOf(
                scoreText(normalizedQuery, app.label),
                (scoreText(normalizedQuery, app.packageName) - 180).coerceAtLeast(0),
            )
            if (score > 0) {
                matches += ScoredSearchItem(
                    score = score,
                    priority = PRIORITY_APP,
                    sequence = sequence++,
                    item = SearchItem(
                        id = "search:app:${app.packageName}",
                        kind = SearchResultKind.App,
                        title = app.label,
                        subtitle = app.packageName,
                        sourceLabel = "App",
                        packageName = app.packageName,
                    ),
                )
            }
        }

        return matches
            .sortedWith(scoredComparator)
            .distinctBy { it.item.id }
            .map(ScoredSearchItem::item)
            .take(MAX_LOCAL_RESULTS)
    }

    suspend fun searchTmdb(query: String): List<SearchItem> {
        val provider = tmdbSearchRepository ?: return emptyList()
        if (!provider.isConfigured || normalize(query).length < MIN_TMDB_QUERY_LENGTH) return emptyList()
        return provider.search(query)
            .take(MAX_TMDB_RESULTS)
            .map(::tmdbSearchItem)
    }

    suspend fun browseTmdb(): List<SearchBrowseSection> {
        val provider = tmdbSearchRepository ?: return emptyList()
        if (!provider.isConfigured) return emptyList()
        return provider.browse().map { section ->
            SearchBrowseSection(
                key = section.key,
                title = section.title,
                items = section.items.map(::tmdbSearchItem),
            )
        }
    }

    suspend fun loadTmdbDetails(item: MediaItem): MediaItem =
        tmdbSearchRepository?.loadDetails(item) ?: item

    private fun tmdbSearchItem(media: MediaItem): SearchItem = SearchItem(
        id = "search:tmdb:${media.type}:${media.tmdbId}",
        kind = SearchResultKind.Tmdb,
        title = media.title,
        subtitle = media.releaseYear?.toString(),
        artworkUri = media.preferredArtworkUri,
        sourceLabel = "TMDB",
        media = media,
    )

    private fun scoreMedia(
        query: String,
        media: MediaItem,
        includeLongMetadata: Boolean,
    ): Int? {
        val titleScore = maxOf(
            scoreText(query, media.title),
            scoreText(query, media.originalTitle),
            scoreText(query, media.episodeTitle),
        )
        val metadataScore = if (includeLongMetadata) {
            scoreText(
                query,
                listOfNotNull(media.subtitle, media.overview).joinToString(" "),
            )
        } else {
            scoreText(query, media.subtitle)
        }
        val score = maxOf(titleScore, metadataScore.takeIf { it > 0 }?.minus(220) ?: 0)
        return score.takeIf { it > 0 }
    }

    internal fun scoreText(query: String, value: String?): Int {
        val normalizedValue = normalize(value.orEmpty())
        if (query.isBlank() || normalizedValue.isBlank()) return 0
        if (normalizedValue == query) return 1_000
        if (normalizedValue.startsWith(query)) return 920
        if (normalizedValue.split(' ').any { it.startsWith(query) }) return 850
        if (normalizedValue.contains(query)) return 760

        val queryTokens = query.split(' ').filter(String::isNotBlank)
        if (queryTokens.size > 1 && queryTokens.all(normalizedValue::contains)) return 680
        return 0
    }

    internal fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS_REGEX, "")
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC_REGEX, " ")
        .trim()
        .replace(MULTI_SPACE_REGEX, " ")

    private data class ScoredSearchItem(
        val score: Int,
        val priority: Int,
        val sequence: Int,
        val item: SearchItem,
    )

    private companion object {
        const val MIN_LOCAL_QUERY_LENGTH = 2
        const val MIN_METADATA_QUERY_LENGTH = 3
        const val MIN_TMDB_QUERY_LENGTH = 3
        const val MAX_LOCAL_RESULTS = 60
        const val MAX_EPG_RESULTS = 24
        const val MAX_TMDB_RESULTS = 12
        const val PRIORITY_WATCH_NEXT = 0
        const val PRIORITY_PREVIEW = 1
        const val PRIORITY_EPG = 2
        const val PRIORITY_APP = 3

        val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
        val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
        val MULTI_SPACE_REGEX = Regex("\\s+")

        val scoredComparator = compareByDescending<ScoredSearchItem> { it.score }
            .thenBy { it.priority }
            .thenBy { it.sequence }
    }
}
