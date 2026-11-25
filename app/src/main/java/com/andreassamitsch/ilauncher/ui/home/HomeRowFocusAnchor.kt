package com.andreassamitsch.ilauncher.ui.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import com.andreassamitsch.ilauncher.ui.HomeTopNavigationFocusRequester
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Child horizontal bring-into-view must not move the vertical Home keyline. */
private data object VerticalFocusBringIntoViewBoundaryElement :
    ModifierNodeElement<VerticalFocusBringIntoViewBoundaryNode>() {
    override fun create() = VerticalFocusBringIntoViewBoundaryNode()
    override fun update(node: VerticalFocusBringIntoViewBoundaryNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "homeVerticalFocusBringIntoViewBoundary"
    }
}

private class VerticalFocusBringIntoViewBoundaryNode :
    Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?,
    ) = Unit
}

private class RowFocusAnchor {
    var hasFocus = false
    var job: Job? = null
}

/** LazyColumn owns Y. The focused row aligns by stable list index, without observing
 * scroll position in composition or tracking every row's root coordinates. */
@Composable
internal fun AnchoredHomeRow(
    listState: LazyListState,
    rowIndex: Int,
    onVerticalMove: (Int) -> Boolean = { false },
    content: @Composable (onRowFocused: () -> Unit) -> Unit,
) {
    val anchor = remember { RowFocusAnchor() }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .then(VerticalFocusBringIntoViewBoundaryElement)
            .onPreviewKeyEvent { composeEvent ->
                val event = composeEvent.nativeKeyEvent
                if (rowIndex == 0 &&
                    event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                    event.action == AndroidKeyEvent.ACTION_DOWN
                ) {
                    HomeTopNavigationFocusRequester.requestFocus()
                    true
                } else if (event.action == AndroidKeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> onVerticalMove(-1)
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> onVerticalMove(1)
                        else -> false
                    }
                } else false
            }
            .onFocusChanged { focusState ->
                val gained = focusState.hasFocus && !anchor.hasFocus
                anchor.hasFocus = focusState.hasFocus
                if (!anchor.hasFocus) anchor.job?.cancel()
                if (gained) {
                    anchor.job?.cancel()
                    anchor.job = scope.launch {
                        if (listState.firstVisibleItemIndex != rowIndex ||
                            listState.firstVisibleItemScrollOffset > 2
                        ) {
                            listState.animateScrollToItem(rowIndex)
                        }
                    }
                }
            }
            .focusGroup(),
    ) {
        content { }
    }
}
