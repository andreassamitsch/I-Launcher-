package com.andreassamitsch.ilauncher.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

private val MediaCardShape = RoundedCornerShape(10.dp)
private const val FocusedCardScale = 1.045f
private const val GlowSampleWidth = 32
private const val GlowSampleHeight = 18
private const val GlowColorCacheSize = 96
private val GlowFallbackColor = Color(0xFF8FA5C7)

private object GlowColorCache {
    private val colors = object : LinkedHashMap<String, Color>(GlowColorCacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
            size > GlowColorCacheSize
    }

    @Synchronized
    fun get(key: String): Color? = colors[key]

    @Synchronized
    fun put(key: String, color: Color) {
        colors[key] = color
    }
}

@Composable
internal fun rememberFocusedCardBreath(focused: Boolean): Float {
    // Only the single focused card owns an infinite animation. Keeping a transition alive for every
    // off-screen/unfocused rail item is unnecessary work on TV hardware.
    if (!focused) return 0f

    val transition = rememberInfiniteTransition(label = "media-card-breath")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "media-card-breath-alpha",
    )
    return breath
}

@Composable
private fun rememberArtworkGlowColor(
    artworkUri: String?,
    focused: Boolean,
): Color {
    val context = LocalContext.current
    val cached = artworkUri?.let(GlowColorCache::get)
    val color by produceState(
        initialValue = cached ?: GlowFallbackColor,
        key1 = artworkUri,
        key2 = focused,
    ) {
        val uri = artworkUri?.takeIf { focused && it.isNotBlank() } ?: return@produceState
        GlowColorCache.get(uri)?.let {
            value = it
            return@produceState
        }

        // The card image itself may be a hardware bitmap. Pixel sampling must not read that bitmap
        // directly. A tiny dedicated Coil request reuses Coil's memory/disk cache but explicitly
        // asks for a software bitmap that is safe to inspect on the CPU.
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(GlowSampleWidth, GlowSampleHeight)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) {
            val bitmap = result.image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val sampled = extractArtworkGlowColor(pixels) ?: GlowFallbackColor
            GlowColorCache.put(uri, sampled)
            value = sampled
        }
    }
    return color
}

@Composable
internal fun BoxScope.FocusedArtworkGlow(
    artworkUri: String?,
    focused: Boolean,
    breath: Float,
) {
    if (!focused || artworkUri.isNullOrBlank()) return

    val sampled = rememberArtworkGlowColor(artworkUri, focused)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.025f
                scaleY = 1.04f
                shape = MediaCardShape
                clip = false
                shadowElevation = 24.dp.toPx()
                ambientShadowColor = sampled.copy(alpha = 0.43f + breath * 0.10f)
                spotShadowColor = sampled.copy(alpha = 0.58f + breath * 0.12f)
            }
            // The real card is drawn above this source surface, so only its coloured halo remains.
            .background(sampled.copy(alpha = 0.025f), MediaCardShape),
    )
}

/**
 * Choose a stable, vivid-but-not-neon halo colour from a tiny artwork sample.
 *
 * This intentionally avoids a Palette dependency. Transparent pixels, near-black letterboxing and
 * low-saturation near-white UI/text regions contribute very little. Saturated mid-bright pixels are
 * weighted most strongly so a blue ocean card, warm orange poster and red programme card visibly
 * produce different focus auras.
 */
internal fun extractArtworkGlowColor(pixels: IntArray): Color? {
    var weightedRed = 0.0
    var weightedGreen = 0.0
    var weightedBlue = 0.0
    var totalWeight = 0.0

    for (pixel in pixels) {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 144) continue

        val red = (pixel ushr 16) and 0xFF
        val green = (pixel ushr 8) and 0xFF
        val blue = pixel and 0xFF
        val high = max(red, max(green, blue)).toFloat()
        val low = min(red, min(green, blue)).toFloat()
        if (high < 28f) continue

        val value = high / 255f
        val saturation = if (high <= 0f) 0f else (high - low) / high
        if (value > 0.94f && saturation < 0.12f) continue

        val midToneBonus = if (value in 0.24f..0.92f) 0.55f else 0.10f
        val weight = 0.18f + saturation * 1.85f + midToneBonus
        weightedRed += red * weight
        weightedGreen += green * weight
        weightedBlue += blue * weight
        totalWeight += weight
    }

    if (totalWeight <= 0.0) return null

    var red = (weightedRed / totalWeight).toFloat()
    var green = (weightedGreen / totalWeight).toFloat()
    var blue = (weightedBlue / totalWeight).toFloat()

    // Increase chroma a little so the aura stays recognisable on dark TV backgrounds, but keep
    // brightness bounded to avoid a fluorescent halo around already bright artwork.
    val high = max(red, max(green, blue))
    val low = min(red, min(green, blue))
    val midpoint = (high + low) * 0.5f
    val chromaBoost = 1.28f
    red = midpoint + (red - midpoint) * chromaBoost
    green = midpoint + (green - midpoint) * chromaBoost
    blue = midpoint + (blue - midpoint) * chromaBoost

    val boostedHigh = max(red, max(green, blue)).coerceAtLeast(1f)
    val targetHigh = boostedHigh.coerceIn(130f, 225f)
    val brightnessScale = targetHigh / boostedHigh
    red = (red * brightnessScale).coerceIn(0f, 255f)
    green = (green * brightnessScale).coerceIn(0f, 255f)
    blue = (blue * brightnessScale).coerceIn(0f, 255f)

    return Color(red / 255f, green / 255f, blue / 255f, 1f)
}

@Composable
internal fun BoxScope.FocusedBreathingBorder(
    focused: Boolean,
    breath: Float,
) {
    if (!focused) return

    val width = (1.35f + breath * 0.95f).dp
    val alpha = 0.72f + breath * 0.28f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = FocusedCardScale
                scaleY = FocusedCardScale
            }
            .border(
                border = BorderStroke(width, Color.White.copy(alpha = alpha)),
                shape = MediaCardShape,
            ),
    )
}

internal val FocusedMediaCardShape = MediaCardShape
