package com.andreassamitsch.ilauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.model.LiveTvChannel

@Composable
fun LiveTvCard(
    channel: LiveTvChannel,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val artwork = channel.now?.preferredArtworkUri
    var focused by remember(channel.serviceReference) { mutableStateOf(false) }
    val breath = rememberFocusedCardBreath(focused)

    Column(
        modifier = Modifier.width(172.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(97.dp),
        ) {
            FocusedArtworkGlow(
                artworkUri = artwork,
                focused = focused,
                breath = breath,
            )

            TouchCard(
                onClick = onClick,
                modifier = modifier
                    .fillMaxSize()
                    .onFocusChanged { focusState ->
                        focused = focusState.isFocused
                        if (focusState.isFocused) onFocused?.invoke()
                    },
                scale = CardDefaults.scale(focusedScale = 1.045f),
                shape = CardDefaults.shape(shape = FocusedMediaCardShape),
                border = CardDefaults.border(
                    border = Border.None,
                    focusedBorder = Border.None,
                    pressedBorder = Border.None,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (!artwork.isNullOrBlank()) {
                        AsyncImage(
                            model = artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (!channel.piconUri.isNullOrBlank()) {
                        AsyncImage(
                            model = channel.piconUri,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(12.dp)
                                .size(width = 122.dp, height = 42.dp),
                        )
                    } else {
                        Text(
                            text = channel.name.take(1).uppercase(),
                            style = MaterialTheme.typography.displayMedium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    if (!artwork.isNullOrBlank() && !channel.piconUri.isNullOrBlank()) {
                        AsyncImage(
                            model = channel.piconUri,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(width = 46.dp, height = 20.dp),
                        )
                    }

                    channel.progressFraction()?.let { progress ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f)),
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
            }

            FocusedBreathingBorder(
                focused = focused,
                breath = breath,
            )
        }

        Text(
            text = channel.now?.title ?: channel.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(top = 5.dp, start = 2.dp, end = 2.dp)
                .alpha(if (focused) 1f else 0.82f),
        )
    }
}
