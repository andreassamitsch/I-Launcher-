package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCardDto
import com.andreassamitsch.servusprovider.api.ServusCollectionRefDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusEditorialShowPolicyTest {
    @Test
    fun normalReferenceRailIsAllowedForOpenedShow() {
        assertTrue(
            ServusEditorialShowPolicy.includeOpenedShowCollection(
                ServusCollectionRefDto(
                    id = "episodes-reference",
                    listType = "reference",
                    label = "Aktuelle Videos",
                ),
            ),
        )
    }

    @Test
    fun obviousRecommendationReferenceRailIsRejected() {
        assertFalse(
            ServusEditorialShowPolicy.includeOpenedShowCollection(
                ServusCollectionRefDto(
                    id = "recommendations",
                    listType = "reference",
                    label = "Das könnte Ihnen auch gefallen",
                ),
            ),
        )
    }

    @Test
    fun FleischhackerWebAliasMatchesStableShowProduct() {
        val show = show(
            id = ServusBranding.FLEISCHHACKER_SHOW_ID,
            title = ServusBranding.FLEISCHHACKER_SHOW_NAME,
        )
        val candidate = ServusSourcedCard(
            card = ServusCardDto(
                id = "episode",
                type = "video",
                contentType = "episode",
                title = "Deutschland und das Ende des Leistungsprinzips",
                showName = "Servus Kommentar mit Michael Fleischhacker",
            ),
        )

        assertTrue(ServusEditorialShowPolicy.matchesKnownAlias(candidate, show))
    }

    @Test
    fun FleischhackerAliasDoesNotRelaxOtherShows() {
        val unrelated = show(id = "OTHER", title = "Andere Sendung")
        val candidate = ServusSourcedCard(
            card = ServusCardDto(
                id = "episode",
                type = "video",
                contentType = "episode",
                title = "Beitrag",
                showName = "Servus Kommentar mit Michael Fleischhacker",
            ),
        )

        assertFalse(ServusEditorialShowPolicy.matchesKnownAlias(candidate, unrelated))
    }

    private fun show(id: String, title: String) = ServusShow(
        id = id,
        title = title,
        description = null,
        categoryId = "talks",
        categoryTitle = "Talks & Meinungen",
        artworkUri = null,
        squareArtworkUri = null,
        logoUri = null,
        episodes = emptyList(),
    )
}
