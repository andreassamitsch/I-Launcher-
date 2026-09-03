package com.andreassamitsch.servusprovider.data

import android.content.Context
import java.util.Locale

/**
 * Local user selection for the aggregate "ServusTV Aktuelles" channel.
 *
 * Before the user changes anything, the existing legacy news discovery remains authoritative.
 * The first explicit toggle starts from the catalogue shows that correspond to the previous
 * defaults (Servus Nachrichten / 90 Sekunden / Der Wegscheider), so updating the app does not
 * unexpectedly empty the channel.
 */
class ServusCurrentChannelSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = preferences.contains(KEY_SELECTED_SHOW_IDS)

    fun effectiveSelectedShowIds(categories: List<ServusCategory>): Set<String> {
        if (!isConfigured()) return ServusCurrentChannelPolicy.defaultSelectedShowIds(categories)
        return preferences.getStringSet(KEY_SELECTED_SHOW_IDS, emptySet()).orEmpty().toSet()
    }

    fun isSelected(show: ServusShow, categories: List<ServusCategory>): Boolean =
        show.id in effectiveSelectedShowIds(categories)

    fun setSelected(
        showId: String,
        selected: Boolean,
        categories: List<ServusCategory>,
    ) {
        val current = effectiveSelectedShowIds(categories).toMutableSet()
        if (selected) current += showId else current -= showId
        preferences.edit()
            .putStringSet(KEY_SELECTED_SHOW_IDS, current)
            .apply()
    }

    /**
     * Builds the visible aggregate rail from the fast legacy feed plus selected catalogue shows.
     * At least the newest cached item of every selected show is reserved before the rail is cut to
     * its UI limit. This prevents timestamped 90-second updates from pushing a newly selected show
     * with no source timestamp completely out of the visible rail.
     *
     * Legacy fast-feed episodes are enriched with the matching catalogue show's identity and logo.
     * This keeps the quick refresh cheap while still giving Android-TV launchers enough metadata to
     * make mixed "Aktuelles" rows visually attributable to their originating show.
     */
    fun effectiveEpisodes(
        categories: List<ServusCategory>,
        legacyEpisodes: List<ServusNewsEpisode>,
    ): List<ServusNewsEpisode> {
        if (!isConfigured()) return legacyEpisodes.take(MAX_CURRENT_EPISODES)

        val allShows = categories.flatMap { it.shows }.distinctBy { it.id }
        val selectedIds = effectiveSelectedShowIds(categories)
        val selectedShows = allShows.filter { it.id in selectedIds }
        return ServusCurrentChannelPolicy.composeCurrentEpisodes(
            selectedShows = selectedShows,
            allShows = allShows,
            legacyEpisodes = legacyEpisodes,
            limit = MAX_CURRENT_EPISODES,
        )
    }

    private companion object {
        const val PREFS_NAME = "servus_current_channel_selection"
        const val KEY_SELECTED_SHOW_IDS = "selected_show_ids"
        const val MAX_CURRENT_EPISODES = 20
    }
}

object ServusCurrentChannelPolicy {
    fun defaultSelectedShowIds(categories: List<ServusCategory>): Set<String> = categories
        .flatMap { it.shows }
        .filter { show -> isLegacyDefaultTitle(show.title) }
        .mapTo(linkedSetOf()) { it.id }

    fun isLegacyDefaultTitle(title: String): Boolean {
        val normalized = normalize(title)
        return normalized.contains("servus nachrichten") || normalized.contains("wegscheider")
    }

    fun composeCurrentEpisodes(
        selectedShows: List<ServusShow>,
        allShows: List<ServusShow>,
        legacyEpisodes: List<ServusNewsEpisode>,
        limit: Int,
    ): List<ServusNewsEpisode> {
        if (limit <= 0 || selectedShows.isEmpty()) return emptyList()

        val filteredLegacy = legacyEpisodes.mapNotNull { episode ->
            matchingSelectedShow(
                episode = episode,
                selectedShows = selectedShows,
                allShows = allShows,
            )?.let { show ->
                episode.copy(
                    showId = episode.showId ?: show.id,
                    showName = episode.showName?.takeIf { it.isNotBlank() } ?: show.title,
                    logoUri = episode.logoUri ?: show.logoUri,
                    categoryId = episode.categoryId ?: show.categoryId,
                    categoryTitle = episode.categoryTitle ?: show.categoryTitle,
                )
            }
        }
        val selectedCatalogueEpisodes = selectedShows.flatMap { it.episodes }
        val merged = ServusNewsPolicy.deduplicateEpisodes(filteredLegacy + selectedCatalogueEpisodes)
        if (merged.size <= limit) return merged

        val anchorIds = selectedShows
            .mapNotNull { it.episodes.firstOrNull()?.id }
            .toSet()
        val anchors = merged.filter { it.id in anchorIds }
            .take(limit)
        if (anchors.size >= limit) return sortByAvailability(anchors)

        val filler = merged
            .filterNot { it.id in anchorIds }
            .take(limit - anchors.size)
        return sortByAvailability(filler + anchors)
    }

    fun matchesSelectedShow(
        episode: ServusNewsEpisode,
        selectedShows: List<ServusShow>,
        allShows: List<ServusShow>,
    ): Boolean = matchingSelectedShow(episode, selectedShows, allShows) != null

    fun matchingSelectedShow(
        episode: ServusNewsEpisode,
        selectedShows: List<ServusShow>,
        allShows: List<ServusShow>,
    ): ServusShow? {
        episode.showId?.let { showId ->
            selectedShows.firstOrNull { it.id == showId }?.let { return it }
        }

        return when (ServusNewsPolicy.contentKind(episode)) {
            ServusContentKind.NEWS_90_SECONDS -> {
                selectedShows.firstOrNull { it.id == ServusCatalogPolicy.NEWS_90_SECONDS_SHOW_ID }
                    ?: run {
                        val dedicated90Shows = allShows.filter { normalize(it.title).contains("90 sekunden") }
                        if (dedicated90Shows.isNotEmpty()) {
                            selectedShows.firstOrNull { normalize(it.title).contains("90 sekunden") }
                        } else {
                            selectedShows.firstOrNull { normalize(it.title).contains("servus nachrichten") }
                        }
                    }
            }

            ServusContentKind.FULL_NEWS -> selectedShows.firstOrNull { show ->
                val title = normalize(show.title)
                title.contains("servus nachrichten") && !title.contains("90 sekunden")
            }

            ServusContentKind.WEGSCHEIDER -> selectedShows.firstOrNull {
                normalize(it.title).contains("wegscheider")
            }

            null -> {
                val episodeShow = normalize(episode.showName.orEmpty())
                episodeShow.takeIf { it.isNotBlank() }?.let { normalizedEpisodeShow ->
                    selectedShows.firstOrNull { show ->
                        val showTitle = normalize(show.title)
                        showTitle == normalizedEpisodeShow ||
                            showTitle.contains(normalizedEpisodeShow) ||
                            normalizedEpisodeShow.contains(showTitle)
                    }
                }
            }
        }
    }

    private fun sortByAvailability(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> =
        episodes.sortedWith(
            compareByDescending<ServusNewsEpisode> { ServusNewsPolicy.recencyMillis(it) ?: Long.MIN_VALUE },
        )

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()
}
