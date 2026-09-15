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
    imageAlpha: Float = 0.9f,
    centeredLogo: Boolean = item.type == JoynMediaType.CHANNEL,
    onFocused: (JoynMediaItem) -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "joynTileFocus")
    val shape = RoundedCornerShape(12.dp)

    // primaryImage/thumbnailImage is normally the correct catalogue artwork. The previous UI
    // preferred heroLandscape unconditionally, which caused a number of logo-like/empty cards.
    val primaryArtwork = item.imageUrl ?: item.backdropUrl
    val fallbackArtwork = when {
        item.imageUrl != null && item.backdropUrl != null && item.backdropUrl != item.imageUrl -> item.backdropUrl
        item.imageUrl == null -> item.backdropUrl
        else -> null
    }
    var artworkAspect by remember(primaryArtwork, fallbackArtwork) { mutableStateOf<Float?>(null) }
    val cardWidth = joynAdaptiveCardWidth(cardHeight, artworkAspect, fallbackWidth)
    val narrowArtwork = artworkAspect?.let { it < 0.92f } == true
    val footerHeight = when {
        narrowArtwork -> 74.dp
        subtitle != null -> 64.dp
        else -> 58.dp
    }

    Box(
        Modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A2029), Color(0xFF0E1117)),
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
        }

        val logo = item.logoUrl?.takeIf(String::isNotBlank)
        if (logo != null && (centeredLogo || primaryArtwork == null && fallbackArtwork == null)) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = if (centeredLogo) {
                    Modifier.align(Alignment.Center).padding(30.dp).fillMaxSize()
                } else {
                    Modifier.align(Alignment.TopStart).padding(10.dp).size(
                        width = if (compact) 82.dp else 98.dp,
                        height = if (compact) 34.dp else 40.dp,
                    )
                },
                contentScale = ContentScale.Fit,
            )
        } else if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(
                    width = if (compact) 82.dp else 98.dp,
                    height = if (compact) 34.dp else 40.dp,
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                displayTitle,
                color = Color.White,
                fontSize = when {
                    narrowArtwork && compact -> 12.sp
                    compact -> 13.sp
                    narrowArtwork -> 13.sp
                    else -> 14.sp
                },
                lineHeight = when {
                    narrowArtwork && compact -> 14.sp
                    compact -> 16.sp
                    else -> 17.sp
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = if (narrowArtwork) 3 else 2,
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
