package com.andreassamitsch.ilauncher.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.data.handoff.ContentHandoffMode
import com.andreassamitsch.ilauncher.data.handoff.ContentSearchHandoff
import com.andreassamitsch.ilauncher.data.handoff.ContentSearchTarget
import com.andreassamitsch.ilauncher.data.tv.SeriesResumePosition
import com.andreassamitsch.ilauncher.data.tv.SeriesResumeRepository
import com.andreassamitsch.ilauncher.data.tv.seriesPlaybackTarget
import com.andreassamitsch.ilauncher.data.youtube.YouTubeEmbedPlayer
import com.andreassamitsch.ilauncher.model.*
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.TouchCard
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.discover.LocalTmdbDiscoveryLoader
import com.andreassamitsch.ilauncher.ui.trailer.TrailerPlayerActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest

private const val DETAILS_ROW_SIMILAR = "similar"
private const val DETAILS_ROW_COLLECTION = "collection"
private const val DETAILS_ROW_CAST = "cast"
private const val DETAILS_ROW_DIRECTORS = "directors"

private data class DetailsFocusTarget(val rowKey: String, val itemKey: String)

internal fun detailMediaFocusKey(media: MediaItem): String =
    "${media.type}:${media.tmdbId ?: media.id}"

/**
 * TMDB stores the parent series id in MediaItem.tmdbId and the concrete episode id separately in
 * tmdbEpisodeId. This lets a Watch Next episode reuse the normal series catalog endpoints without
 * changing how the episode is represented on the home screen.
 */
internal fun detailSeriesContext(media: MediaItem): MediaItem? = when {
    media.tmdbId == null -> null
    media.type == MediaType.Series -> media
    media.type == MediaType.Episode -> media.copy(type = MediaType.Series)
    else -> null
}

/** The opened Watch Next episode is the strongest resume signal for its own detail page. */
internal fun detailSeriesResume(media: MediaItem): SeriesResumePosition? {
    if (media.type != MediaType.Episode) return null
    val seasonNumber = media.seasonNumber ?: return null
    val episodeNumber = media.episodeNumber ?: return null
    return SeriesResumePosition(
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = media.episodeTitle,
        playbackPositionMillis = media.playbackPositionMillis,
        durationMillis = media.durationMillis,
        lastEngagementTimeUtcMillis = media.lastEngagementTimeUtcMillis,
    )
}

internal fun preferredSeriesSeason(
    seasons: List<SeriesSeason>,
    resume: SeriesResumePosition?,
): Int? = resume?.seasonNumber
    ?.takeIf { wanted -> seasons.any { it.seasonNumber == wanted } }
    ?: seasons.firstOrNull { it.seasonNumber > 0 }?.seasonNumber
    ?: seasons.firstOrNull()?.seasonNumber

@Composable
fun DetailsScreen(
    item: MediaItem,
    sourceLabel: String?,
    onPlay: (() -> Unit)?,
    onBack: () -> Unit,
    onTrailer: (() -> Unit)? = null,
    onTrailerSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val loader = LocalTmdbDiscoveryLoader.current
    var displayItem by remember(item.id) { mutableStateOf(item) }
    var relatedContent by remember(item.id) { mutableStateOf(MediaRelatedContent.Empty) }
    var credits by remember(item.id) { mutableStateOf(MediaCredits()) }
    var selectedPersonId by remember(item.id) { mutableStateOf<Int?>(null) }
    var selectedPerson by remember(item.id) { mutableStateOf<PersonDetails?>(null) }
    var personLoading by remember(item.id) { mutableStateOf(false) }
    var relatedMedia by remember(item.id) { mutableStateOf<MediaItem?>(null) }
    var detailsFocusTarget by remember(item.id) { mutableStateOf<DetailsFocusTarget?>(null) }
    var detailsFocusRestoreGeneration by remember(item.id) { mutableIntStateOf(0) }
    var personWorkFocusRestoreKey by remember(item.id) { mutableStateOf<String?>(null) }
    var personWorkFocusRestoreGeneration by remember(item.id) { mutableIntStateOf(0) }

    // Watch Next enrichment can finish after details were opened while keeping the same internal id.
    // Key the effect by the complete immutable item so a newly resolved TMDB id is picked up.
    LaunchedEffect(item, loader) {
        displayItem = item
        relatedContent = MediaRelatedContent.Empty
        credits = MediaCredits()

        val detailed = if (
            loader != null &&
            item.tmdbId != null &&
            item.type in setOf(MediaType.Movie, MediaType.Series)
        ) {
            runCatching { loader.loadDetails(item) }.getOrDefault(item)
        } else {
            item
        }
        displayItem = detailed

        if (loader != null && detailed.tmdbId != null) {
            coroutineScope {
                val relatedDeferred = async {
                    runCatching { loader.loadRelated(detailed) }.getOrDefault(MediaRelatedContent.Empty)
                }
                val creditsDeferred = async {
                    runCatching { loader.loadCredits(detailed) }.getOrDefault(MediaCredits())
                }
                relatedContent = relatedDeferred.await()
                credits = creditsDeferred.await()
            }
        }
    }
    LaunchedEffect(selectedPersonId, loader) {
        val personId = selectedPersonId ?: run {
            selectedPerson = null
            personLoading = false
            return@LaunchedEffect
        }
        if (loader == null) {
            selectedPerson = null
            personLoading = false
            return@LaunchedEffect
        }
        personLoading = true
        selectedPerson = runCatching { loader.loadPerson(personId) }.getOrNull()
        personLoading = false
    }

    val closeRelatedMedia: () -> Unit = {
        relatedMedia = null
        if (selectedPersonId != null) {
            personWorkFocusRestoreGeneration += 1
        } else {
            detailsFocusRestoreGeneration += 1
        }
    }
    val closePerson: () -> Unit = {
        selectedPersonId = null
        selectedPerson = null
        personLoading = false
        detailsFocusRestoreGeneration += 1
    }

    BackHandler {
        when {
            relatedMedia != null -> closeRelatedMedia()
            selectedPersonId != null -> closePerson()
            else -> onBack()
        }
    }

    relatedMedia?.let { related ->
        DetailsScreen(
            item = related,
            sourceLabel = "TMDB",
            onPlay = null,
            onBack = closeRelatedMedia,
            modifier = modifier,
        )
        return
    }
    if (selectedPersonId != null) {
        PersonDetailsScreen(
            person = selectedPerson,
            isLoading = personLoading,
            onBack = closePerson,
            onOpenMedia = { media ->
                personWorkFocusRestoreKey = detailMediaFocusKey(media)
                relatedMedia = media
            },
            focusRestoreMediaKey = personWorkFocusRestoreKey,
            focusRestoreGeneration = personWorkFocusRestoreGeneration,
            modifier = modifier,
        )
        return
    }

    MediaDetailsContent(
        item = displayItem,
        sourceLabel = sourceLabel,
        relatedContent = relatedContent,
        credits = credits,
        onOpenMedia = { rowKey, media ->
            detailsFocusTarget = DetailsFocusTarget(rowKey, detailMediaFocusKey(media))
            relatedMedia = media
        },
        onOpenPerson = { rowKey, selectedPerson ->
            detailsFocusTarget = DetailsFocusTarget(rowKey, selectedPerson.tmdbId.toString())
            selectedPersonId = selectedPerson.tmdbId
        },
        focusRestoreTarget = detailsFocusTarget,
        focusRestoreGeneration = detailsFocusRestoreGeneration,
        onPlay = onPlay,
        onTrailer = onTrailer,
        onTrailerSearch = onTrailerSearch,
        modifier = modifier,
    )
}

@Composable
private fun MediaDetailsContent(
    item: MediaItem,
    sourceLabel: String?,
    relatedContent: MediaRelatedContent,
    credits: MediaCredits,
    onOpenMedia: (String, MediaItem) -> Unit,
    onOpenPerson: (String, MediaPerson) -> Unit,
    focusRestoreTarget: DetailsFocusTarget?,
    focusRestoreGeneration: Int,
    onPlay: (() -> Unit)?,
    onTrailer: (() -> Unit)?,
    onTrailerSearch: (() -> Unit)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val loader = LocalTmdbDiscoveryLoader.current
    val scrollState = rememberScrollState()
    val firstActionRequester = remember(item.id) { FocusRequester() }
    val internalTrailerId = remember(item.trailer) {
        item.trailer?.takeIf { it.provider == TrailerProvider.YouTube }?.externalId
            ?.takeIf { YouTubeEmbedPlayer.html(it) != null }
    }
    val handoff = remember(context) { ContentSearchHandoff(context.applicationContext) }
    val seriesResumeRepository = remember(context) { SeriesResumeRepository(context.applicationContext) }
    val seriesContext = remember(item) { detailSeriesContext(item) }
    val directSeriesResume = remember(item) { detailSeriesResume(item) }
    var seriesResume by remember(item.id) { mutableStateOf(directSeriesResume) }
    var seasons by remember(item.id) { mutableStateOf<List<SeriesSeason>>(emptyList()) }
    var selectedSeasonNumber by remember(item.id) { mutableStateOf<Int?>(null) }
    var selectedSeasonContent by remember(item.id) { mutableStateOf<SeriesSeasonContent?>(null) }
    var seasonSelectionTouched by remember(item.id) { mutableStateOf(false) }
    var seasonContentLoading by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, seriesContext, directSeriesResume) {
        seriesResume = directSeriesResume
        val catalogSeries = seriesContext ?: return@LaunchedEffect
        seriesResumeRepository.observe(catalogSeries).collectLatest { resume ->
            // When details were opened from Watch Next, do not replace its exact episode with a
            // weaker title-based lookup from a different row/provider.
            seriesResume = directSeriesResume ?: resume
        }
    }
    LaunchedEffect(seriesContext?.tmdbId, loader) {
        seasons = emptyList()
        selectedSeasonNumber = null
        selectedSeasonContent = null
        seasonSelectionTouched = false
        val catalogSeries = seriesContext ?: return@LaunchedEffect
        if (loader == null) return@LaunchedEffect
        val loaded = runCatching { loader.loadSeriesSeasons(catalogSeries) }.getOrDefault(emptyList())
        seasons = loaded
        selectedSeasonNumber = preferredSeriesSeason(loaded, seriesResume)
    }
    LaunchedEffect(seriesResume, seasons, seasonSelectionTouched) {
        if (seasonSelectionTouched) return@LaunchedEffect
        val resumeSeason = seriesResume?.seasonNumber ?: return@LaunchedEffect
        if (seasons.any { it.seasonNumber == resumeSeason }) selectedSeasonNumber = resumeSeason
    }
    LaunchedEffect(seriesContext?.tmdbId, selectedSeasonNumber, loader) {
        val seasonNumber = selectedSeasonNumber ?: run {
            selectedSeasonContent = null
            seasonContentLoading = false
            return@LaunchedEffect
        }
        val catalogSeries = seriesContext
        if (catalogSeries == null || loader == null) {
            selectedSeasonContent = null
            seasonContentLoading = false
            return@LaunchedEffect
        }
        seasonContentLoading = true
        selectedSeasonContent = runCatching { loader.loadSeriesSeason(catalogSeries, seasonNumber) }.getOrNull()
        seasonContentLoading = false
    }

    val externalTargets = remember(item, onPlay, handoff) {
        if ((item.tmdbId != null || item.tmdbEpisodeId != null) && onPlay == null) handoff.availableTargets()
        else emptyList()
    }
    val seriesPlaybackItem = remember(item, seriesResume) {
        if (item.type == MediaType.Series) seriesPlaybackTarget(item, seriesResume) else item
    }
    val cloudStreamDirect = remember(externalTargets, seriesPlaybackItem, handoff) {
        ContentSearchTarget.CloudStream in externalTargets &&
            handoff.mode(ContentSearchTarget.CloudStream, seriesPlaybackItem) == ContentHandoffMode.DirectPlay
    }
    val hasTrailer = internalTrailerId != null || onTrailer != null || onTrailerSearch != null
    val firstActionKey = when {
        onPlay != null -> "play"
        externalTargets.isNotEmpty() -> "external:${externalTargets.first().name}"
        hasTrailer -> "trailer"
        else -> null
    }
    val artwork = item.heroBackdropUri ?: item.backdropUri ?: item.episodeStillUri
        ?: item.sourceArtworkUri ?: item.posterUri ?: item.preferredArtworkUri

    LaunchedEffect(item.id, firstActionKey, focusRestoreTarget, focusRestoreGeneration) {
        if (focusRestoreTarget != null && focusRestoreGeneration > 0) return@LaunchedEffect
        if (firstActionKey == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstActionRequester.requestFocus() }
    }
    fun Modifier.firstAction(key: String) = if (firstActionKey == key) focusRequester(firstActionRequester) else this

    Box(modifier.fillMaxSize()) {
        artwork?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.background,
                        .34f to MaterialTheme.colorScheme.background.copy(alpha = .98f),
                        .58f to MaterialTheme.colorScheme.background.copy(alpha = .78f),
                        .82f to MaterialTheme.colorScheme.background.copy(alpha = .24f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .72f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background.copy(alpha = .90f),
                    ),
                ),
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(horizontal = 44.dp, vertical = 26.dp),
        ) {
            Spacer(Modifier.height(62.dp))
            item.logoUri?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 238.dp, height = 88.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (item.logoUri.isNullOrBlank()) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(.62f),
                )
            }

            val metadata = buildList {
                when (item.type) {
                    MediaType.Movie -> add("Film")
                    MediaType.Series -> add("Serie")
                    MediaType.Episode -> add("Episode")
                    MediaType.Unknown -> Unit
                }
                item.releaseYear?.let { add(it.toString()) }
                item.subtitle?.takeIf(String::isNotBlank)?.let(::add)
                item.durationMillis?.takeIf { it > 0 }?.let { add("${it / 60_000} Min.") }
                item.voteAverage?.takeIf { it > 0.0 }?.let { add("TMDB %.1f".format(it)) }
            }.joinToString(" · ")
            if (metadata.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    metadata,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(.62f),
                )
            }
            if (item.source.provider == "epg") sourceLabel?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(5.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                onPlay?.let { play ->
                    TouchButton(onClick = play, modifier = Modifier.firstAction("play")) {
                        Text(if ((item.playbackPositionMillis ?: 0L) > 0L) "Fortsetzen" else "Wiedergeben")
                    }
                }
                externalTargets.forEach { target ->
                    val targetItem = if (target == ContentSearchTarget.CloudStream && item.type == MediaType.Series) {
                        seriesPlaybackItem
                    } else {
                        item
                    }
                    val mode = handoff.mode(target, targetItem)
                    ProviderHandoffAction(
                        target = target,
                        handoff = handoff,
                        item = targetItem,
                        playPositionLabel = if (
                            target == ContentSearchTarget.CloudStream &&
                            item.type == MediaType.Series &&
                            mode == ContentHandoffMode.DirectPlay
                        ) {
                            "S${targetItem.seasonNumber} E${targetItem.episodeNumber}"
                        } else null,
                        modifier = Modifier.firstAction("external:${target.name}"),
                    )
                    if (
                        target == ContentSearchTarget.CloudStream &&
                        mode == ContentHandoffMode.DirectPlay &&
                        handoff.canSearch(target)
                    ) {
                        ProviderHandoffAction(
                            target = target,
                            handoff = handoff,
                            item = item,
                            forceSearch = true,
                        )
                    }
                }
                when {
                    internalTrailerId != null -> TouchButton(
                        onClick = { TrailerPlayerActivity.start(context, internalTrailerId) },
                        modifier = Modifier.firstAction("trailer"),
                    ) { Text("Trailer") }
                    onTrailer != null -> TouchButton(onClick = onTrailer, modifier = Modifier.firstAction("trailer")) { Text("Trailer") }
                    onTrailerSearch != null -> TouchButton(onClick = onTrailerSearch, modifier = Modifier.firstAction("trailer")) { Text("Trailer suchen") }
                }
            }

            if (item.type == MediaType.Series && cloudStreamDirect) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (seriesResume != null) {
                        "Weiterschauen in CloudStream: ${seriesResume?.label}"
                    } else {
                        "CloudStream startet bei S1 E1"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item.overview?.takeIf(String::isNotBlank)?.let { overview ->
                Spacer(Modifier.height(18.dp))
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .94f),
                    modifier = Modifier.fillMaxWidth(.62f),
                )
            }

            if (seriesContext != null && seasons.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                SeriesEpisodesSection(
                    seasons = seasons,
                    selectedSeasonNumber = selectedSeasonNumber,
                    seasonContent = selectedSeasonContent,
                    isLoading = seasonContentLoading,
                    resume = seriesResume,
                    onSelectSeason = { seasonNumber ->
                        seasonSelectionTouched = true
                        selectedSeasonNumber = seasonNumber
                    },
                    onPlayEpisode = { episode ->
                        if (ContentSearchTarget.CloudStream in externalTargets) {
                            handoff.launch(ContentSearchTarget.CloudStream, episode)
                        }
                    },
                )
            }

            val hasSimilar = relatedContent.similar.isNotEmpty()
            val collection = relatedContent.collection?.takeIf { it.items.isNotEmpty() }
            val hasCollection = collection != null

            if (hasSimilar) {
                Spacer(Modifier.height(30.dp))
                MediaRow(
                    title = when (item.type) {
                        MediaType.Movie -> "Ähnliche Filme"
                        else -> "Ähnliche Serien"
                    },
                    rowKey = DETAILS_ROW_SIMILAR,
                    items = relatedContent.similar,
                    onOpen = onOpenMedia,
                    focusRestoreTarget = focusRestoreTarget,
                    focusRestoreGeneration = focusRestoreGeneration,
                )
            }
            collection?.let { mediaCollection ->
                Spacer(Modifier.height(if (hasSimilar) 22.dp else 30.dp))
                MediaRow(
                    title = "Filmreihe · ${mediaCollection.title}",
                    rowKey = DETAILS_ROW_COLLECTION,
                    items = mediaCollection.items,
                    onOpen = onOpenMedia,
                    focusRestoreTarget = focusRestoreTarget,
                    focusRestoreGeneration = focusRestoreGeneration,
                )
            }
            if (credits.cast.isNotEmpty()) {
                Spacer(Modifier.height(if (hasSimilar || hasCollection) 22.dp else 30.dp))
                PersonRow(
                    title = "Schauspieler",
                    rowKey = DETAILS_ROW_CAST,
                    people = credits.cast,
                    onOpen = onOpenPerson,
                    focusRestoreTarget = focusRestoreTarget,
                    focusRestoreGeneration = focusRestoreGeneration,
                )
            }
            if (credits.directors.isNotEmpty()) {
                Spacer(
                    Modifier.height(
                        if (hasSimilar || hasCollection || credits.cast.isNotEmpty()) 22.dp else 30.dp,
                    ),
                )
                PersonRow(
                    title = "Regie",
                    rowKey = DETAILS_ROW_DIRECTORS,
                    people = credits.directors,
                    onOpen = onOpenPerson,
                    focusRestoreTarget = focusRestoreTarget,
                    focusRestoreGeneration = focusRestoreGeneration,
                )
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}

@Composable
private fun SeriesEpisodesSection(
    seasons: List<SeriesSeason>,
    selectedSeasonNumber: Int?,
    seasonContent: SeriesSeasonContent?,
    isLoading: Boolean,
    resume: SeriesResumePosition?,
    onSelectSeason: (Int) -> Unit,
    onPlayEpisode: (MediaItem) -> Unit,
) {
    Text("Staffeln", style = MaterialTheme.typography.titleMedium)
    val seasonRowState = rememberLazyListState()
    LaunchedEffect(seasons, selectedSeasonNumber) {
        val targetIndex = seasons.indexOfFirst { it.seasonNumber == selectedSeasonNumber }
        if (targetIndex >= 0) seasonRowState.scrollToItem(targetIndex)
    }
    LazyRow(
        state = seasonRowState,
        modifier = Modifier.fillMaxWidth().touchScrollFallback(seasonRowState, Orientation.Horizontal),
        contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp, end = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(seasons, key = { "season-${it.seasonNumber}" }) { season ->
            TouchButton(onClick = { onSelectSeason(season.seasonNumber) }) {
                Text(
                    buildString {
                        if (season.seasonNumber == selectedSeasonNumber) append("● ")
                        append(season.title)
                        if (season.episodeCount > 0) append(" · ${season.episodeCount}")
                    },
                )
            }
        }
    }

    when {
        isLoading -> Text(
            "Episoden werden geladen …",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        seasonContent != null -> {
            Text(
                "Episoden · ${seasonContent.season.title}",
                style = MaterialTheme.typography.titleMedium,
            )
            val episodeRowState = rememberLazyListState()
            LaunchedEffect(
                seasonContent.season.seasonNumber,
                seasonContent.episodes,
                resume?.seasonNumber,
                resume?.episodeNumber,
            ) {
                if (resume?.seasonNumber != seasonContent.season.seasonNumber) return@LaunchedEffect
                val targetIndex = seasonContent.episodes.indexOfFirst {
                    it.episodeNumber == resume.episodeNumber
                }
                if (targetIndex >= 0) episodeRowState.scrollToItem(targetIndex)
            }
            LazyRow(
                state = episodeRowState,
                modifier = Modifier.fillMaxWidth().touchScrollFallback(episodeRowState, Orientation.Horizontal),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp, end = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(
                    seasonContent.episodes,
                    key = { "episode-${it.tmdbEpisodeId ?: it.id}" },
                ) { episode ->
                    val isResumeEpisode = resume?.let {
                        it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber
                    } == true
                    EpisodeCard(
                        episode = episode,
                        isResumeEpisode = isResumeEpisode,
                        onClick = { onPlayEpisode(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: MediaItem,
    isResumeEpisode: Boolean,
    onClick: () -> Unit,
) {
    TouchCard(onClick = onClick) {
        Column(Modifier.width(246.dp)) {
            Box(
                Modifier.width(246.dp).height(138.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val artwork = episode.episodeStillUri ?: episode.backdropUri ?: episode.posterUri
                artwork?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isResumeEpisode) {
                    Text(
                        "WEITERSCHAUEN",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = .82f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append("E${episode.episodeNumber ?: 0}")
                    episode.episodeTitle?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.overview?.takeIf(String::isNotBlank)?.let { overview ->
                Spacer(Modifier.height(3.dp))
                Text(
                    overview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProviderHandoffAction(
    target: ContentSearchTarget,
    handoff: ContentSearchHandoff,
    item: MediaItem,
    forceSearch: Boolean = false,
    playPositionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val appIcon = remember(target, handoff) { handoff.appIcon(target) }
    val mode = remember(target, item, handoff, forceSearch) {
        if (forceSearch) ContentHandoffMode.Search else handoff.mode(target, item)
    }
    val isDirectPlay = mode == ContentHandoffMode.DirectPlay
    val actionDescription = if (isDirectPlay) {
        "Mit ${target.displayName} abspielen"
    } else {
        "In ${target.displayName} suchen"
    }
    TouchButton(
        onClick = {
            if (forceSearch) handoff.launchSearch(target, item) else handoff.launch(target, item)
        },
        onLongClick = if (target == ContentSearchTarget.CloudStream && isDirectPlay && !forceSearch) {
            { handoff.launchProviderChooser(target, item) }
        } else {
            null
        },
        modifier = modifier.width(if (playPositionLabel == null) 82.dp else 142.dp).height(48.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        if (appIcon != null) {
            AsyncImage(
                model = appIcon,
                contentDescription = target.displayName,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)),
            )
        } else {
            Text(target.displayName.take(1), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.width(1.dp).height(20.dp)
                .background(LocalContentColor.current.copy(alpha = .22f)),
        )
        Spacer(Modifier.width(8.dp))
        Image(
            painter = painterResource(if (isDirectPlay) R.drawable.ic_play else R.drawable.ic_search),
            contentDescription = actionDescription,
            modifier = Modifier.size(17.dp),
            colorFilter = ColorFilter.tint(LocalContentColor.current),
        )
        playPositionLabel?.let { label ->
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    rowKey: String,
    items: List<MediaItem>,
    onOpen: (String, MediaItem) -> Unit,
    focusRestoreTarget: DetailsFocusTarget?,
    focusRestoreGeneration: Int,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val rowState = rememberLazyListState()
    val restoreRequester = remember(rowKey) { FocusRequester() }
    val restoreItemKey = focusRestoreTarget?.takeIf { it.rowKey == rowKey }?.itemKey
    LaunchedEffect(items, restoreItemKey, focusRestoreGeneration) {
        if (restoreItemKey == null || focusRestoreGeneration <= 0) return@LaunchedEffect
        val targetIndex = items.indexOfFirst { detailMediaFocusKey(it) == restoreItemKey }
        if (targetIndex < 0) return@LaunchedEffect
        rowState.scrollToItem(targetIndex)
        withFrameNanos { }
        runCatching { restoreRequester.requestFocus() }
    }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth().touchScrollFallback(rowState, Orientation.Horizontal),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp, end = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = { "detail-related-${it.type}-${it.tmdbId}" }) { media ->
            val cardModifier = if (detailMediaFocusKey(media) == restoreItemKey) {
                Modifier.focusRequester(restoreRequester)
            } else Modifier
            WatchNextCard(
                item = media,
                onClick = { onOpen(rowKey, media) },
                modifier = cardModifier,
            )
        }
    }
}

@Composable
private fun PersonRow(
    title: String,
    rowKey: String,
    people: List<MediaPerson>,
    onOpen: (String, MediaPerson) -> Unit,
    focusRestoreTarget: DetailsFocusTarget?,
    focusRestoreGeneration: Int,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val rowState = rememberLazyListState()
    val restoreRequester = remember(rowKey) { FocusRequester() }
    val restoreItemKey = focusRestoreTarget?.takeIf { it.rowKey == rowKey }?.itemKey
    LaunchedEffect(people, restoreItemKey, focusRestoreGeneration) {
        if (restoreItemKey == null || focusRestoreGeneration <= 0) return@LaunchedEffect
        val targetIndex = people.indexOfFirst { it.tmdbId.toString() == restoreItemKey }
        if (targetIndex < 0) return@LaunchedEffect
        rowState.scrollToItem(targetIndex)
        withFrameNanos { }
        runCatching { restoreRequester.requestFocus() }
    }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth().touchScrollFallback(rowState, Orientation.Horizontal),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp, end = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(people, key = { "person-${it.tmdbId}" }) { person ->
            val cardModifier = if (person.tmdbId.toString() == restoreItemKey) {
                Modifier.focusRequester(restoreRequester)
            } else Modifier
            TouchCard(onClick = { onOpen(rowKey, person) }, modifier = cardModifier) {
                Column(Modifier.width(116.dp)) {
                    Box(
                        Modifier.width(116.dp).height(154.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        person.profileUri?.let { profile ->
                            AsyncImage(
                                model = profile,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(person.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    person.role?.takeIf(String::isNotBlank)?.let { role ->
                        Text(
                            role,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
