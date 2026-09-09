package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Dedicated category resolver. Joyn category lanes differ between markets/builds and can
 * wrap their assets one level deeper than the generic catalogue response. This resolver
 * walks the returned union tree and falls back to the landing pages that originally
 * advertised the block. */
internal class JoynCategoryApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("joyn_protocol", Context.MODE_PRIVATE)
    private val core = JoynApiClient(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val country: JoynCountry
        get() = JoynCountry.fromIsoCountry(Locale.getDefault().country)

    suspend fun loadCategory(blockId: String, fallbackTitle: String): JoynCataloguePage {
        val direct = persistedGraphQl(
            operationName = "LandingBlocks",
            hash = HASH_LANDING_BLOCKS,
            variables = JSONObject().put("ids", JSONArray().put(blockId)),
        )
        val directBlock = direct.findBlock(blockId)
        val directItems = directBlock?.deepMediaItems().orEmpty()
        if (directItems.isNotEmpty()) {
            return categoryPage(blockId, fallbackTitle, directBlock, directItems)
        }

        // Some Joyn market variants advertise a block with content in LandingPageClient but
        // return a lightweight shell when the same block is requested via LandingBlocks.
        for (path in CATEGORY_SOURCE_PATHS) {
            val landing = runCatching {
                persistedGraphQl(
                    operationName = "LandingPageClient",
                    hash = HASH_LANDING_PAGE,
                    variables = JSONObject().put("path", path),
                )
            }.getOrNull() ?: continue
            val block = landing.findBlock(blockId) ?: continue
            val items = block.deepMediaItems()
            if (items.isNotEmpty()) return categoryPage(blockId, fallbackTitle, block, items)
        }

        val directKeys = directBlock?.keys()?.asSequence()?.toList()?.sorted().orEmpty()
        error(
            "Joyn Kategorie '$fallbackTitle' enthält keine auswertbaren Inhalte " +
                "(Block $blockId, Felder: ${directKeys.joinToString().ifBlank { "keine" }}).",
        )
    }

    private fun categoryPage(
        blockId: String,
        fallbackTitle: String,
        block: JSONObject?,
        items: List<JoynMediaItem>,
    ): JoynCataloguePage {
        val title = block?.optString("headline")?.takeIf(String::isNotBlank)
            ?: block?.optString("title")?.takeIf(String::isNotBlank)
            ?: fallbackTitle
        return JoynCataloguePage(
            title = title,
            lanes = listOf(
                JoynLane(
                    id = "category:$blockId",
                    title = title,
                    items = items.distinctBy { "${it.type}:${it.id}" }.take(200),
                ),
            ),
        )
    }

    private suspend fun persistedGraphQl(
        operationName: String,
        hash: String,
        variables: JSONObject,
    ): JSONObject {
        val session = ensureSession()
        val extensions = JSONObject().put(
            "persistedQuery",
            JSONObject().put("version", 1).put("sha256Hash", hash),
        )
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", operationName)
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val json = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-api-key", session.apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", session.country.name)
                .header("Joyn-Distribution-Tenant", session.country.graphqlTenant)
                .header("Authorization", session.authorization)
                .get()
                .build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("Joyn Kategorie HTTP ${response.code}: ${body.take(260)}")
            JSONObject(body)
        }
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw IOException("Joyn $operationName: ${errors.toString().take(320)}")
        }
        return json.optJSONObject("data") ?: error("Joyn $operationName lieferte keine Daten")
    }

    private suspend fun ensureSession(): CategorySession {
        val selectedCountry = country
        val cacheKey = "api_key_${selectedCountry.name}"
        var token = readToken()
        val cachedAt = prefs.getLong("${cacheKey}_at", 0L)
        val nowMs = System.currentTimeMillis()
        val now = nowMs / 1000L
        val keyFresh = !prefs.getString(cacheKey, null).isNullOrBlank() &&
            nowMs - cachedAt < API_KEY_TTL_MS
        val tokenFresh = token != null && now < token.createdAt + token.expiresIn - 1800L
        if (!keyFresh || !tokenFresh) {
            core.loadCatalogue("/neu-beliebt")
            token = readToken()
        }
        return CategorySession(
            country = selectedCountry,
            apiKey = prefs.getString(cacheKey, null)?.takeIf(String::isNotBlank)
                ?: error("Joyn API-Key ist nicht verfügbar"),
            authorization = token?.let { "${it.tokenType} ${it.accessToken}" }
                ?: error("Joyn Sitzung ist nicht verfügbar"),
        )
    }

    private fun readToken(): CategoryToken? {
        val raw = prefs.getString("auth_token", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CategoryToken(
                accessToken = json.getString("accessToken"),
                tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer",
                expiresIn = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L,
                createdAt = json.optLong("createdAt"),
            )
        }.getOrNull()
    }

    private fun JSONObject.findBlock(blockId: String): JSONObject? {
        if (optString("id") == blockId) return this
        val keys = keys()
        while (keys.hasNext()) {
            when (val value = opt(keys.next())) {
                is JSONObject -> value.findBlock(blockId)?.let { return it }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        val child = value.optJSONObject(index) ?: continue
                        child.findBlock(blockId)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.deepMediaItems(): List<JoynMediaItem> {
        val found = mutableListOf<JoynMediaItem>()
        val stack = mutableListOf<Any>(this)
        var visited = 0
        while (stack.isNotEmpty() && visited < 10_000) {
            val value = stack.removeAt(stack.lastIndex)
            visited += 1
            when (value) {
                is JSONObject -> {
                    if (value.optString("__typename") in MEDIA_TYPENAMES) {
                        value.toMediaItem()?.let(found::add)
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        when (val child = value.opt(keys.next())) {
                            is JSONObject -> stack.add(child)
                            is JSONArray -> stack.add(child)
                        }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        when (val child = value.opt(index)) {
                            is JSONObject -> stack.add(child)
                            is JSONArray -> stack.add(child)
                        }
                    }
                }
            }
        }
        return found.distinctBy { "${it.type}:${it.id}" }
    }

    private fun JSONObject.toMediaItem(): JoynMediaItem? {
        val typename = optString("__typename")
        val brand = optJSONObject("brand")
        val title = optString("title").takeIf(String::isNotBlank)
            ?: optString("name").takeIf(String::isNotBlank)
            ?: brand?.optString("title")?.takeIf(String::isNotBlank)
            ?: return null
        val id = optString("id").takeIf(String::isNotBlank)
            ?: optJSONObject("video")?.optString("id")?.takeIf(String::isNotBlank)
            ?: return null
        val type = when (typename) {
            "Movie" -> JoynMediaType.MOVIE
            "Series" -> JoynMediaType.SERIES
            "Episode" -> JoynMediaType.EPISODE
            "Compilation", "CompilationItem" -> JoynMediaType.COMPILATION
            "Extra" -> JoynMediaType.EXTRA
            "SportsMatch", "SportsStage", "SportsCompetition" -> JoynMediaType.SPORT
            "Brand", "ChannelPage" -> JoynMediaType.CHANNEL
            "Teaser" -> JoynMediaType.COLLECTION
            else -> return null
        }
        val logo = optJSONObject("artLogoImage")?.urlValue()
            ?: optJSONObject("logo")?.urlValue()
            ?: brand?.optJSONObject("logo")?.urlValue()
        val image = optJSONObject("primaryImage")?.urlValue()
            ?: optJSONObject("thumbnailImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: optJSONObject("heroPortraitImage")?.urlValue()
            ?: optJSONArray("images").firstImageUrl()
            ?: logo
        val backdrop = optJSONObject("heroLandscapeImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: image
        return JoynMediaItem(
            id = id,
            title = title,
            description = optString("description").takeIf(String::isNotBlank)
                ?: optString("secondaryTitle").takeIf(String::isNotBlank),
            path = optString("path").takeIf(String::isNotBlank),
            type = type,
            imageUrl = image,
            backdropUrl = backdrop,
            logoUrl = logo,
            videoId = optJSONObject("video")?.optString("id")?.takeIf(String::isNotBlank),
            seasonId = optJSONObject("season")?.optString("id")?.takeIf(String::isNotBlank),
            seasonNumber = optJSONObject("season")?.optInt("number")?.takeIf { it > 0 }
                ?: optJSONObject("season")?.optInt("seasonNumber")?.takeIf { it > 0 },
            episodeNumber = optInt("number").takeIf { it > 0 },
            licenseTypes = optJSONArray("licenseTypes").toStringSet(),
            markings = optJSONArray("markings").toStringSet(),
        )
    }

    private fun JSONObject.urlValue(): String? = optString("url").takeIf(String::isNotBlank)

    private fun JSONArray?.firstImageUrl(): String? {
        val source = this ?: return null
        var fallback: String? = null
        for (i in 0 until source.length()) {
            val image = source.optJSONObject(i) ?: continue
            val url = image.optString("url").takeIf(String::isNotBlank) ?: continue
            if (fallback == null) fallback = url
            if (image.optString("type") in setOf("LIVE_STILL", "PRIMARY", "HERO_LANDSCAPE")) return url
        }
        return fallback
    }

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val source = this@toStringSet ?: return@buildSet
        for (i in 0 until source.length()) source.optString(i).takeIf(String::isNotBlank)?.let(::add)
    }

    private data class CategorySession(
        val country: JoynCountry,
        val apiKey: String,
        val authorization: String,
    )

    private data class CategoryToken(
        val accessToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val createdAt: Long,
    )

    companion object {
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val HASH_LANDING_PAGE = "b71b3871aebfe266b63a4bf7daaa35645e17be2469b850265ba75146fa60affc"
        private const val HASH_LANDING_BLOCKS = "1655591f83b0dc1508ad4d52c5f37f72d410f48ad08c3e5f2de8622f86a21c68"
        private val CATEGORY_SOURCE_PATHS = listOf("/", "/filme", "/serien", "/sport", "/neu-beliebt")
        private val MEDIA_TYPENAMES = setOf(
            "Movie", "Series", "Episode", "Compilation", "CompilationItem", "Extra",
            "SportsMatch", "SportsStage", "SportsCompetition", "Brand", "ChannelPage", "Teaser",
        )
    }
}
