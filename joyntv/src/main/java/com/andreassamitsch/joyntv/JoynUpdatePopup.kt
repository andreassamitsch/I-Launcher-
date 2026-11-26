package com.andreassamitsch.joyntv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Text

@Composable
internal fun JoynUpdatePopup(
    state: JoynUpdateState,
    info: JoynUpdateInfo,
    message: String?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    val laterFocus = remember { FocusRequester() }
    val downloading = state is JoynUpdateState.Downloading
    val ready = state is JoynUpdateState.ReadyToInstall
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF20252E), RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Joyn TV – Update verfügbar", color = Color.White, fontSize = 23.sp)
            Text("Installiert: ${BuildConfig.VERSION_NAME}\nNeu: ${info.versionName}", color = Color.White, fontSize = 16.sp)
            if (downloading) {
                val progress = (state as JoynUpdateState.Downloading).progressPercent
                Text(progress?.let { "Download: $it %" } ?: "Update wird heruntergeladen …", color = Color.White)
            } else if (ready) {
                Text("Download abgeschlossen. Das Update kann installiert werden.", color = Color.White)
            }
            message?.let { Text(it, color = Color(0xFFFFC5C5)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!downloading) {
                    Button(
                        onClick = if (ready) onInstall else onDownload,
                        modifier = Modifier.focusRequester(primaryFocus),
                    ) { Text(if (ready) "Installieren" else "Update laden") }
                }
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(laterFocus)) { Text("Später") }
            }
            LaunchedEffect(downloading, ready) {
                if (downloading) laterFocus.requestFocus() else primaryFocus.requestFocus()
            }
        }
    }
}
