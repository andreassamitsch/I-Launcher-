package com.andreassamitsch.ilauncher.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.Card as TvCard
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape

@Composable
fun TouchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    scale: ButtonScale = ButtonDefaults.scale(),
    colors: ButtonColors = ButtonDefaults.colors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    var longPressHandled by remember(onLongClick) { mutableStateOf(false) }
    val remoteLongPressModifier = if (onLongClick == null) {
        Modifier
    } else {
        Modifier.onPreviewKeyEvent { composeEvent ->
            val event = composeEvent.nativeKeyEvent
            val isConfirm = event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                event.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
            when {
                !enabled || !isConfirm -> false
                event.action == AndroidKeyEvent.ACTION_DOWN && event.repeatCount > 0 -> {
                    longPressHandled = true
                    true
                }
                event.action == AndroidKeyEvent.ACTION_UP && longPressHandled -> {
                    longPressHandled = false
                    onLongClick()
                    true
                }
                else -> false
            }
        }
    }

    TvButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(remoteLongPressModifier)
            .touchTap(onClick = onClick, enabled = enabled, onLongClick = onLongClick),
        scale = scale,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun TouchCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    handleTvLongClick: Boolean = true,
    scale: CardScale = CardDefaults.scale(),
    shape: CardShape = CardDefaults.shape(),
    border: CardBorder = CardDefaults.border(),
    content: @Composable ColumnScope.() -> Unit,
) {
    TvCard(
        onClick = onClick,
        onLongClick = if (handleTvLongClick) onLongClick else null,
        modifier = modifier.touchTap(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        scale = scale,
        shape = shape,
        border = border,
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
