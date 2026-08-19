package com.andreassamitsch.ilauncher.data.cloudstream

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class CloudStreamLaunchMode {
    DirectPlay,
    SearchFallback,
}

enum class CloudStreamProviderSelection {
    Automatic,
    Choose,
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
    fun resolvedPackageName(): String? = directPackageName() ?: searchPackageName()

    fun isAvailable(): Boolean = resolvedPackageName() != null

    fun actionMode(item: MediaItem): CloudStreamLaunchMode? {
        if (directPackageName() != null) return CloudStreamLaunchMode.DirectPlay
        return if (searchPackageName() != null) CloudStreamLaunchMode.SearchFallback else null
    }

    fun launch(
        item: MediaItem,
        providerSelection: CloudStreamProviderSelection = CloudStreamProviderSelection.Automatic,
    ): CloudStreamLaunchMode? {
        val request = item.toCloudStreamMediaRequest()
        val directPackage = directPackageName()
        val mode: CloudStreamLaunchMode
        val targetPackage: String
        val intent = if (directPackage != null) {
            mode = CloudStreamLaunchMode.DirectPlay
            targetPackage = directPackage
            directPlayIntent(request, providerSelection).setPackage(directPackage)
        } else {
            val searchPackage = searchPackageName() ?: return null
            mode = CloudStreamLaunchMode.SearchFallback
            targetPackage = searchPackage
            searchIntent(request.title).setPackage(searchPackage)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        Log.d(TAG, "launch mode=$mode package=$targetPackage")
        return runCatching {
            context.startActivity(intent)
            mode
        }.onFailure { error ->
            Log.w(TAG, "launch failed mode=$mode package=$targetPackage", error)
        }.getOrNull()
    }

    private fun directPackageName(): String? = resolvePackage(
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("cloudstreamplay://v1?title=capability&type=movie"),
        ),
    )

    private fun searchPackageName(): String? = resolvePackage(searchIntent(query = "test"))

    private fun resolvePackage(intent: Intent): String? {
        val packageManager = context.packageManager

        // Android does not guarantee that queryIntentActivities() returns multiple CloudStream
        // variants in the order we want. Probe our known package variants explicitly first so
        // the I-Launcher bridge build wins over an installed official CloudStream app.
        val explicitKnownPackage = CLOUDSTREAM_PACKAGE_CANDIDATES.firstOrNull { packageName ->
            Intent(intent).setPackage(packageName).resolveActivity(packageManager) != null
        }
        if (explicitKnownPackage != null) return explicitKnownPackage

        val discovered = packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo.packageName }
            .toList()

        return selectPreferredCloudStreamPackage(discovered)
    }

    private fun directPlayIntent(
        request: CloudStreamMediaRequest,
        providerSelection: CloudStreamProviderSelection,
    ): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(buildCloudStreamPlayUri(request, providerSelection)),
    )

    private fun searchIntent(query: String): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("$SEARCH_SCHEME://${Uri.encode(normalizeCloudStreamTitle(query))}"),
    )

    private companion object {
        const val SEARCH_SCHEME = "cloudstreamsearch"
        const val TAG = "CLOUDSTREAM_BRIDGE"
    }
}

private const val CLOUDSTREAM_BASE_PACKAGE = "com.lagradost.cloudstream3"

internal val CLOUDSTREAM_PACKAGE_CANDIDATES = listOf(
    "$CLOUDSTREAM_BASE_PACKAGE.prerelease.debug",
    "$CLOUDSTREAM_BASE_PACKAGE.prerelease",
    "$CLOUDSTREAM_BASE_PACKAGE.debug",
    CLOUDSTREAM_BASE_PACKAGE,
)

internal fun selectPreferredCloudStreamPackage(packageNames: Iterable<String>): String? {
    val cloudStreamPackages = packageNames
        .filter { packageName ->
            packageName == CLOUDSTREAM_BASE_PACKAGE || packageName.startsWith("$CLOUDSTREAM_BASE_PACKAGE.")
        }
        .distinct()
        .toList()

    return CLOUDSTREAM_PACKAGE_CANDIDATES.firstOrNull(cloudStreamPackages::contains)
        ?: cloudStreamPackages.sorted().firstOrNull()
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

internal fun buildCloudStreamPlayUri(
    request: CloudStreamMediaRequest,
    providerSelection: CloudStreamProviderSelection = CloudStreamProviderSelection.Automatic,
): String {
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
        if (providerSelection == CloudStreamProviderSelection.Choose) add("selection" to "choose")
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
