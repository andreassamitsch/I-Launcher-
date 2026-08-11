package com.andreassamitsch.ilauncher.ui.components

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

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

    // Re-render the focused artwork behind the card so the halo automatically carries the card's
    // own colours. Blur is the outer draw effect while the inner source layer is rounded and
    // enlarged, so the final aura spreads beyond the card without reading as a second rectangle.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AsyncImage(
            model = artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(
                    radius = 30.dp,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                )
                .graphicsLayer {
                    scaleX = 1.18f
                    scaleY = 1.30f
                    alpha = 0.17f + breath * 0.07f
                    shape = MediaCardShape
                    clip = true
                },
        )
    } else {
        // Pre-Android-12 Compose has no hardware blur. Keep the fallback deliberately faint so it
        // reads as a colour aura instead of a second card rectangle.
        AsyncImage(
            model = artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.16f
                    scaleY = 1.22f
                    alpha = 0.045f + breath * 0.02f
                    shape = MediaCardShape
                    clip = true
                },
        )
    }
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
