package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusSourceKeyTest {
    @Test
    fun wholeShowKeyRemainsRawIdForPreferenceCompatibility() {
        assertEquals("SHOW", ServusSourceKey.show("SHOW"))
        assertEquals("SHOW", ServusSourceKey.parentShowId("SHOW"))
        assertFalse(ServusSourceKey.isCollection("SHOW"))
    }

    @Test
    fun collectionKeyRoundTripsParentAndCollection() {
        val key = ServusSourceKey.collection("SHOW", "COLLECTION")

        assertEquals("collection:SHOW:COLLECTION", key)
        assertTrue(ServusSourceKey.isCollection(key))
        assertEquals("SHOW", ServusSourceKey.parentShowId(key))
        assertEquals("COLLECTION", ServusSourceKey.collectionId(key))
    }

    @Test
    fun validKeysOnlyExposeContentCollections() {
        val content = ServusShowCollection(
            id = "EPISODES",
            title = "Aktuelle Sendungen",
            role = ServusCollectionRole.CONTENT,
        )
        val recommendation = ServusShowCollection(
            id = "RELATED",
            title = "Das könnte Ihnen auch gefallen",
            role = ServusCollectionRole.RECOMMENDATION,
        )
        val show = ServusShow(
            id = "SHOW",
            title = "Testsendung",
            description = null,
            categoryId = "CAT",
            categoryTitle = "Test",
            artworkUri = null,
            squareArtworkUri = null,
            logoUri = null,
            episodes = emptyList(),
            collections = listOf(content, recommendation),
        )
        val categories = listOf(ServusCategory("CAT", "Test", 0, listOf(show)))

        val keys = ServusSourceKey.validKeys(categories)

        assertTrue("SHOW" in keys)
        assertTrue("collection:SHOW:EPISODES" in keys)
        assertFalse("collection:SHOW:RELATED" in keys)
    }
}
