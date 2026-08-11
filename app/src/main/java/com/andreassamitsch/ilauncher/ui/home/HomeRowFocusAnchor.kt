package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Keeps the currently focused Home row at one stable vertical stage position.
 *
 * Compose's default focus relocation only scrolls the minimum distance necessary to expose the
 * focused child. On a Hero-over-rails layout that can leave a sliced remnant of the previous row
 * inside the Hero. We instead measure the complete row (header + rail) and, after the focus/layout
 * frame has settled, align its leading edge to the Home rail stage. Horizontal focus moves in the
 * same row become no-ops because the row is already at the target.
 */
@Composable
internal fun AnchoredHomeRow(
    scrollState: ScrollState,
    targetTop: Dp,
    content: @Composable (onRowFocused: () -> Unit) -> Unit,
) {
    var rowTopInRoot by remember { mutableFloatStateOf(Float.NaN) }
    val targetTopPx = with(LocalDensity.current) { targetTop.toPx() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            rowTopInRoot = coordinates.positionInRoot().y
        },
    ) {
        content {
            scope.launch {
                // Let navigation visibility and the platform's initial focus relocation settle
                // before applying the stable Home-row anchor.
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
    }
}
