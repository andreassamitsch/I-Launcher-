package com.andreassamitsch.ilauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
 * The navigation is an overlay layer and never participates in Home's row-keyline geometry.
 * Content destinations stay on the left while search/settings are compact utilities on the right,
 * matching current Google-TV/TCL layouts. On Home, leaving the navigation for the content rails
 * visually collapses the bar to the small top chevron seen on Google TV. The invisible navigation
 * remains in the focus tree at the same coordinates, so UP can focus it again without reflowing or
 * scrolling Home; gaining focus immediately fades the bar back in.
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
    var navigationHasFocus by remember { mutableStateOf(true) }
    val collapsed = activeSection == LauncherSection.Home && !navigationHasFocus
    val navAlpha by animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "top-nav-alpha",
    )
    val cueAlpha by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "top-nav-cue-alpha",
    )

    LaunchedEffect(selectedSection) {
        selectedFocusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GoogleTvTopNavigationHeight)
            .zIndex(20f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GoogleTvTopNavigationHeight)
                .graphicsLayer { alpha = navAlpha }
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
                    .onFocusChanged { navigationHasFocus = it.hasFocus }
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
                    label = "Empfehlungen",
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
                    label = null,
                    section = LauncherSection.Search,
                    selected = selectedSection == LauncherSection.Search,
                    focusRequester = if (selectedSection == LauncherSection.Search) selectedFocusRequester else null,
                    onClick = { onSelect(LauncherSection.Search) },
                    compact = true,
                    glyph = GoogleTvUtilityGlyph.Search,
                )
                GoogleTvNavDestination(
                    label = null,
                    section = LauncherSection.Settings,
                    selected = selectedSection == LauncherSection.Settings,
                    focusRequester = if (selectedSection == LauncherSection.Settings) selectedFocusRequester else null,
                    onClick = { onSelect(LauncherSection.Settings) },
                    compact = true,
                    glyph = GoogleTvUtilityGlyph.Settings,
                )
            }
        }

        GoogleTvCollapsedNavigationCue(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 7.dp)
                .graphicsLayer { alpha = cueAlpha },
        )
    }
}

@Composable
internal fun GoogleTvCollapsedNavigationCue(
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .size(width = 26.dp, height = 18.dp)
            .zIndex(21f),
    ) {
        val color = Color.White.copy(alpha = 0.62f)
        val stroke = 1.6.dp.toPx()
        val y = size.height * 0.62f
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.28f, y),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.36f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.36f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.72f, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private enum class GoogleTvUtilityGlyph {
    Search,
    Settings,
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun GoogleTvNavDestination(
    label: String?,
    section: LauncherSection,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    glyph: GoogleTvUtilityGlyph? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val idleContainer = if (selected) onSurface.copy(alpha = 0.94f) else Color.Transparent
    val idleContent = if (selected) surface else onSurface.copy(alpha = 0.82f)
    val focusedContainer = if (selected) onSurface else onSurface.copy(alpha = 0.16f)
    val focusedContent = if (selected) surface else onSurface
    val focusModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    TouchButton(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = ButtonDefaults.colors(
            containerColor = idleContainer,
            contentColor = idleContent,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContent,
            pressedContainerColor = if (selected) onSurface.copy(alpha = 0.86f) else onSurface.copy(alpha = 0.24f),
            pressedContentColor = if (selected) surface else onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f),
        ),
        scale = ButtonDefaults.scale(
            focusedScale = if (selected) 1.015f else 1.025f,
            pressedScale = 0.985f,
        ),
        contentPadding = PaddingValues(
            horizontal = if (compact) 0.dp else 13.dp,
            vertical = 0.dp,
        ),
        modifier = focusModifier
            .height(36.dp)
            .then(if (compact) Modifier.width(38.dp) else Modifier),
    ) {
        when (glyph) {
            GoogleTvUtilityGlyph.Search -> SearchGlyph(if (selected) surface else onSurface.copy(alpha = 0.90f))
            GoogleTvUtilityGlyph.Settings -> SettingsGlyph(if (selected) surface else onSurface.copy(alpha = 0.90f))
            null -> Unit
        }
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
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

@Composable
private fun SettingsGlyph(color: Color) {
    Canvas(modifier = Modifier.size(17.dp)) {
        val stroke = 1.55.dp.toPx()
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color,
            radius = size.minDimension * 0.22f,
            center = center,
            style = Stroke(width = stroke),
        )
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45.0) - 90.0)
            val inner = size.minDimension * 0.34f
            val outer = size.minDimension * 0.45f
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(center.x + dx * inner, center.y + dy * inner),
                end = androidx.compose.ui.geometry.Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
