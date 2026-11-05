package com.andreassamitsch.ilauncher.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.andreassamitsch.ilauncher.model.LiveTvProgram

/** Calculated from actual EPG timestamps and wall clock, not video playback position. */
internal data class LiveTvTimeline(
    val progress: Float,
    val timeLabel: String,
    val endingSoon: Boolean,
)

internal object LiveTvTimelinePolicy {
    fun snapshot(program: LiveTvProgram?, nowUtcMillis: Long): LiveTvTimeline? {
        if (program == null || program.durationMillis <= 0) return null
        val start = program.startUtcMillis
        val end = program.endUtcMillis
        if (end <= start) return null
        val progress = ((nowUtcMillis - start).toDouble() / program.durationMillis.toDouble())
            .coerceIn(0.0, 1.0).toFloat()
        val label = when {
            nowUtcMillis < start -> {
                val minutes = (start - nowUtcMillis + 59_999L) / 60_000L
                "Beginnt in $minutes Min."
            }
            nowUtcMillis >= end -> "Sendung beendet"
            else -> {
                val minutes = (end - nowUtcMillis + 59_999L) / 60_000L
                "Noch $minutes Min."
            }
        }
        return LiveTvTimeline(
            progress = progress,
            timeLabel = label,
            endingSoon = nowUtcMillis >= start && nowUtcMillis < end &&
                end - nowUtcMillis <= 5 * 60_000L,
        )
    }
}

@Composable
internal fun LiveTvTimelineBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, shape),
        )
    }
}
