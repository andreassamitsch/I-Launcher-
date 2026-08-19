package com.andreassamitsch.ilauncher.model

data class AppContentProgram(
    val sourceOrder: Int,
    val media: MediaItem,
    val weight: Int? = null,
)

data class AppContentChannel(
    val id: String,
    val sourceOrder: Int,
    val packageName: String?,
    val title: String,
    val appLinkIntentUri: String?,
    val programs: List<AppContentProgram>,
)

data class AppContentChannelsLoadResult(
    val channels: List<AppContentChannel>,
    val queriedChannelCount: Int = channels.size,
    val systemBrowsableChannelCount: Int = channels.size,
    val queriedProgramCount: Int = channels.sumOf { it.programs.size },
    val errorMessage: String? = null,
) {
    val isAvailable: Boolean
        get() = errorMessage == null

    val usableChannelCount: Int
        get() = channels.count { it.programs.isNotEmpty() }

    val displayableProgramCount: Int
        get() = channels.sumOf { it.programs.size }
}
