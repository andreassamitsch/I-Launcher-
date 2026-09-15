package com.andreassamitsch.joyntv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Joyn mixes landscape stills and portrait posters in the same catalogue lanes. Cards must not
 * blindly crop everything to 16:9: measure the delivered artwork and let the caller adapt its
 * width while the image itself is always shown completely.
 *
 * A second Joyn artwork URL can be supplied as a fallback. This matters because the catalogue API
 * occasionally exposes an image variant that is empty/unavailable while another variant for the
 * same asset is valid.
 */
@Composable
internal fun JoynAdaptiveArtwork(
    model: Any?,
    fallbackModel: Any? = null,
    contentDescription: String?,
    modifier: Modifier,
    alpha: Float = 1f,
    onAspectRatio: (Float) -> Unit,
) {
    var activeModel by remember(model, fallbackModel) { mutableStateOf(model ?: fallbackModel) }

    AsyncImage(
        model = activeModel,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        alpha = alpha,
        onSuccess = { state ->
            val size = state.painter.intrinsicSize
            if (
                size.width.isFinite() &&
                size.height.isFinite() &&
                size.width > 0f &&
                size.height > 0f
            ) {
                onAspectRatio(size.width / size.height)
            }
        },
        onError = {
            if (fallbackModel != null && activeModel != fallbackModel) {
                activeModel = fallbackModel
            }
        },
    )
}

internal fun joynAdaptiveCardWidth(
    height: Dp,
    aspectRatio: Float?,
    fallbackWidth: Dp,
): Dp {
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: return fallbackWidth
    val normalized = ratio.coerceIn(MIN_CARD_ASPECT, MAX_CARD_ASPECT)
    return (height * normalized).coerceAtMost(fallbackWidth)
}

private const val MIN_CARD_ASPECT = 0.66f
private const val MAX_CARD_ASPECT = 2.20f
