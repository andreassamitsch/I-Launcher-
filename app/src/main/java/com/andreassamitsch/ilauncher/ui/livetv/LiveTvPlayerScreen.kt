package com.andreassamitsch.ilauncher.ui.livetv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.andreassamitsch.ilauncher.data.epg.EpgState
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifResolvedStream
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifStreamHttpException
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.ui.components.TouchButton
import com.andreassamitsch.ilauncher.ui.components.TouchCard
import com.andreassamitsch.ilauncher.ui.components.touchScrollFallback
import com.andreassamitsch.ilauncher.ui.epg.EpgScreen
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.delay

private const val PLAYER_OVERLAY_TIMEOUT_MILLIS = 3_000L
private const val LONG_OK_THRESHOLD_MILLIS = 650L

@OptIn(UnstableApi::class)
@Composable
internal fun LiveTvPlayerScreen(
    channels: List<LiveTvChannel>,
    initialServiceReference: String,
    onResolveStream: suspend (LiveTvChannel) -> OpenWebifResolvedStream,
    epgState: EpgState,
    initialShowEpg: Boolean = false,
    initialEpgProgramStartUtcMillis: Long? = null,
    onRefreshEpg: () -> Unit,
    onEnrichEpgProgram: (serviceReference: String, startUtcMillis: Long) -> Unit,
    onOpenEpgProgramDetails: (LiveTvChannel, LiveTvProgram) -> Unit,
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
    var showExitConfirmation by remember { mutableStateOf(false) }
    var showEpg by remember(initialShowEpg, initialServiceReference) {
        mutableStateOf(initialShowEpg)
    }
    var selectedEpgServiceReference by remember(initialServiceReference) {
        mutableStateOf(initialServiceReference)
    }
    var selectedEpgProgramStartUtcMillis by remember(
        initialServiceReference,
        initialEpgProgramStartUtcMillis,
    ) {
        mutableStateOf(initialEpgProgramStartUtcMillis)
    }
    val epgChannelListState = rememberLazyListState()
    val epgProgramListState = rememberLazyListState()
    val zapListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val rootFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }
    val epgButtonFocusRequester = remember { FocusRequester() }
    val epgBackFocusRequester = remember { FocusRequester() }
    val exitCancelFocusRequester = remember { FocusRequester() }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    val currentChannel = channels.getOrNull(currentIndex)
    val selectedEpgProgram = selectedEpgProgramStartUtcMillis?.let { start ->
        epgState.guide(selectedEpgServiceReference)
            .firstOrNull { it.startUtcMillis == start }
    }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        overlayVisible = true
        showExitConfirmation = false
        currentIndex = LiveTvZapping.nextIndex(currentIndex, channels.size, delta)
    }

    fun selectChannel(index: Int) {
        if (index !in channels.indices) return
        overlayVisible = true
        showExitConfirmation = false
        currentIndex = index
    }

    fun openEpg() {
        val channel = currentChannel ?: return
        val now = System.currentTimeMillis()
        selectedEpgServiceReference = channel.serviceReference
        selectedEpgProgramStartUtcMillis = epgState.guide(channel.serviceReference)
            .firstOrNull { program ->
                now >= program.startUtcMillis && now < program.endUtcMillis
            }
            ?.startUtcMillis
        showExitConfirmation = false
        showEpg = true
    }

    fun requestExit() {
        showEpg = false
        overlayVisible = true
        showExitConfirmation = true
    }

    BackHandler {
        when {
            showExitConfirmation -> showExitConfirmation = false
            showEpg -> showEpg = false
            overlayVisible -> overlayVisible = false
            else -> requestExit()
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
        if (!showEpg) {
            selectedEpgServiceReference = channel.serviceReference
            selectedEpgProgramStartUtcMillis = null
        }
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

    LaunchedEffect(currentIndex, channels.size) {
        if (channels.isNotEmpty()) {
            zapListState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    LaunchedEffect(
        overlayVisible,
        currentChannel?.serviceReference,
        loading,
        errorMessage,
        showEpg,
        showExitConfirmation,
    ) {
        if (overlayVisible && !loading && errorMessage == null && !showEpg && !showExitConfirmation) {
            delay(PLAYER_OVERLAY_TIMEOUT_MILLIS)
            overlayVisible = false
        }
    }

    LaunchedEffect(overlayVisible, showEpg, showExitConfirmation, currentIndex, selectedEpgProgramStartUtcMillis) {
        withFrameNanos { }
        when {
            showExitConfirmation -> runCatching { exitCancelFocusRequester.requestFocus() }
            showEpg && selectedEpgProgramStartUtcMillis == null -> runCatching {
                epgBackFocusRequester.requestFocus()
            }
            showEpg -> Unit // EpgScreen restores focus to the selected/current programme.
            overlayVisible -> runCatching { overlayFocusRequester.requestFocus() }
            else -> runCatching { rootFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (showEpg || showExitConfirmation) return@onPreviewKeyEvent false

                val nativeEvent = keyEvent.nativeKeyEvent
                val isConfirmKey = nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

                if (isConfirmKey) {
                    when (keyEvent.type) {
                        KeyEventType.KeyDown -> {
                            if (nativeEvent.isLongPress || nativeEvent.repeatCount > 0) {
                                openEpg()
                                return@onPreviewKeyEvent true
                            }
                            if (!overlayVisible) {
                                overlayVisible = true
                                return@onPreviewKeyEvent true
                            }
                        }
                        KeyEventType.KeyUp -> {
                            if (nativeEvent.eventTime - nativeEvent.downTime >= LONG_OK_THRESHOLD_MILLIS) {
                                openEpg()
                                return@onPreviewKeyEvent true
                            }
                        }
                        else -> Unit
                    }
                }

                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

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
                        if (overlayVisible) false else {
                            zap(+1)
                            true
                        }
                    }
                    Key.DirectionDown -> {
                        if (overlayVisible) false else {
                            zap(-1)
                            true
                        }
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
                    isClickable = true
                    setOnClickListener {
                        if (!showEpg && !showExitConfirmation) overlayVisible = true
                    }
                }
            },
            update = {
                it.player = player
                it.setOnClickListener {
                    if (!showEpg && !showExitConfirmation) overlayVisible = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible && !showEpg) {
            currentChannel?.let { channel ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                        .padding(horizontal = 36.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    channel.piconUri?.let { picon ->
                        AsyncImage(
                            model = picon,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 104.dp, height = 56.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "${currentIndex + 1} · ${channel.name}",
                            style = MaterialTheme.typography.headlineSmall,
                        )
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
                    .padding(horizontal = 30.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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

                Text("Jetzt im TV", style = MaterialTheme.typography.titleMedium)
                LazyRow(
                    state = zapListState,
                    modifier = Modifier.touchScrollFallback(
                        zapListState,
                        Orientation.Horizontal,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = channels,
                        key = { _, channel -> channel.serviceReference },
                    ) { index, channel ->
                        CompactLiveTvCard(
                            channel = channel,
                            channelNumber = index + 1,
                            selected = index == currentIndex,
                            onClick = { selectChannel(index) },
                            modifier = if (index == currentIndex) {
                                Modifier
                                    .focusRequester(overlayFocusRequester)
                                    .focusProperties { down = epgButtonFocusRequester }
                            } else {
                                Modifier
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TouchButton(
                        onClick = ::openEpg,
                        modifier = Modifier.focusRequester(epgButtonFocusRequester),
                    ) {
                        Text("EPG")
                    }
                    TouchButton(onClick = ::requestExit) { Text("TV verlassen") }
                    currentChannel?.let {
                        Text(
                            "${currentIndex + 1}/${channels.size} · ${it.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (showEpg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
                    .padding(horizontal = 36.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = currentChannel?.let { "TV-Guide · ${it.name}" } ?: "TV-Guide",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TouchButton(
                        onClick = { showEpg = false },
                        modifier = Modifier.focusRequester(epgBackFocusRequester),
                    ) {
                        Text("Zurück zum TV")
                    }
                }

                EpgScreen(
                    state = epgState,
                    channels = channels,
                    selectedServiceReference = selectedEpgServiceReference,
                    selectedProgram = selectedEpgProgram,
                    onSelectChannel = { serviceReference ->
                        selectedEpgServiceReference = serviceReference
                        selectedEpgProgramStartUtcMillis = null
                    },
                    onSelectProgram = { serviceReference, program ->
                        selectedEpgServiceReference = serviceReference
                        selectedEpgProgramStartUtcMillis = program.startUtcMillis
                        onEnrichEpgProgram(serviceReference, program.startUtcMillis)
                    },
                    onOpenProgramDetails = { serviceReference, program ->
                        val channel = channels.firstOrNull { it.serviceReference == serviceReference }
                        if (channel != null) onOpenEpgProgramDetails(channel, program)
                    },
                    onRefresh = onRefreshEpg,
                    channelListState = epgChannelListState,
                    programListState = epgProgramListState,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showExitConfirmation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(460.dp)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(16.dp),
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(16.dp),
                        )
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("Live TV verlassen?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Die laufende Wiedergabe wird beendet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TouchButton(
                            onClick = { showExitConfirmation = false },
                            modifier = Modifier.focusRequester(exitCancelFocusRequester),
                        ) {
                            Text("Abbrechen")
                        }
                        TouchButton(onClick = onBack) { Text("TV verlassen") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactLiveTvCard(
    channel: LiveTvChannel,
    channelNumber: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val artwork = channel.now?.preferredArtworkUri
    TouchCard(
        onClick = onClick,
        modifier = modifier
            .width(190.dp)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            ),
        scale = CardDefaults.scale(focusedScale = 1.04f),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !artwork.isNullOrBlank() -> AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    !channel.piconUri.isNullOrBlank() -> AsyncImage(
                        model = channel.piconUri,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                    )
                    else -> Text(channelNumber.toString(), style = MaterialTheme.typography.headlineSmall)
                }
                if (!artwork.isNullOrBlank() && !channel.piconUri.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.piconUri,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(width = 54.dp, height = 28.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "$channelNumber · ${channel.name}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel.now?.title ?: "Keine EPG-Daten",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
