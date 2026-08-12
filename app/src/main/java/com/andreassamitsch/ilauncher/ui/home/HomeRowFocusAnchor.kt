package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Framework-default focus scrolling for the nested horizontal rail. */
private val DefaultHomeRowBringIntoViewSpec = object : BringIntoViewSpec {}

/**
 * Keeps the currently focused Home row at one stable vertical stage position.
 *
 * Google TV treats a horizontal rail as one focus group on the vertical grid: entering another
 * rail may move the vertical keyline, while left/right movement inside the same rail must not
 * trigger another vertical alignment pass. Compose focus events bubble through the row, so we
 * observe descendant focus at this stable row container and anchor only on the false -> true
 * `hasFocus` transition.
 *
 * HomeScreen disables automatic bring-into-view on its outer vertical scrollable. Nested LazyRows
 * opt back into Compose's framework-default BringIntoViewSpec here so horizontal focus scrolling
 * still behaves normally. This mirrors the Android-TV migration guidance for nested TV layouts:
 * one vertical keyline owner, default scrolling for the horizontal child layout.
 */
@Composable
internal fun AnchoredHomeRow(
    scrollState: ScrollState,
    targetTop: Dp,
    content: @Composable (onRowFocused: () -> Unit) -> Unit,
) {
    var rowTopInRoot by remember { mutableFloatStateOf(Float.NaN) }
    var rowHasFocus by remember { mutableStateOf(false) }
    val targetTopPx = with(LocalDensity.current) { targetTop.toPx() }
    val scope = rememberCoroutineScope()

    fun anchorRow() {
        scope.launch {
            // Let navigation visibility and the focus transition settle before applying the stable
            // Home keyline. This runs once when focus enters the row, never per horizontal card.
            withFrameNanos { }
            withFrameNanos { }

            val currentTop = rowTopInRoot
            if (!currentTop.isFinite()) return@launch

            val targetScroll = (
                scrollState.value + (currentTop - targetTopPx)
                ).roundToInt().coerceIn(0, scrollState.maxValue)
            if (abs(targetScroll - scrollState.value) > 2) {
                scrollState.animateScrollTo(targetScroll)
            }
        }
    }

    Column(
        modifier = Modifier
            .onFocusChanged { focusState ->
                val gainedRowFocus = focusState.hasFocus && !rowHasFocus
                rowHasFocus = focusState.hasFocus
                if (gainedRowFocus) anchorRow()
            }
            .focusGroup()
            .onGloballyPositioned { coordinates ->
                rowTopInRoot = coordinates.positionInRoot().y
            },
    ) {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides DefaultHomeRowBringIntoViewSpec,
        ) {
            // Child focus still updates Hero/content state at the call sites. The legacy callback is
            // intentionally a no-op; vertical alignment is owned by the row-level transition above.
            content { }
        }
    }
}
