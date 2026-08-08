package com.andreassamitsch.ilauncher.data.tv

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchNextSourcePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _hiddenPackages = MutableStateFlow(loadHiddenPackages())
    val hiddenPackages: StateFlow<Set<String>> = _hiddenPackages.asStateFlow()

    fun setVisible(packageName: String, visible: Boolean) {
        val updated = _hiddenPackages.value.toMutableSet()
        if (visible) {
            updated.remove(packageName)
        } else {
            updated.add(packageName)
        }
        preferences.edit().putStringSet(KEY_HIDDEN_PACKAGES, updated).apply()
        _hiddenPackages.value = updated.toSet()
    }

    fun showAll() {
        preferences.edit().remove(KEY_HIDDEN_PACKAGES).apply()
        _hiddenPackages.value = emptySet()
    }

    private fun loadHiddenPackages(): Set<String> =
        preferences.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toSet().orEmpty()

    private companion object {
        const val PREFS_NAME = "watch_next_sources"
        const val KEY_HIDDEN_PACKAGES = "hidden_packages"
    }
}
