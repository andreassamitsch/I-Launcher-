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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val PEOPLE_TAG = "TMDB_PEOPLE"

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
                    .allRelevantCredits()
                    .mapNotNull { it.toMediaItem(images.content) }
                    .distinctBy { media -> "${media.type}:${media.tmdbId}" }
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

    private fun TmdbCombinedCreditsDto.allRelevantCredits(): List<TmdbPersonCreditDto> =
        (cast + crew)
            .asSequence()
            .filter { !it.adult }
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .sortedWith(
                compareByDescending<TmdbPersonCreditDto> { it.popularity }
                    .thenByDescending { it.voteAverage },
            )
            .toList()

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