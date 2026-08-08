package com.andreassamitsch.ilauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.apps.InstalledAppsRepository
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.data.update.UpdateState
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
    updateManager: UpdateManager,
) {
    var section by rememberSaveable { mutableStateOf(LauncherSection.Home) }
    val updateState by updateManager.state.collectAsState()
    val apps by produceState<List<InstalledApp>>(
        initialValue = emptyList(),
        key1 = installedAppsRepository,
    ) {
        value = withContext(Dispatchers.IO) {
            installedAppsRepository.loadApps()
        }
    }

    LaunchedEffect(updateManager) {
        updateManager.checkForUpdates()
    }

    val openApp: (InstalledApp) -> Unit = { app -> installedAppsRepository.launch(app) }
    val updateAttentionLabel = when (updateState) {
        is UpdateState.Available,
        is UpdateState.ReadyToInstall,
        -> "Update verfügbar"

        is UpdateState.SigningRequired -> "Update-Setup nötig"
        else -> null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LauncherSection.entries.forEach { item ->
                    Button(onClick = { section = item }) {
                        Text(item.label)
                    }
                }

                updateAttentionLabel?.let { label ->
                    Button(onClick = { section = LauncherSection.Settings }) {
                        Text(label)
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

                LauncherSection.Settings -> SettingsScreen(updateManager = updateManager)
            }
        }
    }
}
