package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enriches catalogue cards whose landing-page payload only contains an art-logo.
 *
 * Joyn deliberately keeps the landing response small. Brand/show teasers can therefore contain a
 * logo and path but omit the real hero art even though the detail page exposes it. For these few
 * cards we resolve the matching detail operation in the background and merge the richer artwork
 * back into the existing [JoynMediaItem]. Normal poster/still cards never trigger this client.
 */
internal class JoynArtworkDetailClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val core = JoynApiClient(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .build()

    private val country: JoynCountry
        get() = JoynCountry.fromIsoCountry(Locale.getDefault().country)

    suspend fun enrichPage(page: JoynCataloguePage, maxItems: Int = MAX_PAGE_ENRICHMENTS): JoynCataloguePage =
        coroutineScope {
            val candidates = page.lanes
                .asSequence()
                .flatMap { it.items.asSequence() }
                .filter { it.needsJoynArtworkEnrichment() }
                .distinctBy { it.enrichmentKey() }
                .take(maxItems)
                .toList()

            if (candidates.isEmpty()) return@coroutineScope page

            val gate = Semaphore(MAX_CONCURRENT_REQUESTS)
            val enrichedByKey = candidates.map { item ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val enriched = runCatching { enrich(item) }.getOrDefault(item)
                        item.enrichmentKey() to enriched
                    }
                }
            }.awaitAll().toMap()

            page.copy(
                lanes = page.lanes.map { lane ->
                    lane.copy(
                        items = lane.items.map { item ->
                            enrichedByKey[item.enrichmentKey()] ?: item
                        },
                    )
                },
            )
        }

    private suspend fun enrich(item: JoynMediaItem): JoynMediaItem {
        if (!item.needsJoynArtworkEnrichment()) return item
        val path = item.path?.trim()?.takeIf(String::isNotEmpty) ?: return item
        val cacheKey = "${country.name}:${item.type.name}:$path"
        DETAIL_CACHE[cacheKey]?.let { cached ->
            return item.mergeArtwork(cached)
        }

        var best: JoynArtworkDetail? = null
        for (kind in detailOrder(item, path)) {
            val detail = runCatching { loadDetail(kind, path) }.getOrNull() ?: continue
            if (best == null || detail.contentScore > best.contentScore) best = detail
            if (detail.backdropUrl != null) break
        }

        val resolved = best ?: JoynArtworkDetail()
        DETAIL_CACHE[cacheKey] = resolved
        return item.mergeArtwork(resolved)
    }

    private suspend fun loadDetail(kind: DetailKind, path: String): JoynArtworkDetail {
        val root = when (kind) {
            DetailKind.SERIES -> {
                val data = persistedGraphQl(
                    operationName = "SeriesDetailNewPageStatic",
                    hash = HASH_SERIES,
                    variables = JSONObject()
                        .put("path", path)
                        .put("licenseFilter", "ALL"),
                )
                data.optJSONObject("page")?.optJSONObject("series")
                    ?: error("Joyn Serie '$path' wurde nicht gefunden")
            }

            DetailKind.MOVIE -> {
                val data = persistedGraphQl(
                    operationName = "PageMovieDetailStatic",
                    hash = HASH_MOVIE,
                    variables = JSONObject().put("path", path),
                )
                data.optJSONObject("page")?.optJSONObject("movie")
                    ?: error("Joyn Film '$path' wurde nicht gefunden")
            }

            DetailKind.COLLECTION -> {
                val data = persistedGraphQl(
                    operationName = "PageCollectionsDetail",
                    hash = HASH_COLLECTION,
                    variables = JSONObject().put("path", path),
                )
                data.optJSONObject("page")
                    ?: error("Joyn Sammlung '$path' wurde nicht gefunden")
            }

            DetailKind.CHANNEL -> {
                val data = persistedGraphQl(
                    operationName = "PageDetailMediaLibrary",
                    hash = HASH_CHANNEL,
                    variables = JSONObject()
                        .put("path", path)
                        .put("first", 1)
                        .put("offset", 0),
                )
                data.optJSONObject("page")
                    ?: error("Joyn Mediathek '$path' wurde nicht gefunden")
            }
        }

        // For a real sender page do not steal the first show's poster as channel artwork. For
        // Series/Collection pages, however, nested objects are intentionally searched because Joyn
        // often nests the hero image below page.series/page.collection.
        return extractArtwork(root, searchNestedContent = kind != DetailKind.CHANNEL)
    }

    private fun detailOrder(item: JoynMediaItem, path: String): List<DetailKind> = buildList {
        val normalized = path.lowercase(Locale.ROOT)

        // The path is more reliable than the lightweight landing typename. A Joyn teaser can be
        // typed Brand/ChannelPage while actually pointing at a full series detail page.
        if (normalized.startsWith("/serien") || normalized.contains("/serien/")) add(DetailKind.SERIES)
        if (normalized.startsWith("/filme") || normalized.contains("/filme/")) add(DetailKind.MOVIE)

        when (item.type) {
            JoynMediaType.SERIES -> add(DetailKind.SERIES)
            JoynMediaType.MOVIE -> add(DetailKind.MOVIE)
            JoynMediaType.CHANNEL -> add(DetailKind.CHANNEL)
            JoynMediaType.COLLECTION -> add(DetailKind.COLLECTION)
            else -> Unit
        }

        add(DetailKind.COLLECTION)
        add(DetailKind.SERIES)
        add(DetailKind.MOVIE)
        add(DetailKind.CHANNEL)
    }.distinct()

    private fun extractArtwork(root: JSONObject, searchNestedContent: Boolean): JoynArtworkDetail {
        val objects = if (searchNestedContent) root.flattenObjects() else buildList {
            add(root)
            root.optJSONObject("brand")?.let(::add)
            root.optJSONObject("series")?.let(::add)
            root.optJSONObject("movie")?.let(::add)
            root.optJSONObject("collection")?.let(::add)
        }

        val backdrop = objects.firstImageField("heroLandscapeImage")
            ?: objects.firstImageType("HERO_LANDSCAPE")
        val primary = objects.firstImageField("primaryImage")
            ?: objects.firstImageField("thumbnailImage")
            ?: objects.firstImageField("posterImage")
            ?: objects.firstImageType("PRIMARY")
            ?: objects.firstImageField("heroPortraitImage")
            ?: objects.firstImageField("heroPortrait")
        val logo = objects.firstImageField("artLogoImage")
            ?: objects.firstImageField("logo")
        val description = objects.firstNotNullOfOrNull { obj ->
            obj.optString("description").takeIf(String::isNotBlank)
                ?: obj.optString("secondaryTitle").takeIf(String::isNotBlank)
        }

        return JoynArtworkDetail(
            imageUrl = primary,
            backdropUrl = backdrop,
            logoUrl = logo,
            description = description,
        )
    }

    private suspend fun persistedGraphQl(
        operationName: String,
        hash: String,
        variables: JSONObject,
    ): JSONObject {
        var session = ensureSession()
        return try {
            persistedGraphQlOnce(session, operationName, hash, variables)
        } catch (error: ArtworkHttpException) {
            if (error.statusCode !in setOf(401, 403)) throw error
            core.loadCatalogue("/neu-beliebt")
            session = ensureSession()
            persistedGraphQlOnce(session, operationName, hash, variables)
        } catch (error: ArtworkGraphQlException) {
            if (!error.details.contains("INVALID_JWT", ignoreCase = true)) throw error
            core.loadCatalogue("/neu-beliebt")
            session = ensureSession()
            persistedGraphQlOnce(session, operationName, hash, variables)
        }
    }

    private fun persistedGraphQlOnce(
        session: ArtworkSession,
        operationName: String,
        hash: String,
        variables: JSONObject,
    ): JSONObject {
        val extensions = JSONObject().put(
            "persistedQuery",
            JSONObject().put("version", 1).put("sha256Hash", hash),
        )
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", operationName)
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-api-key", session.apiKey)
            .header("Joyn-Platform", "web")
            .header("Joyn-Country", session.country.name)
            .header("Joyn-Distribution-Tenant", session.country.graphqlTenant)
            .header("Authorization", session.authorization)
            .get()
            .build()

        val json = client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw ArtworkHttpException(response.code, body)
            JSONObject(body)
        }
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw ArtworkGraphQlException(operationName, errors.toString())
        }
        return json.optJSONObject("data")
            ?: error("Joyn $operationName lieferte keine Daten")
    }

    private suspend fun ensureSession(): ArtworkSession {
        val selectedCountry = country
        val cacheKey = "api_key_${selectedCountry.name}"
        val cachedAt = prefs.getLong("${cacheKey}_at", 0L)
        val apiKeyFresh = !prefs.getString(cacheKey, null).isNullOrBlank() &&
            System.currentTimeMillis() - cachedAt < API_KEY_TTL_MS
        var token = readStoredToken()
        val now = System.currentTimeMillis() / 1000L
        val tokenFresh = token != null && now < token.createdAt + token.expiresIn - TOKEN_MARGIN_SECONDS

        if (!apiKeyFresh || !tokenFresh) {
            core.loadCatalogue("/neu-beliebt")
            token = readStoredToken()
        }

        val apiKey = prefs.getString(cacheKey, null)?.takeIf(String::isNotBlank)
            ?: error("Joyn API-Key ist nicht verfügbar")
        val stored = token ?: error("Joyn Sitzung ist nicht verfügbar")
        return ArtworkSession(
            country = selectedCountry,
            apiKey = apiKey,
            authorization = "${stored.tokenType} ${stored.accessToken}",
        )
    }

    private fun readStoredToken(): ArtworkStoredToken? {
        val raw = prefs.getString("auth_token", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ArtworkStoredToken(
                accessToken = json.getString("accessToken"),
                tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer",
                expiresIn = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L,
                createdAt = json.optLong("createdAt"),
            )
        }.getOrNull()
    }

    private fun JSONObject.flattenObjects(maxObjects: Int = 80): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val queue = ArrayDeque<JSONObject>()
        queue.add(this)
        while (queue.isNotEmpty() && result.size < maxObjects) {
            val current = queue.removeFirst()
            result += current
            val keys = current.keys()
            while (keys.hasNext() && result.size + queue.size < maxObjects) {
                when (val value = current.opt(keys.next())) {
                    is JSONObject -> queue.add(value)
                    is JSONArray -> {
                        for (index in 0 until value.length()) {
                            value.optJSONObject(index)?.let(queue::add)
                            if (result.size + queue.size >= maxObjects) break
                        }
                    }
                }
            }
        }
        return result
    }

    private fun List<JSONObject>.firstImageField(field: String): String? =
        firstNotNullOfOrNull { obj -> obj.optJSONObject(field)?.urlValue() }

    private fun List<JSONObject>.firstImageType(type: String): String? =
        firstNotNullOfOrNull { obj ->
            val images = obj.optJSONArray("images") ?: return@firstNotNullOfOrNull null
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                if (image.optString("type").equals(type, ignoreCase = true)) {
                    image.urlValue()?.let { return@firstNotNullOfOrNull it }
                }
            }
            null
        }

    private fun JSONObject.urlValue(): String? = optString("url").takeIf(String::isNotBlank)

    private fun JoynMediaItem.mergeArtwork(detail: JoynArtworkDetail): JoynMediaItem = copy(
        description = description ?: detail.description,
        imageUrl = detail.imageUrl ?: imageUrl,
        backdropUrl = detail.backdropUrl ?: detail.imageUrl ?: backdropUrl,
        logoUrl = detail.logoUrl ?: logoUrl,
    )

    private fun JoynMediaItem.enrichmentKey(): String =
        "${type.name}:${path.orEmpty()}:$id"

    private enum class DetailKind { SERIES, MOVIE, COLLECTION, CHANNEL }

    private data class JoynArtworkDetail(
        val imageUrl: String? = null,
        val backdropUrl: String? = null,
        val logoUrl: String? = null,
        val description: String? = null,
    ) {
        val contentScore: Int
            get() = (if (backdropUrl != null) 4 else 0) +
                (if (imageUrl != null) 2 else 0) +
                (if (logoUrl != null) 1 else 0)
    }

    private data class ArtworkSession(
        val country: JoynCountry,
        val apiKey: String,
        val authorization: String,
    )

    private data class ArtworkStoredToken(
        val accessToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val createdAt: Long,
    )

    private class ArtworkHttpException(val statusCode: Int, body: String) :
        IOException("Joyn Artwork HTTP $statusCode: ${body.take(220)}")

    private class ArtworkGraphQlException(operation: String, val details: String) :
        IOException("Joyn $operation: ${details.take(240)}")

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val TOKEN_MARGIN_SECONDS = 1800L
        private const val MAX_PAGE_ENRICHMENTS = 14
        private const val MAX_CONCURRENT_REQUESTS = 4
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val HASH_SERIES = "e867452d17ef36e5c077db5cdcad7563a9aebede497c24ac8fae779723bc462d"
        private const val HASH_MOVIE = "9ae6bcd8c45a5e350438d1cc415a022fe053e938c93438509f60ae3abb425fa7"
        private const val HASH_COLLECTION = "bf3a273afa54de5a577de160cc82e55824b0e92c87c8bb0b470087eedaeb7c18"
        private const val HASH_CHANNEL = "f61159391eed95487997fe2a9eab1fe25198e12b35f6206f60eb9f477187fab3"

        private val DETAIL_CACHE = ConcurrentHashMap<String, JoynArtworkDetail>()
    }
}
