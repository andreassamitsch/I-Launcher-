package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.ServusCollectionRefDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServusCatalogDiagnosticsTest {
    @Test
    fun landingDiagnosticShowsCollectionTypeDistribution() {
        val diagnostics = ServusCatalogDiagnosticBuilder()
        diagnostics.recordLanding(
            listOf(
                ServusCollectionRefDto(id = "1", listType = "reference", label = "News"),
                ServusCollectionRefDto(id = "2", listType = "reference", label = "Sport"),
                ServusCollectionRefDto(id = "3", listType = "standard", label = "Wissen"),
            ),
        )
        diagnostics.recordCategoryFilter(1)

        val failure = diagnostics.failure("Kategorien", "Test").message.orEmpty()
        assertTrue(failure.contains("Landing: 3 Collections"))
        assertTrue(failure.contains("reference=2"))
        assertTrue(failure.contains("standard=1"))
        assertTrue(failure.contains("nach Filter: 1 Kategorien"))
    }

    @Test
    fun categoryDiagnosticExposesCardAndShowCounts() {
        val diagnostics = ServusCatalogDiagnosticBuilder()
        diagnostics.recordCategory("News & Magazine", cardCount = 24, recognizedShowCount = 0)
        diagnostics.recordUniqueShows(0)

        val failure = diagnostics.failure("Sendungsfilter", "0 Treffer").message.orEmpty()
        assertTrue(failure.contains("News & Magazine: 24 Karten / 0 erkannte Sendungen"))
        assertTrue(failure.contains("eindeutige Sendungskarten: 0"))
    }

    @Test
    fun diagnosticSanitizerRemovesUrlsAndLineBreaks() {
        val sanitized = ServusCatalogDiagnosticBuilder.sanitize(
            "HTTP 404 https://tv-api.redbull.com/products/token/secret\nzweite Zeile",
        )

        assertTrue(sanitized.contains("HTTP 404"))
        assertTrue(sanitized.contains("<URL>"))
        assertFalse(sanitized.contains("tv-api.redbull.com"))
        assertFalse(sanitized.contains("\n"))
    }
}
