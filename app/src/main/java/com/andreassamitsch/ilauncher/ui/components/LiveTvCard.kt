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
import java.text.DateFormat
import java.util.Date

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
            .width(300.dp)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused?.invoke()
            },
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(146.dp)
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
                            .padding(14.dp)
                            .size(width = 180.dp, height = 62.dp),
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
                            .padding(10.dp)
                            .size(width = 82.dp, height = 38.dp),
                    )
                }

                channel.progressFraction()?.let { progress ->
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

            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel.now?.title ?: "Keine EPG-Daten",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                channel.now?.let { program ->
                    Text(
                        text = "${formatTime(program.startUtcMillis)}–${formatTime(program.endUtcMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                channel.next?.let { next ->
                    Text(
                        text = "Danach: ${next.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatTime(utcMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMillis))
