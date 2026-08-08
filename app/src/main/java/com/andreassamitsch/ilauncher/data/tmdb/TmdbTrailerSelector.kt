package com.andreassamitsch.ilauncher.data.tmdb

internal object TmdbTrailerSelector {
    fun preferredYouTubeId(videos: List<TmdbVideoDto>): String? = videos
        .asSequence()
        .filter { it.site.equals("YouTube", ignoreCase = true) }
        .filter { it.key.isNotBlank() }
        .filter { it.type.equals("Trailer", ignoreCase = true) || it.type.equals("Teaser", ignoreCase = true) }
        .sortedWith(
            compareBy<TmdbVideoDto> { typeRank(it.type) }
                .thenBy { if (it.official) 0 else 1 }
                .thenBy { languageRank(it.language) }
                .thenByDescending { it.publishedAt.orEmpty() },
        )
        .firstOrNull()
        ?.key

    private fun typeRank(type: String?): Int = when {
        type.equals("Trailer", ignoreCase = true) -> 0
        type.equals("Teaser", ignoreCase = true) -> 1
        else -> 2
    }

    private fun languageRank(language: String?): Int = when (language) {
        "de" -> 0
        "en" -> 1
        null -> 2
        else -> 3
    }
}
