package com.andreassamitsch.joyntv

import android.content.Context

internal fun openJoynMedia(context: Context, item: JoynMediaItem): Boolean {
    val playbackRef = item.videoId
        ?: item.path?.takeIf { item.type == JoynMediaType.MOVIE && it.isNotBlank() }
    if (!playbackRef.isNullOrBlank()) {
        context.startActivity(PlayerActivity.vodIntent(context, playbackRef, item.title))
        return true
    }

    val path = item.path?.takeIf(String::isNotBlank)
    when (item.type) {
        JoynMediaType.SERIES -> if (path != null) {
            context.startActivity(SeriesActivity.intent(context, item))
            return true
        }
        JoynMediaType.CHANNEL -> if (path != null) {
            context.startActivity(BrowseActivity.channelIntent(context, path, item.title))
            return true
        }
        JoynMediaType.CATEGORY -> if (path != null) {
            context.startActivity(BrowseActivity.categoryIntent(context, path, item.title))
            return true
        }
        JoynMediaType.COLLECTION -> if (path != null) {
            context.startActivity(BrowseActivity.collectionIntent(context, path, item.title))
            return true
        }
        JoynMediaType.COMPILATION -> if (path != null) {
            context.startActivity(BrowseActivity.compilationIntent(context, path, item.title))
            return true
        }
        JoynMediaType.UNKNOWN -> if (path != null) {
            // LandingPageClient currently maps Joyn Teaser entries as UNKNOWN in the
            // legacy catalogue mapper. Teasers are collection links in Joyn's API.
            context.startActivity(BrowseActivity.collectionIntent(context, path, item.title))
            return true
        }
        else -> Unit
    }
    return false
}
