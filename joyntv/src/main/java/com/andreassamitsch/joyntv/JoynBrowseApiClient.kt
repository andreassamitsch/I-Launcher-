package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browse-only Joyn GraphQL operations that complement the playback/catalogue client.
 *
 * The class deliberately reuses the token and API-key cache owned by [JoynApiClient].
 * That keeps one Joyn session for the whole APK while letting the browse surface evolve
 * independently from the playback code.
 */
internal class JoynBrowseApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("joyn_protocol", Context.MODE_PRIVATE)
    private val core = JoynApiClient(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val country: JoynCountry
        get() = JoynCountry.fromIsoCountry(Locale.getDefault().country)

    suspend fun loadCategories(path: String = "/"): JoynCataloguePage {
        val response = persistedGraphQl(
            operationName = "LandingPageClient",
            hash = HASH_LANDING_PAGE,
            variables = JSONObject().put("path", path),
        )
        val page = response.optJSONObject("page")
            ?: error("Joyn Kategorien: Seite '$path' wurde nicht gefunden")
        val blocks = page.allBlocks().filter { block ->
            block.optString("__typename") in CATEGORY_LANES
        }
        val resolved = resolveMissingBlocks(blocks)
        val items = blocks.mapNotNull { original ->
            val blockId = original.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val block = resolved[blockId] ?: original
            val preview = block.optJSONArray("assets")?.firstMediaItem()
            JoynMediaItem(
                id = "category:$blockId",
                title = block.optString("headline").takeIf(String::isNotBlank)
                    ?: block.optString("title").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null,
                description = block.optString("description").takeIf(String::isNotBlank),
                path = blockId,
                type = JoynMediaType.CATEGORY,
                imageUrl = preview?.imageUrl ?: preview?.backdropUrl,
                backdropUrl = preview?.backdropUrl ?: preview?.imageUrl,
            )
        }.distinctBy { it.id }

        return JoynCataloguePage(
            title = "Kategorien",
            lanes = listOfNotNull(
                JoynLane("joyn-categories", "Kategorien", items).takeIf { items.isNotEmpty() },
            ),
        )
    }

    suspend fun loadMediaLibraries(): JoynCataloguePage {
        val response = persistedGraphQl(
            operationName = "Navigation",
            hash = HASH_NAVIGATION,
            variables = JSONObject(),
        )
        val mediaLibraries = response.optJSONObject("mediatheken")
        val items = buildList {
            mediaLibraries?.optJSONArray("blocks")?.let { blocks ->
                for (index in 0 until blocks.length()) {
                    val assets = blocks.optJSONObject(index)?.optJSONArray("assets") ?: continue
                    addAll(assets.toMediaItems().filter { it.type == JoynMediaType.CHANNEL })
                }
            }
        }.distinctBy { it.id }

        return JoynCataloguePage(
            title = mediaLibraries?.optString("title")?.takeIf(String::isNotBlank) ?: "Mediatheken",
            lanes = listOfNotNull(
                JoynLane("joyn-mediatheken", "Mediatheken", items).takeIf { items.isNotEmpty() },
            ),
        )
    }

    suspend fun loadCategory(blockId: String, fallbackTitle: String): JoynCataloguePage {
        val response = persistedGraphQl(
            operationName = "LandingBlocks",
            hash = HASH_LANDING_BLOCKS,
            variables = JSONObject().put("ids", JSONArray().put(blockId)),
        )
        val block = response.optJSONArray("blocks")?.findObjectById(blockId)
            ?: error("Joyn Kategorie '$fallbackTitle' wurde nicht gefunden")
        val title = block.optString("headline").takeIf(String::isNotBlank) ?: fallbackTitle
        val items = available(block.optJSONArray("assets").toMediaItems(), loadHasPlus())
        return JoynCataloguePage(
            title = title,
            lanes = listOfNotNull(JoynLane("category:$blockId", title, items).takeIf { items.isNotEmpty() }),
        )
    }

    suspend fun loadChannel(path: String, fallbackTitle: String): JoynCataloguePage {
        val first = 32
        var offset = 0
        var pages = 0
        var pageTitle: String? = null
        val allItems = mutableListOf<JoynMediaItem>()

        while (pages < MAX_CHANNEL_PAGES) {
            val response = persistedGraphQl(
                operationName = "PageDetailMediaLibrary",
                hash = HASH_CHANNEL,
                variables = JSONObject()
                    .put("path", path)
                    .put("first", first)
                    .put("offset", offset),
            )
            val page = response.optJSONObject("page") ?: break
            if (pageTitle.isNullOrBlank()) {
                pageTitle = page.optString("title").takeIf(String::isNotBlank)
                    ?: page.optJSONObject("brand")?.optString("title")?.takeIf(String::isNotBlank)
            }
            val batch = page.optJSONArray("assets").toMediaItems()
            if (batch.isEmpty()) break
            allItems += batch
            pages += 1
            if (batch.size < first) break
            offset += first
        }

        val items = available(allItems.distinctBy { it.id }, loadHasPlus())
        val title = pageTitle ?: fallbackTitle
        return JoynCataloguePage(
            title = title,
            lanes = listOfNotNull(JoynLane("channel:$path", "Sendungen", items).takeIf { items.isNotEmpty() }),
        )
    }

    suspend fun loadCollection(path: String, fallbackTitle: String): JoynCataloguePage {
        val response = persistedGraphQl(
            operationName = "PageCollectionsDetail",
            hash = HASH_COLLECTION,
            variables = JSONObject().put("path", path),
        )
        val page = response.optJSONObject("page")
            ?: error("Joyn Sammlung '$fallbackTitle' wurde nicht gefunden")
        val blocks = page.allBlocks()
        val resolved = resolveMissingBlocks(blocks)
        val hasPlus = loadHasPlus()
        val lanes = blocks.mapNotNull { original ->
            val id = original.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val block = resolved[id] ?: original
            if (block.optString("__typename") !in COLLECTION_BLOCKS) return@mapNotNull null
            val title = block.optString("headline").takeIf(String::isNotBlank)
                ?: block.optString("title").takeIf(String::isNotBlank)
                ?: fallbackTitle
            val items = available(block.optJSONArray("assets").toMediaItems(), hasPlus)
            JoynLane("collection:$id", title, items).takeIf { items.isNotEmpty() }
        }
        return JoynCataloguePage(
            title = page.optString("title").takeIf(String::isNotBlank) ?: fallbackTitle,
            lanes = lanes.distinctBy { it.id },
        )
    }

    suspend fun loadCompilation(path: String, fallbackTitle: String): JoynCataloguePage {
        val response = persistedGraphQl(
            operationName = "CompilationDetailPageStatic",
            hash = HASH_COMPILATION,
            variables = JSONObject().put("path", path),
        )
        val compilation = response.optJSONObject("page")?.optJSONObject("compilation")
            ?: error("Joyn Compilation '$fallbackTitle' wurde nicht gefunden")
        val title = compilation.optString("title").takeIf(String::isNotBlank) ?: fallbackTitle
        val items = available(compilation.optJSONArray("compilationItems").toMediaItems(), loadHasPlus())
        return JoynCataloguePage(
            title = title,
            lanes = listOfNotNull(JoynLane("compilation:$path", title, items).takeIf { items.isNotEmpty() }),
        )
    }

    private suspend fun loadHasPlus(): Boolean =
        runCatching { core.accountState(refreshRemote = true).hasPlus }.getOrDefault(false)

    private fun available(items: List<JoynMediaItem>, hasPlus: Boolean): List<JoynMediaItem> =
        items.filter { it.isFree || hasPlus }.distinctBy { it.id }

    private suspend fun resolveMissingBlocks(blocks: List<JSONObject>): Map<String, JSONObject> {
        val unresolvedIds = blocks
            .filter { it.optJSONArray("assets")?.length() ?: 0 == 0 }
            .mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
            .distinct()
        if (unresolvedIds.isEmpty()) return emptyMap()

        val ids = JSONArray().apply { unresolvedIds.forEach(::put) }
        val response = persistedGraphQl(
            operationName = "LandingBlocks",
            hash = HASH_LANDING_BLOCKS,
            variables = JSONObject().put("ids", ids),
        )
        return buildMap {
            response.optJSONArray("blocks")?.let { resolved ->
                for (index in 0 until resolved.length()) {
                    val block = resolved.optJSONObject(index) ?: continue
                    block.optString("id").takeIf(String::isNotBlank)?.let { put(it, block) }
                }
            }
        }
    }

    private suspend fun persistedGraphQl(
        operationName: String,
        hash: String,
        variables: JSONObject,
    ): JSONObject {
        var session = ensureSession()
        return try {
            persistedGraphQlOnce(session, operationName, hash, variables)
        } catch (error: JoynBrowseHttpException) {
            if (error.statusCode !in setOf(401, 403)) throw error
            core.loadLiveChannels()
            session = ensureSession()
            persistedGraphQlOnce(session, operationName, hash, variables)
        } catch (error: JoynBrowseGraphQlException) {
            if (!error.details.contains("INVALID_JWT", ignoreCase = true)) throw error
            core.loadLiveChannels()
            session = ensureSession()
            persistedGraphQlOnce(session, operationName, hash, variables)
        }
    }

    private fun persistedGraphQlOnce(
        session: BrowseSession,
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
            if (!response.isSuccessful) throw JoynBrowseHttpException(response.code, body)
            JSONObject(body)
        }
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw JoynBrowseGraphQlException(operationName, errors.toString())
        }
        return json.optJSONObject("data")
            ?: error("Joyn $operationName lieferte keine Daten")
    }

    private suspend fun ensureSession(): BrowseSession {
        val selectedCountry = country
        val cacheKey = "api_key_${selectedCountry.name}"
        val cachedAt = prefs.getLong("${cacheKey}_at", 0L)
        val apiKeyFresh = !prefs.getString(cacheKey, null).isNullOrBlank() &&
            System.currentTimeMillis() - cachedAt < API_KEY_TTL_MS
        val storedToken = readStoredToken()
        val now = System.currentTimeMillis() / 1000L
        val tokenFresh = storedToken != null && now < storedToken.createdAt + storedToken.expiresIn - 1800L

        if (!apiKeyFresh || !tokenFresh) {
            core.loadCatalogue("/neu-beliebt")
        }

        val apiKey = prefs.getString(cacheKey, null)?.takeIf(String::isNotBlank)
            ?: error("Joyn API-Key ist nicht verfügbar")
        val token = readStoredToken() ?: error("Joyn Sitzung ist nicht verfügbar")
        return BrowseSession(
            country = selectedCountry,
            apiKey = apiKey,
            authorization = "${token.tokenType} ${token.accessToken}",
        )
    }

    private fun readStoredToken(): BrowseStoredToken? {
        val raw = prefs.getString("auth_token", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            BrowseStoredToken(
                accessToken = json.getString("accessToken"),
                tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer",
                expiresIn = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L,
                createdAt = json.optLong("createdAt"),
            )
        }.getOrNull()
    }

    private fun JSONObject.toBrowseMediaItem(): JoynMediaItem? {
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
            else -> JoynMediaType.UNKNOWN
        }
        val logo = optJSONObject("artLogoImage")?.urlValue()
            ?: optJSONObject("logo")?.urlValue()
            ?: brand?.optJSONObject("logo")?.urlValue()
        val primary = optJSONObject("primaryImage")?.urlValue()
            ?: optJSONObject("thumbnailImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: optJSONObject("heroPortraitImage")?.urlValue()
            ?: optJSONObject("heroPortrait")?.urlValue()
            ?: optJSONArray("images").firstImageUrl()
            ?: logo
        val backdrop = optJSONObject("heroLandscapeImage")?.urlValue()
            ?: optJSONObject("posterImage")?.urlValue()
            ?: primary
        return JoynMediaItem(
            id = id,
            title = title,
            description = optString("description").takeIf(String::isNotBlank)
                ?: optString("secondaryTitle").takeIf(String::isNotBlank),
            path = optString("path").takeIf(String::isNotBlank),
            type = type,
            imageUrl = primary,
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

    private fun JSONArray?.toMediaItems(): List<JoynMediaItem> = buildList {
        val source = this@toMediaItems ?: return@buildList
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.toBrowseMediaItem()?.let(::add)
        }
    }

    private fun JSONArray.firstMediaItem(): JoynMediaItem? {
        for (index in 0 until length()) {
            optJSONObject(index)?.toBrowseMediaItem()?.let { return it }
        }
        return null
    }

    private fun JSONObject.allBlocks(): List<JSONObject> = buildList {
        optJSONArray("blocks").appendObjectsTo(this)
        optJSONArray("lazyBlocks").appendObjectsTo(this)
    }

    private fun JSONArray?.appendObjectsTo(target: MutableList<JSONObject>) {
        val source = this ?: return
        for (index in 0 until source.length()) source.optJSONObject(index)?.let(target::add)
    }

    private fun JSONArray?.findObjectById(id: String): JSONObject? {
        val source = this ?: return null
        for (index in 0 until source.length()) {
            val value = source.optJSONObject(index) ?: continue
            if (value.optString("id") == id) return value
        }
        return null
    }

    private fun JSONObject.urlValue(): String? = optString("url").takeIf(String::isNotBlank)

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val source = this@toStringSet ?: return@buildSet
        for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun JSONArray?.firstImageUrl(): String? {
        val source = this ?: return null
        var fallback: String? = null
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val url = item.optString("url").takeIf(String::isNotBlank) ?: continue
            if (fallback == null) fallback = url
            if (item.optString("type") in setOf("LIVE_STILL", "PRIMARY", "HERO_LANDSCAPE")) return url
        }
        return fallback
    }

    private data class BrowseSession(
        val country: JoynCountry,
        val apiKey: String,
        val authorization: String,
    )

    private data class BrowseStoredToken(
        val accessToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val createdAt: Long,
    )

    private class JoynBrowseHttpException(val statusCode: Int, body: String) :
        IOException("Joyn Browse HTTP $statusCode: ${body.take(240)}")

    private class JoynBrowseGraphQlException(operation: String, val details: String) :
        IOException("Joyn $operation: ${details.take(260)}")

    companion object {
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val MAX_CHANNEL_PAGES = 40
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val CATEGORY_LANES = setOf("StandardLane", "CollectionLane", "FeaturedLane")
        private val COLLECTION_BLOCKS = setOf("StandardLane", "Grid", "FeaturedLane", "CollectionLane")

        private const val HASH_NAVIGATION = "818622ff5afe143664241034ba0c537650ec9a2eeae4d568830b47d8de605b7e"
        private const val HASH_LANDING_PAGE = "b71b3871aebfe266b63a4bf7daaa35645e17be2469b850265ba75146fa60affc"
        private const val HASH_LANDING_BLOCKS = "1655591f83b0dc1508ad4d52c5f37f72d410f48ad08c3e5f2de8622f86a21c68"
        private const val HASH_CHANNEL = "f61159391eed95487997fe2a9eab1fe25198e12b35f6206f60eb9f477187fab3"
        private const val HASH_COLLECTION = "bf3a273afa54de5a577de160cc82e55824b0e92c87c8bb0b470087eedaeb7c18"
        private const val HASH_COMPILATION = "f4103ea8a4ebecaf873e439029ea8e8e478031596097808596e7053b5618cae4"
    }
}
