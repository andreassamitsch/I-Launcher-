package com.andreassamitsch.joyntv

import android.content.Context

internal fun openJoynMedia(context: Context, item: JoynMediaItem): Boolean {
    // Joyn exposes internal genre/category teaser targets in LandingPageClient that are not
    // standalone browse pages for this client (for example /filme/genre/*). Do not open a page
    // that is known to resolve to zero blocks.
    if (item.isUnsupportedJoynBrowseTarget()) return false

    val playbackRef = item.videoId
        ?: item.path?.takeIf { item.type == JoynMediaType.MOVIE && it.isNotBlank() }
    if (!playbackRef.isNullOrBlank()) {
        context.startActivity(PlayerActivity.vodIntent(context, item))
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
            // legacy catalogue mapper. Valid teaser collection links can still be opened here;
            // unsupported genre targets were rejected above.
            context.startActivity(BrowseActivity.collectionIntent(context, path, item.title))
            return true
        }
        else -> Unit
    }
    return false
}
