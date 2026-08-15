package com.andreassamitsch.ilauncher.data.cloudstream

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class CloudStreamLaunchMode {
    DirectPlay,
    SearchFallback,
}

data class CloudStreamMediaRequest(
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val type: MediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val tmdbId: Int? = null,
    val tmdbEpisodeId: Int? = null,
    val imdbId: String? = null,
)

class CloudStreamLauncher(private val context: Context) {
    fun resolvedPackageName(): String? = resolvePackage(searchIntent(query = "test"))

    fun isAvailable(): Boolean = resolvedPackageName() != null

    fun actionMode(item: MediaItem): CloudStreamLaunchMode? {
        val packageName = resolvedPackageName() ?: return null
        val request = item.toCloudStreamMediaRequest()
        return if (directPlayIntent(request).setPackage(packageName).resolveActivity(context.packageManager) != null) {
            CloudStreamLaunchMode.DirectPlay
        } else {
            CloudStreamLaunchMode.SearchFallback
        }
    }

    fun launch(item: MediaItem): CloudStreamLaunchMode? {
        val packageName = resolvedPackageName() ?: return null
        val request = item.toCloudStreamMediaRequest()
        val directIntent = directPlayIntent(request).setPackage(packageName)
        val mode: CloudStreamLaunchMode
        val intent = if (directIntent.resolveActivity(context.packageManager) != null) {
            mode = CloudStreamLaunchMode.DirectPlay
            directIntent
        } else {
            mode = CloudStreamLaunchMode.SearchFallback
            searchIntent(request.title).setPackage(packageName)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(intent)
            mode
        }.getOrNull()
    }

    private fun resolvePackage(intent: Intent): String? {
        val packageManager = context.packageManager
        val discovered = packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo.packageName }
            .firstOrNull { it == BASE_PACKAGE || it.startsWith("$BASE_PACKAGE.") }
        if (discovered != null) return discovered

        return PACKAGE_CANDIDATES.firstOrNull { packageName ->
            intent.setPackage(packageName).resolveActivity(packageManager) != null
        }
    }

    private fun directPlayIntent(request: CloudStreamMediaRequest): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(buildCloudStreamPlayUri(request)),
    )

    private fun searchIntent(query: String): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("$SEARCH_SCHEME://${Uri.encode(normalizeCloudStreamTitle(query))}"),
    )

    private companion object {
        const val SEARCH_SCHEME = "cloudstreamsearch"
        const val BASE_PACKAGE = "com.lagradost.cloudstream3"
        val PACKAGE_CANDIDATES = listOf(
            BASE_PACKAGE,
            "$BASE_PACKAGE.prerelease",
            "$BASE_PACKAGE.debug",
            "$BASE_PACKAGE.prerelease.debug",
        )
    }
}

internal fun MediaItem.toCloudStreamMediaRequest(): CloudStreamMediaRequest = CloudStreamMediaRequest(
    title = normalizeCloudStreamTitle(title),
    originalTitle = originalTitle?.let(::normalizeCloudStreamTitle)?.takeIf(String::isNotBlank),
    year = releaseYear,
    type = type,
    season = seasonNumber,
    episode = episodeNumber,
    episodeTitle = episodeTitle?.let(::normalizeCloudStreamTitle)?.takeIf(String::isNotBlank),
    tmdbId = tmdbId,
    tmdbEpisodeId = tmdbEpisodeId,
    imdbId = imdbId?.trim()?.takeIf(String::isNotBlank),
)

internal fun buildCloudStreamPlayUri(request: CloudStreamMediaRequest): String {
    val parameters = buildList {
        add("title" to normalizeCloudStreamTitle(request.title))
        request.originalTitle?.let(::normalizeCloudStreamTitle)?.takeIf(String::isNotBlank)?.let { add("originalTitle" to it) }
        request.year?.let { add("year" to it.toString()) }
        add("type" to request.type.cloudStreamValue())
        request.season?.let { add("season" to it.toString()) }
        request.episode?.let { add("episode" to it.toString()) }
        request.episodeTitle?.let(::normalizeCloudStreamTitle)?.takeIf(String::isNotBlank)?.let { add("episodeTitle" to it) }
        request.tmdbId?.let { add("tmdbId" to it.toString()) }
        request.tmdbEpisodeId?.let { add("tmdbEpisodeId" to it.toString()) }
        request.imdbId?.trim()?.takeIf(String::isNotBlank)?.let { add("imdbId" to it) }
    }
    return buildString {
        append("cloudstreamplay://v1")
        if (parameters.isNotEmpty()) {
            append('?')
            append(parameters.joinToString("&") { (key, value) -> "$key=${encodeQueryValue(value)}" })
        }
    }
}

internal fun normalizeCloudStreamTitle(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")

private fun MediaType.cloudStreamValue(): String = when (this) {
    MediaType.Movie -> "movie"
    MediaType.Series -> "series"
    MediaType.Episode -> "episode"
    MediaType.Unknown -> "unknown"
}

private fun encodeQueryValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
