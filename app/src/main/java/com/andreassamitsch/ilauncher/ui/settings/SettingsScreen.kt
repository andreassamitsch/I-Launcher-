package com.andreassamitsch.ilauncher.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.data.tv.EnrichedWatchNextItem
import com.andreassamitsch.ilauncher.data.update.InstallResult
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.data.update.UpdateState
import com.andreassamitsch.ilauncher.model.AppContentChannelsLoadResult
import com.andreassamitsch.ilauncher.model.InstalledApp
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.system.HomeLauncherManager
import com.andreassamitsch.ilauncher.system.TvProviderPermissionManager
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TMDB_APPROVED_LOGO_URL =
    "https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_2-d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b.svg"

private enum class SettingsPage {
    Overview,
    Setup,
    ContentSources,
    Diagnostics,
    AboutUpdates,
}

@Composable
fun SettingsScreen(
    updateManager: UpdateManager,
    watchNextResult: WatchNextLoadResult,
    previewChannelsResult: AppContentChannelsLoadResult,
    installedApps: List<InstalledApp>,
    hiddenWatchNextPackages: Set<String>,
    onSetWatchNextSourceVisible: (String, Boolean) -> Unit,
    onShowAllWatchNextSources: () -> Unit,
    hiddenPreviewChannelIds: Set<String>,
    onSetPreviewChannelVisible: (String, Boolean) -> Unit,
    onShowAllPreviewChannels: () -> Unit,
    hasTvListingsPermission: Boolean,
    onRequestTvListingsPermission: () -> Unit,
    tmdbConfigured: Boolean,
    enrichedWatchNextItems: List<EnrichedWatchNextItem>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val updateState by updateManager.state.collectAsState()
    val installSourceStatus = remember { HomeLauncherManager.installSourceStatus(context) }

    var page by remember { mutableStateOf(SettingsPage.Overview) }
    var isDefaultHome by remember { mutableStateOf(HomeLauncherManager.isDefaultHome(context)) }
    var isHomeRoleAvailable by remember {
        mutableStateOf(HomeLauncherManager.isHomeRoleAvailable(context))
    }
    var isHomeRoleHeld by remember { mutableStateOf(HomeLauncherManager.isHomeRoleHeld(context)) }
    var isHomeOverrideEnabled by remember {
        mutableStateOf(HomeLauncherManager.isHomeButtonOverrideEnabled(context))
    }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var showPreviewDiagnosisDetails by remember { mutableStateOf(false) }
    var showWatchNextDiagnosisDetails by remember { mutableStateOf(false) }
    var showTmdbDiagnosisDetails by remember { mutableStateOf(false) }

    val appLabels = remember(installedApps) {
        installedApps.associate { it.packageName to it.label }
    }
    val watchNextSources = remember(watchNextResult.items, appLabels) {
        watchNextResult.items
            .mapNotNull { it.packageName }
            .groupingBy { it }
            .eachCount()
            .map { (packageName, count) ->
                WatchNextSourceRow(
                    packageName = packageName,
                    label = appLabels[packageName] ?: packageName,
                    count = count,
                )
            }
            .sortedBy { it.label.lowercase() }
    }
    val tmdbResolvedItems = remember(enrichedWatchNextItems) {
        enrichedWatchNextItems.filter { it.media.tmdbId != null }
    }

    fun refreshLauncherStatus() {
        isDefaultHome = HomeLauncherManager.isDefaultHome(context)
        isHomeRoleAvailable = HomeLauncherManager.isHomeRoleAvailable(context)
        isHomeRoleHeld = HomeLauncherManager.isHomeRoleHeld(context)
        isHomeOverrideEnabled = HomeLauncherManager.isHomeButtonOverrideEnabled(context)
        updateManager.refreshDownloadState()
    }

    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshLauncherStatus()
                }
            }
            activity.lifecycle.addObserver(observer)
            onDispose {
                activity.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(updateState) {
        if (updateState is UpdateState.Downloading) {
            while (true) {
                delay(1_000)
                updateManager.refreshDownloadState()
            }
        }
    }

    BackHandler(enabled = page != SettingsPage.Overview) {
        page = SettingsPage.Overview
    }

    val launcherReady = isDefaultHome || isHomeRoleHeld || isHomeOverrideEnabled
    val diagnosticsNeedAttention = !hasTvListingsPermission ||
        watchNextResult.errorMessage != null ||
        previewChannelsResult.errorMessage != null

    key(page) {
        when (page) {
            SettingsPage.Overview -> SettingsOverviewPage(
                hasTvListingsPermission = hasTvListingsPermission,
                launcherReady = launcherReady,
                visibleWatchNextSources = watchNextSources.count {
                    it.packageName !in hiddenWatchNextPackages
                },
                watchNextSourceCount = watchNextSources.size,
                visiblePreviewChannels = previewChannelsResult.channels.count {
                    it.id !in hiddenPreviewChannelIds
                },
                previewChannelCount = previewChannelsResult.channels.size,
                diagnosticsNeedAttention = diagnosticsNeedAttention,
                tmdbConfigured = tmdbConfigured,
                updateState = updateState,
                onOpenSetup = { page = SettingsPage.Setup },
                onOpenContentSources = { page = SettingsPage.ContentSources },
                onOpenDiagnostics = { page = SettingsPage.Diagnostics },
                onOpenAboutUpdates = { page = SettingsPage.AboutUpdates },
                modifier = modifier,
            )

            SettingsPage.Setup -> SettingsSetupPage(
                hasTvListingsPermission = hasTvListingsPermission,
                onRequestTvListingsPermission = onRequestTvListingsPermission,
                isDefaultHome = isDefaultHome,
                isHomeRoleAvailable = isHomeRoleAvailable,
                isHomeRoleHeld = isHomeRoleHeld,
                isHomeOverrideEnabled = isHomeOverrideEnabled,
                installSourceLabel = buildString {
                    append(installSourceStatus.label)
                    installSourceStatus.installerPackageName?.let { append(" ($it)") }
                },
                restrictedSettingsLikely = installSourceStatus.restrictedSettingsLikely,
                onOpenTvPermissions = { TvProviderPermissionManager.openAppDetails(context) },
                onOpenDefaultHome = { HomeLauncherManager.openDefaultHomeSelection(context) },
                onOpenAppDetails = { HomeLauncherManager.openAppDetails(context) },
                onOpenAccessibility = { HomeLauncherManager.openAccessibilitySettings(context) },
                onBack = { page = SettingsPage.Overview },
                modifier = modifier,
            )

            SettingsPage.ContentSources -> SettingsContentSourcesPage(
                hasTvListingsPermission = hasTvListingsPermission,
                watchNextSources = watchNextSources,
                hiddenWatchNextPackages = hiddenWatchNextPackages,
                onSetWatchNextSourceVisible = onSetWatchNextSourceVisible,
                onShowAllWatchNextSources = onShowAllWatchNextSources,
                previewChannelsResult = previewChannelsResult,
                hiddenPreviewChannelIds = hiddenPreviewChannelIds,
                appLabels = appLabels,
                onSetPreviewChannelVisible = onSetPreviewChannelVisible,
                onShowAllPreviewChannels = onShowAllPreviewChannels,
                onBack = { page = SettingsPage.Overview },
                modifier = modifier,
            )

            SettingsPage.Diagnostics -> SettingsDiagnosticsPage(
                hasTvListingsPermission = hasTvListingsPermission,
                watchNextResult = watchNextResult,
                previewChannelsResult = previewChannelsResult,
                tmdbConfigured = tmdbConfigured,
                enrichedWatchNextItems = enrichedWatchNextItems,
                tmdbResolvedItems = tmdbResolvedItems,
                showPreviewDiagnosisDetails = showPreviewDiagnosisDetails,
                onTogglePreviewDiagnosisDetails = {
                    showPreviewDiagnosisDetails = !showPreviewDiagnosisDetails
                },
                showWatchNextDiagnosisDetails = showWatchNextDiagnosisDetails,
                onToggleWatchNextDiagnosisDetails = {
                    showWatchNextDiagnosisDetails = !showWatchNextDiagnosisDetails
                },
                showTmdbDiagnosisDetails = showTmdbDiagnosisDetails,
                onToggleTmdbDiagnosisDetails = {
                    showTmdbDiagnosisDetails = !showTmdbDiagnosisDetails
                },
                onBack = { page = SettingsPage.Overview },
                modifier = modifier,
            )

            SettingsPage.AboutUpdates -> SettingsAboutUpdatesPage(
                updateManager = updateManager,
                updateState = updateState,
                updateMessage = updateMessage,
                onClearUpdateMessage = { updateMessage = null },
                onSetUpdateMessage = { updateMessage = it },
                tmdbConfigured = tmdbConfigured,
                tmdbResolvedCount = tmdbResolvedItems.size,
                enrichedItemCount = enrichedWatchNextItems.size,
                onBack = { page = SettingsPage.Overview },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun SettingsOverviewPage(
    hasTvListingsPermission: Boolean,
    launcherReady: Boolean,
    visibleWatchNextSources: Int,
    watchNextSourceCount: Int,
    visiblePreviewChannels: Int,
    previewChannelCount: Int,
    diagnosticsNeedAttention: Boolean,
    tmdbConfigured: Boolean,
    updateState: UpdateState,
    onOpenSetup: () -> Unit,
    onOpenContentSources: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAboutUpdates: () -> Unit,
    modifier: Modifier,
) {
    SettingsScrollablePage(
        title = "Einstellungen",
        subtitle = "Die wichtigsten Bereiche auf einen Blick. OK öffnet eine Unterseite, Zurück führt wieder hierher.",
        modifier = modifier,
    ) {
        SettingsNavigationRow(
            title = "Einrichtung",
            subtitle = "TV-Berechtigung, Standard-Launcher und Home-Taste",
            status = when {
                !hasTvListingsPermission -> "TV-Freigabe fehlt"
                !launcherReady -> "Launcher prüfen"
                else -> "Bereit"
            },
            attention = !hasTvListingsPermission || !launcherReady,
            onClick = onOpenSetup,
        )

        SettingsNavigationRow(
            title = "Inhalte & Quellen",
            subtitle = "Weiterschauen-Apps und Android-TV-Kanäle ein- oder ausblenden",
            status = "$visibleWatchNextSources/$watchNextSourceCount Quellen · $visiblePreviewChannels/$previewChannelCount Kanäle",
            onClick = onOpenContentSources,
        )

        SettingsNavigationRow(
            title = "Diagnose",
            subtitle = "TvProvider, Watch Next, Preview Channels und TMDB gezielt prüfen",
            status = if (diagnosticsNeedAttention) "Prüfen" else "Keine Fehler",
            attention = diagnosticsNeedAttention,
            onClick = onOpenDiagnostics,
        )

        SettingsNavigationRow(
            title = "Über & Updates",
            subtitle = "Version, Update-Installation, TMDB-Status und Credits",
            status = updateOverviewLabel(updateState, tmdbConfigured),
            attention = updateState is UpdateState.Error || updateState is UpdateState.SigningRequired,
            onClick = onOpenAboutUpdates,
        )
    }
}

@Composable
private fun SettingsSetupPage(
    hasTvListingsPermission: Boolean,
    onRequestTvListingsPermission: () -> Unit,
    isDefaultHome: Boolean,
    isHomeRoleAvailable: Boolean,
    isHomeRoleHeld: Boolean,
    isHomeOverrideEnabled: Boolean,
    installSourceLabel: String,
    restrictedSettingsLikely: Boolean,
    onOpenTvPermissions: () -> Unit,
    onOpenDefaultHome: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScrollablePage(
        title = "Einrichtung",
        subtitle = "Alles, was I Launcher für TV-Inhalte und die Home-Taste benötigt.",
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsSectionTitle("TV-Inhalte")
        SettingsStatusText(
            text = if (hasTvListingsPermission) {
                "Freigegeben · Watch Next und Preview Channels anderer Apps können gelesen werden."
            } else {
                "Freigabe fehlt · Android liefert sonst nur eingeschränkte TvProvider-Daten."
            },
            attention = !hasTvListingsPermission,
        )
        if (!hasTvListingsPermission) {
            TouchButton(
                onClick = onRequestTvListingsPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("TV-Inhalte freigeben")
            }
            TouchButton(
                onClick = onOpenTvPermissions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("App-Info / Berechtigungen öffnen")
            }
        }

        SettingsSectionTitle("Launcher & Home-Taste")
        SettingsStatusText(
            text = when {
                isDefaultHome || isHomeRoleHeld -> "I Launcher ist als Standard-Home-App aktiv."
                isHomeOverrideEnabled -> "Home-Fallback über Bedienungshilfen ist aktiv."
                isHomeRoleAvailable -> "Android unterstützt die Home-Rolle. I Launcher kann als Standard-Home-App gesetzt werden."
                else -> "Der TV bietet keine freie Home-Rolle an. Der Bedienungshilfe-Fallback ist erforderlich."
            },
            attention = !isDefaultHome && !isHomeRoleHeld && !isHomeOverrideEnabled,
        )

        TouchButton(
            onClick = onOpenDefaultHome,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Als Standard-Launcher festlegen")
        }

        if (!isDefaultHome && !isHomeRoleHeld) {
            if (!isHomeOverrideEnabled) {
                TouchButton(
                    onClick = onOpenAppDetails,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Eingeschränkte Einstellungen / App-Info öffnen")
                }
            }
            TouchButton(
                onClick = onOpenAccessibility,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isHomeOverrideEnabled) {
                        "Home-Fallback verwalten"
                    } else {
                        "Home-Fallback in Bedienungshilfen aktivieren"
                    },
                )
            }
        }

        Text(
            text = "Installationsquelle: $installSourceLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!isHomeOverrideEnabled && restrictedSettingsLikely) {
            SettingsStatusText(
                text = "Android stuft diese APK als seitlich installiert ein. Auf Android 13+ muss „Eingeschränkte Einstellungen zulassen“ gegebenenfalls einmalig freigegeben werden.",
                attention = true,
            )
        }

        Text(
            text = "Der Home-Fallback reagiert auf einen gelieferten HOME-Key und auf das Sichtbarwerden des System-Launchers nach einem Home-Druck.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsContentSourcesPage(
    hasTvListingsPermission: Boolean,
    watchNextSources: List<WatchNextSourceRow>,
    hiddenWatchNextPackages: Set<String>,
    onSetWatchNextSourceVisible: (String, Boolean) -> Unit,
    onShowAllWatchNextSources: () -> Unit,
    previewChannelsResult: AppContentChannelsLoadResult,
    hiddenPreviewChannelIds: Set<String>,
    appLabels: Map<String, String>,
    onSetPreviewChannelVisible: (String, Boolean) -> Unit,
    onShowAllPreviewChannels: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScrollablePage(
        title = "Inhalte & Quellen",
        subtitle = "Bestimme, welche Quellen auf Home sichtbar sind. Die Reihenfolge der gelieferten Inhalte bleibt unverändert.",
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsSectionTitle("Weiterschauen")
        when {
            !hasTvListingsPermission -> SettingsStatusText(
                text = "TV-Inhalte müssen zuerst unter Einrichtung freigegeben werden.",
                attention = true,
            )

            watchNextSources.isEmpty() -> Text(
                text = "Noch keine Watch-Next-Quellen gefunden.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = "OK blendet eine komplette App-Quelle ein oder aus. Rohdaten und Quellreihenfolge werden dadurch nicht verändert.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                watchNextSources.forEach { source ->
                    val visible = source.packageName !in hiddenWatchNextPackages
                    SettingsToggleRow(
                        title = source.label,
                        subtitle = buildString {
                            append("${source.count} Einträge")
                            if (source.label != source.packageName) {
                                append(" · ${source.packageName}")
                            }
                        },
                        enabled = visible,
                        onClick = {
                            onSetWatchNextSourceVisible(source.packageName, !visible)
                        },
                    )
                }
                if (hiddenWatchNextPackages.isNotEmpty()) {
                    TouchButton(
                        onClick = onShowAllWatchNextSources,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Alle Weiterschauen-Quellen anzeigen")
                    }
                }
            }
        }

        SettingsSectionTitle("App-Kanäle")
        when {
            !hasTvListingsPermission -> SettingsStatusText(
                text = "TV-Inhalte müssen zuerst unter Einrichtung freigegeben werden.",
                attention = true,
            )

            previewChannelsResult.errorMessage != null -> SettingsStatusText(
                text = previewChannelsResult.errorMessage,
                attention = true,
            )

            previewChannelsResult.channels.isEmpty() -> Text(
                text = "Android TvProvider liefert aktuell keine sichtbaren Preview Channels anderer Apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = "Jeder sichtbare Android-TV-Kanal erscheint als eigene Home-Reihe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                previewChannelsResult.channels.forEach { channel ->
                    val visible = channel.id !in hiddenPreviewChannelIds
                    val sourceLabel = channel.packageName?.let(appLabels::get)
                    val label = if (!sourceLabel.isNullOrBlank() && sourceLabel != channel.title) {
                        "${channel.title} · $sourceLabel"
                    } else {
                        channel.title
                    }
                    SettingsToggleRow(
                        title = label,
                        subtitle = "${channel.programs.size} Programme",
                        enabled = visible,
                        onClick = { onSetPreviewChannelVisible(channel.id, !visible) },
                    )
                }
                if (hiddenPreviewChannelIds.isNotEmpty()) {
                    TouchButton(
                        onClick = onShowAllPreviewChannels,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Alle App-Kanäle anzeigen")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDiagnosticsPage(
    hasTvListingsPermission: Boolean,
    watchNextResult: WatchNextLoadResult,
    previewChannelsResult: AppContentChannelsLoadResult,
    tmdbConfigured: Boolean,
    enrichedWatchNextItems: List<EnrichedWatchNextItem>,
    tmdbResolvedItems: List<EnrichedWatchNextItem>,
    showPreviewDiagnosisDetails: Boolean,
    onTogglePreviewDiagnosisDetails: () -> Unit,
    showWatchNextDiagnosisDetails: Boolean,
    onToggleWatchNextDiagnosisDetails: () -> Unit,
    showTmdbDiagnosisDetails: Boolean,
    onToggleTmdbDiagnosisDetails: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScrollablePage(
        title = "Diagnose",
        subtitle = "Technische Details sind standardmäßig eingeklappt und nur bei einer Fehlersuche im Bedienweg.",
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsSectionTitle("Android TvProvider")
        SettingsStatusText(
            text = if (hasTvListingsPermission) {
                "TV-Inhalte freigegeben."
            } else {
                "READ_TV_LISTINGS ist nicht freigegeben. Watch Next und App-Kanäle können unvollständig sein."
            },
            attention = !hasTvListingsPermission,
        )

        SettingsSectionTitle("Watch Next")
        SettingsStatusText(
            text = watchNextResult.errorMessage
                ?: "${watchNextResult.items.size} Einträge · Abfrage nach last_engagement_time_utc_millis absteigend.",
            attention = watchNextResult.errorMessage != null,
        )
        TouchButton(
            onClick = onToggleWatchNextDiagnosisDetails,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showWatchNextDiagnosisDetails) "Watch-Next-Rohdaten ausblenden" else "Watch-Next-Rohdaten anzeigen")
        }
        if (showWatchNextDiagnosisDetails) {
            watchNextResult.items.take(30).forEach { item ->
                val progress = if (item.playbackPositionMillis != null && item.durationMillis != null) {
                    "${item.playbackPositionMillis}/${item.durationMillis} ms"
                } else {
                    "kein Fortschritt"
                }
                DiagnosticLine(
                    buildString {
                        append("#${item.sourceOrder} | ")
                        append(item.packageName ?: "Paket unbekannt")
                        append(" | ")
                        append(item.displayTitle)
                        item.displaySubtitle?.let { append(" | $it") }
                        append(" | $progress")
                        append(" | type=${watchNextTypeLabel(item.watchNextType)}")
                        item.lastEngagementTimeUtcMillis?.let { append(" | engagement=$it") }
                        append(" | intent=${if (item.intentUri.isNullOrBlank()) "nein" else "ja"}")
                        append(" | bild=${if (item.artworkUri.isNullOrBlank()) "nein" else "ja"}")
                    },
                )
            }
            if (watchNextResult.items.size > 30) {
                DiagnosticLine("+ ${watchNextResult.items.size - 30} weitere Einträge")
            }
        }

        SettingsSectionTitle("App-Kanäle")
        SettingsStatusText(
            text = when {
                !hasTvListingsPermission -> "Diagnose eingeschränkt, solange die TV-Freigabe fehlt."
                previewChannelsResult.errorMessage != null -> previewChannelsResult.errorMessage
                else -> "${previewChannelsResult.queriedChannelCount} Preview Channels roh · ${previewChannelsResult.channels.size} sichtbar · ${previewChannelsResult.queriedProgramCount} Programme."
            },
            attention = !hasTvListingsPermission || previewChannelsResult.errorMessage != null,
        )
        TouchButton(
            onClick = onTogglePreviewDiagnosisDetails,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showPreviewDiagnosisDetails) "App-Kanal-Details ausblenden" else "App-Kanal-Details anzeigen")
        }
        if (showPreviewDiagnosisDetails) {
            when {
                !hasTvListingsPermission -> DiagnosticLine(
                    "READ_TV_LISTINGS fehlt. Andere Apps können deshalb nicht vollständig ausgewertet werden.",
                    attention = true,
                )

                previewChannelsResult.errorMessage != null -> DiagnosticLine(
                    previewChannelsResult.errorMessage,
                    attention = true,
                )

                else -> {
                    if (
                        previewChannelsResult.queriedChannelCount > 0 &&
                        previewChannelsResult.channels.isEmpty()
                    ) {
                        DiagnosticLine(
                            "Preview Channels existieren, Android markiert aktuell aber keinen als browsable.",
                            attention = true,
                        )
                    } else if (previewChannelsResult.queriedChannelCount == 0) {
                        DiagnosticLine("TvProvider liefert aktuell keinen TYPE_PREVIEW-Kanal.")
                    }
                    previewChannelsResult.channels.take(20).forEach { channel ->
                        DiagnosticLine(
                            buildString {
                                append("#${channel.sourceOrder} | ")
                                append(channel.packageName ?: "Paket unbekannt")
                                append(" | ")
                                append(channel.title)
                                append(" | programme=${channel.programs.size}")
                                append(" | appLink=${if (channel.appLinkIntentUri.isNullOrBlank()) "nein" else "ja"}")
                            },
                        )
                        channel.programs.take(5).forEach { program ->
                            DiagnosticLine(
                                buildString {
                                    append("  P#${program.sourceOrder} | ")
                                    append(program.media.title)
                                    program.weight?.let { append(" | weight=$it") }
                                    append(" | intent=${if (program.media.source.intentUri.isNullOrBlank()) "nein" else "ja"}")
                                    append(" | bild=${if (program.media.preferredArtworkUri.isNullOrBlank()) "nein" else "ja"}")
                                },
                            )
                        }
                        if (channel.programs.size > 5) {
                            DiagnosticLine("  + ${channel.programs.size - 5} weitere Programme")
                        }
                    }
                }
            }
        }

        SettingsSectionTitle("TMDB")
        SettingsStatusText(
            text = if (tmdbConfigured) {
                "Aktiv · ${tmdbResolvedItems.size} von ${enrichedWatchNextItems.size} sichtbaren Watch-Next-Einträgen aufgelöst."
            } else {
                "Nicht aktiv · dieser Build enthält keinen TMDB Read-Access-Token."
            },
            attention = !tmdbConfigured,
        )
        TouchButton(
            onClick = onToggleTmdbDiagnosisDetails,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showTmdbDiagnosisDetails) "TMDB-Auflösungen ausblenden" else "TMDB-Auflösungen anzeigen")
        }
        if (showTmdbDiagnosisDetails) {
            tmdbResolvedItems.take(20).forEach { item ->
                DiagnosticLine(
                    buildString {
                        append(item.media.title)
                        append(" | tmdb=${item.media.tmdbId}")
                        append(" | type=${item.media.type.name}")
                        item.media.tmdbEpisodeId?.let { append(" | episode=$it") }
                        item.media.resolverConfidence?.let {
                            append(" | confidence=${(it * 100).toInt()}%")
                        }
                    },
                )
            }
            if (tmdbResolvedItems.isEmpty()) {
                DiagnosticLine("Aktuell keine aufgelösten Watch-Next-Einträge.")
            }
        }
    }
}

@Composable
private fun SettingsAboutUpdatesPage(
    updateManager: UpdateManager,
    updateState: UpdateState,
    updateMessage: String?,
    onClearUpdateMessage: () -> Unit,
    onSetUpdateMessage: (String) -> Unit,
    tmdbConfigured: Boolean,
    tmdbResolvedCount: Int,
    enrichedItemCount: Int,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()

    SettingsScrollablePage(
        title = "Über & Updates",
        subtitle = "Versionsstand, sichere In-App-Updates und verwendete Metadatenquellen.",
        onBack = onBack,
        modifier = modifier,
    ) {
        SettingsSectionTitle("Updates")
        Text(
            text = "Installiert: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.titleMedium,
        )
        SettingsStatusText(
            text = updateStateText(updateState),
            attention = updateState is UpdateState.Error || updateState is UpdateState.SigningRequired,
        )

        TouchButton(
            onClick = {
                onClearUpdateMessage()
                scope.launch { updateManager.checkForUpdates() }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
        ) {
            Text("Jetzt nach Update suchen")
        }

        when (updateState) {
            is UpdateState.Available -> {
                TouchButton(
                    onClick = { updateManager.startDownload(updateState.info) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Update ${updateState.info.versionName} herunterladen")
                }
            }

            is UpdateState.ReadyToInstall -> {
                if (!updateManager.canRequestPackageInstalls()) {
                    TouchButton(
                        onClick = { updateManager.openUnknownSourcesSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Installation aus dieser Quelle erlauben")
                    }
                }
                TouchButton(
                    onClick = {
                        onClearUpdateMessage()
                        scope.launch {
                            onSetUpdateMessage(
                                when (val result = updateManager.installDownloadedUpdate()) {
                                    InstallResult.Started -> "Android-Paketinstaller wurde geöffnet."
                                    InstallResult.PermissionRequired -> "Erlaube zuerst die Installation aus dieser Quelle und starte die Installation erneut."
                                    is InstallResult.Error -> result.message
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Update ${updateState.info.versionName} installieren")
                }
            }

            else -> Unit
        }

        updateMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = "I Launcher prüft beim Start automatisch. Download und Prüfsummenprüfung laufen ohne die TV-Oberfläche zu blockieren; die eigentliche Installation übernimmt Android.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsSectionTitle("TMDB & Credits")
        SettingsStatusText(
            text = if (tmdbConfigured) {
                "TMDB ist in diesem Build aktiv · $tmdbResolvedCount von $enrichedItemCount sichtbaren Watch-Next-Einträgen aktuell aufgelöst."
            } else {
                "TMDB ist in diesem Build nicht konfiguriert."
            },
            attention = !tmdbConfigured,
        )
        AsyncImage(
            model = TMDB_APPROVED_LOGO_URL,
            contentDescription = "TMDB",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(88.dp),
        )
        Text(
            text = "Film-, Serien- und Episodenmetadaten sowie zugehörige Bilder können von TMDB stammen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsScrollablePage(
    title: String,
    subtitle: String,
    modifier: Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .touchScrollFallback(scrollState, Orientation.Vertical)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            TouchButton(onClick = onBack) {
                Text("‹ Zurück")
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(2.dp))
        content()
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    status: String,
    onClick: () -> Unit,
    attention: Boolean = false,
) {
    TouchButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TouchButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (enabled) "AN" else "AUS",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SettingsStatusText(
    text: String,
    attention: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun DiagnosticLine(
    text: String,
    attention: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class WatchNextSourceRow(
    val packageName: String,
    val label: String,
    val count: Int,
)

private fun updateOverviewLabel(state: UpdateState, tmdbConfigured: Boolean): String = when (state) {
    UpdateState.Idle -> if (tmdbConfigured) "TMDB aktiv" else "TMDB inaktiv"
    UpdateState.Checking -> "Prüfe Update …"
    is UpdateState.UpToDate -> "Aktuell"
    is UpdateState.Available -> "${state.info.versionName} verfügbar"
    is UpdateState.SigningRequired -> "Update gesperrt"
    is UpdateState.Downloading -> state.progressPercent?.let { "$it %" } ?: "Download …"
    is UpdateState.ReadyToInstall -> "Bereit zur Installation"
    is UpdateState.Error -> "Update-Fehler"
}

private fun updateStateText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Update-Prüfung noch nicht gestartet."
    UpdateState.Checking -> "Suche nach einer neuen Version …"
    is UpdateState.UpToDate -> "I Launcher ist aktuell."
    is UpdateState.Available -> "Neue Version ${state.info.versionName} (${state.info.versionCode}) verfügbar."
    is UpdateState.SigningRequired -> "Neue Version ${state.info.versionName} ist vorhanden, aber nicht mit dem stabilen Development-Signing-Key update-kompatibel."
    is UpdateState.Downloading -> state.progressPercent?.let {
        "Update ${state.info.versionName} wird heruntergeladen: $it %"
    } ?: "Update ${state.info.versionName} wird heruntergeladen …"
    is UpdateState.ReadyToInstall -> "Update ${state.info.versionName} ist bereit zur Installation."
    is UpdateState.Error -> "Update-Fehler: ${state.message}"
}

private fun watchNextTypeLabel(type: Int?): String = when (type) {
    0 -> "CONTINUE"
    1 -> "NEXT"
    2 -> "NEW"
    3 -> "WATCHLIST"
    else -> type?.toString() ?: "?"
}