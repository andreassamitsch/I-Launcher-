package com.andreassamitsch.ilauncher.ui.livetv

import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.andreassamitsch.ilauncher.data.livetv.LiveTvAudioLanguage
import java.util.Locale

/** Immutable snapshot: the group indices are valid only for the currently prepared Media3 item. */
internal data class LiveTvAudioTrack(
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String?,
    val label: String?,
    val roleFlags: Int,
    val selectionFlags: Int,
    val channelCount: Int,
    val supported: Boolean,
    val selected: Boolean,
)

internal object LiveTvAudioSelection {
    // Real-world Enigma2 stream labels are often more descriptive than stream role flags.
    private val specialLabel = Regex(
        "hörfilm|hoerfilm|audiodeskription|audio.?description|descriptive|hörgeschädigt|" +
            "hoergeschaedigt|gehörlos|gehoerlos|hearing.?impaired|visually.?impaired|" +
            "kommentar|commentary",
        RegexOption.IGNORE_CASE,
    )
    private const val SPECIAL_ROLES =
        C.ROLE_FLAG_DESCRIBES_VIDEO or C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_SUPPLEMENTARY

    fun fromMedia3(tracks: Tracks): List<LiveTvAudioTrack> = buildList {
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                add(
                    LiveTvAudioTrack(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        language = format.language,
                        label = format.label,
                        roleFlags = format.roleFlags,
                        selectionFlags = format.selectionFlags,
                        channelCount = format.channelCount,
                        supported = group.isTrackSupported(trackIndex),
                        selected = group.isTrackSelected(trackIndex),
                    ),
                )
            }
        }
    }

    fun isSpecial(track: LiveTvAudioTrack): Boolean =
        (track.roleFlags and SPECIAL_ROLES) != 0 ||
            specialLabel.containsMatchIn(track.label.orEmpty())

    fun chooseAutomatic(
        tracks: List<LiveTvAudioTrack>,
        preferredLanguage: LiveTvAudioLanguage,
        avoidSpecialTracks: Boolean,
    ): LiveTvAudioTrack? {
        // In the unmodified automatic mode Media3/receiver metadata decides.
        if (preferredLanguage == LiveTvAudioLanguage.Automatic && !avoidSpecialTracks) return null
        val supported = tracks.filter { it.supported }
        if (supported.isEmpty()) return null
        val matchingLanguage = preferredLanguage.languageTag?.let { language ->
            supported.filter { normalizeLanguage(it.language) == language }
        }.orEmpty()
        val languageCandidates = matchingLanguage.ifEmpty { supported }
        val standardCandidates = if (avoidSpecialTracks) {
            languageCandidates.filterNot(::isSpecial).ifEmpty { languageCandidates }
        } else {
            languageCandidates
        }
        return standardCandidates.sortedWith(
            compareByDescending<LiveTvAudioTrack> {
                (it.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
            }.thenByDescending {
                (it.roleFlags and C.ROLE_FLAG_MAIN) != 0
            }.thenBy { it.groupIndex }
                .thenBy { it.trackIndex },
        ).firstOrNull()
    }

    fun findManual(
        tracks: List<LiveTvAudioTrack>,
        previous: LiveTvAudioTrack,
    ): LiveTvAudioTrack? = tracks.firstOrNull {
        it.supported &&
            it.groupIndex == previous.groupIndex &&
            it.trackIndex == previous.trackIndex &&
            normalizeLanguage(it.language) == normalizeLanguage(previous.language) &&
            it.roleFlags == previous.roleFlags
    }

    fun displayLabel(track: LiveTvAudioTrack, number: Int): String {
        val language = when (normalizeLanguage(track.language)) {
            "de" -> "Deutsch"
            "en" -> "Englisch"
            "und", "" -> "Sprache unbekannt"
            else -> Locale.forLanguageTag(track.language.orEmpty())
                .getDisplayLanguage(Locale.GERMAN)
                .ifBlank { track.language.orEmpty() }
        }
        val role = track.label?.takeIf { it.isNotBlank() } ?: when {
            (track.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0 -> "Audiodeskription"
            (track.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0 -> "Kommentar"
            else -> "Tonspur " + number
        }
        val channels = when (track.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        return listOfNotNull(language, role, channels).distinct().joinToString(" · ")
    }

    private fun normalizeLanguage(value: String?): String = when (
        value?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
    ) {
        "de", "deu", "ger" -> "de"
        "en", "eng" -> "en"
        else -> value?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')
            ?.substringBefore('_').orEmpty()
    }
}
