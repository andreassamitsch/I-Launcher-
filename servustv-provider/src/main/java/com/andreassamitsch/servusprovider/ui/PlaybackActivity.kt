package com.andreassamitsch.servusprovider.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
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
import java.util.Locale

class PlaybackActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var settingsDialog: Dialog? = null
    private lateinit var rootView: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var controlsFade: View
    private lateinit var controlsContainer: LinearLayout
    private lateinit var timeline: SeekBar
    private lateinit var timeText: TextView
    private lateinit var settingsButton: Button
    private var selectedVideoSummary = "wird ermittelt"
    private var selectedAudioSummary = "wird ermittelt"
    private var userSeeking = false

    private val hideControlsRunnable = Runnable {
        if (settingsDialog?.isShowing != true) {
            if (settingsButton.hasFocus()) rootView.requestFocus()
            controlsContainer.visibility = View.GONE
            controlsFade.visibility = View.GONE
        }
    }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateTimeline()
            if (player != null) handler.postDelayed(this, TIMELINE_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        rootView.requestFocus()

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
        handler.removeCallbacksAndMessages(null)
        settingsDialog?.dismiss()
        settingsDialog = null
        releasePlayer()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (settingsDialog?.isShowing == true) return super.dispatchKeyEvent(event)

        val settingsOwnsOk = settingsButton.hasFocus() &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        if (settingsOwnsOk) return super.dispatchKeyEvent(event)

        val handledKey = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MENU,
            -> true

            else -> false
        }
        if (!handledKey) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> seekBy(-SEEK_INTERVAL_MS)

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> seekBy(SEEK_INTERVAL_MS)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> togglePlayback()

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                showControls()
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                showControls()
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> showControls(focusSettings = true)

            KeyEvent.KEYCODE_MENU -> showSettingsDialog()
        }
        return true
    }

    private fun buildUi(): FrameLayout {
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        playerView = PlayerView(this).apply {
            useController = false
            isFocusable = false
            isClickable = true
            // The TV path is fully D-Pad driven. On a phone a tap gives a minimal Play/Pause
            // interaction without bringing back Media3's large touch controller.
            setOnClickListener { togglePlayback() }
        }
        rootView.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        controlsFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(
                    Color.argb(235, 0, 0, 0),
                    Color.argb(155, 0, 0, 0),
                    Color.TRANSPARENT,
                ),
            )
        }
        rootView.addView(
            controlsFade,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CONTROLS_FADE_HEIGHT_DP),
                Gravity.BOTTOM,
            ),
        )

        controlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(18), dp(48), dp(30))
        }
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeText = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        infoRow.addView(
            timeText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        settingsButton = Button(this).apply {
            text = "Einstellungen"
            isAllCaps = false
            setOnClickListener { showSettingsDialog() }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    controlsContainer.visibility = View.VISIBLE
                    controlsFade.visibility = View.VISIBLE
                }
            }
        }
        infoRow.addView(
            settingsButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
        )
        controlsContainer.addView(
            infoRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        timeline = SeekBar(this).apply {
            max = TIMELINE_MAX
            progress = 0
            secondaryProgress = 0
            isFocusable = false
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        userSeeking = true
                        handler.removeCallbacks(hideControlsRunnable)
                    }

                    override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val duration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
                        val target = (duration * value) / TIMELINE_MAX
                        timeText.text = "${formatTime(target)} / ${formatTime(duration)}"
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        val exoPlayer = player
                        val duration = exoPlayer?.duration?.takeIf { it > 0 && it != C.TIME_UNSET }
                        if (exoPlayer != null && duration != null) {
                            exoPlayer.seekTo((duration * seekBar.progress) / TIMELINE_MAX)
                        }
                        userSeeking = false
                        updateTimeline()
                        showControls()
                    }
                },
            )
        }
        controlsContainer.addView(
            timeline,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)).apply {
                topMargin = dp(2)
            },
        )
        rootView.addView(
            controlsContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        progress = ProgressBar(this)
        rootView.addView(progress, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))

        message = TextView(this).apply {
            text = "ServusTV-Stream wird geladen …"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        rootView.addView(
            message,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = dp(42)
            },
        )
        return rootView
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

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            updateTimeline()
                            if (playbackState == Player.STATE_ENDED) {
                                // VOD has no useful post-roll screen in this app. Returning to the
                                // list/launcher immediately also avoids leaving a black PlayerView open.
                                finish()
                            }
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
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
        showControls()
    }

    private fun seekBy(deltaMillis: Long) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val target = (exoPlayer.currentPosition + deltaMillis).coerceAtLeast(0L).let { position ->
            if (duration != null) position.coerceAtMost(duration) else position
        }
        exoPlayer.seekTo(target)
        updateTimeline(positionOverride = target)
        showControls()
    }

    private fun togglePlayback() {
        val exoPlayer = player ?: return
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        showControls()
    }

    private fun showControls(focusSettings: Boolean = false) {
        controlsContainer.visibility = View.VISIBLE
        controlsFade.visibility = View.VISIBLE
        updateTimeline()
        handler.removeCallbacks(hideControlsRunnable)
        if (focusSettings) settingsButton.requestFocus()
        scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (settingsDialog?.isShowing != true) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun updateTimeline(positionOverride: Long? = null) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = positionOverride ?: exoPlayer.currentPosition.coerceAtLeast(0L)
        val buffered = exoPlayer.bufferedPosition.coerceAtLeast(position)
        if (duration > 0) {
            if (!userSeeking) {
                timeline.progress = ((position.coerceAtMost(duration) * TIMELINE_MAX) / duration).toInt()
            }
            timeline.secondaryProgress = ((buffered.coerceAtMost(duration) * TIMELINE_MAX) / duration).toInt()
        } else if (!userSeeking) {
            timeline.progress = 0
            timeline.secondaryProgress = 0
        }
        if (!userSeeking) timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun showSettingsDialog() {
        handler.removeCallbacks(hideControlsRunnable)
        settingsDialog?.dismiss()

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 20, 20, 20))
                cornerRadius = dp(18).toFloat()
            }
        }
        panel.addView(TextView(this).apply {
            text = "Einstellungen"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = "Qualität\n$selectedVideoSummary\n\nAudio\n$selectedAudioSummary\n\nSpringen\n10 Sekunden mit D-Pad links/rechts\n\nTouch\nTippen = Play/Pause, Zeitleiste = Position"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(16), 0, dp(18))
        })
        val closeButton = Button(this).apply {
            text = "Schließen"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(closeButton)
        dialog.setContentView(panel)
        dialog.setOnDismissListener {
            settingsDialog = null
            rootView.requestFocus()
            showControls()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM or Gravity.END)
            val maxWidth = resources.displayMetrics.widthPixels - dp(32)
            setLayout(minOf(dp(460), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply {
                x = dp(16)
                y = dp(48)
            }
        }
        settingsDialog = dialog
        closeButton.requestFocus()
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
                        if (format.width > 0 && format.height > 0) {
                            append(format.width)
                            append('×')
                            append(format.height)
                        } else {
                            append("Video")
                        }
                        if (format.bitrate > 0) append(" · ${format.bitrate / 1_000} kbit/s")
                    }

                    C.TRACK_TYPE_AUDIO -> selectedAudio += buildString {
                        format.language?.let { append(it.uppercase(Locale.ROOT)) }
                        if (format.channelCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(format.channelCount)
                            append(" Kanäle")
                        }
                        if (format.sampleRate > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(format.sampleRate / 1_000f)
                            append(" kHz")
                        }
                        if (isEmpty()) append("Audio")
                    }
                }
            }
        }
        selectedVideoSummary = selectedVideo.joinToString().ifBlank { "wird ermittelt" }
        selectedAudioSummary = selectedAudio.joinToString().ifBlank { "wird ermittelt" }
        Log.i(
            TAG,
            "Selected tracks: video=$selectedVideoSummary, audio=$selectedAudioSummary, tunneling=requested",
        )
    }

    private fun showError(text: String) {
        progress.visibility = View.GONE
        controlsContainer.visibility = View.GONE
        controlsFade.visibility = View.GONE
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ServusPlayback"
        const val TIMELINE_MAX = 1_000
        const val CONTROLS_FADE_HEIGHT_DP = 170
        const val SEEK_INTERVAL_MS = 10_000L
        const val CONTROLS_TIMEOUT_MS = 3_500L
        const val TIMELINE_UPDATE_INTERVAL_MS = 500L
    }
}
