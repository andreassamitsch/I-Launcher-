package com.andreassamitsch.ilauncher.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.data.update.InstallResult
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.data.update.UpdateState
import com.andreassamitsch.ilauncher.model.WatchNextLoadResult
import com.andreassamitsch.ilauncher.system.HomeLauncherManager
import com.andreassamitsch.ilauncher.system.TvProviderPermissionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    updateManager: UpdateManager,
    watchNextResult: WatchNextLoadResult,
    hasTvListingsPermission: Boolean,
    onRequestTvListingsPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val updateState by updateManager.state.collectAsState()
    val scrollState = rememberScrollState()

    var isDefaultHome by remember { mutableStateOf(HomeLauncherManager.isDefaultHome(context)) }
    var isHomeRoleAvailable by remember {
        mutableStateOf(HomeLauncherManager.isHomeRoleAvailable(context))
    }
    var isHomeRoleHeld by remember { mutableStateOf(HomeLauncherManager.isHomeRoleHeld(context)) }
    var isHomeOverrideEnabled by remember {
        mutableStateOf(HomeLauncherManager.isHomeButtonOverrideEnabled(context))
    }
    var updateMessage by remember { mutableStateOf<String?>(null) }

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

    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Einstellungen",
            style = MaterialTheme.typography.displaySmall,
        )

        Text(
            text = "Android-Freigaben",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = if (hasTvListingsPermission) {
                "TV-Inhalte: freigegeben. I Launcher darf Watch Next und Preview Channels anderer Apps lesen."
            } else {
                "TV-Inhalte: Freigabe fehlt. Ohne „TV-Programme/Kanäle lesen“ sieht I Launcher bei TvProvider-Abfragen nur eigene Einträge."
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (hasTvListingsPermission) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        if (!hasTvListingsPermission) {
            Button(onClick = onRequestTvListingsPermission) {
                Text("TV-Inhalte freigeben")
            }
            Button(onClick = { TvProviderPermissionManager.openAppDetails(context) }) {
                Text("App-Info / Berechtigungen öffnen")
            }
        }

        Text(
            text = if (isHomeOverrideEnabled) {
                "Home-Taste: Accessibility-Fallback ist freigegeben und aktiv."
            } else {
                "Home-Taste: Accessibility-Fallback ist nicht aktiv. Diese Sonderfreigabe kann Android nur in den Bedienungshilfen erteilen."
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (isHomeOverrideEnabled || isDefaultHome || isHomeRoleHeld) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        Text(
            text = "Launcher",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = when {
                isDefaultHome || isHomeRoleHeld -> "I Launcher ist als Standard-Home-App aktiv."
                isHomeOverrideEnabled -> "Home-Fallback über Bedienungshilfen ist aktiv."
                isHomeRoleAvailable -> "Android unterstützt die Home-Rolle. I Launcher kann direkt als Standard-Home-App angefordert werden."
                else -> "Der TV bietet keine freie Home-Rolle an. Verwende den Bedienungshilfe-Fallback."
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Button(
            onClick = { HomeLauncherManager.openDefaultHomeSelection(context) },
        ) {
            Text("Als Standard-Launcher festlegen")
        }

        if (!isDefaultHome && !isHomeRoleHeld) {
            Button(
                onClick = { HomeLauncherManager.openAccessibilitySettings(context) },
            ) {
                Text(
                    if (isHomeOverrideEnabled) {
                        "Home-Fallback verwalten"
                    } else {
                        "Home-Fallback über Bedienungshilfen aktivieren"
                    },
                )
            }

            if (!isHomeOverrideEnabled) {
                Button(
                    onClick = { HomeLauncherManager.openAppDetails(context) },
                ) {
                    Text("App-Info / eingeschränkte Einstellungen")
                }
            }

            Text(
                text = "Falls sich „I Launcher – Home-Taste“ in den Bedienungshilfen nicht einschalten lässt: App-Info öffnen, im Menü „Eingeschränkte Einstellungen zulassen“ aktivieren und danach die Bedienungshilfe erneut einschalten. Das betrifft insbesondere seitlich installierte Apps auf neueren Android-Versionen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = "Der Bedienungshilfe-Fallback reagiert sowohl auf einen gelieferten HOME-Key als auch darauf, wenn der System-Launcher nach einem Home-Druck sichtbar wird.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = "Watch Next Diagnose",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (!hasTvListingsPermission) {
            Text(
                text = "Diagnose pausiert, bis die Android-Berechtigung für TV-Inhalte erteilt wurde.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (watchNextResult.errorMessage != null) {
            Text(
                text = watchNextResult.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "TvProvider liefert ${watchNextResult.items.size} Einträge. Die Cursor-Reihenfolge wird unverändert verwendet.",
                style = MaterialTheme.typography.bodyMedium,
            )

            watchNextResult.items.take(20).forEach { item ->
                val progress = if (item.playbackPositionMillis != null && item.durationMillis != null) {
                    "${item.playbackPositionMillis}/${item.durationMillis} ms"
                } else {
                    "kein Fortschritt"
                }
                Text(
                    text = buildString {
                        append("#${item.sourceOrder} | ")
                        append(item.packageName ?: "Paket unbekannt")
                        append(" | ")
                        append(item.displayTitle)
                        item.displaySubtitle?.let { append(" | $it") }
                        append(" | $progress")
                        append(" | type=${item.watchNextType ?: "?"}")
                        append(" | intent=${if (item.intentUri.isNullOrBlank()) "nein" else "ja"}")
                        append(" | bild=${if (item.artworkUri.isNullOrBlank()) "nein" else "ja"}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (watchNextResult.items.size > 20) {
                Text(
                    text = "+ ${watchNextResult.items.size - 20} weitere Einträge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = "Updates",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = "Installiert: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = when (val state = updateState) {
                UpdateState.Idle -> "Update-Prüfung noch nicht gestartet."
                UpdateState.Checking -> "Suche nach einer neuen Version …"
                is UpdateState.UpToDate -> "I Launcher ist aktuell."
                is UpdateState.Available -> "Neue Version ${state.info.versionName} (${state.info.versionCode}) verfügbar."
                is UpdateState.SigningRequired -> "Neue Version ${state.info.versionName} ist vorhanden, aber der Development-Kanal hat noch keinen dauerhaft hinterlegten Signing-Key. Automatische Updates bleiben deshalb sicherheitshalber gesperrt."
                is UpdateState.Downloading -> state.progressPercent?.let {
                    "Update ${state.info.versionName} wird heruntergeladen: $it %"
                } ?: "Update ${state.info.versionName} wird heruntergeladen …"
                is UpdateState.ReadyToInstall -> "Update ${state.info.versionName} ist bereit zur Installation."
                is UpdateState.Error -> "Update-Fehler: ${state.message}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = {
                updateMessage = null
                scope.launch { updateManager.checkForUpdates() }
            },
        ) {
            Text("Jetzt nach Update suchen")
        }

        when (val state = updateState) {
            is UpdateState.Available -> {
                Button(onClick = { updateManager.startDownload(state.info) }) {
                    Text("Update herunterladen")
                }
            }

            is UpdateState.ReadyToInstall -> {
                if (!updateManager.canRequestPackageInstalls()) {
                    Button(onClick = { updateManager.openUnknownSourcesSettings() }) {
                        Text("Installation aus dieser Quelle erlauben")
                    }
                }

                Button(
                    onClick = {
                        updateMessage = null
                        scope.launch {
                            updateMessage = when (val result = updateManager.installDownloadedUpdate()) {
                                InstallResult.Started -> "Android-Paketinstaller wurde geöffnet."
                                InstallResult.PermissionRequired -> "Erlaube zuerst die Installation aus dieser Quelle und starte danach die Installation erneut."
                                is InstallResult.Error -> result.message
                            }
                        }
                    },
                ) {
                    Text("Update installieren")
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
            text = "I Launcher prüft beim Start automatisch auf neue Versionen. Der Download blockiert den Launcher nicht; die eigentliche APK-Installation wird an den Android-Systeminstaller übergeben.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
