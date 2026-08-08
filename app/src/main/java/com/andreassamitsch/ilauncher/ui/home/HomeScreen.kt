package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.ui.components.AppCard

@Composable
fun HomeScreen(
    apps: List<InstalledApp>,
    onOpenApp: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
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

        Text(
            text = "Apps",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (apps.isEmpty()) {
            Text("Installierte Apps werden geladen …")
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp),
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
