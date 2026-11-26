package com.andreassamitsch.ilauncher.ui.livetv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class LiveTvSessionProgramme(
    val serviceReference: String,
    val channelName: String,
    val title: String,
    val subtitle: String?,
    val artworkUri: String?,
)

internal fun liveTvSessionProgramme(
    channel: LiveTvChannel,
    guide: List<LiveTvProgram>,
    nowUtcMillis: Long,
): LiveTvSessionProgramme {
    fun LiveTvProgram.isCurrent() =
        nowUtcMillis >= startUtcMillis && nowUtcMillis < endUtcMillis

    val programme = guide.firstOrNull { it.isCurrent() }
        ?: channel.now?.takeIf { it.isCurrent() }
        ?: channel.next?.takeIf { it.isCurrent() }
    return LiveTvSessionProgramme(
        serviceReference = channel.serviceReference,
        channelName = channel.name,
        title = programme?.title?.takeIf { it.isNotBlank() } ?: channel.name,
        subtitle = programme?.subtitle?.takeIf { it.isNotBlank() },
        artworkUri = programme?.preferredArtworkUri?.takeIf { it.isNotBlank() }
            ?: channel.piconUri?.takeIf { it.isNotBlank() },
    )
}

/**
 * Publish the actual live player to Android's system media controllers. Use the platform
 * session as a metadata adapter so EPG changes never replace a MediaSource (including
 * the custom SAT and Joyn DRM sources). Do not expose stream/artwork URLs or pretend
 * the EPG time range is a seekable media timeline.
 *
 * All methods are called on the player's main application looper.
 */
internal class LiveTvMediaSession(
    context: Context,
    private val player: Player,
) : Player.Listener {
    private val appContext = context.applicationContext
    private val session = MediaSession(appContext, "ILauncherLiveTv")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null
    private var programme: LiveTvSessionProgramme? = null
    private var artwork: Bitmap? = null
    private var publishedState: Int? = null

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (session.isActive && player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                    player.play()
                }
            }

            override fun onPause() {
                if (session.isActive && player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                    player.pause()
                }
            }
        }, Handler(Looper.getMainLooper()))
        player.addListener(this)
        syncPlaybackState()
    }

    fun updateProgramme(value: LiveTvSessionProgramme) {
        if (programme == value) return
        val artworkChanged = programme?.artworkUri != value.artworkUri
        programme = value
        if (artworkChanged) {
            artworkJob?.cancel()
            artwork = null
        }
        publishMetadata()
        syncPlaybackState()
        if (artworkChanged && value.artworkUri != null) {
            artworkJob = scope.launch {
                val bitmap = try {
                    val result = SingletonImageLoader.get(appContext).execute(
                        ImageRequest.Builder(appContext)
                            .data(value.artworkUri)
                            .size(320, 180)
                            .allowHardware(false)
                            .build(),
                    )
                    (result as? SuccessResult)?.image?.toBitmap()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                if (programme?.artworkUri == value.artworkUri && bitmap != null) {
                    artwork = bitmap
                    publishMetadata()
                }
            }
        }
    }

    private fun publishMetadata() {
        val value = programme ?: return
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, value.serviceReference)
            .putString(MediaMetadata.METADATA_KEY_TITLE, value.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, value.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, value.channelName)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, value.channelName)
        value.subtitle?.let {
            metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it)
        }
        artwork?.let {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        session.setMetadata(metadata.build())
    }

    override fun onEvents(player: Player, events: Player.Events) {
        syncPlaybackState()
    }

    private fun syncPlaybackState() {
        val active = programme != null &&
            player.currentMediaItem?.mediaId == programme?.serviceReference &&
            player.playerError == null &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        val state = when {
            !active -> PlaybackState.STATE_STOPPED
            player.isPlaying -> PlaybackState.STATE_PLAYING
            !player.playWhenReady -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_BUFFERING
        }
        if (state != publishedState) {
            val actions = if (active && player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
            } else {
                0L
            }
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        state,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
                    )
                    .build(),
            )
            publishedState = state
        }
        if (session.isActive != active) session.isActive = active
    }

    fun release() {
        player.removeListener(this)
        scope.cancel()
        session.isActive = false
        session.release()
    }
}
