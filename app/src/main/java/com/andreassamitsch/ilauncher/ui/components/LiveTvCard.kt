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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    TouchCard(
        onClick = onClick,
        modifier = modifier
            .width(236.dp)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused?.invoke()
            },
        scale = CardDefaults.scale(focusedScale = 1.028f),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(133.dp)
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
                            .padding(16.dp)
                            .size(width = 160.dp, height = 54.dp),
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
                            .padding(7.dp)
                            .size(width = 62.dp, height = 27.dp),
                    )
                }

                channel.progressFraction()?.let { progress ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)),
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

            Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
                Text(
                    text = channel.now?.title ?: channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.now != null) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
