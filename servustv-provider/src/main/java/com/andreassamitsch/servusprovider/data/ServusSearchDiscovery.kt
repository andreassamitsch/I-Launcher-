package com.andreassamitsch.servusprovider.data

import com.andreassamitsch.servusprovider.api.SearchResponseDto
import com.andreassamitsch.servusprovider.api.ServusApi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException

/**
 * Loads the additive ServusTV search pages used to discover the small "Aktuelles" candidate set.
 *
 * The API occasionally answers individual parallel search pages with transient 5xx responses. A
 * single such page must not invalidate all other healthy search results. We therefore keep the
 * request fan-out deliberately small, retry transient failures and only skip an exhausted
 * transient page when another page of the same search query succeeded. Unexpected/non-transient
 * HTTP errors still fail immediately, and a completely failed query group still fails the refresh
 * so an incomplete snapshot is never treated as fully trustworthy.
 */
internal class ServusSearchDiscovery(
    private val api: ServusApi,
    parallelism: Int = DEFAULT_PARALLELISM,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
) {
    private val semaphore = Semaphore(parallelism.coerceAtLeast(1))

    suspend fun loadResponseGroups(
        market: String,
        queries: List<String>,
        offsets: List<Int>,
    ): List<List<SearchResponseDto>> = coroutineScope {
        queries.map { query ->
            async {
                val pages = offsets.map { offset ->
                    async {
                        try {
                            SearchPageResult.Success(
                                retryTransient {
                                    semaphore.withPermit {
                                        api.search(market, query, offset)
                                    }
                                },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            if (!isTransientFailure(throwable)) throw throwable
                            SearchPageResult.TransientFailure(throwable)
                        }
                    }
                }.awaitAll()

                val successful = pages.mapNotNull { result ->
                    (result as? SearchPageResult.Success)?.response
                }
                if (successful.isEmpty()) {
                    val firstFailure = pages
                        .filterIsInstance<SearchPageResult.TransientFailure>()
                        .firstOrNull()
                        ?.throwable
                    throw ServusSearchRefreshException(
                        query = query,
                        failedPages = pages.size,
                        cause = firstFailure,
                    )
                }
                successful
            }
        }.awaitAll()
    }

    private suspend fun <T> retryTransient(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (!isTransientFailure(throwable) || attempt >= maxAttempts.coerceAtLeast(1)) {
                    throw throwable
                }
                delay(retryDelayMillis.coerceAtLeast(0L) * attempt)
                attempt++
            }
        }
    }

    internal companion object {
        const val DEFAULT_PARALLELISM = 4
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_DELAY_MS = 300L

        fun isTransientHttpStatus(statusCode: Int): Boolean =
            statusCode == 408 || statusCode == 429 || statusCode in 500..599

        fun isTransientFailure(throwable: Throwable): Boolean = when (throwable) {
            is IOException -> true
            is HttpException -> isTransientHttpStatus(throwable.code())
            else -> false
        }
    }

    private sealed interface SearchPageResult {
        data class Success(val response: SearchResponseDto) : SearchPageResult
        data class TransientFailure(val throwable: Throwable) : SearchPageResult
    }
}

internal class ServusSearchRefreshException(
    query: String,
    failedPages: Int,
    cause: Throwable?,
) : IllegalStateException(
    buildString {
        append("ServusTV-Suche fehlgeschlagen: ")
        append(failedPages)
        append(" Suchseiten für '")
        append(query.take(80))
        append("' ohne erfolgreiche Antwort")
        cause?.let { throwable ->
            append(" (")
            append(throwable.javaClass.simpleName)
            throwable.message?.takeIf { it.isNotBlank() }?.let { message ->
                append(": ")
                append(message.take(120))
            }
            append(")")
        }
    },
    cause,
)
