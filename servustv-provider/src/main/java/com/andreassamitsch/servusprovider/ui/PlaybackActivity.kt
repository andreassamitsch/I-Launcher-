package com.andreassamitsch.servusprovider.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.data.ServusPlaybackResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        val contentId = intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
        if (contentId == null) {
            showError("Keine ServusTV-Content-ID übergeben")
            return
        }
        resolveAndPlay(contentId)
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        releasePlayer()
        super.onDestroy()
    }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4_000
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))

        message = TextView(this).apply {
            text = "ServusTV-Stream wird geladen …"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        root.addView(
            message,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = dp(42)
            },
        )
        return root
    }

    private fun resolveAndPlay(contentId: String) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ServusPlaybackResolver(applicationContext).resolve(contentId)
                }
            }
            result.onSuccess(::startPlayer).onFailure { throwable ->
                showError(throwable.message ?: "Stream konnte nicht aufgelöst werden")
            }
        }
    }

    private fun startPlayer(url: String) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ServusNetwork.WEB_USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.servustv.com/",
                    "Origin" to "https://www.servustv.com",
                ),
            )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .setTunnelingEnabled(true),
            )
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .build()
            .also { exoPlayer ->
                exoPlayer.addListener(
                    object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            logSelectedTracks(tracks)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Media3 playback error: ${error.errorCodeName}", error)
                            showError("Wiedergabefehler: ${error.errorCodeName}")
                        }
                    },
                )
                playerView.player = exoPlayer
                exoPlayer.setMediaItem(MediaItem.fromUri(url))
                exoPlayer.playWhenReady = true
                exoPlayer.prepare()
            }
        progress.visibility = View.GONE
        message.visibility = View.GONE
    }

    private fun logSelectedTracks(tracks: Tracks) {
        val selectedVideo = mutableListOf<String>()
        val selectedAudio = mutableListOf<String>()
        tracks.groups.forEach { group ->
            repeat(group.length) { trackIndex ->
                if (!group.isTrackSelected(trackIndex)) return@repeat
                val format = group.getTrackFormat(trackIndex)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> selectedVideo += buildString {
                        append(format.width)
                        append('x')
                        append(format.height)
                        if (format.bitrate > 0) append(" @ ${format.bitrate}bps")
                        format.sampleMimeType?.let { append(" $it") }
                    }

                    C.TRACK_TYPE_AUDIO -> selectedAudio += buildString {
                        format.sampleMimeType?.let { append(it) }
                        if (format.channelCount > 0) append(" ${format.channelCount}ch")
                        if (format.sampleRate > 0) append(" ${format.sampleRate}Hz")
                        if (format.bitrate > 0) append(" ${format.bitrate}bps")
                        format.language?.let { append(" lang=$it") }
                    }.trim()
                }
            }
        }
        Log.i(
            TAG,
            "Selected tracks: video=${selectedVideo.joinToString().ifBlank { "none" }}, " +
                "audio=${selectedAudio.joinToString().ifBlank { "none" }}, tunneling=requested",
        )
    }

    private fun showError(text: String) {
        progress.visibility = View.GONE
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ServusPlayback"
    }
}
