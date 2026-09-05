package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServusShowRefreshPolicyTest {
    @Test
    fun `periodic refresh includes additional current shows and tv channel shows only`() {
        val news = show("news", "Servus Nachrichten")
        val weather = show("weather", "Servus Wetter")
        val sport = show("sport", "Sport und Talk")
        val categories = listOf(ServusCategory("c", "Sendungen", 0, listOf(news, weather, sport)))

        val ids = ServusShowRefreshPolicy.periodicShowIds(
            categories = categories,
            currentSelectionConfigured = true,
            currentSelectedIds = setOf("news", "weather"),
            tvChannelSelectedIds = setOf("sport"),
        )

        assertEquals(linkedSetOf("weather", "sport"), ids)
    }

    @Test
    fun `legacy current defaults do not add catalogue episode traffic before explicit configuration`() {
        val categories = listOf(
            ServusCategory("c", "Sendungen", 0, listOf(show("news", "Servus Nachrichten"), show("other", "Andere Sendung"))),
        )

        val ids = ServusShowRefreshPolicy.periodicShowIds(
            categories = categories,
            currentSelectionConfigured = false,
            currentSelectedIds = setOf("news"),
            tvChannelSelectedIds = emptySet(),
        )

        assertEquals(emptySet<String>(), ids)
    }

    private fun show(id: String, title: String) = ServusShow(
        id = id,
        title = title,
        description = null,
        categoryId = "c",
        categoryTitle = "Sendungen",
        artworkUri = null,
        squareArtworkUri = null,
        logoUri = null,
        episodes = emptyList(),
    )
}
