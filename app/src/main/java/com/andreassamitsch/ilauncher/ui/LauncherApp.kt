package com.andreassamitsch.ilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.apps.InstalledAppsRepository
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.ui.apps.AppsScreen
import com.andreassamitsch.ilauncher.ui.home.HomeScreen
import com.andreassamitsch.ilauncher.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LauncherSection(val label: String) {
    Home("Home"),
    Apps("Apps"),
    Settings("Einstellungen"),
}

@Composable
fun LauncherApp(
    installedAppsRepository: InstalledAppsRepository,
) {
    var section by rememberSaveable { mutableStateOf(LauncherSection.Home) }
    val apps by produceState<List<InstalledApp>>(
        initialValue = emptyList(),
        key1 = installedAppsRepository,
    ) {
        value = withContext(Dispatchers.Default) {
            installedAppsRepository.loadApps()
        }
    }

    val openApp: (InstalledApp) -> Unit = { app -> installedAppsRepository.launch(app) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080A0E))
            .padding(horizontal = 56.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LauncherSection.entries.forEach { item ->
                Button(onClick = { section = item }) {
                    Text(item.label)
                }
            }
        }

        when (section) {
            LauncherSection.Home -> HomeScreen(
                apps = apps,
                onOpenApp = openApp,
            )

            LauncherSection.Apps -> AppsScreen(
                apps = apps,
                onOpenApp = openApp,
            )

            LauncherSection.Settings -> SettingsScreen()
        }
    }
}
