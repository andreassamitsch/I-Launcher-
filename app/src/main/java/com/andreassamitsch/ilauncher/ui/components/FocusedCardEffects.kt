package com.andreassamitsch.ilauncher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BlurMaskFilter
import android.graphics.RectF
import android.util.LruCache
import kotlin.math.roundToInt
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

// Keep the existing card API and the mid-point look, but do not run a frame clock
// on a stationary TV screen. The focus scale remains animated by the TV card itself.
@Composable
internal fun rememberFocusedCardBreath(focused: Boolean): Float = if (focused) 0.5f else 0f

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

        // Rapid DPAD movement should not start a new software decode for every card.
        delay(160L)

        // The display image may be a hardware bitmap. Pixel sampling therefore uses a tiny,
        // dedicated software request that reuses Coil's normal caches. Some DrawableImage sources
        // (including Android resources) report no positive intrinsic size, so toBitmap must receive
        // explicit dimensions rather than relying on Image.width/Image.height defaults.
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(GlowSampleWidth, GlowSampleHeight)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            if (result is SuccessResult) {
                withContext(Dispatchers.Default) {
                    val bitmap = result.image.toBitmap(GlowSampleWidth, GlowSampleHeight)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    extractArtworkGlowColor(pixels) ?: GlowFallbackColor
                }
            } else {
                GlowFallbackColor
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            GlowFallbackColor
        }.let { sampled ->
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
            }
            .drawWithCache {
                // Cache a half-resolution neutral alpha mask per geometry/density, then tint
                // during drawing. Changing focus colour never regenerates the blur.
                val width = size.width.roundToInt().coerceAtLeast(1)
                val height = size.height.roundToInt().coerceAtLeast(1)
                val padding = 30.dp.toPx().roundToInt().coerceAtLeast(1)
                val corner = 10.dp.toPx().roundToInt()
                val mask = GlowMasks.get(width, height, padding, corner)
                val tint = ColorFilter.tint(sampled)
                onDrawBehind {
                    drawImage(
                        image = mask,
                        dstOffset = IntOffset(-padding, -padding),
                        dstSize = IntSize(width + 2 * padding, height + 2 * padding),
                        alpha = 0.9f + breath * 0.1f,
                        colorFilter = tint,
                    )
                }
            },
    )
}

private data class GlowMaskKey(val width: Int, val height: Int, val padding: Int, val corner: Int)

/** Reuse a small alpha mask across cards, including across focus changes. */
private object GlowMasks {
    private val cache = LruCache<GlowMaskKey, ImageBitmap>(8)

    fun get(width: Int, height: Int, padding: Int, corner: Int): ImageBitmap {
        val key = GlowMaskKey(width, height, padding, corner)
        cache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(
            ((width + 2 * padding + 1) / 2).coerceAtLeast(1),
            ((height + 2 * padding + 1) / 2).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(0.5f, 0.5f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        fun halo(radius: Float, spread: Float, alpha: Int) {
            paint.alpha = alpha
            paint.maskFilter = BlurMaskFilter(radius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            val rect = RectF(
                padding - spread, padding - spread,
                padding + width + spread, padding + height + spread,
            )
            canvas.drawRoundRect(rect, corner + spread, corner + spread, paint)
        }
        halo(padding * 0.67f, padding * 0.23f, 75)
        halo(padding * 0.27f, padding * 0.10f, 95)
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }
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
