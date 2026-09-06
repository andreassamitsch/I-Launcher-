package com.andreassamitsch.servusprovider.data

/**
 * Adds editorially verified ServusTV formats that are available through the current/news APIs but
 * are not necessarily exposed as standalone cards by the generic `sendungen` catalogue.
 *
 * `Servus Nachrichten in 90 Sekunden` is such a format: ServusTV exposes a dedicated product and
 * current episodes, while the generic catalogue can omit the show card. Keeping the augmentation
 * at the catalogue boundary makes the show available consistently to the standalone UI, show
 * details, user selections and TvProvider publishing without duplicating it when ServusTV starts
 * returning the show normally.
 */
internal object ServusCatalogAugmentation {
    fun withNinetySecondNewsShow(
        categories: List<ServusCategory>,
        currentEpisodes: List<ServusNewsEpisode>,
    ): List<ServusCategory> {
        if (categories.isEmpty()) return categories

        val targetCategoryIndex = categories.indexOfFirst { category ->
            category.shows.any { show ->
                show.id == ServusBranding.NEWS_SHOW_ID ||
                    show.id == ServusBranding.NEWS_90_SECONDS_SHOW_ID
            }
        }
        if (targetCategoryIndex < 0) return categories

        val targetCategory = categories[targetCategoryIndex]
        val ninetySecondEpisodes = ServusCatalogPolicy.selectChannelEpisodes(
            currentEpisodes
                .map(ServusBranding::canonicalizeEpisode)
                .filter { episode ->
                    episode.showId == ServusBranding.NEWS_90_SECONDS_SHOW_ID ||
                        ServusNewsPolicy.contentKind(episode) == ServusContentKind.NEWS_90_SECONDS
                }
                .map { episode ->
                    episode.copy(
                        showId = ServusBranding.NEWS_90_SECONDS_SHOW_ID,
                        showName = ServusBranding.NEWS_90_SECONDS_SHOW_NAME,
                        logoUri = ServusBranding.NEWS_90_SECONDS_LOGO_URI,
                        categoryId = targetCategory.id,
                        categoryTitle = targetCategory.title,
                        contentKindHint = ServusContentKind.NEWS_90_SECONDS,
                    )
                },
        )

        val existingIndex = targetCategory.shows.indexOfFirst {
            it.id == ServusBranding.NEWS_90_SECONDS_SHOW_ID
        }
        val updatedShows = if (existingIndex >= 0) {
            targetCategory.shows.mapIndexed { index, show ->
                if (index != existingIndex) return@mapIndexed show
                val mergedEpisodes = ServusCatalogPolicy.selectChannelEpisodes(
                    show.episodes.map(ServusBranding::canonicalizeEpisode) + ninetySecondEpisodes,
                )
                show.copy(
                    title = ServusBranding.NEWS_90_SECONDS_SHOW_NAME,
                    categoryId = targetCategory.id,
                    categoryTitle = targetCategory.title,
                    artworkUri = show.artworkUri
                        ?: ninetySecondEpisodes.firstNotNullOfOrNull { it.artworkUri },
                    logoUri = ServusBranding.NEWS_90_SECONDS_LOGO_URI,
                    episodes = mergedEpisodes,
                )
            }
        } else {
            val show = ServusShow(
                id = ServusBranding.NEWS_90_SECONDS_SHOW_ID,
                title = ServusBranding.NEWS_90_SECONDS_SHOW_NAME,
                description = null,
                categoryId = targetCategory.id,
                categoryTitle = targetCategory.title,
                artworkUri = ninetySecondEpisodes.firstNotNullOfOrNull { it.artworkUri },
                squareArtworkUri = null,
                logoUri = ServusBranding.NEWS_90_SECONDS_LOGO_URI,
                episodes = ninetySecondEpisodes,
            )
            val mainNewsIndex = targetCategory.shows.indexOfFirst {
                it.id == ServusBranding.NEWS_SHOW_ID
            }
            val insertionIndex = if (mainNewsIndex >= 0) mainNewsIndex + 1 else targetCategory.shows.size
            buildList {
                addAll(targetCategory.shows.take(insertionIndex))
                add(show)
                addAll(targetCategory.shows.drop(insertionIndex))
            }
        }

        return categories.mapIndexed { index, category ->
            if (index == targetCategoryIndex) category.copy(shows = updatedShows) else category
        }
    }
}
