package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
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
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    apps: List<InstalledApp>,
    watchNextItems: List<EnrichedWatchNextItem>,
    watchNextError: String?,
    previewChannels: List<AppContentChannel>,
    previewChannelsError: String?,
    hasTvListingsPermission: Boolean,
    liveTvState: OpenWebifState,
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
    val visiblePreviewChannels = remember(previewChannels) {
        previewChannels.filter { it.programs.isNotEmpty() }
    }
    val defaultHero = remember(watchNextItems, liveTvState.channels, apps, appLabels) {
        watchNextItems.firstOrNull()?.let { item ->
            mediaHero(
                item = item.media,
                sourceLabel = item.media.source.packageName?.let(appLabels::get),
            )
        } ?: liveTvState.channels.firstOrNull()?.let(::liveTvHero)
            ?: apps.firstOrNull()?.let(::appHero)
            ?: HomeHeroContent(
                key = "launcher",
                eyebrow = "Home",
                title = "Deine Inhalte. Deine Apps. Keine Werbung.",
                description = "Content zuerst – schnell, ruhig und für die Fernbedienung gebaut.",
            )
    }
    var hero by remember { mutableStateOf(defaultHero) }

    LaunchedEffect(defaultHero) {
        if (hero.key == "launcher" || hero.key == defaultHero.key) {
            hero = defaultHero
        }
    }

    LaunchedEffect(contentScrollState.value) {
        onNavigationVisibilityChange(contentScrollState.value == 0)
    }

    LaunchedEffect(
        watchNextFocusRestoreSourceId,
        watchNextFocusRestoreGeneration,
    ) {
        val sourceId = watchNextFocusRestoreSourceId ?: return@LaunchedEffect
        if (watchNextFocusRestoreGeneration <= 0) return@LaunchedEffect
        val targetIndex = watchNextItems.indexOfFirst { item ->
            item.media.source.sourceId == sourceId
        }
        if (targetIndex < 0) return@LaunchedEffect
        watchNextListState.scrollToItem(targetIndex)
        withFrameNanos { }
        watchNextRestoreFocusRequester.requestFocus()
    }

    LaunchedEffect(
        liveTvFocusRestoreServiceReference,
        liveTvFocusRestoreGeneration,
    ) {
        val serviceReference = liveTvFocusRestoreServiceReference ?: return@LaunchedEffect
        if (liveTvFocusRestoreGeneration <= 0) return@LaunchedEffect
        val targetIndex = liveTvState.channels.indexOfFirst { channel ->
            channel.serviceReference == serviceReference
        }
        if (targetIndex < 0) return@LaunchedEffect
        liveTvListState.scrollToItem(targetIndex)
        withFrameNanos { }
        liveTvRestoreFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeHero(
            content = hero,
            onOpenMediaDetails = onOpenMediaDetails,
            onOpenApp = onOpenApp,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(contentScrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Weiterschauen",
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (watchNextItems.isNotEmpty()) {
                    Text(
                        text = "OK: Fortsetzen · INFO/lange OK: Details",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                !hasTvListingsPermission -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "TV-Inhalte-Berechtigung fehlt. Watch Next und Preview Channels anderer Apps sind dadurch nicht verfügbar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onRequestTvListingsPermission) {
                            Text("TV-Inhalte freigeben")
                        }
                    }
                }

                watchNextError != null -> Text(
                    text = watchNextError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )

                watchNextItems.isEmpty() -> Text(
                    text = "Android TvProvider liefert aktuell keine Watch-Next-Einträge.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyRow(
                    state = watchNextListState,
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        items = watchNextItems,
                        key = { "watch-next-${it.sourceItem.id}-${it.sourceItem.sourceOrder}" },
                    ) { item ->
                        val cardModifier = if (
                            item.media.source.sourceId == watchNextFocusRestoreSourceId
                        ) {
                            Modifier.focusRequester(watchNextRestoreFocusRequester)
                        } else {
                            Modifier
                        }
                        val sourceLabel = item.media.source.packageName?.let(appLabels::get)
                        WatchNextCard(
                            item = item.media,
                            onClick = { onOpenWatchNext(item) },
                            onDetails = { onOpenWatchNextDetails(item) },
                            onFocused = { hero = mediaHero(item.media, sourceLabel) },
                            modifier = cardModifier,
                        )
                    }
                }
            }

            if (liveTvState.configured) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Jetzt im TV", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = liveTvState.receiverLabel?.let { "Gigablue · $it" } ?: "Gigablue / OpenWebif",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    liveTvState.channels.isNotEmpty() -> LazyRow(
                        state = liveTvListState,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(
                            items = liveTvState.channels,
                            key = { it.serviceReference },
                        ) { channel ->
                            val cardModifier = if (
                                channel.serviceReference == liveTvFocusRestoreServiceReference
                            ) {
                                Modifier.focusRequester(liveTvRestoreFocusRequester)
                            } else {
                                Modifier
                            }
                            LiveTvCard(
                                channel = channel,
                                onClick = { onPlayLiveTvChannel(channel) },
                                onFocused = { hero = liveTvHero(channel) },
                                modifier = cardModifier,
                            )
                        }
                    }

                    liveTvState.isRefreshing -> Text(
                        "Gigablue wird aktualisiert …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Button(onClick = onOpenLiveTv) {
                        Text("Live TV in Einstellungen konfigurieren")
                    }
                }
            }

            if (hasTvListingsPermission && previewChannelsError != null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("App-Kanäle", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = previewChannelsError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            visiblePreviewChannels.forEach { channel ->
                key(channel.id) {
                    val channelListState = rememberLazyListState()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(channel.title, style = MaterialTheme.typography.headlineSmall)
                        val sourceLabel = channel.packageName?.let(appLabels::get)
                        if (!sourceLabel.isNullOrBlank() && sourceLabel != channel.title) {
                            Text(
                                text = sourceLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    LazyRow(
                        state = channelListState,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(
                            items = channel.programs,
                            key = { it.media.id },
                        ) { program ->
                            val sourceLabel = channel.packageName?.let(appLabels::get) ?: channel.title
                            WatchNextCard(
                                item = program.media,
                                onClick = { onOpenPreviewProgram(channel, program) },
                                onFocused = {
                                    hero = mediaHero(
                                        item = program.media,
                                        sourceLabel = sourceLabel,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Text("Apps", style = MaterialTheme.typography.headlineSmall)
            if (apps.isEmpty()) {
                Text("Installierte Apps werden geladen …")
            } else {
                LazyRow(
                    state = appsListState,
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        items = apps.take(12),
                        key = { it.packageName },
                    ) { app ->
                        AppCard(
                            app = app,
                            onClick = { onOpenApp(app) },
                            onFocused = { hero = appHero(app) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HomeHero(
    content: HomeHeroContent,
    onOpenMediaDetails: (MediaItem, String?) -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
) {
    Card(
        onClick = {
            when {
                content.detailsMedia != null -> onOpenMediaDetails(
                    content.detailsMedia,
                    content.sourceLabel,
                )
                content.app != null -> onOpenApp(content.app)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp),
        scale = CardDefaults.scale(focusedScale = 1.01f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            content.artworkUri?.takeIf { it.isNotBlank() }?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.10f),
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.64f)
                    .padding(horizontal = 30.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                content.logoUri?.takeIf { it.isNotBlank() }?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 190.dp, height = 58.dp),
                    )
                }
                content.eyebrow?.takeIf { it.isNotBlank() }?.let { eyebrow ->
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = content.title,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                content.metadata?.takeIf { it.isNotBlank() }?.let { metadata ->
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (content.detailsMedia != null) {
                    Text(
                        text = "OK: Details",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private data class HomeHeroContent(
    val key: String,
    val title: String,
    val eyebrow: String? = null,
    val metadata: String? = null,
    val description: String? = null,
    val artworkUri: String? = null,
    val logoUri: String? = null,
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
        item.progressFraction?.let { add("${(it * 100).toInt()} % gesehen") }
    }.joinToString(" · ")

    return HomeHeroContent(
        key = "media:${item.source.provider}:${item.source.sourceId}",
        eyebrow = sourceLabel ?: item.subtitle,
        title = item.title,
        metadata = metadata.takeIf { it.isNotBlank() },
        description = item.overview ?: item.episodeTitle,
        artworkUri = item.backdropUri
            ?: item.episodeStillUri
            ?: item.sourceArtworkUri
            ?: item.posterUri,
        logoUri = item.logoUri,
        detailsMedia = item,
        sourceLabel = sourceLabel,
    )
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
    val description = now?.longDescription
        ?: now?.shortDescription
        ?: channel.next?.let { "Danach: ${it.title}" }

    return HomeHeroContent(
        key = "live:${channel.serviceReference}",
        eyebrow = channel.name,
        title = now?.title ?: channel.name,
        metadata = metadata.takeIf { it.isNotBlank() },
        description = description,
        artworkUri = now?.preferredArtworkUri,
        logoUri = channel.piconUri,
        detailsMedia = now?.let { liveProgramMedia(channel, it) },
        sourceLabel = channel.name,
    )
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
    description = "OK: App öffnen",
    app = app,
)

private fun formatHeroTime(utcMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMillis))
