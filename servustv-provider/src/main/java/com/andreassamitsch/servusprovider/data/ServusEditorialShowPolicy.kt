package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCollectionRefDto
import java.util.Locale

/**
 * Small compatibility policy for ServusTV show pages whose web rails are not represented like the
 * older catalogue pages.
 *
 * The opened-show pager may follow `reference` collections because membership is still validated on
 * every returned video card. Obvious recommendation rails are skipped up front so opening a show
 * does not fan out into unrelated content. Known editorial title variants are handled only for the
 * stable product that needs them; the generic exact-membership policy remains conservative.
 */
internal object ServusEditorialShowPolicy {
    fun includeOpenedShowCollection(ref: ServusCollectionRefDto): Boolean {
        if (ref.id.isNullOrBlank()) return false
        return !isRecommendationLabel(ref.label)
    }

    fun matchesKnownAlias(candidate: ServusSourcedCard, show: ServusShow): Boolean {
        if (show.id != ServusBranding.FLEISCHHACKER_SHOW_ID) return false
        return sequenceOf(
            candidate.card.showName,
            candidate.card.subheading,
            candidate.sourceCollectionLabel,
        ).filterNotNull()
            .map(::normalizeWords)
            .any(::looksLikeFleischhackerIdentity)
    }

    fun fallbackSearchQueries(show: ServusShow): List<String> = when (show.id) {
        ServusBranding.FLEISCHHACKER_SHOW_ID -> listOf(
            show.title,
            "Servus Kommentar Michael Fleischhacker",
        ).distinct()
        else -> listOf(show.title)
    }

    internal fun isRecommendationLabel(label: String?): Boolean {
        val normalized = normalizeWords(label.orEmpty())
        if (normalized.isBlank()) return false
        return normalized.contains("das könnte ihnen auch gefallen") ||
            normalized.contains("das koennte ihnen auch gefallen") ||
            normalized.startsWith("mehr zu") ||
            normalized.contains("empfehlungen") ||
            normalized.contains("ähnliche") ||
            normalized.contains("aehnliche") ||
            normalized.contains("related")
    }

    private fun looksLikeFleischhackerIdentity(value: String): Boolean =
        value.contains("servus kommentar") && value.contains("fleischhacker")

    private fun normalizeWords(value: String): String = value
        .lowercase(Locale.GERMAN)
        .replace('–', '-')
        .replace(Regex("""[^a-z0-9äöüß]+"""), " ")
        .trim()
}
