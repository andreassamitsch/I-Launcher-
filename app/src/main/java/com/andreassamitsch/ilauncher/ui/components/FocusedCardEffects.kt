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
import androidx.compose.foundation.shape.RoundedCornerShape

private val MediaCardShape = RoundedCornerShape(10.dp)

@Composable
internal fun rememberFocusedCardBreath(focused: Boolean): Float {
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
    return if (focused) breath else 0f
}

@Composable
internal fun BoxScope.FocusedArtworkGlow(
    artworkUri: String?,
    focused: Boolean,
    breath: Float,
) {
    if (!focused || artworkUri.isNullOrBlank()) return

    // The same artwork is rendered behind the focused card. Its own colours therefore create the
    // glow without palette extraction, another network request or a third-party colour library.
    // Blur is available from Android 12; older devices retain a subtle enlarged colour halo.
    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(
            radius = 16.dp,
            edgeTreatment = BlurredEdgeTreatment.Unbounded,
        )
    } else {
        Modifier
    }

    AsyncImage(
        model = artworkUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.13f
                scaleY = 1.20f
                alpha = 0.26f + breath * 0.10f
            }
            .then(blurModifier),
    )

    // Small sharp colour contribution keeps the effect visible on pre-Android-12 TVs where the
    // platform blur is unavailable.
    AsyncImage(
        model = artworkUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.055f
                scaleY = 1.08f
                alpha = 0.055f + breath * 0.025f
            },
    )
}

@Composable
internal fun BoxScope.FocusedBreathingBorder(
    focused: Boolean,
    breath: Float,
) {
    if (!focused) return

    val width = (1.45f + breath * 0.75f).dp
    val alpha = 0.64f + breath * 0.30f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(
                border = BorderStroke(width, Color.White.copy(alpha = alpha)),
                shape = MediaCardShape,
            ),
    )
}

internal val FocusedMediaCardShape = MediaCardShape
