package com.andreassamitsch.servusprovider.api

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException

/**
 * Small resilience layer around ServusTV's read-only API.
 *
 * The service occasionally returns transient 5xx responses when several discovery requests arrive
 * at once. All GET requests get a bounded retry. Search fan-out is additionally limited while the
 * Aktuelles discovery follows only pagination links advertised by the server. If a later search
 * page (offset > 0) still fails transiently after all retries, it is
 * treated like an empty pagination page; the primary offset=0 page remains mandatory so a whole
 * query cannot silently disappear from the snapshot.
 */
internal class ServusResilientApi(
    private val delegate: ServusApi,
    searchParallelism: Int = DEFAULT_SEARCH_PARALLELISM,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
) : ServusApi {
    private val searchSemaphore = Semaphore(searchParallelism.coerceAtLeast(1))

    override suspend fun session(
        namespace: String,
        category: String,
        osFamily: String,
    ): SessionDto = retryTransient {
        delegate.session(namespace, category, osFamily)
    }

    override suspend fun search(
        market: String,
        query: String,
        offset: Int,
    ): SearchResponseDto {
        return try {
            retryTransient {
                searchSemaphore.withPermit {
                    delegate.search(market, query, offset)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            if (offset > 0 && isTransientFailure(throwable)) {
                SearchResponseDto()
            } else {
                throw throwable
            }
        }
    }

    override suspend fun product(market: String, id: String): ServusCardDto = retryTransient {
        delegate.product(market, id)
    }

    override suspend fun collection(
        market: String,
        id: String,
        offset: Int,
    ): SearchResponseDto = retryTransient {
        delegate.collection(market, id, offset)
    }

    override suspend fun guide(
        market: String,
        id: String,
        complete: Boolean,
    ): SearchResponseDto = retryTransient {
        delegate.guide(market, id, complete)
    }

    override suspend fun dynamicProduct(market: String, id: String): DynamicProductDto = retryTransient {
        delegate.dynamicProduct(market, id)
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
        const val DEFAULT_SEARCH_PARALLELISM = 4
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
}
