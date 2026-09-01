package com.andreassamitsch.servusprovider.data

import android.content.Context

/**
 * Tracks when a content ID is first observed during the periodic refresh.
 *
 * The first successful refresh after this store is introduced is only a baseline: already existing
 * catalogue items must not suddenly receive the app-update time as their online time. On later
 * refreshes, newly appearing IDs can be labelled as "online erkannt" with an accuracy bounded by
 * the WorkManager refresh interval.
 */
class ServusObservedAvailabilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isInitialized(): Boolean = preferences.getBoolean(KEY_INITIALIZED, false)

    fun annotateNewlyObserved(
        episodes: List<ServusNewsEpisode>,
        observedAtMillis: Long,
        detectNewItems: Boolean,
    ): List<ServusNewsEpisode> {
        if (episodes.isEmpty()) return episodes
        val seenIds = preferences.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty()
        val newlyObserved = if (detectNewItems) {
            episodes.map { it.id }.filterNot(seenIds::contains).toSet()
        } else {
            emptySet()
        }
        if (newlyObserved.isNotEmpty()) {
            preferences.edit().apply {
                newlyObserved.forEach { id -> putLong(observedKey(id), observedAtMillis) }
            }.apply()
        }
        return episodes.map { episode ->
            val persisted = preferences.getLong(observedKey(episode.id), 0L).takeIf { it > 0L }
            episode.copy(
                observedAvailableAtMillis = episode.observedAvailableAtMillis
                    ?: persisted
                    ?: observedAtMillis.takeIf { episode.id in newlyObserved },
            )
        }
    }

    /** Adds current items to the known baseline without assigning an observation time. */
    fun baseline(episodes: List<ServusNewsEpisode>) {
        if (episodes.isEmpty()) return
        val seen = preferences.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty().toMutableSet()
        seen += episodes.map { it.id }
        preferences.edit().putStringSet(KEY_SEEN_IDS, seen).apply()
    }

    /** Completes one successful refresh and makes future unseen IDs eligible for observation time. */
    fun finishSuccessfulRefresh(episodes: List<ServusNewsEpisode>) {
        val seen = preferences.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty().toMutableSet()
        seen += episodes.map { it.id }
        preferences.edit()
            .putStringSet(KEY_SEEN_IDS, seen)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }

    private fun observedKey(id: String): String = "$KEY_OBSERVED_PREFIX$id"

    private companion object {
        const val PREFS_NAME = "servus_observed_availability"
        const val KEY_INITIALIZED = "initialized"
        const val KEY_SEEN_IDS = "seen_ids"
        const val KEY_OBSERVED_PREFIX = "observed:"
    }
}
