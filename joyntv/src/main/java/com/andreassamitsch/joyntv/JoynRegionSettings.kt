package com.andreassamitsch.joyntv

import android.content.Context
import java.util.Locale
import java.util.TimeZone

/**
 * Resolves the Joyn market independently from the Android display language.
 *
 * Android TV devices in Austria are frequently configured as "Deutsch (Deutschland)",
 * which makes Locale.country return DE. A persisted app override therefore has priority;
 * automatic mode additionally considers the device timezone before falling back to Locale.
 */
internal class JoynRegionSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentCountry(): JoynCountry = selectedCountry() ?: automaticCountry()

    fun selectedCountry(): JoynCountry? = prefs.getString(KEY_COUNTRY_OVERRIDE, null)
        ?.let { value -> runCatching { JoynCountry.valueOf(value) }.getOrNull() }

    fun isAutomatic(): Boolean = selectedCountry() == null

    fun setCountry(country: JoynCountry?) {
        val editor = prefs.edit()
        if (country == null) editor.remove(KEY_COUNTRY_OVERRIDE)
        else editor.putString(KEY_COUNTRY_OVERRIDE, country.name)

        // Auth tokens are tenant/market specific. Never carry a DE session into AT/CH or vice versa.
        editor.remove("auth_token").apply()
    }

    private fun automaticCountry(): JoynCountry {
        return when (TimeZone.getDefault().id) {
            "Europe/Vienna" -> JoynCountry.AT
            "Europe/Zurich", "Europe/Busingen" -> JoynCountry.CH
            else -> JoynCountry.fromIsoCountry(Locale.getDefault().country)
        }
    }

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_COUNTRY_OVERRIDE = "country_override"
    }
}
