package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.ui.components.AppCard
import com.andreassamitsch.ilauncher.ui.components.WatchNextCard

@Composable
fun HomeScreen(
    apps: List<InstalledApp>,
    watchNextItems: List<EnrichedWatchNextItem>,
    watchNextError: String?,
    hasTvListingsPermission: Boolean,
    onRequestTvListingsPermission: () -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
    onOpenWatchNext: (EnrichedWatchNextItem) -> Unit,
    onOpenWatchNextDetails: (EnrichedWatchNextItem) -> Unit,
    modifier: Modifier = Modifier,
    watchNextListState: LazyListState = rememberLazyListState(),
    appsListState: LazyListState = rememberLazyListState(),
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "I Launcher benötigt Androids Berechtigung „TV-Programme/Kanäle lesen“, um Watch Next und später Preview Channels anderer Apps anzuzeigen.",
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
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(
                        items = watchNextItems,
                        key = { "watch-next-${it.sourceItem.id}-${it.sourceItem.sourceOrder}" },
                    ) { item ->
                        WatchNextCard(
                            item = item.media,
                            onClick = { onOpenWatchNext(item) },
                            onDetails = { onOpenWatchNextDetails(item) },
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
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
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
