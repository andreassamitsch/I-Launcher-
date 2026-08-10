package com.andreassamitsch.ilauncher.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Card as TvCard
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.Button as TvButton

/**
 * Compose for TV keeps its existing D-Pad/focus handling. These wrappers add pointer taps only,
 * so phone/tablet smoke tests do not change the remote-control behaviour on Android TV.
 */
@Composable
fun TouchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TvButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.touchTap(onClick = onClick, enabled = enabled),
        content = content,
    )
}

@Composable
fun TouchCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    scale: CardScale = CardDefaults.scale(),
    content: @Composable ColumnScope.() -> Unit,
) {
    TvCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.touchTap(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        scale = scale,
        content = content,
    )
}

fun Modifier.touchTap(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
): Modifier = pointerInput(enabled, onClick, onLongClick) {
    detectTapGestures(
        onTap = {
            if (enabled) onClick()
        },
        onLongPress = if (onLongClick == null) {
            null
        } else {
            {
                if (enabled) onLongClick()
            }
        },
    )
}

/**
 * Normal Compose scroll containers get first chance to consume drag events. This final-pass fallback
 * only handles an otherwise unconsumed drag. dispatchRawDelta is deliberately limited to this
 * low-level compatibility path; regular TV/D-Pad and native Compose scrolling stay unchanged.
 */
fun Modifier.touchScrollFallback(
    state: ScrollableState,
    orientation: Orientation,
): Modifier = pointerInput(state, orientation) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Final,
        )
        var lastPosition: Offset = down.position

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break

            val currentPosition = change.position
            val dragDelta = when (orientation) {
                Orientation.Vertical -> currentPosition.y - lastPosition.y
                Orientation.Horizontal -> currentPosition.x - lastPosition.x
            }
            lastPosition = currentPosition

            if (!change.isConsumed && dragDelta != 0f) {
                state.dispatchRawDelta(-dragDelta)
            }
        }
    }
}
