package com.andreassamitsch.servusprovider.data

import android.content.Context

/**
 * Local opt-in for one Android-TV Preview Channel per ServusTV show.
 *
 * Selection changes carry a small persistent sync marker so the next TvProvider reconciliation also
 * removes legacy/opted-out channels even when the episode payload itself did not change.
 */
class ServusShowChannelSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedShowIds(): Set<String> =
        preferences.getStringSet(KEY_SELECTED_SHOW_IDS, emptySet()).orEmpty().toSet()

    fun effectiveSelectedShowIds(categories: List<ServusCategory>): Set<String> {
        val validIds = categories
            .asSequence()
            .flatMap { it.shows.asSequence() }
            .map { it.id }
            .toSet()
        return selectedShowIds().filterTo(linkedSetOf()) { it in validIds }
    }

    fun isSelected(showId: String): Boolean = showId in selectedShowIds()

    /** True on first run after the opt-in model is introduced and after every selection change. */
    fun needsTvProviderSync(): Boolean =
        !preferences.getBoolean(KEY_SYNC_INITIALIZED, false) ||
            preferences.getBoolean(KEY_SYNC_PENDING, false)

    fun setSelected(showId: String, selected: Boolean) {
        val ids = selectedShowIds().toMutableSet()
        val changed = if (selected) ids.add(showId) else ids.remove(showId)
        if (!changed) return
        preferences.edit()
            .putStringSet(KEY_SELECTED_SHOW_IDS, ids)
            .putBoolean(KEY_SYNC_PENDING, true)
            .apply()
    }

    fun markTvProviderSynced() {
        preferences.edit()
            .putBoolean(KEY_SYNC_INITIALIZED, true)
            .putBoolean(KEY_SYNC_PENDING, false)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "servus_show_channel_selection"
        const val KEY_SELECTED_SHOW_IDS = "selected_show_ids"
        const val KEY_SYNC_INITIALIZED = "tv_provider_sync_initialized"
        const val KEY_SYNC_PENDING = "tv_provider_sync_pending"
    }
}
