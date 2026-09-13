package com.andreassamitsch.ilauncher.data.oscam

import android.content.Context

internal data class OscamConfig(
    val port: Int = DEFAULT_OSCAM_WEBIF_PORT,
    val username: String = "",
    val password: String = "",
)

internal const val DEFAULT_OSCAM_WEBIF_PORT = 8888

internal class OscamStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): OscamConfig = OscamConfig(
        port = preferences.getInt(KEY_PORT, DEFAULT_OSCAM_WEBIF_PORT)
            .takeIf { it in 1..65535 }
            ?: DEFAULT_OSCAM_WEBIF_PORT,
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
    )

    fun save(port: Int, username: String, password: String) {
        require(port in 1..65535) { "Invalid OSCam WebIf port" }
        preferences.edit()
            .putInt(KEY_PORT, port)
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "oscam_webif"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
    }
}
