package com.andreassamitsch.servusprovider.data

import android.content.Context

/** Local opt-in for one Android-TV Preview Channel per ServusTV show. */
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

    fun setSelected(showId: String, selected: Boolean) {
        val ids = selectedShowIds().toMutableSet()
        if (selected) ids += showId else ids -= showId
        preferences.edit().putStringSet(KEY_SELECTED_SHOW_IDS, ids).apply()
    }

    private companion object {
        const val PREFS_NAME = "servus_show_channel_selection"
        const val KEY_SELECTED_SHOW_IDS = "selected_show_ids"
    }
}
