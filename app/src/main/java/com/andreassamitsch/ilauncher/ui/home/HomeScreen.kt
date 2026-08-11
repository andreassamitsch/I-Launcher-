package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.data.home.HomePreferences
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifState
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.ui.components.AppCard
import com.andreassamitsch.ilauncher.ui.components.LiveTvCard
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.TouchCard
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    apps: List<InstalledApp>,
    watchNextItems: List<EnrichedWatchNextItem>,
    watchNextError: String?,
    previewChannels: List<AppContentChannel>,
    previewChannelsError: String?,
    hasTvListingsPermission: Boolean,
    liveTvState: OpenWebifState,
    homeRowOrder: List<String>,
    onMoveHomeApp: (String, Int) -> Unit,
    onRequestTvListingsPermission: () -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
    onOpenWatchNext: (EnrichedWatchNextItem) -> Unit,
    onOpenWatchNextDetails: (EnrichedWatchNextItem) -> Unit,
    onOpenMediaDetails: (MediaItem, String?) -> Unit,
    onOpenPreviewProgram: (AppContentChannel, AppContentProgram) -> Unit,
    onOpenLiveTv: () -> Unit,
    onPlayLiveTvChannel: (LiveTvChannel) -> Unit,
    onNavigationVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    watchNextListState: LazyListState = rememberLazyListState(),
    liveTvListState: LazyListState = rememberLazyListState(),
    appsListState: LazyListState = rememberLazyListState(),
    watchNextFocusRestoreSourceId: String? = null,
    watchNextFocusRestoreGeneration: Int = 0,
    liveTvFocusRestoreServiceReference: String? = null,
    liveTvFocusRestoreGeneration: Int = 0,
) {
    val watchNextRestoreFocusRequester = remember { FocusRequester() }
    val liveTvRestoreFocusRequester = remember { FocusRequester() }
    val contentScrollState = rememberScrollState()
    val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
    val visiblePreviewChannels = remember(previewChannels) {
        previewChannels.filter { it.programs.isNotEmpty() }
    }
    val previewByRowKey = remember(visiblePreviewChannels) {
        visiblePreviewChannels.associateBy { HomePreferences.previewRowKey(it.id) }
    }
    val defaultHero = remember(watchNextItems, visiblePreviewChannels, appLabels) {
        watchNextItems.firstOrNull()?.let { item ->
            mediaHero(item.media, item.media.source.packageName?.let(appLabels::get))
        } ?: visiblePreviewChannels.firstOrNull()?.let { channel ->
            channel.programs.firstOrNull()?.let { program ->
                mediaHero(program.media, channel.packageName?.let(appLabels::get) ?: channel.title)
            }
        } ?: HomeHeroContent(
            key = "launcher",
            eyebrow = "Für dich",
            title = "Inhalte zuerst",
            description = "Weiterschauen, App-Kanäle und Live-TV an einem Ort – ohne Werbung.",
        )
    }
    var hero by remember { mutableStateOf(defaultHero) }
    var heroSelectedByUser by remember { mutableStateOf(false) }
    var movingAppPackage by remember { mutableStateOf<String?>(null) }

    fun selectHero(content: HomeHeroContent) {
        heroSelectedByUser = true
        hero = content
        onNavigationVisibilityChange(false)
    }

    LaunchedEffect(defaultHero, heroSelectedByUser) {
        if (!heroSelectedByUser) hero = defaultHero
    }

    LaunchedEffect(watchNextFocusRestoreSourceId, watchNextFocusRestoreGeneration) {
        val sourceId = watchNextFocusRestoreSourceId ?: return@LaunchedEffect
        if (watchNextFocusRestoreGeneration <= 0) return@LaunchedEffect
        val targetIndex = watchNextItems.indexOfFirst { it.media.source.sourceId == sourceId }
        if (targetIndex < 0) return@LaunchedEffect
        watchNextListState.scrollToItem(targetIndex)
        withFrameNanos { }
        watchNextRestoreFocusRequester.requestFocus()
    }

    LaunchedEffect(liveTvFocusRestoreServiceReference, liveTvFocusRestoreGeneration) {
        val serviceReference = liveTvFocusRestoreServiceReference ?: return@LaunchedEffect
        if (liveTvFocusRestoreGeneration <= 0) return@LaunchedEffect
        val targetIndex = liveTvState.channels.indexOfFirst { it.serviceReference == serviceReference }
        if (targetIndex < 0) return@LaunchedEffect
        liveTvListState.scrollToItem(targetIndex)
        withFrameNanos { }
        liveTvRestoreFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .touchScrollFallback(contentScrollState, Orientation.Vertical),
    ) {
        HomeHero(
            content = hero,
            onOpenMediaDetails = onOpenMediaDetails,
            onOpenApp = onOpenApp,
            onFocused = { onNavigationVisibilityChange(true) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(contentScrollState)
                    .touchScrollFallback(contentScrollState, Orientation.Vertical)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                homeRowOrder.forEach { rowKey ->
                    when (rowKey) {
                        HomePreferences.ROW_WATCH_NEXT -> WatchNextHomeRow(
                            items = watchNextItems,
                            error = watchNextError,
                            hasTvListingsPermission = hasTvListingsPermission,
                            onRequestTvListingsPermission = onRequestTvListingsPermission,
                            appLabels = appLabels,
                            listState = watchNextListState,
                            restoreSourceId = watchNextFocusRestoreSourceId,
                            restoreRequester = watchNextRestoreFocusRequester,
                            onOpen = onOpenWatchNext,
                            onDetails = onOpenWatchNextDetails,
                            onFocused = ::selectHero,
                        )

                        HomePreferences.ROW_LIVE_TV -> if (liveTvState.configured) {
                            LiveTvHomeRow(
                                state = liveTvState,
                                listState = liveTvListState,
                                restoreServiceReference = liveTvFocusRestoreServiceReference,
                                restoreRequester = liveTvRestoreFocusRequester,
                                onConfigure = onOpenLiveTv,
                                onPlay = onPlayLiveTvChannel,
                                onFocused = ::selectHero,
                            )
                        }

                        HomePreferences.ROW_APPS -> AppsHomeRow(
                            apps = apps,
                            listState = appsListState,
                            movingAppPackage = movingAppPackage,
                            onMoveMode = { movingAppPackage = it },
                            onMove = onMoveHomeApp,
                            onOpen = onOpenApp,
                            onFocused = { onNavigationVisibilityChange(false) },
                        )

                        else -> previewByRowKey[rowKey]?.let { channel ->
                            PreviewHomeRow(
                                channel = channel,
                                sourceApp = channel.packageName?.let(appsByPackage::get),
                                sourceLabel = channel.packageName?.let(appLabels::get) ?: channel.title,
                                onOpen = onOpenPreviewProgram,
                                onFocused = ::selectHero,
                            )
                        }
                    }
                }

                if (hasTvListingsPermission && previewChannelsError != null) {
                    Text(
                        text = previewChannelsError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 38.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            if (contentScrollState.value > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(38.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.0f),
                                ),
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun WatchNextHomeRow(
    items: List<EnrichedWatchNextItem>,
    error: String?,
    hasTvListingsPermission: Boolean,
    onRequestTvListingsPermission: () -> Unit,
    appLabels: Map<String, String>,
    listState: LazyListState,
    restoreSourceId: String?,
    restoreRequester: FocusRequester,
    onOpen: (EnrichedWatchNextItem) -> Unit,
    onDetails: (EnrichedWatchNextItem) -> Unit,
    onFocused: (HomeHeroContent) -> Unit,
) {
    HomeRowHeader("Weiterschauen")
    when {
        !hasTvListingsPermission -> TouchButton(
            onClick = onRequestTvListingsPermission,
            modifier = Modifier.padding(horizontal = 38.dp),
        ) {
            Text("TV-Inhalte freigeben")
        }
        error != null -> Text(
            error,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 38.dp),
        )
        items.isEmpty() -> Text(
            "Keine Weiterschauen-Einträge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 38.dp),
        )
        else -> LazyRow(
            state = listState,
            modifier = Modifier.touchScrollFallback(listState, Orientation.Horizontal),
            contentPadding = PaddingValues(start = 38.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                items = items,
                key = { "watch-next-${it.sourceItem.id}-${it.sourceItem.sourceOrder}" },
            ) { item ->
                val cardModifier = if (item.media.source.sourceId == restoreSourceId) {
                    Modifier.focusRequester(restoreRequester)
                } else Modifier
                val sourceLabel = item.media.source.packageName?.let(appLabels::get)
                WatchNextCard(
                    item = item.media,
                    onClick = { onOpen(item) },
                    onDetails = { onDetails(item) },
                    onFocused = { onFocused(mediaHero(item.media, sourceLabel)) },
                    modifier = cardModifier,
                )
            }
        }
    }
}

@Composable
private fun LiveTvHomeRow(
    state: OpenWebifState,
    listState: LazyListState,
    restoreServiceReference: String?,
    restoreRequester: FocusRequester,
    onConfigure: () -> Unit,
    onPlay: (LiveTvChannel) -> Unit,
    onFocused: (HomeHeroContent) -> Unit,
) {
    HomeRowHeader("Jetzt im TV")
    when {
        state.channels.isNotEmpty() -> LazyRow(
            state = listState,
            modifier = Modifier.touchScrollFallback(listState, Orientation.Horizontal),
            contentPadding = PaddingValues(start = 38.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.channels, key = LiveTvChannel::serviceReference) { channel ->
                val cardModifier = if (channel.serviceReference == restoreServiceReference) {
                    Modifier.focusRequester(restoreRequester)
                } else Modifier
                LiveTvCard(
                    channel = channel,
                    onClick = { onPlay(channel) },
                    onFocused = { onFocused(liveTvHero(channel)) },
                    modifier = cardModifier,
                )
            }
        }
        state.isRefreshing -> Text(
            "Live TV wird aktualisiert …",
            modifier = Modifier.padding(horizontal = 38.dp),
        )
        else -> TouchButton(
            onClick = onConfigure,
            modifier = Modifier.padding(horizontal = 38.dp),
        ) { Text("Live TV konfigurieren") }
    }
}

@Composable
private fun PreviewHomeRow(
    channel: AppContentChannel,
    sourceApp: InstalledApp?,
    sourceLabel: String,
    onOpen: (AppContentChannel, AppContentProgram) -> Unit,
    onFocused: (HomeHeroContent) -> Unit,
) {
    key(channel.id) {
        val listState = rememberLazyListState()
        HomeRowHeader(channel.title, sourceApp)
        LazyRow(
            state = listState,
            modifier = Modifier.touchScrollFallback(listState, Orientation.Horizontal),
            contentPadding = PaddingValues(start = 38.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(channel.programs, key = { it.media.id }) { program ->
                WatchNextCard(
                    item = program.media,
                    onClick = { onOpen(channel, program) },
                    onFocused = { onFocused(mediaHero(program.media, sourceLabel)) },
                )
            }
        }
    }
}

@Composable
private fun AppsHomeRow(
    apps: List<InstalledApp>,
    listState: LazyListState,
    movingAppPackage: String?,
    onMoveMode: (String?) -> Unit,
    onMove: (String, Int) -> Unit,
    onOpen: (InstalledApp) -> Unit,
    onFocused: () -> Unit,
) {
    HomeRowHeader("Meine Apps")
    if (apps.isEmpty()) {
        Text(
            "Installierte Apps werden geladen …",
            modifier = Modifier.padding(horizontal = 38.dp),
        )
    } else {
        LazyRow(
            state = listState,
            modifier = Modifier.touchScrollFallback(listState, Orientation.Horizontal),
            contentPadding = PaddingValues(start = 38.dp, end = 18.dp, top = 3.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(apps, key = InstalledApp::packageName) { app ->
                val moveMode = movingAppPackage == app.packageName
                AppCard(
                    app = app,
                    onClick = {
                        if (moveMode) onMoveMode(null) else onOpen(app)
                    },
                    onLongClick = { onMoveMode(app.packageName) },
                    moveMode = moveMode,
                    onMove = { delta -> onMove(app.packageName, delta) },
                    onFocused = onFocused,
                )
            }
        }
    }
}

@Composable
private fun HomeRowHeader(title: String, sourceApp: InstalledApp? = null) {
    Row(
        modifier = Modifier.padding(start = 38.dp, end = 18.dp, top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sourceApp?.let { app ->
            val icon = remember(app.icon) { app.icon.asImageBitmap() }
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeHero(
    content: HomeHeroContent,
    onOpenMediaDetails: (MediaItem, String?) -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
    onFocused: () -> Unit,
) {
    TouchCard(
        onClick = {
            when {
                content.detailsMedia != null -> onOpenMediaDetails(content.detailsMedia, content.sourceLabel)
                content.app != null -> onOpenApp(content.app)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        scale = CardDefaults.scale(focusedScale = 1.0f),
    ) {
        Crossfade(
            targetState = content,
            animationSpec = tween(durationMillis = 230),
            label = "home-hero",
        ) { heroContent ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                heroContent.artworkUri?.takeIf { it.isNotBlank() }?.let { artwork ->
                    if (heroContent.fitArtwork) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .fillMaxWidth(0.62f)
                                .fillMaxHeight(0.94f)
                                .padding(end = 4.dp, top = 6.dp),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            AsyncImage(
                                model = artwork,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        AsyncImage(
                            model = artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.00f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                                    0.25f to MaterialTheme.colorScheme.background.copy(alpha = 0.91f),
                                    0.44f to MaterialTheme.colorScheme.background.copy(alpha = 0.63f),
                                    0.64f to MaterialTheme.colorScheme.background.copy(alpha = 0.21f),
                                    0.83f to MaterialTheme.colorScheme.background.copy(alpha = 0.02f),
                                    1.00f to MaterialTheme.colorScheme.background.copy(alpha = 0.00f),
                                ),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to MaterialTheme.colorScheme.background.copy(alpha = 0.04f),
                                    0.50f to MaterialTheme.colorScheme.background.copy(alpha = 0.03f),
                                    0.78f to MaterialTheme.colorScheme.background.copy(alpha = 0.38f),
                                    1.00f to MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                                ),
                            ),
                        ),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.43f)
                        .padding(start = 38.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    heroContent.logoUri?.takeIf { it.isNotBlank() }?.let { logo ->
                        AsyncImage(
                            model = logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 230.dp, height = 72.dp),
                        )
                    }
                    heroContent.eyebrow?.takeIf { it.isNotBlank() }?.let { eyebrow ->
                        Text(
                            text = eyebrow,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!heroContent.titleCoveredByLogo || heroContent.logoUri.isNullOrBlank()) {
                        Text(
                            text = heroContent.title,
                            style = MaterialTheme.typography.headlineLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    heroContent.metadata?.takeIf { it.isNotBlank() }?.let { metadata ->
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    heroContent.description?.takeIf { it.isNotBlank() }?.let { description ->
                        AutoScrollingHeroDescription(heroContent.key, description)
                    }
                    if (heroContent.detailsMedia != null || heroContent.app != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = if (heroContent.app != null) "Öffnen" else "Details",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.background,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoScrollingHeroDescription(key: String, text: String) {
    val scrollState = remember(key, text) { androidx.compose.foundation.ScrollState(0) }
    LaunchedEffect(key, text, scrollState.maxValue) {
        if (scrollState.maxValue <= 0) return@LaunchedEffect
        delay(7_500)
        while (true) {
            val duration = (scrollState.maxValue * 125).coerceIn(16_000, 52_000)
            scrollState.animateScrollTo(
                scrollState.maxValue,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing),
            )
            delay(5_500)
            scrollState.scrollTo(0)
            delay(7_500)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .verticalScroll(scrollState),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private data class HomeHeroContent(
    val key: String,
    val title: String,
    val eyebrow: String? = null,
    val metadata: String? = null,
    val description: String? = null,
    val artworkUri: String? = null,
    val fitArtwork: Boolean = false,
    val logoUri: String? = null,
    val titleCoveredByLogo: Boolean = false,
    val detailsMedia: MediaItem? = null,
    val sourceLabel: String? = null,
    val app: InstalledApp? = null,
)

private fun mediaHero(item: MediaItem, sourceLabel: String?): HomeHeroContent {
    val metadata = buildList {
        when (item.type) {
            MediaType.Movie -> add("Film")
            MediaType.Series -> add("Serie")
            MediaType.Episode -> {
                val episode = buildString {
                    item.seasonNumber?.let { append("S$it") }
                    item.episodeNumber?.let { append(" E$it") }
                }.trim()
                add(if (episode.isBlank()) "Episode" else episode)
            }
            MediaType.Unknown -> Unit
        }
        item.releaseYear?.let { add(it.toString()) }
        item.voteAverage?.takeIf { it > 0.0 }?.let { add("TMDB %.1f".format(it)) }
    }.joinToString(" · ")
    val artwork = mediaHeroArtwork(item)

    return HomeHeroContent(
        key = "media:${item.source.provider}:${item.source.sourceId}",
        title = item.title,
        metadata = metadata.takeIf { it.isNotBlank() },
        description = item.overview ?: item.episodeTitle,
        artworkUri = artwork.first,
        fitArtwork = artwork.second,
        logoUri = item.logoUri,
        titleCoveredByLogo = !item.logoUri.isNullOrBlank(),
        detailsMedia = item,
        sourceLabel = sourceLabel,
    )
}

internal fun mediaHeroArtwork(item: MediaItem): Pair<String?, Boolean> {
    if (item.tmdbId != null) {
        return when (item.type) {
            MediaType.Episode -> (item.episodeStillUri ?: item.backdropUri) to false
            MediaType.Series,
            MediaType.Movie,
            -> item.backdropUri to false
            MediaType.Unknown -> (item.backdropUri ?: item.episodeStillUri) to false
        }
    }

    return when (item.type) {
        MediaType.Episode -> when {
            !item.episodeStillUri.isNullOrBlank() -> item.episodeStillUri to false
            !item.backdropUri.isNullOrBlank() -> item.backdropUri to false
            else -> item.sourceArtworkUri to true
        }
        MediaType.Series,
        MediaType.Movie,
        -> when {
            !item.backdropUri.isNullOrBlank() -> item.backdropUri to false
            else -> item.sourceArtworkUri to true
        }
        MediaType.Unknown -> when {
            !item.backdropUri.isNullOrBlank() -> item.backdropUri to false
            !item.episodeStillUri.isNullOrBlank() -> item.episodeStillUri to false
            else -> item.sourceArtworkUri to true
        }
    }
}

private fun liveTvHero(channel: LiveTvChannel): HomeHeroContent {
    val now = channel.now
    val metadata = buildList {
        now?.let { add("${formatHeroTime(it.startUtcMillis)}–${formatHeroTime(it.endUtcMillis)}") }
        now?.seasonNumber?.let { season ->
            val episode = now.episodeNumber?.let { " E$it" }.orEmpty()
            add("S$season$episode")
        }
        now?.releaseYear?.let { add(it.toString()) }
        now?.categories?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
        now?.voteAverage?.takeIf { it > 0.0 }?.let { add("TMDB %.1f".format(it)) }
    }.joinToString(" · ")
    val description = now?.longDescription ?: now?.shortDescription
    val artwork = liveTvHeroArtwork(now)

    return HomeHeroContent(
        key = "live:${channel.serviceReference}",
        eyebrow = channel.name,
        title = now?.title ?: channel.name,
        metadata = metadata.takeIf { it.isNotBlank() },
        description = description,
        artworkUri = artwork.first,
        fitArtwork = artwork.second,
        logoUri = channel.piconUri,
        detailsMedia = now?.let { liveProgramMedia(channel, it) },
        sourceLabel = channel.name,
    )
}

internal fun liveTvHeroArtwork(program: LiveTvProgram?): Pair<String?, Boolean> {
    if (program == null) return null to false
    if (program.tmdbId == null) return program.imageUri to true

    return when (program.tmdbType ?: MediaType.Unknown) {
        MediaType.Episode -> (program.episodeStillUri ?: program.backdropUri) to false
        MediaType.Series,
        MediaType.Movie,
        -> program.backdropUri to false
        MediaType.Unknown -> (program.backdropUri ?: program.episodeStillUri) to false
    }
}

private fun liveProgramMedia(channel: LiveTvChannel, program: LiveTvProgram): MediaItem = MediaItem(
    id = "live:${channel.serviceReference}:${program.startUtcMillis}",
    type = program.tmdbType ?: MediaType.Unknown,
    title = program.title,
    subtitle = program.subtitle,
    overview = program.longDescription ?: program.shortDescription,
    releaseYear = program.releaseYear,
    tmdbId = program.tmdbId,
    tmdbEpisodeId = program.tmdbEpisodeId,
    seasonNumber = program.seasonNumber,
    episodeNumber = program.episodeNumber,
    posterUri = program.posterUri,
    backdropUri = program.backdropUri,
    episodeStillUri = program.episodeStillUri,
    sourceArtworkUri = program.imageUri,
    voteAverage = program.voteAverage,
    source = MediaSource(
        provider = "openwebif",
        sourceId = "${channel.serviceReference}:${program.startUtcMillis}",
    ),
)

private fun appHero(app: InstalledApp): HomeHeroContent = HomeHeroContent(
    key = "app:${app.packageName}",
    eyebrow = "App",
    title = app.label,
    app = app,
)

private fun formatHeroTime(utcMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMillis))
