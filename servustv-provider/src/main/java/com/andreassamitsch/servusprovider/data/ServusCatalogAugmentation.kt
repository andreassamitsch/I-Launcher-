package com.andreassamitsch.servusprovider.data

import java.util.Locale

/**
 * Adds editorially verified ServusTV formats that are available through dedicated ServusTV-On
 * products but are not necessarily exposed as standalone cards by the generic `sendungen` catalogue.
 *
 * Keep this augmentation at the cache boundary so standalone UI, show pages, user selections and
 * TvProvider publishing all see the same identities without creating duplicates when ServusTV later
 * starts returning one of these products in the generic catalogue itself.
 */
internal object ServusCatalogAugmentation {
    fun withEditorialShows(
        categories: List<ServusCategory>,
        currentEpisodes: List<ServusNewsEpisode>,
    ): List<ServusCategory> {
        if (categories.isEmpty()) return categories
        return addWeatherNinetySecondShow(
            addNewsNinetySecondShow(categories, currentEpisodes),
        )
    }

    /** Backwards-compatible helper retained for existing callers/tests from the first augmentation. */
    fun withNinetySecondNewsShow(
        categories: List<ServusCategory>,
        currentEpisodes: List<ServusNewsEpisode>,
    ): List<ServusCategory> = withEditorialShows(categories, currentEpisodes)

    private fun addNewsNinetySecondShow(
        categories: List<ServusCategory>,
        currentEpisodes: List<ServusNewsEpisode>,
    ): List<ServusCategory> {
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
            insertAfter(targetCategory.shows, mainNewsIndex, show)
        }

        return categories.mapIndexed { index, category ->
            if (index == targetCategoryIndex) category.copy(shows = updatedShows) else category
        }
    }

    private fun addWeatherNinetySecondShow(categories: List<ServusCategory>): List<ServusCategory> {
        val targetCategoryIndex = categories.indexOfFirst { category ->
            category.shows.any { show ->
                show.id == ServusBranding.WEATHER_90_SECONDS_SHOW_ID || isMainWeatherShow(show)
            }
        }
        if (targetCategoryIndex < 0) return categories

        val category = categories[targetCategoryIndex]
        val existingIndex = category.shows.indexOfFirst {
            it.id == ServusBranding.WEATHER_90_SECONDS_SHOW_ID
        }
        val mainWeatherIndex = category.shows.indexOfFirst(::isMainWeatherShow)
        val mainWeather = category.shows.getOrNull(mainWeatherIndex)

        val updatedShows = if (existingIndex >= 0) {
            category.shows.mapIndexed { index, show ->
                if (index != existingIndex) return@mapIndexed show
                show.copy(
                    title = ServusBranding.WEATHER_90_SECONDS_SHOW_NAME,
                    description = show.description ?: ServusBranding.WEATHER_90_SECONDS_DESCRIPTION,
                    categoryId = category.id,
                    categoryTitle = category.title,
                    artworkUri = show.artworkUri ?: mainWeather?.artworkUri,
                    squareArtworkUri = show.squareArtworkUri ?: mainWeather?.squareArtworkUri,
                    logoUri = show.logoUri ?: mainWeather?.logoUri,
                )
            }
        } else {
            val synthetic = ServusShow(
                id = ServusBranding.WEATHER_90_SECONDS_SHOW_ID,
                title = ServusBranding.WEATHER_90_SECONDS_SHOW_NAME,
                description = ServusBranding.WEATHER_90_SECONDS_DESCRIPTION,
                categoryId = category.id,
                categoryTitle = category.title,
                // Use the official main-weather artwork as a temporary catalogue fallback. Opening
                // the show lazily replaces it with the dedicated product metadata when available.
                artworkUri = mainWeather?.artworkUri,
                squareArtworkUri = mainWeather?.squareArtworkUri,
                logoUri = mainWeather?.logoUri,
                episodes = emptyList(),
            )
            insertAfter(category.shows, mainWeatherIndex, synthetic)
        }

        return categories.mapIndexed { index, value ->
            if (index == targetCategoryIndex) value.copy(shows = updatedShows) else value
        }
    }

    private fun isMainWeatherShow(show: ServusShow): Boolean {
        if (show.id == ServusBranding.WEATHER_90_SECONDS_SHOW_ID) return false
        return normalize(show.title) == "servus wetter"
    }

    private fun insertAfter(
        shows: List<ServusShow>,
        anchorIndex: Int,
        show: ServusShow,
    ): List<ServusShow> {
        val insertionIndex = if (anchorIndex >= 0) anchorIndex + 1 else shows.size
        return buildList {
            addAll(shows.take(insertionIndex))
            add(show)
            addAll(shows.drop(insertionIndex))
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()
}
