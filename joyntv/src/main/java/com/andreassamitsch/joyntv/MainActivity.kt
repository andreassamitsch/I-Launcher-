package com.andreassamitsch.joyntv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        setContent {
            JoynTvTheme {
                JoynHome(
                    repository = repository,
                    onPlay = { channel ->
                        startActivity(PlayerActivity.intent(this, channel.id, channel.title))
                    },
                )
            }
        }
    }
}

@Composable
private fun JoynHome(
    repository: JoynRepository,
    onPlay: (JoynLiveChannel) -> Unit,
) {
    var channels by remember { mutableStateOf<List<JoynLiveChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) { repository.loadLiveChannelsAndPublish() }
        }.onSuccess {
            channels = it
            selectedIndex = 0
        }.onFailure {
            errorText = it.message ?: it.javaClass.simpleName
        }
        loading = false
    }

    LaunchedEffect(channels) {
        if (channels.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    val selected = channels.getOrNull(selectedIndex)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        selected?.let { channel ->
            AsyncImage(
                model = channel.currentProgram?.imageUrl ?: channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.46f,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x33080A0E),
                        0.56f to Color(0xCC080A0E),
                        1f to Color(0xFF080A0E),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 54.dp),
        ) {
            Hero(selected)
            Spacer(Modifier.weight(1f))

            when {
                loading -> Text(
                    text = "Joyn wird geladen …",
                    modifier = Modifier.padding(horizontal = 64.dp, vertical = 36.dp),
                    fontSize = 22.sp,
                )

                errorText != null -> Column(Modifier.padding(horizontal = 64.dp, vertical = 32.dp)) {
                    Text("Joyn konnte nicht geladen werden", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(errorText.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                }

                channels.isEmpty() -> Text(
                    text = "Keine freien Live-Sender verfügbar.",
                    modifier = Modifier.padding(horizontal = 64.dp, vertical = 36.dp),
                    fontSize = 20.sp,
                )

                else -> {
                    Text(
                        text = "Live TV",
                        modifier = Modifier.padding(horizontal = 64.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 64.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(channels, key = { _, item -> item.id }) { index, channel ->
                            LiveChannelCard(
                                channel = channel,
                                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                onFocused = { selectedIndex = index },
                                onClick = { onPlay(channel) },
                            )
                        }
                    }
                    Spacer(Modifier.height(42.dp))
                }
            }
        }
    }
}

@Composable
private fun Hero(channel: JoynLiveChannel?) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.58f)
            .padding(horizontal = 64.dp),
    ) {
        Text(
            text = "JOYN  ·  I LAUNCHER",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = channel?.currentProgram?.title ?: channel?.title ?: "Joyn TV",
            fontSize = 42.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        channel?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = listOfNotNull(it.title, it.quality).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
            )
            it.currentProgram?.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveChannelCard(
    channel: JoynLiveChannel,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.055f else 1f, label = "channelFocus")
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .width(276.dp)
            .height(156.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(Color(0xFF161A21))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFFF4F6FA) else Color(0x44555C68),
                shape = shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        AsyncImage(
            model = channel.currentProgram?.imageUrl ?: channel.logoUrl,
            contentDescription = channel.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = if (channel.currentProgram?.imageUrl != null) 0.72f else 0.32f,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE8080A0E)))),
        )
        channel.logoUrl?.let { logo ->
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(width = 82.dp, height = 36.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(13.dp),
        ) {
            Text(
                text = channel.currentProgram?.title ?: channel.title,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.currentProgram?.title != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = channel.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
