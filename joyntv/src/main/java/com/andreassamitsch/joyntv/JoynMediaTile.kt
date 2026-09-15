package com.andreassamitsch.joyntv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/** Shared catalogue tile so phone/TV card rendering stays consistent across all screens. */
@Composable
internal fun JoynMediaTile(
    item: JoynMediaItem,
    compact: Boolean,
    cardHeight: Dp,
    fallbackWidth: Dp,
    displayTitle: String = item.title,
    subtitle: String? = null,
    imageAlpha: Float = 1f,
    centeredLogo: Boolean = false,
    onFocused: (JoynMediaItem) -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "joynTileFocus")
    val shape = RoundedCornerShape(12.dp)
    val artwork = remember(item.imageUrl, item.backdropUrl, item.logoUrl, item.type) {
        item.resolveJoynTileArtwork()
    }
    val primaryArtwork = artwork.primary
    val fallbackArtwork = artwork.fallback
    var artworkAspect by remember(primaryArtwork, fallbackArtwork) { mutableStateOf<Float?>(null) }
    val cardWidth = joynAdaptiveCardWidth(cardHeight, artworkAspect, fallbackWidth)
    val narrowArtwork = artworkAspect?.let { it < 0.92f } == true
    val longestWord = remember(displayTitle) { displayTitle.longestJoynTitleWordLength() }

    val titleFontSize = when {
        narrowArtwork && compact && longestWord >= 10 -> 9.sp
        narrowArtwork && compact -> 10.sp
        narrowArtwork && longestWord >= 10 -> 10.sp
        narrowArtwork -> 11.sp
        compact -> 13.sp
        else -> 14.sp
    }
    val titleLineHeight = when {
        narrowArtwork && compact && longestWord >= 10 -> 11.sp
        narrowArtwork && compact -> 12.sp
        narrowArtwork -> 13.sp
        compact -> 16.sp
        else -> 17.sp
    }
    val footerHeight = when {
        narrowArtwork -> 66.dp
        subtitle != null -> 64.dp
        else -> 58.dp
    }
    val horizontalTextPadding = if (narrowArtwork) 8.dp else 12.dp

    Box(
        Modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B222C), Color(0xFF0D1016)),
                ),
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF46505D), shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused(item)
            }
            .clickable {
                onFocused(item)
                onClick()
            }
            .focusable(),
    ) {
        if (primaryArtwork != null || fallbackArtwork != null) {
            JoynAdaptiveArtwork(
                model = primaryArtwork,
                fallbackModel = fallbackArtwork,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                alpha = imageAlpha,
                onAspectRatio = { artworkAspect = it },
            )
        } else {
            // Deliberate neutral fallback. A card with no valid content art should look intentional,
            // not like a failed image request.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF242D3A), Color.Transparent),
                            radius = 360f,
                        ),
                    ),
            )
        }

        val logo = item.logoUrl?.takeIf(String::isNotBlank)
        val showCenteredLogo = logo != null && (centeredLogo || artwork.centerLogo)
        val showOverlayLogo = logo != null && artwork.overlayLogo && !showCenteredLogo

        if (showCenteredLogo) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = 22.dp, end = 22.dp, bottom = footerHeight / 2)
                    .fillMaxWidth()
                    .height(if (compact) 50.dp else 62.dp),
                contentScale = ContentScale.Fit,
            )
        } else if (showOverlayLogo) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(
                    width = if (compact) 76.dp else 88.dp,
                    height = if (compact) 30.dp else 34.dp,
                ),
                contentScale = ContentScale.Fit,
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(footerHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD9080A0E), Color(0xFF080A0E)),
                    ),
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = horizontalTextPadding, vertical = if (narrowArtwork) 8.dp else 10.dp),
        ) {
            Text(
                displayTitle,
                color = Color.White,
                fontSize = titleFontSize,
                lineHeight = titleLineHeight,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (narrowArtwork) 3 else 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    color = Color(0xFFCED3DC),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if ("SVOD" in item.licenseTypes || "PLUS" in item.markings || "PREMIUM" in item.markings) {
                Spacer(Modifier.height(2.dp))
                Text("PLUS+", color = Color(0xFFD7DBE3), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
