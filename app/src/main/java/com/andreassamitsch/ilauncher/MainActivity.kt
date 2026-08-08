package com.andreassamitsch.ilauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.andreassamitsch.ilauncher.data.apps.InstalledAppsRepository
import com.andreassamitsch.ilauncher.data.tmdb.TmdbRepository
import com.andreassamitsch.ilauncher.data.tv.WatchNextEnrichmentRepository
import com.andreassamitsch.ilauncher.data.tv.WatchNextRepository
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.ui.LauncherApp
import com.andreassamitsch.ilauncher.ui.theme.ILauncherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val installedAppsRepository = InstalledAppsRepository(applicationContext)
        val watchNextRepository = WatchNextRepository(applicationContext)
        val tmdbRepository = TmdbRepository(applicationContext)
        val watchNextEnrichmentRepository = WatchNextEnrichmentRepository(tmdbRepository)
        val updateManager = UpdateManager(applicationContext)

        setContent {
            ILauncherTheme {
                LauncherApp(
                    installedAppsRepository = installedAppsRepository,
                    watchNextRepository = watchNextRepository,
                    watchNextEnrichmentRepository = watchNextEnrichmentRepository,
                    updateManager = updateManager,
                )
            }
        }
    }
}
