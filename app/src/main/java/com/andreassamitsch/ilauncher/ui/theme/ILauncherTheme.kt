package com.andreassamitsch.ilauncher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val ILauncherDarkColors = darkColorScheme(
    primary = Color(0xFFE7EAF0),
    onPrimary = Color(0xFF14171C),
    primaryContainer = Color(0xFF2B3038),
    onPrimaryContainer = Color(0xFFF4F6FA),
    secondary = Color(0xFFC7CCD6),
    onSecondary = Color(0xFF171A1F),
    background = Color(0xFF080A0E),
    onBackground = Color(0xFFF4F6FA),
    surface = Color(0xFF10141B),
    onSurface = Color(0xFFF4F6FA),
    surfaceVariant = Color(0xFF252A32),
    onSurfaceVariant = Color(0xFFD7DBE3),
    border = Color(0xFF555C68),
)

@Composable
fun ILauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ILauncherDarkColors,
        content = content,
    )
}
