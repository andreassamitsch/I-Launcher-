package com.andreassamitsch.ilauncher.ui.home

import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.unit.Dp
import com.andreassamitsch.ilauncher.ui.HomeTopNavigationFocusRequester
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Home alone owns vertical positioning through its explicit row keyline. A horizontally focused
 * child may still request bring-into-view for its LazyRow, but that request must stop at the row
 * before it can move Home's outer vertical ScrollState.
 *
 * Keep this boundary local to AnchoredHomeRow. Generic vertically scrollable pages intentionally
 * do not install it, so DPAD focus there can bring controls below the viewport into view.
 */
private data object VerticalFocusBringIntoViewBoundaryElement :
    ModifierNodeElement<VerticalFocusBringIntoViewBoundaryNode>() {
    override fun create(): VerticalFocusBringIntoViewBoundaryNode =
        VerticalFocusBringIntoViewBoundaryNode()

    override fun update(node: VerticalFocusBringIntoViewBoundaryNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "homeVerticalFocusBringIntoViewBoundary"
    }
}

private class VerticalFocusBringIntoViewBoundaryNode :
    Modifier.Node(),
    BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?,
    ) {
        // Intentionally satisfied here. Entering another Home row is aligned explicitly below;
        // LEFT/RIGHT child focus must never move the whole Home page vertically.
    }
}

/**
 * Keeps the currently focused Home row at one stable vertical stage position.
 *
 * The Google-TV launcher uses a vertical grid keyline and treats each horizontal rail as one
 * vertical focus unit. We mirror that model here: entering another row may move the Home keyline,
 * while LEFT/RIGHT movement inside the same focus group never schedules another vertical scroll.
 * Automatic child bring-into-view propagation into Home's vertical ScrollState is stopped by the
 * boundary installed directly on this Home row.
 *
 * Home's overlay navigation and the large Hero overlap spatially. On real TV focus engines an
 * automatic UP search from the first rail can therefore choose the Hero and then get trapped below
 * the invisible navigation. While Home is at its top scroll position this row intercepts DPAD_UP
 * before child focus search and sends it directly to the selected Home navigation destination.
 * Lower rows keep normal UP/DOWN focus behavior because their anchored scroll position is > 0.
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
    val homeAtTop = scrollState.value <= 2

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
            .then(VerticalFocusBringIntoViewBoundaryElement)
            .onPreviewKeyEvent { composeEvent ->
                val event = composeEvent.nativeKeyEvent
                if (
                    homeAtTop &&
                    event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                    event.action == AndroidKeyEvent.ACTION_DOWN
                ) {
                    HomeTopNavigationFocusRequester.requestFocus()
                    true
                } else {
                    false
                }
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
