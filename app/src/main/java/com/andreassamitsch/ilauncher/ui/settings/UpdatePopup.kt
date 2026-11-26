package com.andreassamitsch.ilauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.update.InstallResult
import com.andreassamitsch.ilauncher.data.update.UpdateManager
import com.andreassamitsch.ilauncher.data.update.UpdateState
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.theme.ILauncherTheme
import kotlinx.coroutines.launch

internal fun updatePromptVersion(state: UpdateState): Int? = when (state) {
    is UpdateState.Available -> state.info.versionCode.takeIf { state.info.updateCompatible }
    is UpdateState.Downloading -> state.info.versionCode
    is UpdateState.ReadyToInstall -> state.info.versionCode
    else -> null
}

@Composable
internal fun UpdatePopup(manager: UpdateManager, state: UpdateState, enabled: Boolean) {
    // "Später" silences this version for this launcher session, not a newer build.
    var dismissedVersion by rememberSaveable { mutableIntStateOf(-1) }
    var actionVersion by rememberSaveable { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val version = updatePromptVersion(state)
        ?: actionVersion.takeIf { it > 0 && state is UpdateState.Error }
    if (!enabled || version == null || dismissedVersion == version) return

    val dismiss = { dismissedVersion = version; message = null }
    val focus = remember { FocusRequester() }
    val stage = when (state) {
        is UpdateState.Available -> 1
        is UpdateState.Downloading -> 2
        is UpdateState.ReadyToInstall -> 3
        else -> 4
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(version, stage) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        ILauncherTheme {
            val dialogShape = RoundedCornerShape(24.dp)
            val colors = MaterialTheme.colorScheme
            val buttonColors = ButtonDefaults.colors(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurface,
                focusedContainerColor = colors.onSurface,
                focusedContentColor = colors.surface,
                pressedContainerColor = colors.secondary,
                pressedContentColor = colors.onSecondary,
                disabledContainerColor = colors.surfaceVariant,
                disabledContentColor = colors.onSurfaceVariant,
            )
            val buttonPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            val buttonTextStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth(0.90f)
                    .background(colors.surface, dialogShape)
                    .border(1.dp, colors.border, dialogShape)
                    .padding(30.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("I Launcher – Update", color = colors.onSurface,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 27.sp, fontWeight = FontWeight.SemiBold))
                Text(when (state) {
                    is UpdateState.Available -> "Neue Version ${state.info.versionName} ist verfügbar."
                    is UpdateState.Downloading -> state.progressPercent?.let { "Download: $it %" } ?: "Download läuft …"
                    is UpdateState.ReadyToInstall -> "${state.info.versionName} ist bereit zur Installation."
                    is UpdateState.Error -> state.message
                    else -> ""
                }, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp))
                message?.let { Text(it, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 25.sp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state) {
                        is UpdateState.Available -> TouchButton(
                            onClick = {
                                actionVersion = version
                                message = null
                                manager.startDownload(state.info)
                            },
                            modifier = Modifier.focusRequester(focus),
                            colors = buttonColors,
                            contentPadding = buttonPadding,
                        ) { Text("Jetzt aktualisieren", style = buttonTextStyle) }
                        is UpdateState.ReadyToInstall -> TouchButton(
                            onClick = {
                                if (!installing) {
                                    actionVersion = version
                                    installing = true
                                    scope.launch {
                                        try {
                                            message = when (val result = manager.installDownloadedUpdate()) {
                                                InstallResult.Started -> "Android-Paketinstaller wurde geöffnet."
                                                InstallResult.PermissionRequired -> "Bitte Installation aus dieser Quelle erlauben und erneut versuchen."
                                                is InstallResult.Error -> result.message
                                            }
                                        } finally {
                                            installing = false
                                        }
                                    }
                                }
                            },
                            enabled = !installing,
                            modifier = Modifier.focusRequester(focus),
                            colors = buttonColors,
                            contentPadding = buttonPadding,
                        ) { Text("Installieren", style = buttonTextStyle) }
                        is UpdateState.Error -> TouchButton(
                            onClick = { message = null; scope.launch { manager.checkForUpdates() } },
                            modifier = Modifier.focusRequester(focus),
                            colors = buttonColors,
                            contentPadding = buttonPadding,
                        ) { Text("Erneut prüfen", style = buttonTextStyle) }
                        else -> Unit
                    }
                    TouchButton(
                        onClick = dismiss,
                        modifier = if (state is UpdateState.Downloading) Modifier.focusRequester(focus) else Modifier,
                        colors = buttonColors,
                        contentPadding = buttonPadding,
                    ) { Text("Später", style = buttonTextStyle) }
                }
            }
        }
    }
}
