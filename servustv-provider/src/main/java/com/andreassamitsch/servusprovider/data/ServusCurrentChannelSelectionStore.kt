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
     * Builds the actual aggregate channel from the fast legacy feed plus selected catalogue shows.
     * The legacy feed stays fresh on the existing short refresh interval; catalogue additions are
     * refreshed with the catalogue itself. Once configured, legacy items are filtered against the
     * selected default shows so removing a default show also removes it from Aktuelles.
     */
    fun effectiveEpisodes(
        categories: List<ServusCategory>,
        legacyEpisodes: List<ServusNewsEpisode>,
    ): List<ServusNewsEpisode> {
        if (!isConfigured()) return legacyEpisodes

        val allShows = categories.flatMap { it.shows }.distinctBy { it.id }
        val selectedIds = effectiveSelectedShowIds(categories)
        val selectedShows = allShows.filter { it.id in selectedIds }
        val filteredLegacy = legacyEpisodes.filter { episode ->
            ServusCurrentChannelPolicy.matchesSelectedShow(
                episode = episode,
                selectedShows = selectedShows,
                allShows = allShows,
            )
        }
        val selectedCatalogueEpisodes = selectedShows.flatMap { it.episodes }

        return ServusNewsPolicy.deduplicateEpisodes(filteredLegacy + selectedCatalogueEpisodes)
            .take(MAX_CURRENT_EPISODES)
    }

    private companion object {
        const val PREFS_NAME = "servus_current_channel_selection"
        const val KEY_SELECTED_SHOW_IDS = "selected_show_ids"
        const val MAX_CURRENT_EPISODES = 40
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

    fun matchesSelectedShow(
        episode: ServusNewsEpisode,
        selectedShows: List<ServusShow>,
        allShows: List<ServusShow>,
    ): Boolean {
        episode.showId?.let { showId ->
            if (selectedShows.any { it.id == showId }) return true
        }

        return when (ServusNewsPolicy.contentKind(episode)) {
            ServusContentKind.NEWS_90_SECONDS -> {
                val dedicated90Shows = allShows.filter { normalize(it.title).contains("90 sekunden") }
                if (dedicated90Shows.isNotEmpty()) {
                    selectedShows.any { normalize(it.title).contains("90 sekunden") }
                } else {
                    selectedShows.any { normalize(it.title).contains("servus nachrichten") }
                }
            }

            ServusContentKind.FULL_NEWS -> selectedShows.any { show ->
                val title = normalize(show.title)
                title.contains("servus nachrichten") && !title.contains("90 sekunden")
            }

            ServusContentKind.WEGSCHEIDER -> selectedShows.any {
                normalize(it.title).contains("wegscheider")
            }

            null -> {
                val episodeShow = normalize(episode.showName.orEmpty())
                episodeShow.isNotBlank() && selectedShows.any { show ->
                    val showTitle = normalize(show.title)
                    showTitle == episodeShow ||
                        showTitle.contains(episodeShow) ||
                        episodeShow.contains(showTitle)
                }
            }
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()
}
