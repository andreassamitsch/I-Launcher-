package com.andreassamitsch.ilauncher.ui.livetv

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.andreassamitsch.ilauncher.data.livetv.LiveTvAudioLanguage
import com.andreassamitsch.ilauncher.data.livetv.LiveTvAudioQuality
import java.util.Locale

/** Track indices belong to the currently prepared media item and must not survive a source switch. */
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
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    val bitrate: Int = C.NO_VALUE,
)

internal object LiveTvAudioSelection {
    private val specialLabel = Regex(
        "hörfilm|hoerfilm|audiodeskription|audio.?description|descriptive|hörgeschädigt|" +
            "hoergeschaedigt|gehörlos|gehoerlos|hearing.?impaired|visually.?impaired|" +
            "kommentar|commentary",
        RegexOption.IGNORE_CASE,
    )
    private const val SPECIAL_ROLES =
        C.ROLE_FLAG_DESCRIBES_VIDEO or C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_SUPPLEMENTARY

    @OptIn(UnstableApi::class)
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
                        sampleMimeType = format.sampleMimeType,
                        codecs = format.codecs,
                        bitrate = format.bitrate,
                    ),
                )
            }
        }
    }

    fun isSpecial(track: LiveTvAudioTrack): Boolean =
        (track.roleFlags and SPECIAL_ROLES) != 0 ||
            specialLabel.containsMatchIn(track.label.orEmpty())

    /** E-AC3 JOC is identifiable as Atmos. Ordinary E-AC3/TrueHD alone does not prove Atmos. */
    fun isDolbyAtmos(track: LiveTvAudioTrack): Boolean =
        track.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC ||
            track.codecs?.split(',')?.any { it.trim().equals("ec+3", ignoreCase = true) } == true

    fun chooseAutomatic(
        tracks: List<LiveTvAudioTrack>,
        preferredLanguage: LiveTvAudioLanguage,
        avoidSpecialTracks: Boolean,
        audioQuality: LiveTvAudioQuality = LiveTvAudioQuality.BestAvailable,
        preferSurround: Boolean = true,
    ): LiveTvAudioTrack? {
        if (preferredLanguage == LiveTvAudioLanguage.Automatic && !avoidSpecialTracks &&
            audioQuality == LiveTvAudioQuality.SenderDefault
        ) return null

        val supported = tracks.filter(LiveTvAudioTrack::supported)
        if (supported.isEmpty()) return null
        // Language outranks codec and surround quality: a German stereo track beats English 5.1.
        val matchingLanguage = preferredLanguage.languageTag?.let { language ->
            supported.filter { normalizeLanguage(it.language) == language }
        }.orEmpty()
        val languageCandidates = matchingLanguage.ifEmpty { supported }
        // Never sacrifice a normal German track for better-quality descriptive audio.
        val candidates = if (avoidSpecialTracks) {
            languageCandidates.filterNot(::isSpecial).ifEmpty { languageCandidates }
        } else languageCandidates

        val qualityComparator = compareByDescending<LiveTvAudioTrack> {
            surroundScore(it, preferSurround)
        }.thenByDescending {
            codecScore(it)
        }.thenByDescending {
            it.bitrate.takeIf { value -> value > 0 } ?: 0
        }

        val comparator = when (audioQuality) {
            LiveTvAudioQuality.BestAvailable -> qualityComparator
                .thenByDescending { (it.roleFlags and C.ROLE_FLAG_MAIN) != 0 }
                .thenByDescending { (it.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 }

            LiveTvAudioQuality.SenderDefault ->
                compareByDescending<LiveTvAudioTrack> {
                    (it.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                }.thenByDescending {
                    (it.roleFlags and C.ROLE_FLAG_MAIN) != 0
                }.then(qualityComparator)
        }
        return candidates.sortedWith(
            comparator.thenBy { it.groupIndex }.thenBy { it.trackIndex },
        ).firstOrNull()
    }

    private fun surroundScore(track: LiveTvAudioTrack, preferSurround: Boolean): Int =
        if (preferSurround) {
            if (isDolbyAtmos(track)) 10_000
            else when {
                track.channelCount >= 8 -> 8_000
                track.channelCount >= 6 -> 6_000
                track.channelCount >= 3 -> 4_000
                track.channelCount == 2 -> 2_000
                track.channelCount == 1 -> 1_000
                else -> 0
            }
        } else {
            // When surround preference is off, prefer stereo/mono when they exist.
            if (!isDolbyAtmos(track) && track.channelCount in 1..2) 2_000 else 0
        }

    private fun codecScore(track: LiveTvAudioTrack): Int = when {
        isDolbyAtmos(track) -> 110
        track.sampleMimeType == MimeTypes.AUDIO_TRUEHD -> 100
        track.sampleMimeType == MimeTypes.AUDIO_DTS_HD -> 95
        track.sampleMimeType == MimeTypes.AUDIO_E_AC3 -> 90
        track.sampleMimeType == MimeTypes.AUDIO_AC4 -> 88
        track.sampleMimeType == MimeTypes.AUDIO_AC3 -> 80
        track.sampleMimeType == MimeTypes.AUDIO_DTS -> 75
        track.sampleMimeType == MimeTypes.AUDIO_FLAC -> 70
        track.sampleMimeType == MimeTypes.AUDIO_AAC -> 60
        track.sampleMimeType == MimeTypes.AUDIO_OPUS -> 58
        track.sampleMimeType == MimeTypes.AUDIO_MPEG ||
            track.sampleMimeType == MimeTypes.AUDIO_MPEG_L2 -> 40
        else -> 0
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
            else -> "Tonspur $number"
        }
        val codec = when {
            isDolbyAtmos(track) -> "Dolby Atmos"
            track.sampleMimeType == MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            track.sampleMimeType == MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
            track.sampleMimeType == MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            track.sampleMimeType == MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            track.sampleMimeType == MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            track.sampleMimeType == MimeTypes.AUDIO_DTS -> "DTS"
            track.sampleMimeType == MimeTypes.AUDIO_AAC -> "AAC"
            track.sampleMimeType == MimeTypes.AUDIO_MPEG ||
                track.sampleMimeType == MimeTypes.AUDIO_MPEG_L2 -> "MPEG"
            else -> null
        }
        val channels = when (track.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> track.channelCount.takeIf { it > 2 }?.let { "$it Kanäle" }
        }
        return listOfNotNull(language, role, codec, channels).distinct().joinToString(" · ")
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
