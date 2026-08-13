package com.andreassamitsch.ilauncher.ui.home

import com.andreassamitsch.ilauncher.data.home.HeroTextScrollSpeed
import kotlin.math.roundToInt

/**
 * Converts a vertical overflow distance into animation time using lines per second.
 *
 * Scaling the distance and the rendered line height by the same factor therefore keeps the time
 * identical. The speed is independent of the text field's absolute pixel size or character count.
 */
internal fun heroTextScrollDurationMillis(
    distancePx: Int,
    lineHeightPx: Float,
    speed: HeroTextScrollSpeed,
): Int? {
    if (distancePx <= 0 || lineHeightPx <= 0f || speed.linesPerSecond <= 0f) return null
    val pixelsPerSecond = lineHeightPx * speed.linesPerSecond
    return ((distancePx / pixelsPerSecond) * 1_000f)
        .roundToInt()
        .coerceAtLeast(1)
}
