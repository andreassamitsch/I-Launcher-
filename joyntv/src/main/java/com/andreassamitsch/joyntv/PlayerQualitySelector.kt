package com.andreassamitsch.joyntv

import android.app.AlertDialog
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.roundToInt

private data class VideoQualityOption(
    val group: Tracks.Group,
    val trackIndex: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Float,
)

/**
 * Configures the Media3 PlayerView for Joyn's combined Android-TV / touch use case and reuses
 * Media3's settings button for the video quality chooser.
 *
 * TV:
 * - hidden controller: DPAD left/right seeks 10 seconds, OK toggles play/pause;
 * - DPAD up/down opens the normal Media3 controller;
 * - while the controller is visible its normal focus/navigation remains untouched, including the
 *   quality settings button.
 *
 * Touch:
 * - single tap toggles the normal Media3 controller;
 * - double tap left/right seeks 10 seconds;
 * - double tap in the centre toggles play/pause.
 *
 * The PlayerView keeps the display awake for the complete player session. The quality list is
 * still built from the representations actually exposed by the current DASH manifest and
 * supported by the device. "Auto" keeps Media3's adaptive selection; choosing a resolution pins
 * exactly that representation until the player instance is recreated.
 */
@OptIn(UnstableApi::class)
internal fun PlayerView.installJoynQualitySelector(player: ExoPlayer) {
    keepScreenOn = true
    useController = true
    controllerAutoShow = false
    controllerHideOnTouch = true
    controllerShowTimeoutMs = CONTROLLER_SHOW_TIMEOUT_MS

    installJoynTvKeyControls(player)
    installJoynTouchControls(player)

    val settingsButton = findViewById<View>(androidx.media3.ui.R.id.exo_settings)
    settingsButton?.apply {
        visibility = View.VISIBLE
        contentDescription = "Streaming-Qualität"
        setOnClickListener {
            showQualityDialog(player)
        }
    }
}

@OptIn(UnstableApi::class)
private fun PlayerView.installJoynTvKeyControls(player: ExoPlayer) {
    setOnKeyListener { _, keyCode, event ->
        val controllerVisible = isControllerFullyVisible()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (controllerVisible) {
                    false
                } else {
                    if (event.action == KeyEvent.ACTION_UP) player.seekJoynBy(-SEEK_STEP_MS)
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controllerVisible) {
                    false
                } else {
                    if (event.action == KeyEvent.ACTION_UP) player.seekJoynBy(SEEK_STEP_MS)
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (controllerVisible) {
                    false
                } else {
                    if (event.action == KeyEvent.ACTION_UP) {
                        player.toggleJoynPlayback()
                        showController()
                    }
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (controllerVisible) {
                    false
                } else {
                    if (event.action == KeyEvent.ACTION_UP) showController()
                    true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    player.toggleJoynPlayback()
                    showController()
                }
                true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    player.play()
                    showController()
                }
                true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    player.pause()
                    showController()
                }
                true
            }

            else -> false
        }
    }
}

@OptIn(UnstableApi::class)
private fun PlayerView.installJoynTouchControls(player: ExoPlayer) {
    val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControllerFullyVisible()) hideController() else showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val viewWidth = width.takeIf { it > 0 } ?: return true
                when {
                    e.x < viewWidth * DOUBLE_TAP_EDGE_FRACTION -> player.seekJoynBy(-SEEK_STEP_MS)
                    e.x > viewWidth * (1f - DOUBLE_TAP_EDGE_FRACTION) -> player.seekJoynBy(SEEK_STEP_MS)
                    else -> player.toggleJoynPlayback()
                }
                showController()
                return true
            }
        },
    )

    setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
}

private fun ExoPlayer.seekJoynBy(deltaMs: Long) {
    if (!isCurrentMediaItemSeekable) return
    val upperBound = duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
    val target = (currentPosition + deltaMs).coerceIn(0L, upperBound)
    seekTo(target)
}

private fun ExoPlayer.toggleJoynPlayback() {
    if (playbackState == Player.STATE_ENDED) seekTo(0L)
    if (playWhenReady) pause() else play()
}

@OptIn(UnstableApi::class)
private fun PlayerView.showQualityDialog(player: ExoPlayer) {
    val qualities = availableVideoQualities(player)
    val overrides = player.trackSelectionParameters.overrides
    val manualIndex = qualities.indexOfFirst { option ->
        val override = overrides[option.group.mediaTrackGroup]
        override != null && option.trackIndex in override.trackIndices
    }
    val currentInfo = currentVideoInfo(player.videoFormat)

    val labels = buildList {
        add(
            if (manualIndex < 0) {
                "Auto · aktuell $currentInfo"
            } else {
                "Auto"
            },
        )
        qualities.forEachIndexed { index, quality ->
            val base = qualityLabel(quality)
            add(
                if (index == manualIndex) {
                    "$base · aktuell $currentInfo"
                } else {
                    base
                },
            )
        }
    }.toTypedArray()

    val checkedItem = if (manualIndex >= 0) manualIndex + 1 else 0
    val dialog = AlertDialog.Builder(context)
        .setTitle("Streaming-Qualität")
        .setSingleChoiceItems(labels, checkedItem, null)
        .setNegativeButton("Schließen", null)
        .create()

    dialog.setOnShowListener {
        val list = dialog.listView
        list.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()
            } else {
                val option = qualities.getOrNull(position - 1) ?: return@setOnItemClickListener
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex),
                    )
                    .build()
            }
            dialog.dismiss()
        }
    }
    dialog.show()
}

@OptIn(UnstableApi::class)
private fun availableVideoQualities(player: ExoPlayer): List<VideoQualityOption> {
    val videoGroups = player.currentTracks.groups.filter { group ->
        group.type == C.TRACK_TYPE_VIDEO && group.isSupported
    }
    val selectedGroup = videoGroups.firstOrNull { it.isSelected }
    val sourceGroup = selectedGroup ?: videoGroups.maxByOrNull { group ->
        (0 until group.length).count { index ->
            group.isTrackSupported(index) && group.getTrackFormat(index).height > 0
        }
    } ?: return emptyList()

    return (0 until sourceGroup.length)
        .mapNotNull { index ->
            if (!sourceGroup.isTrackSupported(index)) return@mapNotNull null
            val format = sourceGroup.getTrackFormat(index)
            if (format.height <= 0) return@mapNotNull null
            VideoQualityOption(
                group = sourceGroup,
                trackIndex = index,
                width = format.width,
                height = format.height,
                bitrate = format.bitrate,
                frameRate = format.frameRate,
            )
        }
        .groupBy { it.height }
        .values
        .mapNotNull { sameHeight ->
            sameHeight.maxWithOrNull(
                compareBy<VideoQualityOption> { it.bitrate }
                    .thenBy { it.width }
                    .thenBy { it.frameRate },
            )
        }
        .sortedBy { it.height }
}

private fun qualityLabel(option: VideoQualityOption): String = buildString {
    append(option.height)
    append('p')
    if (option.bitrate > 0) {
        append(" · ")
        append(formatBitrate(option.bitrate))
    }
}

@OptIn(UnstableApi::class)
private fun currentVideoInfo(format: Format?): String {
    if (format == null) return "wird ermittelt"
    val parts = buildList {
        if (format.height > 0) add("${format.height}p")
        if (format.width > 0 && format.height > 0) add("${format.width}×${format.height}")
        if (format.bitrate > 0) add(formatBitrate(format.bitrate))
        if (format.frameRate > 0f) add("${format.frameRate.roundToInt()} fps")
    }
    return parts.joinToString(" · ").ifBlank { "wird ermittelt" }
}

private fun formatBitrate(bitsPerSecond: Int): String {
    val megabits = bitsPerSecond / 1_000_000.0
    return if (megabits >= 0.1) {
        String.format(Locale.GERMANY, "%.1f Mbit/s", megabits)
    } else {
        "${bitsPerSecond / 1_000} kbit/s"
    }
}

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLLER_SHOW_TIMEOUT_MS = 4_500
private const val DOUBLE_TAP_EDGE_FRACTION = 0.4f
