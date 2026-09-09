package com.andreassamitsch.joyntv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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

    LaunchedEffect(channelId) {
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
            playback != null -> Media3Player(playback = requireNotNull(playback))
            errorText != null -> Text(
                text = "$title konnte nicht gestartet werden\n${errorText.orEmpty()}",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(40.dp),
                fontSize = 20.sp,
            )
            else -> Text(
                text = "$title wird gestartet …",
                modifier = Modifier.align(Alignment.Center),
                fontSize = 20.sp,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Media3Player(playback: JoynPlayback) {
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
        onDispose { player.release() }
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
        modifier = Modifier.fillMaxSize(),
    )
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
