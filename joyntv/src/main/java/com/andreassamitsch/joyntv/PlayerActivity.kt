package com.andreassamitsch.joyntv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
            ?: intent.data?.lastPathSegment
            ?: run {
                finish()
                return
            }
        val title = intent.getStringExtra(EXTRA_CHANNEL_TITLE) ?: "Joyn Live"
        val repository = JoynRepository(applicationContext)

        setContent {
            JoynTvTheme {
                JoynPlayer(repository, channelId, title)
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "joyn_channel_id"
        const val EXTRA_CHANNEL_TITLE = "joyn_channel_title"

        fun intent(context: Context, channelId: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_CHANNEL_TITLE, title)
            }
    }
}

@Composable
private fun JoynPlayer(
    repository: JoynRepository,
    channelId: String,
    title: String,
) {
    var playback by remember(channelId) { mutableStateOf<JoynPlayback?>(null) }
    var errorText by remember(channelId) { mutableStateOf<String?>(null) }
    var retryKey by remember(channelId) { mutableIntStateOf(0) }

    LaunchedEffect(channelId, retryKey) {
        playback = null
        errorText = null
        runCatching {
            withContext(Dispatchers.IO) { repository.resolveLivePlayback(channelId) }
        }.onSuccess { playback = it }
            .onFailure { errorText = it.message ?: it.javaClass.simpleName }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            playback != null -> Media3Player(
                playback = requireNotNull(playback),
                onPlaybackError = { error ->
                    playback = null
                    errorText = "${error.errorCodeName}: ${error.message.orEmpty()}".trim()
                },
            )
            errorText != null -> PlaybackError(
                title = title,
                message = errorText.orEmpty(),
                onRetry = { retryKey++ },
            )
            else -> Text(
                text = "$title wird gestartet …",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun PlaybackError(
    title: String,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$title konnte nicht gestartet werden",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message.take(700),
            color = Color(0xFFD7DBE3),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        RetryButton(onRetry)
    }
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .background(if (focused) Color.White else Color(0xFF20252D), shape)
            .border(1.dp, if (focused) Color.White else Color(0xFF5A6470), shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onRetry()
                    true
                } else false
            }
            .clickable(onClick = onRetry)
            .focusable()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Erneut versuchen",
            color = if (focused) Color(0xFF11151B) else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3Player(
    playback: JoynPlayback,
    onPlaybackError: (PlaybackException) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(playback) {
        ExoPlayer.Builder(context).build().apply {
            val drm = playback.licenseUrl?.let { licenseUrl ->
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(licenseUrl)
                    .setLicenseRequestHeaders(
                        mapOf(
                            "User-Agent" to USER_AGENT,
                            "Content-Type" to "application/octet-stream",
                        ),
                    )
                    .build()
            }
            val media = MediaItem.Builder()
                .setUri(playback.manifestUrl)
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .apply { if (drm != null) setDrmConfiguration(drm) }
                .build()
            setMediaItem(media)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                controllerAutoShow = true
                controllerHideOnTouch = false
                requestFocus()
            }
        },
        update = { view -> view.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
