package com.andreassamitsch.ilauncher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Einstellungen",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = "Die Konfiguration für Startseite, Watch Next, TMDB und Gigablue folgt in den nächsten Phasen.",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
