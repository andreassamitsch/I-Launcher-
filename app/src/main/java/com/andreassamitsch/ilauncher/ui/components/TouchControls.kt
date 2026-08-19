package com.andreassamitsch.ilauncher.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.Card as TvCard
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    var confirmPressed by remember(onLongClick) { mutableStateOf(false) }
    var longPressHandled by remember(onLongClick) { mutableStateOf(false) }
    var longPressJob by remember(onLongClick) { mutableStateOf<Job?>(null) }

    DisposableEffect(onLongClick) {
        onDispose { longPressJob?.cancel() }
    }

    val remoteLongPressModifier = if (onLongClick == null) {
        Modifier
    } else {
        Modifier.onPreviewKeyEvent { composeEvent ->
            val event = composeEvent.nativeKeyEvent
            val isConfirm = event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                event.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
            if (!enabled || !isConfirm) return@onPreviewKeyEvent false

            when (event.action) {
                AndroidKeyEvent.ACTION_DOWN -> {
                    if (!confirmPressed) {
                        confirmPressed = true
                        longPressHandled = false
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(ViewConfiguration.getLongPressTimeout().toLong())
                            if (confirmPressed && !longPressHandled) {
                                longPressHandled = true
                                onLongClick()
                            }
                        }
                    }
                    // Once the long press fired, or once Android begins repeating the held key,
                    // keep repeated DOWN events away from the underlying TV button. The initial
                    // DOWN is still allowed through so a normal short OK behaves exactly as before.
                    longPressHandled || event.repeatCount > 0
                }

                AndroidKeyEvent.ACTION_UP -> {
                    confirmPressed = false
                    longPressJob?.cancel()
                    longPressJob = null
                    if (longPressHandled) {
                        longPressHandled = false
                        true
                    } else {
                        false
                    }
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
            // Compose for TV buttons are optimized for D-pad input. Keep the explicit pointer
            // fallback that makes short taps and touch long-presses reliable on phones/tablets.
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
    // A 1.0 focused scale is reserved for the large, static Hero surface.
    // Keep it focusable/clickable but avoid rendering it like a giant TV card.
    val staticHeroScale = CardDefaults.scale(focusedScale = 1.0f)
    val isStaticHeroSurface = scale == staticHeroScale
    val resolvedShape = if (isStaticHeroSurface) {
        CardDefaults.shape(shape = RectangleShape)
    } else {
        shape
    }
    val resolvedBorder = if (isStaticHeroSurface) {
        CardDefaults.border(
            border = Border.None,
            focusedBorder = Border.None,
            pressedBorder = Border.None,
        )
    } else {
        border
    }
    // tv-material raises every focused Surface by zIndex 0.5 so card glows are not covered by
    // siblings. That is correct for normal rail cards, but wrong for Home's intentionally
    // underneath Hero: when the Hero gains focus it must never paint over the overlapping rail.
    // zIndex modifiers are additive, so -1 keeps the Hero below default-z rail content even while
    // tv-material applies its focused +0.5 layer.
    val focusLayeringModifier = if (isStaticHeroSurface) Modifier.zIndex(-1f) else Modifier

    TvCard(
        onClick = onClick,
        onLongClick = if (handleTvLongClick) onLongClick else null,
        modifier = modifier
            .then(focusLayeringModifier)
            .touchTap(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        scale = scale,
        shape = resolvedShape,
        border = resolvedBorder,
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
 * Adds pointer dragging to a Compose scroll state without changing TV focus relocation.
 *
 * Compose for TV click targets can consume pointer movement before a surrounding `verticalScroll`
 * sees it on touch devices. The normal scrollable remains authoritative whenever it reports an
 * active scroll. Only when no native scroll started after touch slop does this fallback take over
 * the drag, including movement already consumed by a child TV row.
 *
 * Normal vertically scrollable pages must keep Compose's automatic bring-into-view propagation so
 * DPAD focus can move the viewport to controls below the fold. Home's exceptional row-keyline
 * boundary therefore lives in AnchoredHomeRow instead of this generic input helper.
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
        var accumulatedDrag = 0f
        var fallbackDragging = false
        val touchSlop = viewConfiguration.touchSlop

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
            if (dragDelta == 0f) continue

            if (!fallbackDragging) {
                accumulatedDrag += dragDelta
                if (kotlin.math.abs(accumulatedDrag) <= touchSlop) continue

                // If Compose's own scrollable accepted the gesture, do not duplicate its delta.
                // If a child TV ListItem consumed it instead, the state remains idle and this
                // fallback deliberately takes ownership so phone/tablet dragging still works.
                if (state.isScrollInProgress) continue
                fallbackDragging = true
            }

            state.dispatchRawDelta(-dragDelta)
            change.consume()
        }
    }
}
