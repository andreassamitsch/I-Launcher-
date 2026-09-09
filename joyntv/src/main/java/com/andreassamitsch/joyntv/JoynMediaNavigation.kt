package com.andreassamitsch.joyntv

import android.content.Context

internal fun openJoynMedia(context: Context, item: JoynMediaItem): Boolean {
    val playbackRef = item.videoId
        ?: item.path?.takeIf { item.type == JoynMediaType.MOVIE && it.isNotBlank() }
    if (!playbackRef.isNullOrBlank()) {
        context.startActivity(PlayerActivity.vodIntent(context, playbackRef, item.title))
        return true
    }
    if (item.type == JoynMediaType.SERIES && !item.path.isNullOrBlank()) {
        context.startActivity(SeriesActivity.intent(context, item))
        return true
    }
    return false
}
