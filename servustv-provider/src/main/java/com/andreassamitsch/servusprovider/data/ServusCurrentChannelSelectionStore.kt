package com.andreassamitsch.servusprovider.data

import android.content.Context
import java.util.Locale

/** Local user selection for the aggregate `ServusTV Aktuelles` channel. */
class ServusCurrentChannelSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = preferences.contains(KEY_SELECTED_SOURCE_KEYS)

    fun selectedSourceKeys(): Set<String> =
        preferences.getStringSet(KEY_SELECTED_SOURCE_KEYS, emptySet()).orEmpty().toSet()

    fun effectiveSelectedSourceKeys(categories: List<ServusCategory>): Set<String> {
        if (!isConfigured()) return ServusCurrentChannelPolicy.defaultSelectedShowIds(categories)
        val valid = ServusSourceKey.validKeys(categories)
        return selectedSourceKeys().filterTo(linkedSetOf()) { it in valid }
    }

    /** Parent show IDs that require a targeted refresh. */
    fun effectiveSelectedShowIds(categories: List<ServusCategory>): Set<String> =
        effectiveSelectedSourceKeys(categories)
            .mapNotNullTo(linkedSetOf(), ServusSourceKey::parentShowId)

    fun selectedCollectionParentShowIds(categories: List<ServusCategory>): Set<String> =
        ServusSourceKey.collectionParentShowIds(effectiveSelectedSourceKeys(categories))

    fun selectedCollectionIdsForShow(showId: String, categories: List<ServusCategory>): Set<String> =
        effectiveSelectedSourceKeys(categories).asSequence()
            .filter(ServusSourceKey::isCollection)
            .filter { ServusSourceKey.parentShowId(it) == showId }
            .mapNotNull(ServusSourceKey::collectionId)
            .toCollection(linkedSetOf())

    fun isSelected(show: ServusShow, categories: List<ServusCategory>): Boolean =
        ServusSourceKey.show(show.id) in effectiveSelectedSourceKeys(categories)

    fun isCollectionSelected(
        showId: String,
        collectionId: String,
        categories: List<ServusCategory>,
    ): Boolean = ServusSourceKey.collection(showId, collectionId) in effectiveSelectedSourceKeys(categories)

    fun setSelected(showId: String, selected: Boolean, categories: List<ServusCategory>) {
        setSourceSelected(ServusSourceKey.show(showId), selected, categories)
    }

    fun setCollectionSelected(
        showId: String,
        collectionId: String,
        selected: Boolean,
        categories: List<ServusCategory>,
    ) {
        setSourceSelected(ServusSourceKey.collection(showId, collectionId), selected, categories)
    }

    private fun setSourceSelected(key: String, selected: Boolean, categories: List<ServusCategory>) {
        val current = effectiveSelectedSourceKeys(categories).toMutableSet()
        if (selected) current += key else current -= key
        preferences.edit().putStringSet(KEY_SELECTED_SOURCE_KEYS, current).apply()
    }

    fun effectiveEpisodes(
        categories: List<ServusCategory>,
        legacyEpisodes: List<ServusNewsEpisode>,
    ): List<ServusNewsEpisode> {
        if (!isConfigured()) {
            return ServusCurrentChannelPolicy.applyCanonicalBranding(legacyEpisodes)
                .take(MAX_CURRENT_EPISODES)
        }

        val allShows = categories.flatMap { it.shows }.distinctBy { it.id }
        val selectedKeys = effectiveSelectedSourceKeys(categories)
        val selectedWholeShows = allShows.filter { ServusSourceKey.show(it.id) in selectedKeys }
        val selectedCollectionSources = allShows.flatMap { show ->
            show.collections
                .filter { collection ->
                    collection.role == ServusCollectionRole.CONTENT &&
                        ServusSourceKey.collection(show.id, collection.id) in selectedKeys
                }
                .map { collection -> show to collection }
        }

        val filteredLegacy = legacyEpisodes.mapNotNull { rawEpisode ->
            val episode = ServusBranding.canonicalizeEpisode(rawEpisode)
            ServusCurrentChannelPolicy.matchingSelectedShow(
                episode = episode,
                selectedShows = selectedWholeShows,
                allShows = allShows,
            )?.let { show -> episodeForShow(episode, show) }
        }
        val wholeShowEpisodes = selectedWholeShows.flatMap { show ->
            show.episodes.map { episodeForShow(ServusBranding.canonicalizeEpisode(it), show) }
        }
        val collectionEpisodes = selectedCollectionSources.flatMap { (show, collection) ->
            collection.episodes.map { raw ->
                val episode = ServusBranding.canonicalizeEpisode(raw)
                episode.copy(
                    categoryId = episode.categoryId ?: show.categoryId,
                    categoryTitle = episode.categoryTitle ?: show.categoryTitle,
                    sourceCollectionId = episode.sourceCollectionId ?: collection.id,
                    sourceCollectionTitle = episode.sourceCollectionTitle ?: collection.title,
                )
            }
        }

        val merged = ServusNewsPolicy.deduplicateEpisodes(filteredLegacy + wholeShowEpisodes + collectionEpisodes)
        if (merged.size <= MAX_CURRENT_EPISODES) return merged

        val anchorIds = buildSet {
            selectedWholeShows.mapNotNullTo(this) { it.episodes.firstOrNull()?.id }
            selectedCollectionSources.mapNotNullTo(this) { it.second.episodes.firstOrNull()?.id }
        }
        val anchors = merged.filter { it.id in anchorIds }.take(MAX_CURRENT_EPISODES)
        if (anchors.size >= MAX_CURRENT_EPISODES) return sortByAvailability(anchors)
        val filler = merged.filterNot { it.id in anchorIds }.take(MAX_CURRENT_EPISODES - anchors.size)
        return sortByAvailability(filler + anchors)
    }

    private fun episodeForShow(episode: ServusNewsEpisode, show: ServusShow): ServusNewsEpisode =
        ServusBranding.canonicalizeEpisode(
            episode.copy(
                showId = when (ServusNewsPolicy.contentKind(episode)) {
                    ServusContentKind.FULL_NEWS -> ServusBranding.NEWS_SHOW_ID
                    ServusContentKind.NEWS_90_SECONDS -> ServusBranding.NEWS_90_SECONDS_SHOW_ID
                    else -> episode.showId ?: show.id
                },
                showName = when (ServusNewsPolicy.contentKind(episode)) {
                    ServusContentKind.FULL_NEWS -> ServusBranding.NEWS_SHOW_NAME
                    ServusContentKind.NEWS_90_SECONDS -> ServusBranding.NEWS_90_SECONDS_SHOW_NAME
                    else -> episode.showName?.takeIf { it.isNotBlank() } ?: show.title
                },
                logoUri = episode.logoUri ?: show.logoUri,
                categoryId = episode.categoryId ?: show.categoryId,
                categoryTitle = episode.categoryTitle ?: show.categoryTitle,
            ),
        )

    private fun sortByAvailability(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> =
        episodes.sortedWith(
            compareByDescending<ServusNewsEpisode> {
                ServusNewsPolicy.recencyMillis(it) ?: Long.MIN_VALUE
            },
        )

    private companion object {
        const val PREFS_NAME = "servus_current_channel_selection"
        // Keep the old preference key so upgrades retain existing whole-show selections.
        const val KEY_SELECTED_SOURCE_KEYS = "selected_show_ids"
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

    fun applyCanonicalBranding(episodes: List<ServusNewsEpisode>): List<ServusNewsEpisode> =
        episodes.map(ServusBranding::canonicalizeEpisode)

    fun composeCurrentEpisodes(
        selectedShows: List<ServusShow>,
        allShows: List<ServusShow>,
        legacyEpisodes: List<ServusNewsEpisode>,
        limit: Int,
    ): List<ServusNewsEpisode> {
        if (limit <= 0 || selectedShows.isEmpty()) return emptyList()
        val filteredLegacy = legacyEpisodes.mapNotNull { rawEpisode ->
            val episode = ServusBranding.canonicalizeEpisode(rawEpisode)
            matchingSelectedShow(episode, selectedShows, allShows)?.let { show ->
                ServusBranding.canonicalizeEpisode(
                    episode.copy(
                        showId = episode.showId ?: show.id,
                        showName = episode.showName?.takeIf { it.isNotBlank() } ?: show.title,
                        logoUri = episode.logoUri ?: show.logoUri,
                        categoryId = episode.categoryId ?: show.categoryId,
                        categoryTitle = episode.categoryTitle ?: show.categoryTitle,
                    ),
                )
            }
        }
        val selectedCatalogueEpisodes = selectedShows.flatMap { it.episodes }
        return ServusNewsPolicy.deduplicateEpisodes(filteredLegacy + selectedCatalogueEpisodes).take(limit)
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
        return when (ServusNewsPolicy.contentKind(episode)) {
            ServusContentKind.NEWS_90_SECONDS -> {
                selectedShows.firstOrNull { it.id == ServusBranding.NEWS_90_SECONDS_SHOW_ID }
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
                show.id == ServusBranding.NEWS_SHOW_ID || run {
                    val title = normalize(show.title)
                    title.contains("servus nachrichten") && !title.contains("90 sekunden")
                }
            }
            ServusContentKind.WEGSCHEIDER -> selectedShows.firstOrNull {
                normalize(it.title).contains("wegscheider")
            }
            null -> {
                episode.showId?.let { showId ->
                    selectedShows.firstOrNull { it.id == showId }?.let { return it }
                }
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

    private fun normalize(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()
}
