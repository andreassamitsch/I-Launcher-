package com.andreassamitsch.ilauncher.ui.discover

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.tmdb.TmdbDiscoveryCatalog
import com.andreassamitsch.ilauncher.data.tmdb.TmdbDiscoveryRowDefinition
import com.andreassamitsch.ilauncher.data.tmdb.TmdbDiscoveryRowKind
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback

@Composable
fun ContentDiscoverySettingsScreen(
    mediaType: MediaType,
    selectedRowKeys: List<String>,
    onSetVisible: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val allRows = remember(mediaType) { TmdbDiscoveryCatalog.rows(mediaType) }
    val rowsByKey = remember(allRows) { allRows.associateBy(TmdbDiscoveryRowDefinition::key) }
    val selectedRows = remember(selectedRowKeys, rowsByKey) {
        selectedRowKeys.mapNotNull(rowsByKey::get)
    }
    val hiddenRows = remember(allRows, selectedRowKeys) {
        val selected = selectedRowKeys.toSet()
        allRows.filter { it.key !in selected }
    }
    val hiddenLists = remember(hiddenRows) { hiddenRows.filter { it.kind != TmdbDiscoveryRowKind.Genre } }
    val hiddenGenres = remember(hiddenRows) { hiddenRows.filter { it.kind == TmdbDiscoveryRowKind.Genre } }
    val pageName = if (mediaType == MediaType.Movie) "Filme" else "Serien"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .touchScrollFallback(scrollState, Orientation.Vertical)
            .padding(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$pageName anpassen", style = MaterialTheme.typography.displaySmall)
            TouchButton(onClick = onBack) { Text("Zurück") }
        }

        Text(
            "Diese Optionen sind absichtlich nur über langes OK auf „$pageName“ in der Navigation erreichbar.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Sichtbare TMDB-Reihen", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Reihenfolge und Auswahl gelten nur für die $pageName-Seite. Mindestens eine Reihe bleibt sichtbar. Zusätzliche Listen werden erst geladen, wenn du sie hier aktivierst.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        selectedRows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}. ${row.title}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                TouchButton(
                    onClick = { onMove(row.key, -1) },
                    enabled = index > 0,
                ) { Text("↑") }
                TouchButton(
                    onClick = { onMove(row.key, +1) },
                    enabled = index < selectedRows.lastIndex,
                ) { Text("↓") }
                TouchButton(
                    onClick = { onSetVisible(row.key, false) },
                    enabled = selectedRows.size > 1,
                ) { Text("Ausblenden") }
            }
        }

        if (hiddenLists.isNotEmpty()) {
            Text("Weitere TMDB-Listen", style = MaterialTheme.typography.headlineSmall)
            hiddenLists.forEach { row ->
                TouchButton(onClick = { onSetVisible(row.key, true) }) {
                    Text("+ ${row.title}")
                }
            }
        }

        if (hiddenGenres.isNotEmpty()) {
            Text("Weitere TMDB-Genres", style = MaterialTheme.typography.headlineSmall)
            hiddenGenres.forEach { row ->
                TouchButton(onClick = { onSetVisible(row.key, true) }) {
                    Text("+ ${row.title}")
                }
            }
        }

        TouchButton(onClick = onReset) { Text("Standardauswahl wiederherstellen") }
    }
}
