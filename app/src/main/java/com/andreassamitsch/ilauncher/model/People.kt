package com.andreassamitsch.ilauncher.model

data class MediaPerson(
    val tmdbId: Int,
    val name: String,
    val role: String? = null,
    val profileUri: String? = null,
)

data class MediaCredits(
    val cast: List<MediaPerson> = emptyList(),
    val directors: List<MediaPerson> = emptyList(),
)

data class PersonDetails(
    val tmdbId: Int,
    val name: String,
    val biography: String? = null,
    val profileUri: String? = null,
    val knownForDepartment: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    val works: List<MediaItem> = emptyList(),
)