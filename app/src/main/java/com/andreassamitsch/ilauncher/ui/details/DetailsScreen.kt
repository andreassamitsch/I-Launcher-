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

    LaunchedEffect(item.id, loader) {
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

    BackHandler {
        when {
            relatedMedia != null -> relatedMedia = null
            selectedPersonId != null -> {
                selectedPersonId = null
                selectedPerson = null
                personLoading = false
            }
            else -> onBack()
        }
    }

    relatedMedia?.let { related ->
        DetailsScreen(
            item = related,
            sourceLabel = "TMDB",
            onPlay = null,
            onBack = { relatedMedia = null },
            modifier = modifier,
        )
        return
    }
    if (selectedPersonId != null) {
        PersonDetailsScreen(
            person = selectedPerson,
            isLoading = personLoading,
            onBack = {
                selectedPersonId = null
                selectedPerson = null
            },
            onOpenMedia = { relatedMedia = it },
            modifier = modifier,
        )
        return
    }

    MediaDetailsContent(
        item = displayItem,
        sourceLabel = sourceLabel,
        relatedContent = relatedContent,
        credits = credits,
        onOpenMedia = { relatedMedia = it },
        onOpenPerson = { selectedPersonId = it.tmdbId },
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
    onOpenMedia: (MediaItem) -> Unit,
    onOpenPerson: (MediaPerson) -> Unit,
    onPlay: (() -> Unit)?,
    onTrailer: (() -> Unit)?,
    onTrailerSearch: (() -> Unit)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val firstActionRequester = remember(item.id) { FocusRequester() }
    val internalTrailerId = remember(item.trailer) {
        item.trailer?.takeIf { it.provider == TrailerProvider.YouTube }?.externalId
            ?.takeIf { YouTubeEmbedPlayer.html(it) != null }
    }
    val handoff = remember(context) { ContentSearchHandoff(context.applicationContext) }
    val externalTargets = remember(item, onPlay, handoff) {
        if ((item.tmdbId != null || item.tmdbEpisodeId != null) && onPlay == null) handoff.availableTargets()
        else emptyList()
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

    LaunchedEffect(item.id, firstActionKey) {
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
                    ProviderHandoffAction(
                        target = target,
                        handoff = handoff,
                        item = item,
                        modifier = Modifier.firstAction("external:${target.name}"),
                    )
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

            item.overview?.takeIf(String::isNotBlank)?.let { overview ->
                Spacer(Modifier.height(18.dp))
                Text(
                    overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .94f),
                    modifier = Modifier.fillMaxWidth(.62f),
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
                    items = relatedContent.similar,
                    onOpen = onOpenMedia,
                )
            }
            collection?.let { mediaCollection ->
                Spacer(Modifier.height(if (hasSimilar) 22.dp else 30.dp))
                MediaRow(
                    title = "Filmreihe · ${mediaCollection.title}",
                    items = mediaCollection.items,
                    onOpen = onOpenMedia,
                )
            }
            if (credits.cast.isNotEmpty()) {
                Spacer(Modifier.height(if (hasSimilar || hasCollection) 22.dp else 30.dp))
                PersonRow("Schauspieler", credits.cast, onOpenPerson)
            }
            if (credits.directors.isNotEmpty()) {
                Spacer(
                    Modifier.height(
                        if (hasSimilar || hasCollection || credits.cast.isNotEmpty()) 22.dp else 30.dp,
                    ),
                )
                PersonRow("Regie", credits.directors, onOpenPerson)
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}

@Composable
private fun ProviderHandoffAction(
    target: ContentSearchTarget,
    handoff: ContentSearchHandoff,
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val appIcon = remember(target, handoff) { handoff.appIcon(target) }
    val mode = remember(target, item, handoff) { handoff.mode(target, item) }
    val isDirectPlay = mode == ContentHandoffMode.DirectPlay
    val actionDescription = if (isDirectPlay) {
        "Mit ${target.displayName} abspielen"
    } else {
        "In ${target.displayName} suchen"
    }
    TouchButton(
        onClick = { handoff.launch(target, item) },
        modifier = modifier.width(82.dp).height(48.dp),
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
    }
}

@Composable
private fun MediaRow(
    title: String,
    items: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth().touchScrollFallback(rowState, Orientation.Horizontal),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp, end = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = { "detail-related-${it.type}-${it.tmdbId}" }) { media ->
            WatchNextCard(item = media, onClick = { onOpen(media) })
        }
    }
}

@Composable
private fun PersonRow(
    title: String,
    people: List<MediaPerson>,
    onOpen: (MediaPerson) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth().touchScrollFallback(rowState, Orientation.Horizontal),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp, end = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(people, key = { "person-${it.tmdbId}" }) { person ->
            TouchCard(onClick = { onOpen(person) }) {
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
