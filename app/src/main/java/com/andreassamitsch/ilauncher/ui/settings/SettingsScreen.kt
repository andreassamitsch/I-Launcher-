package com.andreassamitsch.ilauncher.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.system.HomeLauncherManager

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var isDefaultHome by remember { mutableStateOf(HomeLauncherManager.isDefaultHome(context)) }
    var isHomeOverrideEnabled by remember {
        mutableStateOf(HomeLauncherManager.isHomeButtonOverrideEnabled(context))
    }

    fun refreshLauncherStatus() {
        isDefaultHome = HomeLauncherManager.isDefaultHome(context)
        isHomeOverrideEnabled = HomeLauncherManager.isHomeButtonOverrideEnabled(context)
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Einstellungen",
            style = MaterialTheme.typography.displaySmall,
        )

        Text(
            text = "Launcher",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = when {
                isDefaultHome -> "I Launcher ist als Standard-Launcher aktiv."
                isHomeOverrideEnabled -> "Home-Taste wird über den Google-TV-Fallback zu I Launcher umgeleitet."
                else -> "I Launcher ist noch nicht als Home-Oberfläche aktiviert."
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Button(
            onClick = { HomeLauncherManager.openDefaultHomeSelection(context) },
        ) {
            Text("Als Standard-Launcher festlegen")
        }

        Button(
            onClick = { HomeLauncherManager.openAccessibilitySettings(context) },
        ) {
            Text(
                if (isHomeOverrideEnabled) {
                    "Home-Tasten-Fallback verwalten"
                } else {
                    "Home-Tasten-Fallback für Google TV aktivieren"
                },
            )
        }

        Text(
            text = "Auf Google-TV-Geräten, die die freie Auswahl des Standard-Launchers sperren, kann I Launcher über Bedienungshilfen die Home-Taste übernehmen. Aktiviere dort „I Launcher – Home-Taste“.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
