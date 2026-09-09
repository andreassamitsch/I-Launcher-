package com.andreassamitsch.joyntv

import android.content.Context

internal fun openJoynMedia(context: Context, item: JoynMediaItem): Boolean {
    val videoId = item.videoId
    if (!videoId.isNullOrBlank()) {
        context.startActivity(PlayerActivity.vodIntent(context, videoId, item.title))
        return true
    }
    if (item.type == JoynMediaType.SERIES && !item.path.isNullOrBlank()) {
        context.startActivity(SeriesActivity.intent(context, item))
        return true
    }
    return false
}
