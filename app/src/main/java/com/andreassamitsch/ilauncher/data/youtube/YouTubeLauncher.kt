package com.andreassamitsch.ilauncher.data.youtube

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.TrailerProvider

object YouTubeLauncher {
    fun playTrailer(context: Context, item: MediaItem): Boolean {
        val trailer = item.trailer ?: return false
        if (trailer.provider != TrailerProvider.YouTube || trailer.externalId.isBlank()) return false
        return openUri(
            context = context,
            uri = Uri.parse("https://www.youtube.com/watch?v=${Uri.encode(trailer.externalId)}"),
        )
    }

    fun searchTrailer(context: Context, item: MediaItem): Boolean {
        val query = buildSearchQuery(item)
        return openUri(
            context = context,
            uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"),
        )
    }

    internal fun buildSearchQuery(item: MediaItem): String = buildList {
        add(item.title)
        item.seasonNumber?.let { season ->
            item.episodeNumber?.let { episode -> add("S${season}E${episode}") }
        }
        item.releaseYear?.let { add(it.toString()) }
        add("Trailer")
    }.joinToString(" ")

    private fun openUri(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
