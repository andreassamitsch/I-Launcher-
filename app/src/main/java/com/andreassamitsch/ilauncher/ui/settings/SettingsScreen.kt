package com.andreassamitsch.ilauncher.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
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
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.components.touchTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TMDB_APPROVED_LOGO_URL =
    "https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_2-d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b.svg"

internal enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: SettingsIcon,
) {
    Setup(
        title = "Einrichtung",
        subtitle = "Launcher & TV-Zugriff",
        icon = SettingsIcon.Setup,
    ),
    Content(
        title = "Inhalte",
        subtitle = "Quellen & Kanäle",
        icon = SettingsIcon.Content,
    ),
    LiveTv(
        title = "Live TV",
        subtitle = "Gigablue & EPG",
        icon = SettingsIcon.LiveTv,
    ),
    Diagnostics(
        title = "Diagnose",
        subtitle = "Status & Rohdaten",
        icon = SettingsIcon.Diagnostics,
    ),
    About(
        title = "Über I Launcher",
        subtitle = "Updates & TMDB",
        icon = SettingsIcon.About,
    ),
}

internal enum class SettingsIcon {
    Setup,
    Content,
    LiveTv,
    Diagnostics,
    About,
}

@Composable
internal fun SettingsScreen(
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
    selectedCategory: SettingsCategory = SettingsCategory.Setup,
    onSelectCategory: (SettingsCategory) -> Unit = {},
    liveTvActionFocusRestoreGeneration: Int = 0,
    onOpenLiveTv: () -> Unit,
    hasTvListingsPermission: Boolean,
    onRequestTvListingsPermission: () -> Unit,
    tmdbConfigured: Boolean,
    enrichedWatchNextItems: List<EnrichedWatchNextItem>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val updateState by updateManager.state.collectAsState()
    val installSourceStatus = remember { HomeLauncherManager.installSourceStatus(context) }

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

    val launcherReady = isDefaultHome || isHomeRoleHeld || isHomeOverrideEnabled
    val diagnosticsNeedAttention = !hasTvListingsPermission ||
        watchNextResult.errorMessage != null ||
        previewChannelsResult.errorMessage != null

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        SettingsSidebar(
            selectedCategory = selectedCategory,
            onSelectCategory = onSelectCategory,
            hasTvListingsPermission = hasTvListingsPermission,
            launcherReady = launcherReady,
            diagnosticsNeedAttention = diagnosticsNeedAttention,
            updateState = updateState,
            modifier = Modifier
                .width(286.dp)
                .fillMaxHeight(),
        )

        Spacer(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            key(selectedCategory) {
                when (selectedCategory) {
                    SettingsCategory.Setup -> SettingsSetupPane(
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
                    )

                    SettingsCategory.Content -> SettingsContentPane(
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
                    )

                    SettingsCategory.LiveTv -> SettingsLiveTvPane(
                        onOpenLiveTv = onOpenLiveTv,
                        focusRestoreGeneration = liveTvActionFocusRestoreGeneration,
                    )

                    SettingsCategory.Diagnostics -> SettingsDiagnosticsPane(
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
                    )

                    SettingsCategory.About -> SettingsAboutPane(
                        updateManager = updateManager,
                        updateState = updateState,
                        updateMessage = updateMessage,
                        onClearUpdateMessage = { updateMessage = null },
                        onSetUpdateMessage = { updateMessage = it },
                        tmdbConfigured = tmdbConfigured,
                        tmdbResolvedCount = tmdbResolvedItems.size,
                        enrichedItemCount = enrichedWatchNextItems.size,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSidebar(
    selectedCategory: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    hasTvListingsPermission: Boolean,
    launcherReady: Boolean,
    diagnosticsNeedAttention: Boolean,
    updateState: UpdateState,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                shape = RoundedCornerShape(22.dp),
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(
            text = "Einstellungen",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(modifier = Modifier.size(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .touchScrollFallback(scrollState, Orientation.Vertical),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingsCategory.entries.forEach { category ->
                val trailing = when (category) {
                    SettingsCategory.Setup -> when {
                        !hasTvListingsPermission -> "!"
                        !launcherReady -> "!"
                        else -> null
                    }
                    SettingsCategory.Content -> null
                    SettingsCategory.LiveTv -> null
                    SettingsCategory.Diagnostics -> if (diagnosticsNeedAttention) "!" else null
                    SettingsCategory.About -> when (updateState) {
                        is UpdateState.Available,
                        is UpdateState.ReadyToInstall,
                        is UpdateState.Error,
                        is UpdateState.SigningRequired -> "•"
                        else -> null
                    }
                }
                val onCategoryClick = { onSelectCategory(category) }
                ListItem(
                    selected = selectedCategory == category,
                    onClick = onCategoryClick,
                    headlineContent = {
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selectedCategory == category) FontWeight.SemiBold else null,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = category.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        SettingsIconGlyph(category.icon)
                    },
                    trailingContent = trailing?.let { marker ->
                        {
                            Text(
                                text = marker,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (marker == "!") {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .touchTap(onClick = onCategoryClick),
                )
            }
            Spacer(modifier = Modifier.size(2.dp))
        }
    }
}

@Composable
private fun SettingsIconGlyph(icon: SettingsIcon) {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(28.dp)) {
        val strokeWidth = size.minDimension * 0.075f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val center = Offset(size.width / 2f, size.height / 2f)

        when (icon) {
            SettingsIcon.Setup -> {
                drawCircle(
                    color = color,
                    center = center,
                    radius = size.minDimension * 0.22f,
                    style = stroke,
                )
                val radius = size.minDimension * 0.39f
                drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius * 0.72f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(center.x, center.y + radius * 0.72f), Offset(center.x, center.y + radius), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(center.x - radius, center.y), Offset(center.x - radius * 0.72f, center.y), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(center.x + radius * 0.72f, center.y), Offset(center.x + radius, center.y), strokeWidth, StrokeCap.Round)
            }

            SettingsIcon.Content -> {
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.20f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.60f),
                    style = stroke,
                )
                val play = Path().apply {
                    moveTo(size.width * 0.43f, size.height * 0.36f)
                    lineTo(size.width * 0.68f, size.height * 0.50f)
                    lineTo(size.width * 0.43f, size.height * 0.64f)
                    close()
                }
                drawPath(play, color = color)
            }

            SettingsIcon.LiveTv -> {
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.24f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.52f),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.38f, size.height * 0.82f),
                    end = Offset(size.width * 0.62f, size.height * 0.82f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.50f, size.height * 0.76f),
                    end = Offset(size.width * 0.50f, size.height * 0.82f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            SettingsIcon.Diagnostics -> {
                val path = Path().apply {
                    moveTo(size.width * 0.10f, size.height * 0.58f)
                    lineTo(size.width * 0.28f, size.height * 0.58f)
                    lineTo(size.width * 0.40f, size.height * 0.32f)
                    lineTo(size.width * 0.55f, size.height * 0.72f)
                    lineTo(size.width * 0.68f, size.height * 0.45f)
                    lineTo(size.width * 0.90f, size.height * 0.45f)
                }
                drawPath(path, color = color, style = stroke)
            }

            SettingsIcon.About -> {
                drawCircle(
                    color = color,
                    center = center,
                    radius = size.minDimension * 0.38f,
                    style = stroke,
                )
                drawCircle(
                    color = color,
                    center = Offset(center.x, size.height * 0.34f),
                    radius = strokeWidth * 0.65f,
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, size.height * 0.46f),
                    end = Offset(center.x, size.height * 0.68f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun SettingsSetupPane(
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
) {
    SettingsContentPane(
        title = "Einrichtung",
        subtitle = "TV-Zugriff und Home-Verhalten",
    ) {
        SettingsSectionHeader("TV-Inhalte")
        SettingsActionRow(
            title = "Watch Next & App-Kanäle",
            subtitle = if (hasTvListingsPermission) {
                "Android-TV-Inhalte anderer Apps können gelesen werden."
            } else {
                "Für Weiterschauen und App-Kanäle wird die TV-Freigabe benötigt."
            },
            value = if (hasTvListingsPermission) "Freigegeben" else "Freigabe fehlt",
            actionLabel = if (hasTvListingsPermission) "Öffnen" else "Freigeben",
            attention = !hasTvListingsPermission,
            onClick = if (hasTvListingsPermission) onOpenTvPermissions else onRequestTvListingsPermission,
        )
        if (!hasTvListingsPermission) {
            SettingsActionRow(
                title = "App-Berechtigungen öffnen",
                subtitle = "Android-App-Info und Berechtigungen anzeigen.",
                actionLabel = "Öffnen",
                onClick = onOpenTvPermissions,
            )
        }

        SettingsSectionHeader("Home & Start")
        val launcherActive = isDefaultHome || isHomeRoleHeld
        SettingsActionRow(
            title = "Standard-Launcher",
            subtitle = when {
                launcherActive -> "I Launcher ist die aktive Home-App."
                isHomeRoleAvailable -> "Android unterstützt die Home-Rolle."
                else -> "Der TV bietet keine frei wählbare Home-Rolle an."
            },
            value = if (launcherActive) "Aktiv" else "Nicht aktiv",
            actionLabel = if (launcherActive) "Öffnen" else "Einrichten",
            attention = !launcherActive,
            onClick = onOpenDefaultHome,
        )

        if (!launcherActive) {
            SettingsActionRow(
                title = "Home-Fallback",
                subtitle = if (isHomeOverrideEnabled) {
                    "Bedienungshilfe übernimmt die Home-Taste."
                } else {
                    "Fallback für TVs ohne frei wählbaren Standard-Launcher."
                },
                value = if (isHomeOverrideEnabled) "Aktiv" else "Aus",
                actionLabel = "Öffnen",
                attention = !isHomeOverrideEnabled,
                onClick = onOpenAccessibility,
            )

            if (!isHomeOverrideEnabled) {
                SettingsActionRow(
                    title = "Eingeschränkte Einstellungen",
                    subtitle = "App-Info öffnen, falls Android den Bedienungshilfe-Zugriff blockiert.",
                    actionLabel = "Öffnen",
                    onClick = onOpenAppDetails,
                )
            }
        }

        SettingsSectionHeader("Installation")
        SettingsInfoRow(
            title = "Installationsquelle",
            value = installSourceLabel,
        )
        if (!isHomeOverrideEnabled && restrictedSettingsLikely) {
            SettingsNotice(
                text = "Android stuft diese APK als seitlich installiert ein. Auf Android 13+ kann einmalig „Eingeschränkte Einstellungen zulassen“ nötig sein.",
                attention = true,
            )
        }
    }
}

@Composable
private fun SettingsContentPane(
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
) {
    SettingsContentPane(
        title = "Inhalte",
        subtitle = "Quellen auf Home verwalten",
    ) {
        if (!hasTvListingsPermission) {
            SettingsNotice(
                text = "Die TV-Freigabe fehlt. Aktiviere sie zuerst unter Einrichtung.",
                attention = true,
            )
            return@SettingsContentPane
        }

        SettingsSectionHeader("Weiterschauen")
        if (watchNextSources.isEmpty()) {
            SettingsEmptyState("Noch keine Watch-Next-Quellen gefunden.")
        } else {
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
                    checked = visible,
                    onClick = {
                        onSetWatchNextSourceVisible(source.packageName, !visible)
                    },
                )
            }
            if (hiddenWatchNextPackages.isNotEmpty()) {
                SettingsActionRow(
                    title = "Alle Weiterschauen-Quellen anzeigen",
                    actionLabel = "Anzeigen",
                    onClick = onShowAllWatchNextSources,
                )
            }
        }

        SettingsSectionHeader("App-Kanäle")
        when {
            previewChannelsResult.errorMessage != null -> SettingsNotice(
                text = previewChannelsResult.errorMessage,
                attention = true,
            )

            previewChannelsResult.channels.isEmpty() -> SettingsEmptyState(
                "Android TvProvider liefert aktuell keine sichtbaren App-Kanäle.",
            )

            else -> {
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
                        checked = visible,
                        onClick = { onSetPreviewChannelVisible(channel.id, !visible) },
                    )
                }
                if (hiddenPreviewChannelIds.isNotEmpty()) {
                    SettingsActionRow(
                        title = "Alle App-Kanäle anzeigen",
                        actionLabel = "Anzeigen",
                        onClick = onShowAllPreviewChannels,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsLiveTvPane(
    onOpenLiveTv: () -> Unit,
    focusRestoreGeneration: Int,
) {
    val restoreRequester = remember { FocusRequester() }
    LaunchedEffect(focusRestoreGeneration) {
        if (focusRestoreGeneration <= 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { restoreRequester.requestFocus() }
    }
    SettingsContentPane(
        title = "Live TV",
        subtitle = "Gigablue, Sender und EPG",
    ) {
        SettingsSectionHeader("Receiver")
        SettingsActionRow(
            title = "Gigablue & OpenWebif",
            subtitle = "Verbindung, Bouquets, Sender, EPG und Streams verwalten.",
            actionLabel = "Öffnen",
            onClick = onOpenLiveTv,
            modifier = Modifier.focusRequester(restoreRequester),
        )
        SettingsNotice(
            text = "Live TV bleibt direkt über OpenWebif angebunden. Die bestehende Live-TV-Seite enthält Verbindung, Senderliste und EPG-Konfiguration.",
        )
    }
}

@Composable
private fun SettingsDiagnosticsPane(
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
) {
    SettingsContentPane(
        title = "Diagnose",
        subtitle = "Technische Details nur bei Bedarf",
    ) {
        SettingsSectionHeader("Android TvProvider")
        SettingsInfoRow(
            title = "TV-Zugriff",
            value = if (hasTvListingsPermission) "Freigegeben" else "Fehlt",
            attention = !hasTvListingsPermission,
        )

        SettingsSectionHeader("Watch Next")
        SettingsInfoRow(
            title = "Status",
            value = watchNextResult.errorMessage ?: "${watchNextResult.items.size} Einträge",
            attention = watchNextResult.errorMessage != null,
        )
        SettingsActionRow(
            title = if (showWatchNextDiagnosisDetails) "Watch-Next-Rohdaten ausblenden" else "Watch-Next-Rohdaten anzeigen",
            actionLabel = if (showWatchNextDiagnosisDetails) "Ausblenden" else "Anzeigen",
            onClick = onToggleWatchNextDiagnosisDetails,
        )
        if (showWatchNextDiagnosisDetails) {
            DiagnosticPanel {
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
        }

        SettingsSectionHeader("App-Kanäle")
        SettingsInfoRow(
            title = "Status",
            value = when {
                !hasTvListingsPermission -> "TV-Freigabe fehlt"
                previewChannelsResult.errorMessage != null -> previewChannelsResult.errorMessage
                else -> "${previewChannelsResult.channels.size} sichtbar · ${previewChannelsResult.queriedProgramCount} Programme"
            },
            attention = !hasTvListingsPermission || previewChannelsResult.errorMessage != null,
        )
        SettingsActionRow(
            title = if (showPreviewDiagnosisDetails) "App-Kanal-Details ausblenden" else "App-Kanal-Details anzeigen",
            actionLabel = if (showPreviewDiagnosisDetails) "Ausblenden" else "Anzeigen",
            onClick = onTogglePreviewDiagnosisDetails,
        )
        if (showPreviewDiagnosisDetails) {
            DiagnosticPanel {
                when {
                    !hasTvListingsPermission -> DiagnosticLine(
                        "READ_TV_LISTINGS fehlt. Andere Apps können nicht vollständig ausgewertet werden.",
                        attention = true,
                    )

                    previewChannelsResult.errorMessage != null -> DiagnosticLine(
                        previewChannelsResult.errorMessage,
                        attention = true,
                    )

                    else -> {
                        DiagnosticLine(
                            "${previewChannelsResult.queriedChannelCount} Preview Channels roh · ${previewChannelsResult.channels.size} sichtbar",
                        )
                        previewChannelsResult.channels.take(20).forEach { channel ->
                            DiagnosticLine(
                                buildString {
                                    append("#${channel.sourceOrder} | ")
                                    append(channel.packageName ?: "Paket unbekannt")
                                    append(" | ${channel.title}")
                                    append(" | programme=${channel.programs.size}")
                                },
                            )
                            channel.programs.take(5).forEach { program ->
                                DiagnosticLine(
                                    "  P#${program.sourceOrder} | ${program.media.title} | intent=${if (program.media.source.intentUri.isNullOrBlank()) "nein" else "ja"}",
                                )
                            }
                        }
                    }
                }
            }
        }

        SettingsSectionHeader("TMDB")
        SettingsInfoRow(
            title = "Metadaten",
            value = if (tmdbConfigured) {
                "${tmdbResolvedItems.size}/${enrichedWatchNextItems.size} aufgelöst"
            } else {
                "Nicht konfiguriert"
            },
            attention = !tmdbConfigured,
        )
        SettingsActionRow(
            title = if (showTmdbDiagnosisDetails) "TMDB-Auflösungen ausblenden" else "TMDB-Auflösungen anzeigen",
            actionLabel = if (showTmdbDiagnosisDetails) "Ausblenden" else "Anzeigen",
            onClick = onToggleTmdbDiagnosisDetails,
        )
        if (showTmdbDiagnosisDetails) {
            DiagnosticPanel {
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
}

@Composable
private fun SettingsAboutPane(
    updateManager: UpdateManager,
    updateState: UpdateState,
    updateMessage: String?,
    onClearUpdateMessage: () -> Unit,
    onSetUpdateMessage: (String) -> Unit,
    tmdbConfigured: Boolean,
    tmdbResolvedCount: Int,
    enrichedItemCount: Int,
) {
    val scope = rememberCoroutineScope()
    val updateCheckBusy = updateState is UpdateState.Checking || updateState is UpdateState.Downloading

    SettingsContentPane(
        title = "Über I Launcher",
        subtitle = "Version, Updates und Datenquellen",
    ) {
        SettingsSectionHeader("Version & Updates")
        SettingsInfoRow(
            title = "Installierte Version",
            value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        SettingsInfoRow(
            title = "Update-Status",
            value = updateStateText(updateState),
            attention = updateState is UpdateState.Error || updateState is UpdateState.SigningRequired,
        )
        SettingsActionRow(
            title = "Nach Updates suchen",
            subtitle = when (updateState) {
                UpdateState.Checking -> "Der Development-Kanal wird gerade geprüft."
                is UpdateState.Downloading -> "Ein Update wird bereits heruntergeladen."
                else -> "Prüft den signierten Development-Kanal."
            },
            actionLabel = when (updateState) {
                UpdateState.Checking -> "Prüfung läuft"
                is UpdateState.Downloading -> "Download läuft"
                else -> "Prüfen"
            },
            onClick = {
                if (!updateCheckBusy) {
                    onClearUpdateMessage()
                    scope.launch { updateManager.checkForUpdates() }
                }
            },
        )

        when (updateState) {
            is UpdateState.Available -> SettingsActionRow(
                title = "Update ${updateState.info.versionName} herunterladen",
                actionLabel = "Herunterladen",
                onClick = { updateManager.startDownload(updateState.info) },
            )

            is UpdateState.ReadyToInstall -> {
                if (!updateManager.canRequestPackageInstalls()) {
                    SettingsActionRow(
                        title = "Installation aus dieser Quelle erlauben",
                        actionLabel = "Öffnen",
                        onClick = { updateManager.openUnknownSourcesSettings() },
                    )
                }
                SettingsActionRow(
                    title = "Update ${updateState.info.versionName} installieren",
                    actionLabel = "Installieren",
                    onClick = {
                        onClearUpdateMessage()
                        scope.launch {
                            onSetUpdateMessage(
                                when (val result = updateManager.installDownloadedUpdate()) {
                                    InstallResult.Started -> "Android-Paketinstaller wurde geöffnet."
                                    InstallResult.PermissionRequired -> "Erlaube zuerst die Installation aus dieser Quelle."
                                    is InstallResult.Error -> result.message
                                },
                            )
                        }
                    },
                )
            }

            else -> Unit
        }

        updateMessage?.let { message ->
            SettingsNotice(text = message)
        }

        SettingsSectionHeader("TMDB")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AsyncImage(
                model = TMDB_APPROVED_LOGO_URL,
                contentDescription = "TMDB",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(64.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (tmdbConfigured) "TMDB aktiv" else "TMDB nicht konfiguriert",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (tmdbConfigured) {
                        "$tmdbResolvedCount von $enrichedItemCount sichtbaren Watch-Next-Einträgen aktuell aufgelöst."
                    } else {
                        "Dieser Build enthält keinen TMDB Read-Access-Token."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "Film-, Serien- und Episodenmetadaten sowie zugehörige Bilder können von TMDB stammen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun SettingsContentPane(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 920.dp)
            .verticalScroll(scrollState)
            .touchScrollFallback(scrollState, Orientation.Vertical)
            .padding(end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(10.dp))
        content()
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    actionLabel: String = "Ausführen",
    attention: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = subtitle?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                value?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (attention) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    )
                }
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalContentColor.current,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsInfoRow(
    title: String,
    value: String,
    attention: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsNotice(
    text: String,
    attention: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (attention) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
                },
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

@Composable
private fun SettingsEmptyState(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun DiagnosticPanel(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
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

private fun updateStateText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Noch nicht geprüft"
    UpdateState.Checking -> "Suche nach neuer Version …"
    is UpdateState.UpToDate -> "Aktuell"
    is UpdateState.Available -> "${state.info.versionName} verfügbar"
    is UpdateState.SigningRequired -> "Neue Version vorhanden, Signatur nicht kompatibel"
    is UpdateState.Downloading -> state.progressPercent?.let { "$it % heruntergeladen" }
        ?: "Download läuft …"
    is UpdateState.ReadyToInstall -> "${state.info.versionName} bereit zur Installation"
    is UpdateState.Error -> "Fehler: ${state.message}"
}

private fun watchNextTypeLabel(type: Int?): String = when (type) {
    0 -> "CONTINUE"
    1 -> "NEXT"
    2 -> "NEW"
    3 -> "WATCHLIST"
    else -> type?.toString() ?: "?"
}