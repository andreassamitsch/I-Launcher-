package com.andreassamitsch.servusprovider.data

/** Metadata for the Android media session, independent of signed playback URLs. */
data class ServusPlaybackMetadata(
    val title: String,
    val contextTitle: String? = null,
    val artworkUri: String? = null,
) {
    companion object {
        fun findEpisode(
            id: String,
            news: List<ServusNewsEpisode>,
            shows: List<ServusShow>,
        ): ServusNewsEpisode? = news.firstOrNull { it.id == id }
            ?: shows.asSequence().flatMap { show ->
                show.episodes.asSequence() + show.collections.asSequence().flatMap { it.episodes.asSequence() }
            }.firstOrNull { it.id == id }

        fun episode(item: ServusNewsEpisode): ServusPlaybackMetadata = ServusPlaybackMetadata(
            title = item.title.takeIf { it.isNotBlank() } ?: item.showName ?: "ServusTV",
            contextTitle = item.showName?.takeIf { it.isNotBlank() && it != item.title },
            artworkUri = item.artworkUri ?: item.showArtworkUri,
        )

        fun live(channel: ServusLiveChannel, nowMillis: Long): ServusPlaybackMetadata {
            // currentProgram() also returns upcoming programs; don't advertise those as playing.
            val current = channel.programs.firstOrNull { nowMillis in it.startAtMillis until it.endAtMillis }
            return ServusPlaybackMetadata(
                title = current?.title?.takeIf { it.isNotBlank() } ?: channel.title,
                contextTitle = channel.title.takeIf { current != null && it != current.title },
                artworkUri = channel.artworkUri ?: channel.squareArtworkUri,
            )
        }
    }
}
