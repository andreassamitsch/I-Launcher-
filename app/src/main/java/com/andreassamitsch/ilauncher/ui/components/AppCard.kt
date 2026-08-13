package com.andreassamitsch.ilauncher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.model.InstalledApp

private val AppIconSize = 82.dp
private val AppCardWidth = 94.dp
private val AppLabelHeight = 22.dp

@Composable
fun AppCard(
    app: InstalledApp,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    moveMode: Boolean = false,
    onMove: ((Int) -> Unit)? = null,
    labelAlwaysVisible: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val icon = remember(app.icon) { app.icon.asImageBitmap() }
    var focused by remember(app.packageName) { mutableStateOf(false) }

    // Google TV treats apps as a compact circular icon dock rather than rectangular content cards.
    // Keep the label outside the focus surface so scale/focus decoration never changes row geometry.
    Column(
        modifier = Modifier
            .width(AppCardWidth)
            .zIndex(if (focused) 1f else 0f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TouchCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .size(AppIconSize)
                .onFocusChanged { focusState ->
                    focused = focusState.isFocused
                    if (focusState.isFocused) onFocused?.invoke()
                }
                .onPreviewKeyEvent { event ->
                    if (!moveMode || event.type != KeyEventType.KeyDown || onMove == null) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onMove(-1)
                            true
                        }
                        Key.DirectionRight -> {
                            onMove(+1)
                            true
                        }
                        else -> false
                    }
                },
            scale = CardDefaults.scale(focusedScale = 1.075f),
            shape = CardDefaults.shape(shape = CircleShape),
            border = CardDefaults.border(
                border = Border.None,
                focusedBorder = Border.None,
                pressedBorder = Border.None,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = icon,
                    contentDescription = app.label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )

                if (focused || moveMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = if (moveMode) 3.dp else 2.dp,
                                color = if (moveMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.92f)
                                },
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }

        Text(
            text = if (moveMode) "↔ ${app.label}" else app.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .width(AppCardWidth)
                .height(AppLabelHeight)
                .alpha(if (focused || moveMode || labelAlwaysVisible) 1f else 0f),
        )
    }
}
