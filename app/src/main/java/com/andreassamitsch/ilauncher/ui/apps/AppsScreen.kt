package com.andreassamitsch.ilauncher.ui.apps

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.ui.components.AppCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback

private const val APP_GRID_COLUMNS = 4

@Composable
fun AppsScreen(
    apps: List<InstalledApp>,
    onOpenApp: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Apps",
            style = MaterialTheme.typography.displaySmall,
        )

        if (apps.isEmpty()) {
            Text("Keine startbaren Apps gefunden.")
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(APP_GRID_COLUMNS),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .touchScrollFallback(gridState, Orientation.Vertical),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(
                items = apps,
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
