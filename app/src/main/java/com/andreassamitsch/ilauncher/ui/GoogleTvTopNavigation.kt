package com.andreassamitsch.ilauncher.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * keeps its compact filled pill while focus is down in Home content. When a destination is opened,
 * its selected tab receives the initial focus so we never render two competing pills. The complete
 * navigation floats over the Hero and therefore never changes Home's vertical layout/keyline.
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
    val selectedFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectedSection) {
        selectedFocusRequester.requestFocus()
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
                label = "Suche",
                section = LauncherSection.Search,
                selected = selectedSection == LauncherSection.Search,
                focusRequester = if (selectedSection == LauncherSection.Search) selectedFocusRequester else null,
                showSearchIcon = true,
                onClick = { onSelect(LauncherSection.Search) },
            )
            GoogleTvNavDestination(
                label = "Für dich",
                section = LauncherSection.Home,
                selected = selectedSection == LauncherSection.Home,
                focusRequester = if (selectedSection == LauncherSection.Home) selectedFocusRequester else null,
                onClick = { onSelect(LauncherSection.Home) },
                onLongClick = onOpenHomeSettings,
            )
            GoogleTvNavDestination(
                label = "Apps",
                section = LauncherSection.Apps,
                selected = selectedSection == LauncherSection.Apps,
                focusRequester = if (selectedSection == LauncherSection.Apps) selectedFocusRequester else null,
                onClick = { onSelect(LauncherSection.Apps) },
            )

            Spacer(Modifier.weight(1f))

            GoogleTvNavDestination(
                label = "⚙",
                section = LauncherSection.Settings,
                selected = selectedSection == LauncherSection.Settings,
                focusRequester = if (selectedSection == LauncherSection.Settings) selectedFocusRequester else null,
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
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    showSearchIcon: Boolean = false,
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
    val focusModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

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
        modifier = focusModifier
            .height(36.dp)
            .then(if (compact) Modifier.width(38.dp) else Modifier),
    ) {
        if (showSearchIcon) {
            SearchGlyph()
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchGlyph() {
    val color = MaterialTheme.colorScheme.surface
    Canvas(modifier = Modifier.size(15.dp)) {
        val stroke = 1.7.dp.toPx()
        val radius = size.minDimension * 0.29f
        val centerX = size.width * 0.43f
        val centerY = size.height * 0.43f
        drawCircle(
            color = color,
            radius = radius,
            center = androidx.compose.ui.geometry.Offset(centerX, centerY),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(centerX + radius * 0.72f, centerY + radius * 0.72f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.83f, size.height * 0.83f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
