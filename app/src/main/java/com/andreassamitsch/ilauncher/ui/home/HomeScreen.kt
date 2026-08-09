package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifState
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.model.AppContentChannel
import com.andreassamitsch.ilauncher.model.AppContentProgram
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.ui.components.AppCard
import com.andreassamitsch.ilauncher.ui.components.LiveTvCard
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard

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
    onOpenPreviewProgram: (AppContentChannel, AppContentProgram) -> Unit,
    onOpenLiveTv: () -> Unit,
    onPlayLiveTvChannel: (LiveTvChannel) -> Unit,
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
    val homeScrollState = rememberScrollState()
    val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }
    val visiblePreviewChannels = remember(previewChannels) {
        previewChannels.filter { it.programs.isNotEmpty() }
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
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(homeScrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "I Launcher",
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "Deine Inhalte. Deine Apps. Keine Werbung.",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Weiterschauen",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (watchNextItems.isNotEmpty()) {
                Text(
                    text = "OK: Fortsetzen · INFO oder lange OK: Details",
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

            watchNextError != null -> {
                Text(
                    text = watchNextError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            watchNextItems.isEmpty() -> {
                Text(
                    text = "Android TvProvider liefert aktuell keine Watch-Next-Einträge.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LazyRow(
                    state = watchNextListState,
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
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

                        WatchNextCard(
                            item = item.media,
                            onClick = { onOpenWatchNext(item) },
                            onDetails = { onOpenWatchNextDetails(item) },
                            modifier = cardModifier,
                        )
                    }
                }
            }
        }

        if (liveTvState.configured) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Jetzt im TV",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = liveTvState.receiverLabel?.let { "Gigablue · $it" } ?: "Gigablue / OpenWebif",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                liveTvState.channels.isNotEmpty() -> {
                    LazyRow(
                        state = liveTvListState,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
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
                                modifier = cardModifier,
                            )
                        }
                    }
                }

                liveTvState.isRefreshing -> {
                    Text(
                        "Gigablue wird aktualisiert …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Button(onClick = onOpenLiveTv) {
                        Text("Live TV öffnen / Verbindung prüfen")
                    }
                }
            }
        }

        if (hasTvListingsPermission && previewChannelsError != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "App-Kanäle",
                    style = MaterialTheme.typography.headlineSmall,
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = channel.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
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
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(
                        items = channel.programs,
                        key = { it.media.id },
                    ) { program ->
                        WatchNextCard(
                            item = program.media,
                            onClick = { onOpenPreviewProgram(channel, program) },
                        )
                    }
                }
            }
        }

        Text(
            text = "Apps",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (apps.isEmpty()) {
            Text("Installierte Apps werden geladen …")
        } else {
            LazyRow(
                state = appsListState,
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(
                    items = apps.take(12),
                    key = { it.packageName },
                ) { app ->
                    AppCard(
                        app = app,
                        onClick = { onOpenApp(app) },
                    )
                }
            }
        }
    }
}
