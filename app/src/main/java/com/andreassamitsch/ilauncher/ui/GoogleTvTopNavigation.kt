package com.andreassamitsch.ilauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.ui.components.TouchButton

internal val GoogleTvTopNavigationHeight = 58.dp

@Composable
internal fun GoogleTvTopNavigation(
    activeSection: LauncherSection,
    onSelect: (LauncherSection) -> Unit,
    onOpenSectionSettings: (LauncherSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedSection = when (activeSection) {
        LauncherSection.LiveTv -> LauncherSection.Settings
        LauncherSection.Apps -> null
        else -> activeSection
    }
    val utilityRequester = remember { FocusRequester() }
    val selectedRequester = when (selectedSection) {
        LauncherSection.Home -> HomeTopNavigationFocusRequester
        LauncherSection.Movies -> MoviesTopNavigationFocusRequester
        LauncherSection.Series -> SeriesTopNavigationFocusRequester
        null -> null
        else -> utilityRequester
    }
    var hasFocus by remember { mutableStateOf(true) }
    val collapsed = activeSection == LauncherSection.Home && !hasFocus
    val navAlpha by animateFloatAsState(if (collapsed) 0f else 1f, tween(150), label = "top-nav-alpha")
    val cueAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(180), label = "top-nav-cue-alpha")

    LaunchedEffect(selectedSection, selectedRequester) { selectedRequester?.requestFocus() }

    Box(modifier.fillMaxWidth().height(GoogleTvTopNavigationHeight).zIndex(20f)) {
        Box(
            Modifier.fillMaxWidth().height(GoogleTvTopNavigationHeight)
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
                Modifier.fillMaxWidth().height(GoogleTvTopNavigationHeight)
                    .padding(start = 38.dp, end = 30.dp, top = 10.dp, bottom = 8.dp)
                    .onFocusChanged { hasFocus = it.hasFocus }
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                NavDestination(
                    label = null,
                    section = LauncherSection.Home,
                    selected = selectedSection == LauncherSection.Home,
                    requester = selectedRequester.takeIf { selectedSection == LauncherSection.Home },
                    onClick = { onSelect(LauncherSection.Home) },
                    onLongClick = { onOpenSectionSettings(LauncherSection.Home) },
                    compact = true,
                    glyph = NavGlyph.Home,
                )
                NavDestination(
                    "Filme",
                    LauncherSection.Movies,
                    selectedSection == LauncherSection.Movies,
                    selectedRequester.takeIf { selectedSection == LauncherSection.Movies },
                    { onSelect(LauncherSection.Movies) },
                    { onOpenSectionSettings(LauncherSection.Movies) },
                )
                NavDestination(
                    "Serien",
                    LauncherSection.Series,
                    selectedSection == LauncherSection.Series,
                    selectedRequester.takeIf { selectedSection == LauncherSection.Series },
                    { onSelect(LauncherSection.Series) },
                    { onOpenSectionSettings(LauncherSection.Series) },
                )
                Spacer(Modifier.weight(1f))
                NavDestination(
                    null,
                    LauncherSection.Search,
                    selectedSection == LauncherSection.Search,
                    selectedRequester.takeIf { selectedSection == LauncherSection.Search },
                    { onSelect(LauncherSection.Search) },
                    compact = true,
                    glyph = NavGlyph.Search,
                )
                NavDestination(
                    null,
                    LauncherSection.Settings,
                    selectedSection == LauncherSection.Settings,
                    selectedRequester.takeIf { selectedSection == LauncherSection.Settings },
                    { onSelect(LauncherSection.Settings) },
                    compact = true,
                    glyph = NavGlyph.Settings,
                )
            }
        }
        GoogleTvCollapsedNavigationCue(
            Modifier.align(Alignment.TopCenter).padding(top = 7.dp).graphicsLayer { alpha = cueAlpha },
        )
    }
}

@Composable
internal fun GoogleTvCollapsedNavigationCue(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 26.dp, height = 18.dp).zIndex(21f)) {
        val color = Color.White.copy(alpha = 0.62f)
        val stroke = 1.6.dp.toPx()
        val y = size.height * 0.62f
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(size.width * 0.28f, y),
            androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.36f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(size.width * 0.50f, size.height * 0.36f),
            androidx.compose.ui.geometry.Offset(size.width * 0.72f, y),
            stroke,
            StrokeCap.Round,
        )
    }
}

private enum class NavGlyph { Home, Search, Settings }

@Suppress("UNUSED_PARAMETER")
@Composable
private fun NavDestination(
    label: String?,
    section: LauncherSection,
    selected: Boolean,
    requester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false,
    glyph: NavGlyph? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val idleContainer = if (selected) onSurface.copy(alpha = 0.94f) else Color.Transparent
    val idleContent = if (selected) surface else onSurface.copy(alpha = 0.82f)
    val focusModifier = requester?.let { Modifier.focusRequester(it) } ?: Modifier
    TouchButton(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = ButtonDefaults.colors(
            containerColor = idleContainer,
            contentColor = idleContent,
            focusedContainerColor = if (selected) onSurface else onSurface.copy(alpha = 0.16f),
            focusedContentColor = if (selected) surface else onSurface,
            pressedContainerColor = if (selected) onSurface.copy(alpha = 0.86f) else onSurface.copy(alpha = 0.24f),
            pressedContentColor = if (selected) surface else onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f),
        ),
        scale = ButtonDefaults.scale(
            focusedScale = if (selected) 1.015f else 1.025f,
            pressedScale = 0.985f,
        ),
        contentPadding = PaddingValues(horizontal = if (compact) 0.dp else 13.dp, vertical = 0.dp),
        modifier = focusModifier.height(36.dp).then(if (compact) Modifier.width(38.dp) else Modifier),
    ) {
        val glyphColor = if (selected) surface else onSurface.copy(alpha = 0.90f)
        when (glyph) {
            NavGlyph.Home -> HomeGlyph(glyphColor)
            NavGlyph.Search -> SearchGlyph(glyphColor)
            NavGlyph.Settings -> SettingsGlyph(glyphColor)
            null -> Unit
        }
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    }
}

@Composable
private fun HomeGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val house = Path().apply {
            moveTo(size.width * .16f, size.height * .48f)
            lineTo(size.width * .50f, size.height * .18f)
            lineTo(size.width * .84f, size.height * .48f)
            lineTo(size.width * .75f, size.height * .48f)
            lineTo(size.width * .75f, size.height * .82f)
            lineTo(size.width * .25f, size.height * .82f)
            lineTo(size.width * .25f, size.height * .48f)
            close()
        }
        drawPath(house, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.7.dp.toPx()
        val radius = size.minDimension * .29f
        val center = androidx.compose.ui.geometry.Offset(size.width * .43f, size.height * .43f)
        drawCircle(color, radius, center, style = Stroke(stroke))
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(center.x + radius * .72f, center.y + radius * .72f),
            androidx.compose.ui.geometry.Offset(size.width * .83f, size.height * .83f),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun SettingsGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.55.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val root = size.minDimension * .33f
        val tooth = size.minDimension * .46f
        val gear = Path()
        repeat(6) { index ->
            val centerAngle = index * 60.0 - 90.0
            listOf(-30.0 to root, -18.0 to root, -12.0 to tooth, 12.0 to tooth, 18.0 to root, 30.0 to root)
                .forEach { (offset, radius) ->
                    val angle = Math.toRadians(centerAngle + offset)
                    val x = centerX + kotlin.math.cos(angle).toFloat() * radius
                    val y = centerY + kotlin.math.sin(angle).toFloat() * radius
                    if (gear.isEmpty) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
        }
        gear.close()
        drawPath(gear, color, style = Stroke(stroke, join = StrokeJoin.Round))
        drawCircle(
            color,
            size.minDimension * .15f,
            androidx.compose.ui.geometry.Offset(centerX, centerY),
            style = Stroke(stroke),
        )
    }
}