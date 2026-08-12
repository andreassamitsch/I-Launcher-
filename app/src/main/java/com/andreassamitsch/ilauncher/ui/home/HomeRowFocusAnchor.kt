package com.andreassamitsch.ilauncher.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.andreassamitsch.ilauncher.ui.HomeTopNavigationFocusRequester
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Keeps the currently focused Home row at one stable vertical stage position.
 *
 * The Google-TV launcher uses a vertical grid keyline and treats each horizontal rail as one
 * vertical focus unit. We mirror that model here: entering another row may move the Home keyline,
 * while LEFT/RIGHT movement inside the same focus group never schedules another vertical scroll.
 * Automatic child bring-into-view propagation into Home's vertical ScrollState is stopped by the
 * vertical focus boundary installed in touchScrollFallback().
 *
 * The first row needs one explicit UP edge to the overlay navigation. Hero and navigation overlap
 * spatially, so real TV focus engines cannot reliably infer that edge from bounds alone. A parent
 * focusProperties override applies the same deterministic UP destination to every card in the top
 * row without altering the row's layout or horizontal navigation.
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
    val isTopHomeRow = scrollState.value <= 2 &&
        rowTopInRoot.isFinite() &&
        abs(rowTopInRoot - targetTopPx) <= 3f

    fun anchorRow() {
        scope.launch {
            // Allow focus/navigation state to settle, then place the newly entered row exactly on
            // the Home keyline. This runs once on false -> true row focus, never per child card.
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
            .focusProperties {
                if (isTopHomeRow) up = HomeTopNavigationFocusRequester
            }
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
        // Child focus still updates Hero/content state at call sites. This callback intentionally
        // does nothing: vertical alignment belongs to the row entry transition above.
        content { }
    }
}
