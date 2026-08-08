package com.andreassamitsch.ilauncher.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.model.MediaItem

@Composable
fun DetailsScreen(
    item: MediaItem,
    sourceLabel: String?,
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onTrailer: (() -> Unit)? = null,
    onTrailerSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        item.preferredArtworkUri?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            item.logoUri?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 220.dp, height = 84.dp),
                )
                Spacer(Modifier.height(10.dp))
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val metadataLine = buildList {
                item.releaseYear?.let { add(it.toString()) }
                item.subtitle?.takeIf { it.isNotBlank() }?.let(::add)
                item.voteAverage?.takeIf { it > 0.0 }?.let { add("TMDB %.1f".format(it)) }
            }.joinToString(" · ")

            if (metadataLine.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = metadataLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.68f),
                )
            }

            sourceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Quelle: $label",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onPlay) {
                    Text(if ((item.playbackPositionMillis ?: 0L) > 0L) "Fortsetzen" else "Wiedergeben")
                }
                when {
                    onTrailer != null -> Button(onClick = onTrailer) {
                        Text("Trailer")
                    }

                    onTrailerSearch != null -> Button(onClick = onTrailerSearch) {
                        Text("Trailer suchen")
                    }
                }
                Button(onClick = onBack) {
                    Text("Zurück")
                }
            }
        }
    }
}
