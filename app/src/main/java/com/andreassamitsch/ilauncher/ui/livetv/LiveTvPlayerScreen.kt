package com.andreassamitsch.ilauncher.ui.livetv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifResolvedStream
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifStreamHttpException
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.delay

private const val PLAYER_OVERLAY_TIMEOUT_MILLIS = 3_000L

@OptIn(UnstableApi::class)
@Composable
internal fun LiveTvPlayerScreen(
    channels: List<LiveTvChannel>,
    initialServiceReference: String,
    onResolveStream: suspend (LiveTvChannel) -> OpenWebifResolvedStream,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val initialIndex = channels.indexOfFirst { it.serviceReference == initialServiceReference }
        .coerceAtLeast(0)
    var currentIndex by remember(channels, initialServiceReference) { mutableIntStateOf(initialIndex) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    val rootFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    val currentChannel = channels.getOrNull(currentIndex)

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        overlayVisible = true
        currentIndex = LiveTvZapping.nextIndex(currentIndex, channels.size, delta)
    }

    BackHandler {
        if (overlayVisible) {
            overlayVisible = false
        } else {
            onBack()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                loading = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (loading) overlayVisible = true
                if (playbackState == Player.STATE_READY) errorMessage = null
            }

            override fun onPlayerError(error: PlaybackException) {
                loading = false
                overlayVisible = true
                errorMessage = "Live-TV-Wiedergabe fehlgeschlagen (${error.errorCodeName})."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(currentChannel?.serviceReference) {
        val channel = currentChannel ?: return@LaunchedEffect
        loading = true
        overlayVisible = true
        errorMessage = null
        player.stop()
        player.clearMediaItems()
        runCatching {
            val stream = onResolveStream(channel)
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(6_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(stream.requestHeaders)
            val mediaItem = MediaItem.Builder()
                .setUri(stream.url)
                .setMediaId(channel.serviceReference)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(channel.name)
                        .build(),
                )
                .setMimeType(if (stream.isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
                .build()
            val mediaSource = if (stream.isHls) {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { throwable ->
            loading = false
            overlayVisible = true
            errorMessage = playbackErrorMessage(throwable)
        }
    }

    LaunchedEffect(overlayVisible, currentChannel?.serviceReference, loading, errorMessage) {
        if (overlayVisible && !loading && errorMessage == null) {
            delay(PLAYER_OVERLAY_TIMEOUT_MILLIS)
            overlayVisible = false
        }
    }

    LaunchedEffect(overlayVisible) {
        withFrameNanos { }
        if (overlayVisible) {
            overlayFocusRequester.requestFocus()
        } else {
            rootFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val nativeEvent = keyEvent.nativeKeyEvent
                val isConfirmKey = nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

                if (!overlayVisible && isConfirmKey) {
                    overlayVisible = true
                    return@onPreviewKeyEvent true
                }

                when (keyEvent.key) {
                    Key.ChannelUp -> {
                        zap(+1)
                        true
                    }
                    Key.ChannelDown -> {
                        zap(-1)
                        true
                    }
                    Key.DirectionUp -> {
                        zap(-1)
                        true
                    }
                    Key.DirectionDown -> {
                        zap(+1)
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible) {
            currentChannel?.let { channel ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                        .padding(horizontal = 36.dp, vertical = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    channel.piconUri?.let { picon ->
                        AsyncImage(
                            model = picon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 110.dp, height = 62.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.name, style = MaterialTheme.typography.headlineSmall)
                        channel.now?.let { now ->
                            Text(now.title, style = MaterialTheme.typography.titleMedium)
                        }
                        channel.next?.let { next ->
                            Text(
                                "Danach: ${next.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .padding(horizontal = 36.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                errorMessage?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (loading && errorMessage == null) {
                    Text(
                        "Live TV wird geladen …",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onBack) { Text("Zurück") }
                    Button(onClick = { zap(-1) }) { Text("Sender −") }
                    Button(
                        onClick = { zap(+1) },
                        modifier = Modifier.focusRequester(overlayFocusRequester),
                    ) { Text("Sender +") }
                    currentChannel?.let {
                        Text(
                            "${currentIndex + 1}/${channels.size} · ${it.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    "OK: Info einblenden · Zurück: Info ausblenden · D-Pad ↑/↓ oder CH+/CH−: Sender wechseln",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

internal object LiveTvZapping {
    fun nextIndex(currentIndex: Int, size: Int, delta: Int): Int {
        if (size <= 0) return 0
        val normalized = currentIndex.coerceIn(0, size - 1)
        return ((normalized + delta) % size + size) % size
    }
}

private fun playbackErrorMessage(throwable: Throwable): String = when (throwable) {
    is OpenWebifStreamHttpException -> when (throwable.statusCode) {
        401, 403 -> "Live-TV-Stream: OpenWebif-Authentifizierung fehlgeschlagen."
        else -> "Live-TV-Stream: OpenWebif antwortet mit HTTP ${throwable.statusCode}."
    }
    is UnknownHostException -> "Live-TV-Stream: Receiver-Hostname konnte nicht aufgelöst werden."
    is ConnectException -> "Live-TV-Stream: Gigablue ist nicht erreichbar."
    is SocketTimeoutException -> "Live-TV-Stream: Zeitüberschreitung beim Verbinden."
    else -> "Live-TV-Stream konnte nicht gestartet werden (${throwable.javaClass.simpleName})."
}
