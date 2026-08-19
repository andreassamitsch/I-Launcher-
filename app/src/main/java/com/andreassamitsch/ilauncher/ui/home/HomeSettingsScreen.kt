package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.home.HeroTextScrollSpeed
import com.andreassamitsch.ilauncher.data.home.WatchNextArtworkMode
import com.andreassamitsch.ilauncher.model.AppContentChannelsLoadResult
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback

data class HomeRowOption(
    val key: String,
    val title: String,
)

@Composable
fun HomeSettingsScreen(
    rowOptions: List<HomeRowOption>,
    onMoveRow: (String, Int) -> Unit,
    onResetRows: () -> Unit,
    onResetApps: () -> Unit,
    watchNextResult: WatchNextLoadResult,
    previewChannelsResult: AppContentChannelsLoadResult,
    installedApps: List<InstalledApp>,
    hiddenWatchNextPackages: Set<String>,
    onSetWatchNextSourceVisible: (String, Boolean) -> Unit,
    onShowAllWatchNextSources: () -> Unit,
    watchNextCardArtworkMode: WatchNextArtworkMode,
    onSetWatchNextCardArtworkMode: (WatchNextArtworkMode) -> Unit,
    watchNextHeroArtworkMode: WatchNextArtworkMode,
    onSetWatchNextHeroArtworkMode: (WatchNextArtworkMode) -> Unit,
    heroTextScrollSpeed: HeroTextScrollSpeed,
    onSetHeroTextScrollSpeed: (HeroTextScrollSpeed) -> Unit,
    hiddenPreviewChannelIds: Set<String>,
    onSetPreviewChannelVisible: (String, Boolean) -> Unit,
    onShowAllPreviewChannels: () -> Unit,
    tmdbEnrichedPreviewChannelIds: Set<String>,
    onSetPreviewChannelTmdbEnrichment: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val appLabels = installedApps.associate { it.packageName to it.label }
    val watchNextSources = watchNextResult.items
        .mapNotNull { it.packageName }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedBy { appLabels[it.key]?.lowercase() ?: it.key.lowercase() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .touchScrollFallback(scrollState, Orientation.Vertical)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Home anpassen", style = MaterialTheme.typography.displaySmall)
            TouchButton(onClick = onBack) { Text("Zurück") }
        }

        Text("Reihenfolge", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Die Reihen werden von oben nach unten in dieser Reihenfolge angezeigt.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rowOptions.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${index + 1}. ${row.title}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TouchButton(
                    onClick = { onMoveRow(row.key, -1) },
                    enabled = index > 0,
                ) { Text("↑") }
                TouchButton(
                    onClick = { onMoveRow(row.key, +1) },
                    enabled = index < rowOptions.lastIndex,
                ) { Text("↓") }
            }
        }
        TouchButton(onClick = onResetRows) { Text("Reihenfolge zurücksetzen") }

        Text("Apps auf Home", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Auf Home eine App lange mit OK drücken. Danach mit links/rechts verschieben und mit OK bestätigen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TouchButton(onClick = onResetApps) { Text("App-Reihenfolge zurücksetzen") }

        Text("Hero-Text – Scrollgeschwindigkeit", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Die Geschwindigkeit wird in Zeilen pro Sekunde berechnet. Dadurch bleibt sie bei anderer Schrift- oder Zeilenhöhe gleich und hängt nicht von der Länge des Textfelds ab.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroTextScrollSpeed.entries.forEach { speed ->
                val label = when (speed) {
                    HeroTextScrollSpeed.Off -> "Aus"
                    HeroTextScrollSpeed.Slow -> "Langsam"
                    HeroTextScrollSpeed.Normal -> "Normal"
                    HeroTextScrollSpeed.Fast -> "Schnell"
                }
                TouchButton(onClick = { onSetHeroTextScrollSpeed(speed) }) {
                    Text(if (heroTextScrollSpeed == speed) "✓ $label" else label)
                }
            }
        }

        Text("Weiterschauen – Bilder", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Für Episoden kann getrennt gewählt werden, ob Karten und Hero das Episodenbild oder den Serienhintergrund verwenden.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ArtworkModeRow(
            label = "Karten",
            selected = watchNextCardArtworkMode,
            onSelect = onSetWatchNextCardArtworkMode,
        )
        ArtworkModeRow(
            label = "Hero",
            selected = watchNextHeroArtworkMode,
            onSelect = onSetWatchNextHeroArtworkMode,
        )

        Text("Weiterschauen – Quellen", style = MaterialTheme.typography.headlineSmall)
        if (watchNextSources.isEmpty()) {
            Text("Keine Watch-Next-Quellen gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            watchNextSources.forEach { source ->
                val visible = source.key !in hiddenWatchNextPackages
                val label = appLabels[source.key] ?: source.key
                TouchButton(onClick = { onSetWatchNextSourceVisible(source.key, !visible) }) {
                    Text(if (visible) "✓ $label (${source.value})" else "Ausgeblendet: $label (${source.value})")
                }
            }
            if (hiddenWatchNextPackages.isNotEmpty()) {
                TouchButton(onClick = onShowAllWatchNextSources) { Text("Alle Quellen anzeigen") }
            }
        }

        Text("App-Kanäle", style = MaterialTheme.typography.headlineSmall)
        Text(
            "TMDB-Anreicherung ist je Kanal standardmäßig aus. Bei Aktivierung werden die Inhalte dieses Kanals aufgelöst und mit TMDB-Metadaten/Bildern ergänzt.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        previewChannelsResult.channels.forEach { channel ->
            val visible = channel.id !in hiddenPreviewChannelIds
            val tmdbEnabled = channel.id in tmdbEnrichedPreviewChannelIds
            val app = channel.packageName?.let(appLabels::get)
            val label = if (!app.isNullOrBlank() && app != channel.title) {
                "${channel.title} · $app"
            } else channel.title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TouchButton(onClick = { onSetPreviewChannelVisible(channel.id, !visible) }) {
                    Text(if (visible) "✓ Sichtbar" else "Ausgeblendet")
                }
                TouchButton(onClick = { onSetPreviewChannelTmdbEnrichment(channel.id, !tmdbEnabled) }) {
                    Text(if (tmdbEnabled) "✓ TMDB" else "TMDB aus")
                }
            }
        }
        if (hiddenPreviewChannelIds.isNotEmpty()) {
            TouchButton(onClick = onShowAllPreviewChannels) { Text("Alle App-Kanäle anzeigen") }
        }
    }
}

@Composable
private fun ArtworkModeRow(
    label: String,
    selected: WatchNextArtworkMode,
    onSelect: (WatchNextArtworkMode) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.titleMedium,
        )
        TouchButton(onClick = { onSelect(WatchNextArtworkMode.Episode) }) {
            Text(if (selected == WatchNextArtworkMode.Episode) "✓ Episodenbild" else "Episodenbild")
        }
        TouchButton(onClick = { onSelect(WatchNextArtworkMode.Series) }) {
            Text(if (selected == WatchNextArtworkMode.Series) "✓ Serienbild" else "Serienbild")
        }
    }
}
