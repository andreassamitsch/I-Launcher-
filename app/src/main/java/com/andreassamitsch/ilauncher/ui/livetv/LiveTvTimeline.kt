package com.andreassamitsch.ilauncher.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.andreassamitsch.ilauncher.model.LiveTvProgram

/** Calculated from actual EPG timestamps and wall clock, not video playback position. */
internal data class LiveTvTimeline(
    val progress: Float,
    val timeLabel: String,
    val endingSoon: Boolean,
    val compactLabel: String,
)

internal object LiveTvTimelinePolicy {
    fun snapshot(program: LiveTvProgram?, nowUtcMillis: Long): LiveTvTimeline? {
        if (program == null || program.durationMillis <= 0) return null
        val start = program.startUtcMillis
        val end = program.endUtcMillis
        if (end <= start) return null
        val progress = ((nowUtcMillis - start).toDouble() / program.durationMillis.toDouble())
            .coerceIn(0.0, 1.0).toFloat()
        val (label, compactLabel) = when {
            nowUtcMillis < start -> {
                val minutes = (start - nowUtcMillis + 59_999L) / 60_000L
                "Beginnt in $minutes Min." to "in ${minutes}m"
            }
            nowUtcMillis >= end -> "Sendung beendet" to "Ende"
            else -> {
                val minutes = (end - nowUtcMillis + 59_999L) / 60_000L
                "Noch $minutes Min." to "${minutes}m"
            }
        }
        return LiveTvTimeline(
            progress = progress,
            timeLabel = label,
            endingSoon = nowUtcMillis >= start && nowUtcMillis < end &&
                end - nowUtcMillis <= 5 * 60_000L,
            compactLabel = compactLabel,
        )
    }
}

@Composable
internal fun LiveTvTimelineBar(
    progress: Float,
    modifier: Modifier = Modifier,
    thickness: Dp = 4.dp,
) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(thickness)
                .background(MaterialTheme.colorScheme.primary, shape),
        )
    }
}
