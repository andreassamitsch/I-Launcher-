package com.andreassamitsch.joyntv

import android.app.AlertDialog
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
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
 * Reuses Media3's settings button for a TV-friendly video quality chooser.
 *
 * The list is built from the video representations that are actually exposed by the current
 * DASH manifest and supported by the device. "Auto" keeps Media3's adaptive selection; choosing
 * a resolution pins exactly that representation until the player instance is recreated.
 */
@OptIn(UnstableApi::class)
internal fun PlayerView.installJoynQualitySelector(player: ExoPlayer) {
    val settingsButton = findViewById<View>(androidx.media3.ui.R.id.exo_settings) ?: return
    settingsButton.visibility = View.VISIBLE
    settingsButton.contentDescription = "Streaming-Qualität"
    settingsButton.setOnClickListener {
        showQualityDialog(player)
    }
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
