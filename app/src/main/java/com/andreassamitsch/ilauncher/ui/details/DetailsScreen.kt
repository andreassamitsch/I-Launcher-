package com.andreassamitsch.ilauncher.ui.details

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.R
import com.andreassamitsch.ilauncher.data.handoff.ContentSearchHandoff
import com.andreassamitsch.ilauncher.data.youtube.YouTubeEmbedPlayer
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.TrailerProvider
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.trailer.TrailerPlayerActivity

@Composable
fun DetailsScreen(
    item: MediaItem,
    sourceLabel: String?,
    onPlay: (() -> Unit)?,
    onBack: () -> Unit,
    onTrailer: (() -> Unit)? = null,
    onTrailerSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val firstActionFocusRequester = remember(item.id) { FocusRequester() }
    val internalTrailerId = remember(item.trailer) {
        item.trailer
            ?.takeIf { it.provider == TrailerProvider.YouTube }
            ?.externalId
            ?.takeIf { YouTubeEmbedPlayer.html(it) != null }
    }
    val contentSearchHandoff = remember(context) {
        ContentSearchHandoff(context.applicationContext)
    }
    val externalSearchTargets = remember(item.tmdbId, item.tmdbEpisodeId, onPlay, contentSearchHandoff) {
        if ((item.tmdbId != null || item.tmdbEpisodeId != null) && onPlay == null) {
            contentSearchHandoff.availableTargets()
        } else {
            emptyList()
        }
    }
    val hasTrailerAction = internalTrailerId != null || onTrailer != null || onTrailerSearch != null
    val firstActionKey = when {
        onPlay != null -> "play"
        externalSearchTargets.isNotEmpty() -> "external:${externalSearchTargets.first().name}"
        hasTrailerAction -> "trailer"
        else -> "back"
    }

    LaunchedEffect(item.id, firstActionKey) {
        withFrameNanos { }
        runCatching { firstActionFocusRequester.requestFocus() }
    }

    fun Modifier.firstAction(key: String): Modifier =
        if (firstActionKey == key) focusRequester(firstActionFocusRequester) else this

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
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.76f)),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .touchScrollFallback(scrollState, Orientation.Vertical)
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

            sourceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                onPlay?.let { play ->
                    TouchButton(
                        onClick = play,
                        modifier = Modifier.firstAction("play"),
                    ) {
                        Text(if ((item.playbackPositionMillis ?: 0L) > 0L) "Fortsetzen" else "Wiedergeben")
                    }
                }

                externalSearchTargets.forEach { target ->
                    TouchButton(
                        onClick = { contentSearchHandoff.launch(target, item.title) },
                        modifier = Modifier.firstAction("external:${target.name}"),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                colorFilter = ColorFilter.tint(LocalContentColor.current),
                            )
                            Text(target.displayName)
                        }
                    }
                }

                when {
                    internalTrailerId != null -> TouchButton(
                        onClick = { TrailerPlayerActivity.start(context, internalTrailerId) },
                        modifier = Modifier.firstAction("trailer"),
                    ) {
                        Text("Trailer")
                    }

                    onTrailer != null -> TouchButton(
                        onClick = onTrailer,
                        modifier = Modifier.firstAction("trailer"),
                    ) {
                        Text("Trailer")
                    }

                    onTrailerSearch != null -> TouchButton(
                        onClick = onTrailerSearch,
                        modifier = Modifier.firstAction("trailer"),
                    ) {
                        Text("Trailer suchen")
                    }
                }

                TouchButton(
                    onClick = onBack,
                    modifier = Modifier.firstAction("back"),
                ) {
                    Text("Zurück")
                }
            }

            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(Modifier.height(20.dp))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(0.72f),
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
