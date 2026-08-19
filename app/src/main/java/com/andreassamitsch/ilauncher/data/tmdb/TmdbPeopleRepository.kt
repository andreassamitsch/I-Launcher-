package com.andreassamitsch.ilauncher.data.tmdb

import android.content.Context
import android.util.Log
import com.andreassamitsch.ilauncher.BuildConfig
import com.andreassamitsch.ilauncher.model.MediaCredits
import com.andreassamitsch.ilauncher.model.MediaItem
import com.andreassamitsch.ilauncher.model.MediaPerson
import com.andreassamitsch.ilauncher.model.MediaSource
import com.andreassamitsch.ilauncher.model.MediaType
import com.andreassamitsch.ilauncher.model.PersonDetails
import java.util.Locale
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val PEOPLE_TAG = "TMDB_PEOPLE"
private val NON_NARRATIVE_TV_GENRE_IDS = setOf(10763, 10764, 10767) // News, Reality, Talk
private val SELF_APPEARANCE_EXACT = setOf("self", "himself", "herself", "themselves")
private val SELF_APPEARANCE_PREFIXES = listOf(
    "self -",
    "self –",
    "self —",
    "self (",
    "himself -",
    "himself –",
    "himself —",
    "himself (",
    "herself -",
    "herself –",
    "herself —",
    "herself (",
    "themselves -",
    "themselves –",
    "themselves —",
    "themselves (",
)
private val GENERIC_SHOW_APPEARANCE_EXACT = setOf(
    "guest",
    "guest host",
    "interviewee",
    "presenter",
    "contestant",
    "panelist",
    "correspondent",
)

internal fun TmdbCombinedCreditsDto.rankedRelevantPersonCredits(
    knownForDepartment: String?,
): List<TmdbPersonCreditDto> {
    val candidates = when {
        knownForDepartment.equals("Acting", ignoreCase = true) -> cast
        knownForDepartment.equals("Directing", ignoreCase = true) -> crew.filter(TmdbPersonCreditDto::isDirectorCredit)
        else -> cast + crew
    }

    return candidates
        .asSequence()
        .filter(TmdbPersonCreditDto::isDisplayablePersonWork)
        .sortedWith(
            compareByDescending<TmdbPersonCreditDto> { it.personWorkRecognitionScore() }
                .thenByDescending { it.voteCount }
                .thenByDescending { it.popularity }
                .thenByDescending { it.voteAverage },
        )
        .distinctBy { credit -> "${credit.mediaType}:${credit.id}" }
        .toList()
}

internal fun TmdbPersonCreditDto.isDisplayablePersonWork(): Boolean {
    if (adult || id <= 0) return false
    val hasValidTitle = when (mediaType) {
        "movie" -> !title.isNullOrBlank()
        "tv" -> !name.isNullOrBlank()
        else -> false
    }
    if (!hasValidTitle) return false

    // A person's filmography should show actual acting/directing works, not talk-show,
    // reality or documentary-style self appearances that TMDB also returns as credits.
    if (character.isSelfAppearanceRole()) return false
    if (mediaType == "tv") {
        if (genreIds.any(NON_NARRATIVE_TV_GENRE_IDS::contains)) return false
        if (character.isGenericShowAppearanceRole()) return false
    }
    return true
}

internal fun TmdbPersonCreditDto.personWorkRecognitionScore(): Double {
    // Title recognition remains useful, but it must not let a one-episode appearance in a giant
    // series outrank a person's defining work. TMDB combined credits also exposes movie billing
    // order and TV episode count; use those bounded person-specific signals alongside title fame.
    val voteSignal = ln(voteCount.coerceAtLeast(0).toDouble() + 1.0) * 9.0
    val popularitySignal = ln(popularity.coerceAtLeast(0.0) + 1.0) * 7.0
    val ratingSignal = voteAverage.coerceIn(0.0, 10.0) * 0.35
    val artworkSignal = if (!posterPath.isNullOrBlank() || !backdropPath.isNullOrBlank()) 0.5 else 0.0
    return voteSignal + popularitySignal + ratingSignal + artworkSignal + personRoleProminenceScore()
}

private fun TmdbPersonCreditDto.personRoleProminenceScore(): Double = when (mediaType) {
    "movie" -> when (val billingOrder = order) {
        null -> 0.0
        0 -> 36.0
        1 -> 30.0
        in 2..3 -> 24.0
        in 4..5 -> 16.0
        in 6..9 -> 8.0
        in 10..19 -> -4.0
        else -> -12.0
    }
    "tv" -> when (val episodes = episodeCount) {
        null -> 0.0
        in 24..Int.MAX_VALUE -> 32.0
        in 12..23 -> 26.0
        in 6..11 -> 20.0
        in 3..5 -> 12.0
        2 -> 6.0
        1 -> -24.0
        else -> -8.0
    }
    else -> 0.0
}

private fun TmdbPersonCreditDto.isDirectorCredit(): Boolean =
    job?.contains("director", ignoreCase = true) == true

private fun String?.isSelfAppearanceRole(): Boolean {
    val normalized = this?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized in SELF_APPEARANCE_EXACT ||
        SELF_APPEARANCE_PREFIXES.any(normalized::startsWith)
}

private fun String?.isGenericShowAppearanceRole(): Boolean {
    val normalized = this?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized in GENERIC_SHOW_APPEARANCE_EXACT
}

class TmdbPeopleRepository(
    context: Context,
    readAccessToken: String = BuildConfig.TMDB_READ_ACCESS_TOKEN,
) {
    private val appContext = context.applicationContext
    private val network = TmdbNetworkClient(readAccessToken)
    private val imageConfigurationStore = TmdbImageConfigurationStore(appContext)
    private val creditsCache = LinkedHashMap<String, CachedCredits>()
    private val personCache = LinkedHashMap<Int, CachedPerson>()
    private var profileImages: ProfileImageConfiguration? = null

    suspend fun loadCredits(item: MediaItem): MediaCredits = withContext(Dispatchers.IO) {
        val tmdbId = item.tmdbId ?: return@withContext MediaCredits()
        val creditsType = when (item.type) {
            MediaType.Movie -> MediaType.Movie
            MediaType.Series,
            MediaType.Episode,
            -> MediaType.Series
            MediaType.Unknown -> return@withContext MediaCredits()
        }
        if (!network.isConfigured) return@withContext MediaCredits()

        val key = "${creditsType.name}:$tmdbId"
        val now = System.currentTimeMillis()
        creditsCache[key]
            ?.takeIf { now - it.updatedAtUtcMillis <= CREDITS_CACHE_MILLIS }
            ?.let { return@withContext it.credits }

        val credits = runCatching {
            val images = ensureImageConfigurations(now)
            val dto = when (creditsType) {
                MediaType.Movie -> network.api.movieCredits(tmdbId, LANGUAGE)
                MediaType.Series -> network.api.tvAggregateCredits(tmdbId, LANGUAGE)
                else -> TmdbCreditsDto()
            }
            dto.toMediaCredits(images.profile)
        }.onFailure { throwable ->
            Log.w(PEOPLE_TAG, "TMDB credits failed (${throwable.javaClass.simpleName})")
        }.getOrDefault(MediaCredits())

        creditsCache[key] = CachedCredits(credits, now)
        trimCache(creditsCache, MAX_CREDITS_CACHE_ENTRIES)
        credits
    }

    suspend fun loadPerson(personId: Int): PersonDetails? = withContext(Dispatchers.IO) {
        if (!network.isConfigured || personId <= 0) return@withContext null
        val now = System.currentTimeMillis()
        personCache[personId]
            ?.takeIf { now - it.updatedAtUtcMillis <= PERSON_CACHE_MILLIS }
            ?.let { return@withContext it.person }

        val person = runCatching {
            coroutineScope {
                val detailsDeferred = async { network.api.personDetails(personId, LANGUAGE) }
                val creditsDeferred = async { network.api.personCombinedCredits(personId, LANGUAGE) }
                val imagesDeferred = async { ensureImageConfigurations(now) }

                val details = detailsDeferred.await()
                val combinedCredits = creditsDeferred.await()
                val images = imagesDeferred.await()
                val displayName = details.name?.takeIf(String::isNotBlank) ?: return@coroutineScope null
                val works = combinedCredits
                    .rankedRelevantPersonCredits(details.knownForDepartment)
                    .mapNotNull { it.toMediaItem(images.content) }
                    .take(PERSON_WORK_LIMIT)

                PersonDetails(
                    tmdbId = details.id.takeIf { it > 0 } ?: personId,
                    name = displayName,
                    biography = details.biography?.takeIf(String::isNotBlank),
                    profileUri = images.profile?.url(details.profilePath),
                    knownForDepartment = details.knownForDepartment?.takeIf(String::isNotBlank),
                    birthday = details.birthday?.takeIf(String::isNotBlank),
                    deathday = details.deathday?.takeIf(String::isNotBlank),
                    placeOfBirth = details.placeOfBirth?.takeIf(String::isNotBlank),
                    works = works,
                )
            }
        }.onFailure { throwable ->
            Log.w(PEOPLE_TAG, "TMDB person failed (${throwable.javaClass.simpleName})")
        }.getOrNull() ?: return@withContext null

        personCache[personId] = CachedPerson(person, now)
        trimCache(personCache, MAX_PERSON_CACHE_ENTRIES)
        person
    }

    private suspend fun ensureImageConfigurations(now: Long): ImageConfigurations {
        val cachedContent = imageConfigurationStore.loadFresh(now)
        val cachedProfile = profileImages
        if (cachedContent != null && cachedProfile != null) {
            return ImageConfigurations(cachedContent, cachedProfile)
        }

        return runCatching {
            val dto = network.api.configuration().images
            val content = cachedContent ?: imageConfigurationStore.save(dto, now)
            val profile = cachedProfile ?: ProfileImageConfiguration(
                secureBaseUrl = dto.secureBaseUrl,
                size = chooseProfileImageSize(dto.profileSizes),
            ).also { profileImages = it }
            ImageConfigurations(content, profile)
        }.onFailure { throwable ->
            Log.w(PEOPLE_TAG, "TMDB people image configuration failed (${throwable.javaClass.simpleName})")
        }.getOrElse {
            ImageConfigurations(cachedContent, cachedProfile)
        }
    }

    private fun TmdbCreditsDto.toMediaCredits(profile: ProfileImageConfiguration?): MediaCredits {
        val castPeople = cast
            .asSequence()
            .filter { it.id > 0 && !it.name.isNullOrBlank() }
            .map { member ->
                MediaPerson(
                    tmdbId = member.id,
                    name = requireNotNull(member.name),
                    role = member.character
                        ?.takeIf(String::isNotBlank)
                        ?: member.roles
                            .sortedByDescending(TmdbCreditRoleDto::episodeCount)
                            .mapNotNull(TmdbCreditRoleDto::character)
                            .firstOrNull(String::isNotBlank),
                    profileUri = profile?.url(member.profilePath),
                )
            }
            .distinctBy(MediaPerson::tmdbId)
            .take(CAST_LIMIT)
            .toList()

        val directors = crew
            .asSequence()
            .filter { member ->
                member.job.equals("Director", ignoreCase = true) ||
                    member.jobs.any { it.job.equals("Director", ignoreCase = true) }
            }
            .filter { it.id > 0 && !it.name.isNullOrBlank() }
            .map { member ->
                MediaPerson(
                    tmdbId = member.id,
                    name = requireNotNull(member.name),
                    role = "Regie",
                    profileUri = profile?.url(member.profilePath),
                )
            }
            .distinctBy(MediaPerson::tmdbId)
            .take(DIRECTOR_LIMIT)
            .toList()

        return MediaCredits(cast = castPeople, directors = directors)
    }

    private fun TmdbPersonCreditDto.toMediaItem(images: TmdbImageConfiguration?): MediaItem? {
        val type = when (mediaType) {
            "movie" -> MediaType.Movie
            "tv" -> MediaType.Series
            else -> return null
        }
        val displayTitle = when (type) {
            MediaType.Movie -> title
            MediaType.Series -> name
            else -> null
        }?.takeIf(String::isNotBlank) ?: return null
        val original = when (type) {
            MediaType.Movie -> originalTitle
            MediaType.Series -> originalName
            else -> null
        }
        return MediaItem(
            id = "tmdb:person:${type.name}:$id",
            type = type,
            title = displayTitle,
            originalTitle = original,
            overview = overview,
            releaseYear = TmdbRepository.yearOf(releaseDate ?: firstAirDate),
            tmdbId = id,
            posterUri = images?.url(TmdbImageKind.Poster, posterPath),
            backdropUri = images?.url(TmdbImageKind.Backdrop, backdropPath),
            voteAverage = voteAverage.takeIf { it > 0.0 },
            source = MediaSource(
                provider = "tmdb_people",
                sourceId = "tmdb:${type.name}:$id",
            ),
            resolverConfidence = 1f,
        )
    }

    private fun chooseProfileImageSize(sizes: List<String>): String =
        sizes.firstOrNull { it.equals("h632", ignoreCase = true) }
            ?: sizes
                .mapNotNull { size -> size.removePrefix("w").toIntOrNull()?.let { it to size } }
                .filter { (width, _) -> width >= 185 }
                .minByOrNull { (width, _) -> width }
                ?.second
            ?: sizes.firstOrNull { it == "original" }
            ?: "original"

    private fun <K, V> trimCache(cache: LinkedHashMap<K, V>, maxEntries: Int) {
        while (cache.size > maxEntries) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
    }

    private data class ImageConfigurations(
        val content: TmdbImageConfiguration?,
        val profile: ProfileImageConfiguration?,
    )

    private data class ProfileImageConfiguration(
        val secureBaseUrl: String,
        val size: String,
    ) {
        fun url(path: String?): String? {
            val safePath = path?.takeIf(String::isNotBlank) ?: return null
            return "${secureBaseUrl.trimEnd('/')}/$size/${safePath.trimStart('/')}"
        }
    }

    private data class CachedCredits(
        val credits: MediaCredits,
        val updatedAtUtcMillis: Long,
    )

    private data class CachedPerson(
        val person: PersonDetails,
        val updatedAtUtcMillis: Long,
    )

    private companion object {
        const val LANGUAGE = "de-DE"
        const val CAST_LIMIT = 18
        const val DIRECTOR_LIMIT = 8
        const val PERSON_WORK_LIMIT = 30
        const val CREDITS_CACHE_MILLIS = 6L * 60L * 60L * 1_000L
        const val PERSON_CACHE_MILLIS = 6L * 60L * 60L * 1_000L
        const val MAX_CREDITS_CACHE_ENTRIES = 48
        const val MAX_PERSON_CACHE_ENTRIES = 24
    }
}
