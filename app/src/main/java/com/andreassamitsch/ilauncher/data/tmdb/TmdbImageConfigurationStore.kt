package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context

internal enum class TmdbImageKind {
    Poster,
    Backdrop,
    Logo,
    Still,
}

internal data class TmdbImageConfiguration(
    val secureBaseUrl: String,
    val posterSize: String,
    val backdropSize: String,
    val logoSize: String,
    val stillSize: String,
    val updatedAtUtcMillis: Long,
) {
    fun url(kind: TmdbImageKind, path: String?): String? {
        val safePath = path?.takeIf { it.isNotBlank() } ?: return null
        val size = when (kind) {
            TmdbImageKind.Poster -> posterSize
            TmdbImageKind.Backdrop -> backdropSize
            TmdbImageKind.Logo -> logoSize
            TmdbImageKind.Still -> stillSize
        }
        return "${secureBaseUrl.trimEnd('/')}/$size/${safePath.trimStart('/')}"
    }
}

internal class TmdbImageConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tmdb_image_configuration",
        Context.MODE_PRIVATE,
    )

    fun loadFresh(nowUtcMillis: Long): TmdbImageConfiguration? {
        val updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L)
        if (updatedAt <= 0L || nowUtcMillis - updatedAt > MAX_AGE_MILLIS) return null

        val baseUrl = preferences.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return TmdbImageConfiguration(
            secureBaseUrl = baseUrl,
            posterSize = preferences.getString(KEY_POSTER_SIZE, null) ?: return null,
            backdropSize = preferences.getString(KEY_BACKDROP_SIZE, null) ?: return null,
            logoSize = preferences.getString(KEY_LOGO_SIZE, null) ?: return null,
            stillSize = preferences.getString(KEY_STILL_SIZE, null) ?: return null,
            updatedAtUtcMillis = updatedAt,
        )
    }

    fun save(
        dto: TmdbImageConfigurationDto,
        nowUtcMillis: Long,
    ): TmdbImageConfiguration {
        val configuration = TmdbImageConfiguration(
            secureBaseUrl = dto.secureBaseUrl,
            posterSize = chooseSize(dto.posterSizes, preferredWidth = 500),
            backdropSize = chooseSize(dto.backdropSizes, preferredWidth = 780),
            logoSize = chooseSize(dto.logoSizes, preferredWidth = 500),
            stillSize = chooseSize(dto.stillSizes, preferredWidth = 300),
            updatedAtUtcMillis = nowUtcMillis,
        )

        preferences.edit()
            .putString(KEY_BASE_URL, configuration.secureBaseUrl)
            .putString(KEY_POSTER_SIZE, configuration.posterSize)
            .putString(KEY_BACKDROP_SIZE, configuration.backdropSize)
            .putString(KEY_LOGO_SIZE, configuration.logoSize)
            .putString(KEY_STILL_SIZE, configuration.stillSize)
            .putLong(KEY_UPDATED_AT, configuration.updatedAtUtcMillis)
            .apply()

        return configuration
    }

    private fun chooseSize(sizes: List<String>, preferredWidth: Int): String {
        val numeric = sizes.mapNotNull { size ->
            size.removePrefix("w").toIntOrNull()?.let { width -> width to size }
        }
        return numeric
            .filter { (width, _) -> width >= preferredWidth }
            .minByOrNull { (width, _) -> width }
            ?.second
            ?: numeric.maxByOrNull { (width, _) -> width }?.second
            ?: sizes.firstOrNull { it == "original" }
            ?: "original"
    }

    companion object {
        private const val MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        private const val KEY_BASE_URL = "secure_base_url"
        private const val KEY_POSTER_SIZE = "poster_size"
        private const val KEY_BACKDROP_SIZE = "backdrop_size"
        private const val KEY_LOGO_SIZE = "logo_size"
        private const val KEY_STILL_SIZE = "still_size"
        private const val KEY_UPDATED_AT = "updated_at_utc_millis"
    }
}
