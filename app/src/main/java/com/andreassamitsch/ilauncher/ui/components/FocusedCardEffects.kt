package com.andreassamitsch.ilauncher.ui.components

import android.graphics.Color as AndroidColor
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImage
import coil3.toBitmap

private val MediaCardShape = RoundedCornerShape(10.dp)
private const val FocusedCardScale = 1.045f

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
internal fun BoxScope.FocusedArtworkGlow(
    artworkUri: String?,
    focused: Boolean,
    breath: Float,
) {
    if (!focused || artworkUri.isNullOrBlank()) return

    var glowColor by remember(artworkUri) { mutableStateOf<Color?>(null) }

    // Use Coil's already requested artwork as a tiny colour probe. Only the focused card performs
    // this work and subsequent loads normally hit Coil's memory cache. The probe itself is fully
    // transparent; the visible effect below is a cheap gradient rather than a large live blur.
    AsyncImage(
        model = artworkUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { state ->
            glowColor = sampleGlowColor(state.result.image)
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0f },
    )

    glowColor?.let { sampled ->
        val coreAlpha = 0.30f + breath * 0.08f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.62f
                    scaleY = 2.00f
                }
                .background(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to sampled.copy(alpha = coreAlpha),
                            0.40f to sampled.copy(alpha = coreAlpha * 0.62f),
                            0.68f to sampled.copy(alpha = coreAlpha * 0.20f),
                            0.82f to sampled.copy(alpha = 0.0f),
                            1.00f to Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

private fun sampleGlowColor(image: Image): Color {
    val bitmap = image.toBitmap(width = 16, height = 10)
    val hsv = FloatArray(3)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var totalWeight = 0.0

    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (AndroidColor.alpha(pixel) < 64) continue
            AndroidColor.colorToHSV(pixel, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            if (value < 0.08f) continue

            // Give colourful and reasonably bright pixels more influence so dark letterboxing,
            // black logos, or a single shadow do not turn the whole focus halo grey.
            val weight = (0.15f + saturation * 1.35f) * (0.35f + value * 0.65f)
            red += AndroidColor.red(pixel) * weight
            green += AndroidColor.green(pixel) * weight
            blue += AndroidColor.blue(pixel) * weight
            totalWeight += weight
        }
    }

    if (totalWeight <= 0.0) return Color.White

    val average = AndroidColor.rgb(
        (red / totalWeight).toInt().coerceIn(0, 255),
        (green / totalWeight).toInt().coerceIn(0, 255),
        (blue / totalWeight).toInt().coerceIn(0, 255),
    )
    AndroidColor.colorToHSV(average, hsv)
    if (hsv[1] < 0.08f) return Color(0xFFD8D8D8)

    hsv[1] = (hsv[1] * 1.16f).coerceIn(0.32f, 0.88f)
    hsv[2] = hsv[2].coerceIn(0.40f, 0.92f)
    return Color(AndroidColor.HSVToColor(hsv))
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
