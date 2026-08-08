package com.andreassamitsch.ilauncher.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.ui.components.AppCard

@Composable
fun AppsScreen(
    apps: List<InstalledApp>,
    onOpenApp: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier,
) {
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

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(
                items = apps.chunked(5),
                key = { row -> row.joinToString(separator = "|") { it.packageName } },
            ) { rowApps ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    rowApps.forEach { app ->
                        AppCard(
                            app = app,
                            onClick = { onOpenApp(app) },
                        )
                    }
                }
            }
        }
    }
}
