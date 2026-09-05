package com.andreassamitsch.servusprovider.api

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ServusResilientApiTest {
    @Test
    fun primarySearchRetriesTransient500AndRecovers() = runBlocking {
        var calls = 0
        val delegate = object : StubServusApi() {
            override suspend fun search(market: String, query: String, offset: Int): SearchResponseDto {
                calls++
                if (calls == 1) throw httpException(500)
                return SearchResponseDto(id = "recovered")
            }
        }
        val api = ServusResilientApi(delegate, maxAttempts = 3, retryDelayMillis = 0L)

        val response = api.search("at", "Servus Nachrichten", 0)

        assertEquals("recovered", response.id)
        assertEquals(2, calls)
    }

    @Test
    fun exhaustedSecondarySearchPageBecomesEmptyPaginationPage() = runBlocking {
        var calls = 0
        val delegate = object : StubServusApi() {
            override suspend fun search(market: String, query: String, offset: Int): SearchResponseDto {
                calls++
                throw httpException(500)
            }
        }
        val api = ServusResilientApi(delegate, maxAttempts = 3, retryDelayMillis = 0L)

        val response = api.search("at", "Servus Nachrichten", 15)

        assertTrue(response.cards.isEmpty())
        assertEquals(3, calls)
    }

    @Test
    fun exhaustedPrimarySearchPageStillFailsRefresh() = runBlocking {
        var calls = 0
        val delegate = object : StubServusApi() {
            override suspend fun search(market: String, query: String, offset: Int): SearchResponseDto {
                calls++
                throw httpException(500)
            }
        }
        val api = ServusResilientApi(delegate, maxAttempts = 3, retryDelayMillis = 0L)

        try {
            api.search("at", "Servus Nachrichten", 0)
            fail("Expected primary search page to fail after retries")
        } catch (error: HttpException) {
            assertEquals(500, error.code())
        }
        assertEquals(3, calls)
    }

    @Test
    fun nonTransient403IsNeitherRetriedNorSuppressed() = runBlocking {
        var calls = 0
        val delegate = object : StubServusApi() {
            override suspend fun search(market: String, query: String, offset: Int): SearchResponseDto {
                calls++
                throw httpException(403)
            }
        }
        val api = ServusResilientApi(delegate, maxAttempts = 3, retryDelayMillis = 0L)

        try {
            api.search("at", "Servus Nachrichten", 15)
            fail("Expected non-transient HTTP 403 to propagate")
        } catch (error: HttpException) {
            assertEquals(403, error.code())
        }
        assertEquals(1, calls)
    }

    @Test
    fun productRequestsAlsoRetryTransientServerErrors() = runBlocking {
        var calls = 0
        val delegate = object : StubServusApi() {
            override suspend fun product(market: String, id: String): ServusCardDto {
                calls++
                if (calls < 3) throw httpException(503)
                return ServusCardDto(id = id, title = "Recovered")
            }
        }
        val api = ServusResilientApi(delegate, maxAttempts = 3, retryDelayMillis = 0L)

        val response = api.product("at", "content")

        assertEquals("content", response.id)
        assertEquals(3, calls)
    }

    @Test
    fun transientStatusPolicyCoversServerAndRateLimitResponses() {
        assertTrue(ServusResilientApi.isTransientHttpStatus(408))
        assertTrue(ServusResilientApi.isTransientHttpStatus(429))
        assertTrue(ServusResilientApi.isTransientHttpStatus(500))
        assertTrue(ServusResilientApi.isTransientHttpStatus(503))
    }

    private fun httpException(statusCode: Int): HttpException = HttpException(
        Response.error<Any>(
            statusCode,
            "{}".toResponseBody("application/json".toMediaType()),
        ),
    )

    private open class StubServusApi : ServusApi {
        override suspend fun session(namespace: String, category: String, osFamily: String): SessionDto =
            error("unused")

        override suspend fun search(market: String, query: String, offset: Int): SearchResponseDto =
            error("unused")

        override suspend fun product(market: String, id: String): ServusCardDto =
            error("unused")

        override suspend fun collection(market: String, id: String, offset: Int): SearchResponseDto =
            error("unused")

        override suspend fun guide(market: String, id: String, complete: Boolean): SearchResponseDto =
            error("unused")

        override suspend fun dynamicProduct(market: String, id: String): DynamicProductDto =
            error("unused")
    }
}
