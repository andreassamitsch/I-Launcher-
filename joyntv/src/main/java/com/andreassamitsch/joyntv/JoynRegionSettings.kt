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
 *
 * Joyn authentication is tenant/market specific. Instead of discarding the current token on a
 * region change, we keep one token per market and swap the appropriate token into the legacy
 * `auth_token` slot used by JoynApiClient. This preserves backwards compatibility while allowing
 * AT, DE and CH to remain signed in at the same time.
 */
internal class JoynRegionSettings(context: Context) {
    private val appContext = context.applicationContext.also(::install)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentCountry(): JoynCountry = resolveCountry(Locale.getDefault().country)

    fun selectedCountry(): JoynCountry? = prefs.getString(KEY_COUNTRY_OVERRIDE, null)
        ?.let { value -> runCatching { JoynCountry.valueOf(value) }.getOrNull() }

    fun isAutomatic(): Boolean = selectedCountry() == null

    fun setCountry(country: JoynCountry?) {
        val previousCountry = currentCountry()
        val currentToken = prefs.getString(KEY_ACTIVE_AUTH_TOKEN, null)
        if (!currentToken.isNullOrBlank()) {
            prefs.edit().putString(authKey(previousCountry), currentToken).apply()
        }

        val editor = prefs.edit()
        if (country == null) editor.remove(KEY_COUNTRY_OVERRIDE)
        else editor.putString(KEY_COUNTRY_OVERRIDE, country.name)
        editor.apply()

        val nextCountry = currentCountry()
        val nextToken = prefs.getString(authKey(nextCountry), null)
        prefs.edit().apply {
            if (nextToken.isNullOrBlank()) remove(KEY_ACTIVE_AUTH_TOKEN)
            else putString(KEY_ACTIVE_AUTH_TOKEN, nextToken)
        }.apply()
    }

    /** Stores the currently active token as the persistent session for its market. */
    fun persistActiveSession(country: JoynCountry = currentCountry()) {
        val token = prefs.getString(KEY_ACTIVE_AUTH_TOKEN, null)
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(authKey(country))
            else putString(authKey(country), token)
        }.apply()
    }

    fun clearSession(country: JoynCountry) {
        val editor = prefs.edit().remove(authKey(country))
        if (country == currentCountry()) editor.remove(KEY_ACTIVE_AUTH_TOKEN)
        editor.apply()
    }

    fun sessionJson(country: JoynCountry): String? {
        if (country == currentCountry()) {
            prefs.getString(KEY_ACTIVE_AUTH_TOKEN, null)?.takeIf(String::isNotBlank)?.let { return it }
        }
        return prefs.getString(authKey(country), null)?.takeIf(String::isNotBlank)
    }

    fun saveSessionJson(country: JoynCountry, value: String) {
        if (value.isBlank()) {
            clearSession(country)
            return
        }
        val editor = prefs.edit().putString(authKey(country), value)
        if (country == currentCountry()) editor.putString(KEY_ACTIVE_AUTH_TOKEN, value)
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_COUNTRY_OVERRIDE = "country_override"
        private const val KEY_ACTIVE_AUTH_TOKEN = "auth_token"

        @Volatile
        private var installedContext: Context? = null

        fun install(context: Context) {
            installedContext = context.applicationContext
        }

        fun resolveCountry(localeCountry: String?): JoynCountry {
            val context = installedContext
            val override = context
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_COUNTRY_OVERRIDE, null)
                ?.let { runCatching { JoynCountry.valueOf(it) }.getOrNull() }
            if (override != null) return override

            return when (TimeZone.getDefault().id) {
                "Europe/Vienna" -> JoynCountry.AT
                "Europe/Zurich", "Europe/Busingen" -> JoynCountry.CH
                else -> when (localeCountry?.uppercase(Locale.ROOT)) {
                    "AT" -> JoynCountry.AT
                    "CH" -> JoynCountry.CH
                    else -> JoynCountry.DE
                }
            }
        }

        private fun authKey(country: JoynCountry): String = "auth_token_${country.name}"
    }
}
