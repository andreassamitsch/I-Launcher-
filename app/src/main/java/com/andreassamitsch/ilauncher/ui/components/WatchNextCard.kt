package com.andreassamitsch.ilauncher.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.model.MediaItem

@Composable
fun WatchNextCard(
    item: MediaItem,
    onClick: () -> Unit,
    onDetails: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var longPressHandled by remember(item.id) { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .width(300.dp)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { composeEvent ->
                val event = composeEvent.nativeKeyEvent
                val isConfirmKey = event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    event.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

                when {
                    onDetails != null &&
                        event.action == AndroidKeyEvent.ACTION_DOWN &&
                        event.keyCode == AndroidKeyEvent.KEYCODE_INFO -> {
                        onDetails()
                        true
                    }

                    onDetails != null &&
                        event.action == AndroidKeyEvent.ACTION_DOWN &&
                        isConfirmKey &&
                        event.repeatCount > 0 -> {
                        if (!longPressHandled) {
                            longPressHandled = true
                            onDetails()
                        }
                        true
                    }

                    event.action == AndroidKeyEvent.ACTION_UP &&
                        isConfirmKey &&
                        longPressHandled -> {
                        longPressHandled = false
                        true
                    }

                    else -> false
                }
            },
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val artwork = item.preferredArtworkUri
                if (artwork != null) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    )
                } else {
                    Text(
                        text = item.title.take(1).uppercase(),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                item.logoUri?.takeIf { it.isNotBlank() }?.let { logoUri ->
                    AsyncImage(
                        model = logoUri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(width = 72.dp, height = 32.dp),
                    )
                }

                item.progressFraction?.let { progress ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
