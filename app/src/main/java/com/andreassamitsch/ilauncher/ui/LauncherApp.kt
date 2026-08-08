package com.andreassamitsch.ilauncher.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.apps.InstalledAppsRepository
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.data.tv.WatchNextEnrichmentRepository
import com.andreassamitsch.ilauncher.data.tv.WatchNextRepository
import com.andreassamitsch.ilauncher.data.tv.WatchNextSourcePreferences
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.data.update.UpdateState
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.system.TvProviderPermissionManager
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
    watchNextRepository: WatchNextRepository,
    watchNextEnrichmentRepository: WatchNextEnrichmentRepository,
    updateManager: UpdateManager,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var section by rememberSaveable { mutableStateOf(LauncherSection.Home) }
    val updateState by updateManager.state.collectAsState()
    var hasTvListingsPermission by remember {
        mutableStateOf(TvProviderPermissionManager.hasReadTvListings(context))
    }
    var tvProviderRefreshGeneration by remember { mutableIntStateOf(0) }
    val watchNextSourcePreferences = remember(context) { WatchNextSourcePreferences(context) }
    val hiddenWatchNextPackages by watchNextSourcePreferences.hiddenPackages.collectAsState()

    val tvListingsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        hasTvListingsPermission = TvProviderPermissionManager.hasReadTvListings(context)
        tvProviderRefreshGeneration += 1
    }

    val requestTvListingsPermission: () -> Unit = {
        TvProviderPermissionManager.markInitialRequestShown(context)
        tvListingsPermissionLauncher.launch(TvProviderPermissionManager.READ_TV_LISTINGS)
    }

    val watchNextFlow = remember(
        watchNextRepository,
        hasTvListingsPermission,
        tvProviderRefreshGeneration,
    ) {
        watchNextRepository.observe()
    }
    val watchNextResult by watchNextFlow.collectAsState(
        initial = WatchNextLoadResult(items = emptyList()),
    )
    val visibleWatchNextItems = remember(watchNextResult.items, hiddenWatchNextPackages) {
        watchNextResult.items.filter { item ->
            val packageName = item.packageName
            packageName == null || packageName !in hiddenWatchNextPackages
        }
    }
    var homeWatchNextItems by remember {
        mutableStateOf<List<EnrichedWatchNextItem>>(emptyList())
    }

    LaunchedEffect(visibleWatchNextItems, watchNextEnrichmentRepository) {
        val baseItems = watchNextEnrichmentRepository.base(visibleWatchNextItems)
        homeWatchNextItems = baseItems
        if (baseItems.isNotEmpty() && watchNextEnrichmentRepository.isTmdbConfigured) {
            homeWatchNextItems = watchNextEnrichmentRepository.enrich(baseItems)
        }
    }

    val apps by produceState<List<InstalledApp>>(
        initialValue = emptyList(),
        key1 = installedAppsRepository,
    ) {
        value = withContext(Dispatchers.IO) {
            installedAppsRepository.loadApps()
        }
    }

    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val granted = TvProviderPermissionManager.hasReadTvListings(context)
                    if (granted != hasTvListingsPermission) {
                        hasTvListingsPermission = granted
                        tvProviderRefreshGeneration += 1
                    }
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(Unit) {
        if (TvProviderPermissionManager.shouldShowInitialRequest(context)) {
            TvProviderPermissionManager.markInitialRequestShown(context)
            tvListingsPermissionLauncher.launch(TvProviderPermissionManager.READ_TV_LISTINGS)
        }
    }

    LaunchedEffect(updateManager) {
        updateManager.checkForUpdates()
    }

    val openApp: (InstalledApp) -> Unit = { app -> installedAppsRepository.launch(app) }
    val openWatchNext: (EnrichedWatchNextItem) -> Unit = { item ->
        watchNextRepository.launch(item.sourceItem)
    }
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
                    watchNextItems = homeWatchNextItems,
                    watchNextError = watchNextResult.errorMessage,
                    hasTvListingsPermission = hasTvListingsPermission,
                    onRequestTvListingsPermission = requestTvListingsPermission,
                    onOpenApp = openApp,
                    onOpenWatchNext = openWatchNext,
                )

                LauncherSection.Apps -> AppsScreen(
                    apps = apps,
                    onOpenApp = openApp,
                )

                LauncherSection.Settings -> SettingsScreen(
                    updateManager = updateManager,
                    watchNextResult = watchNextResult,
                    installedApps = apps,
                    hiddenWatchNextPackages = hiddenWatchNextPackages,
                    onSetWatchNextSourceVisible = watchNextSourcePreferences::setVisible,
                    onShowAllWatchNextSources = watchNextSourcePreferences::showAll,
                    hasTvListingsPermission = hasTvListingsPermission,
                    onRequestTvListingsPermission = requestTvListingsPermission,
                    isTmdbConfigured = watchNextEnrichmentRepository.isTmdbConfigured,
                )
            }
        }
    }
}
