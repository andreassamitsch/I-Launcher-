package com.andreassamitsch.ilauncher.ui.epg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import java.text.DateFormat
import java.util.Date

@Composable
fun EpgScreen(
    state: EpgState,
    channels: List<LiveTvChannel>,
    selectedServiceReference: String?,
    selectedProgram: LiveTvProgram?,
    onSelectChannel: (String) -> Unit,
    onSelectProgram: (serviceReference: String, program: LiveTvProgram) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    channelListState: LazyListState = rememberLazyListState(),
    programListState: LazyListState = rememberLazyListState(),
) {
    val selectedChannel = channels.firstOrNull { it.serviceReference == selectedServiceReference }
        ?: channels.firstOrNull()
    val guide = state.guide(selectedChannel?.serviceReference)

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("EPG", style = MaterialTheme.typography.displaySmall)
                Text(
                    text = "XMLTV-Anreicherung · ${state.sourceLabel}" +
                        (state.epgLabel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRefresh, enabled = !state.isRefreshing && channels.isNotEmpty()) {
                Text(if (state.isRefreshing) "Aktualisiere …" else "EPG aktualisieren")
            }
        }

        state.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (channels.isEmpty()) {
            Text(
                "Zuerst im Bereich Live TV eine Gigablue verbinden und ein Bouquet auswählen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sender", style = MaterialTheme.typography.headlineSmall)
                LazyColumn(
                    state = channelListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(channels, key = LiveTvChannel::serviceReference) { channel ->
                        Button(
                            onClick = { onSelectChannel(channel.serviceReference) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (channel.serviceReference == selectedChannel?.serviceReference) {
                                    "✓ ${channel.name}"
                                } else {
                                    channel.name
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    selectedChannel?.name ?: "Programm",
                    style = MaterialTheme.typography.headlineSmall,
                )

                selectedProgram?.takeIf {
                    selectedChannel != null && it in guide
                }?.let { program ->
                    ProgramDetails(program)
                }

                if (guide.isEmpty()) {
                    Text(
                        "Für diesen Sender sind noch keine zugeordneten XMLTV-Programmdaten vorhanden. Die Senderzuordnung kann unter Live TV geprüft werden.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = programListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = guide,
                            key = { "${it.xmltvChannelId}:${it.startUtcMillis}" },
                        ) { program ->
                            Button(
                                onClick = {
                                    selectedChannel?.let { channel ->
                                        onSelectProgram(channel.serviceReference, program)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        "${formatTime(program.startUtcMillis)}–${formatTime(program.endUtcMillis)} · ${program.title}",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    program.subtitle?.let { subtitle ->
                                        Text(
                                            subtitle,
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
                }
            }
        }
    }
}

@Composable
private fun ProgramDetails(program: LiveTvProgram) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        program.preferredArtworkUri?.let { artwork ->
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(260.dp)
                    .height(146.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(program.title, style = MaterialTheme.typography.titleLarge)
            program.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(
                "${formatTime(program.startUtcMillis)}–${formatTime(program.endUtcMillis)}" +
                    episodeLabel(program),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            program.categories.orEmpty().takeIf { it.isNotEmpty() }?.let { categories ->
                Text(
                    categories.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val description = program.longDescription ?: program.shortDescription
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun episodeLabel(program: LiveTvProgram): String = when {
    program.seasonNumber != null && program.episodeNumber != null ->
        " · S${program.seasonNumber}:E${program.episodeNumber}"
    else -> ""
}

private fun formatTime(utcMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcMillis))
