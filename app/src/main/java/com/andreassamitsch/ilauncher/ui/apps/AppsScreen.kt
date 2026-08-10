package com.andreassamitsch.ilauncher.ui.apps

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.ui.components.AppCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback

private val APP_GRID_MIN_CELL_SIZE = 124.dp

@Composable
fun AppsScreen(
    apps: List<InstalledApp>,
    onOpenApp: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Alle Apps",
            style = MaterialTheme.typography.headlineMedium,
        )

        if (apps.isEmpty()) {
            Text("Keine startbaren Apps gefunden.")
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(APP_GRID_MIN_CELL_SIZE),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .touchScrollFallback(gridState, Orientation.Vertical),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(
                items = apps,
                key = { it.packageName },
            ) { app ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AppCard(
                        app = app,
                        onClick = { onOpenApp(app) },
                        labelAlwaysVisible = true,
                    )
                }
            }
        }
    }
}
