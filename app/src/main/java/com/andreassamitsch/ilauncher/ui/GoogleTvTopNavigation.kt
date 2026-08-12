package com.andreassamitsch.ilauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.ui.components.TouchButton

internal val GoogleTvTopNavigationHeight = 58.dp

/**
 * Google-TV-inspired destination navigation.
 *
 * Destination selection and D-Pad focus are deliberately separate states: the current destination
 * keeps its compact filled pill while focus is down in Home content, while a different tab only
 * receives the same pill temporarily when the user actually focuses it. The navigation floats over
 * the Hero and therefore never changes Home's vertical layout/keyline when focus enters/leaves it.
 */
@Composable
internal fun GoogleTvTopNavigation(
    activeSection: LauncherSection,
    onSelect: (LauncherSection) -> Unit,
    onOpenHomeSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedSection = if (activeSection == LauncherSection.LiveTv) {
        LauncherSection.Settings
    } else {
        activeSection
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GoogleTvTopNavigationHeight)
            .zIndex(20f)
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.30f),
                    0.72f to Color.Black.copy(alpha = 0.09f),
                    1f to Color.Transparent,
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GoogleTvTopNavigationHeight)
                .padding(start = 38.dp, end = 30.dp, top = 10.dp, bottom = 8.dp)
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "I Launcher",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.93f),
                modifier = Modifier.padding(end = 8.dp),
            )

            GoogleTvNavDestination(
                label = "⌕  Suche",
                section = LauncherSection.Search,
                selected = selectedSection == LauncherSection.Search,
                onClick = { onSelect(LauncherSection.Search) },
            )
            GoogleTvNavDestination(
                label = "Für dich",
                section = LauncherSection.Home,
                selected = selectedSection == LauncherSection.Home,
                onClick = { onSelect(LauncherSection.Home) },
                onLongClick = onOpenHomeSettings,
            )
            GoogleTvNavDestination(
                label = "Apps",
                section = LauncherSection.Apps,
                selected = selectedSection == LauncherSection.Apps,
                onClick = { onSelect(LauncherSection.Apps) },
            )

            Spacer(Modifier.weight(1f))

            GoogleTvNavDestination(
                label = "⚙",
                section = LauncherSection.Settings,
                selected = selectedSection == LauncherSection.Settings,
                onClick = { onSelect(LauncherSection.Settings) },
                compact = true,
            )
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun GoogleTvNavDestination(
    label: String,
    section: LauncherSection,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val idleContainer = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f)
    } else {
        Color.Transparent
    }
    val idleContent = if (selected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    }

    TouchButton(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = ButtonDefaults.colors(
            containerColor = idleContainer,
            contentColor = idleContent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.surface,
            pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            pressedContentColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f),
        ),
        scale = ButtonDefaults.scale(
            focusedScale = 1.02f,
            pressedScale = 0.985f,
        ),
        contentPadding = PaddingValues(
            horizontal = if (compact) 11.dp else 13.dp,
            vertical = 0.dp,
        ),
        modifier = Modifier
            .height(36.dp)
            .then(if (compact) Modifier.width(38.dp) else Modifier),
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}
