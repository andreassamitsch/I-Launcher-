package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves Joyn teaser/collection pages the same way as the Kodi reference client.
 *
 * PageCollectionsDetail does not mean that every returned block is already a media list:
 * - StandardLane is another folder and must be opened later through LandingBlocks(block.id).
 * - Grid contains directly displayable media assets.
 *
 * Treating StandardLane.assets as final content was the reason genre teasers such as Action or
 * Horror could open as a formally valid but empty page.
 */
internal class JoynCollectionApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val regionSettings = JoynRegionSettings(appContext)
    private val core = JoynApiClient(appContext)
    private val proxySettings = JoynProxySettings(appContext)
    private val client = proxySettings.configure(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS),
    ).build()

    suspend fun loadCollection(path: String, fallbackTitle: String): JoynCataloguePage {
        val session = ensureSession()
        val account = loadAccountContext(session)
        val response = persistedGraphQl(
            session = session,
            operationName = "PageCollectionsDetail",
            hash = HASH_COLLECTION,
            variables = JSONObject().put("path", path),
            userState = account.userState,
        )
        val page = response.optJSONObject("page")
            ?: throw IOException("Joyn Sammlung '$fallbackTitle' wurde nicht gefunden ($path).")

        val blocks = page.optJSONArray("blocks") ?: JSONArray()
        val folderItems = mutableListOf<JoynMediaItem>()
        val lanes = mutableListOf<JoynLane>()
        val blockTypes = mutableListOf<String>()

        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            val type = block.optString("__typename")
            blockTypes += type.ifBlank { "?" }
            val blockId = block.optString("id").takeIf(String::isNotBlank)
            val blockTitle = block.optString("headline").takeIf(String::isNotBlank)
                ?: block.optString("title").takeIf(String::isNotBlank)
                ?: fallbackTitle

            when (type) {
                // Kodi deliberately opens StandardLane as a category folder. Its assets are not
                // treated as the final list here; LandingBlocks resolves the block on click.
                "StandardLane" -> {
                    if (blockId == null) continue
                    val preview = block.optJSONArray("assets").firstMediaItem()
                    folderItems += JoynMediaItem(
                        id = "category:$blockId",
                        title = blockTitle,
                        description = block.optString("description").takeIf(String::isNotBlank),
                        path = blockId,
                        type = JoynMediaType.CATEGORY,
                        imageUrl = preview?.imageUrl ?: preview?.backdropUrl,
                        backdropUrl = preview?.backdropUrl ?: preview?.imageUrl,
                    )
                }

                // Grid is the one collection block Kodi renders directly as media items.
                "Grid" -> {
                    val mapped = block.optJSONArray("assets").toMediaItems()
                    val items = mapped
                        .filter { isAvailable(it, account.hasPlus) }
                        .distinctBy { "${it.type}:${it.id}" }
                    if (items.isNotEmpty()) {
                        lanes += JoynLane(
                            id = "collection-grid:${blockId ?: index}",
                            title = blockTitle,
                            items = items,
                        )
                    }
                }
            }
        }

        if (folderItems.isNotEmpty()) {
            lanes.add(
                0,
                JoynLane(
                    id = "collection-folders:$path",
                    title = "Bereiche",
                    items = folderItems.distinctBy { it.id },
                ),
            )
        }

        if (lanes.none { it.items.isNotEmpty() }) {
            throw IOException(
                "Joyn Sammlung '$fallbackTitle' enthält keine darstellbaren Inhalte " +
                    "(Pfad=$path, blocks=${blocks.length()}, " +
                    "Typen=${blockTypes.distinct().joinToString().ifBlank { "keine" }}, " +
                    "angemeldet=${account.loggedIn}, PLUS=${account.hasPlus}, " +
                    "User-State=${account.userState ?: "-"}).",
            )
        }

        return JoynCataloguePage(
            title = page.optString("title").takeIf(String::isNotBlank) ?: fallbackTitle,
            lanes = lanes,
        )
    }

    private fun isAvailable(item: JoynMediaItem, hasPlus: Boolean): Boolean {
        if (hasPlus) return true
        if (item.licenseTypes.isEmpty()) return true

        // Match Kodi's license logic: a title can expose more than one license type. AVOD/FVOD
        // remains playable for a free account even if SVOD is also present.
        val hasFreeLicense = item.licenseTypes.any { it == "AVOD" || it == "FVOD" }
        if (!hasFreeLicense) return false
        return "PLUS" !in item.markings && "PREMIUM" !in item.markings
    }

    private fun loadAccountContext(session: CollectionSession): AccountContext {
        if (!session.hasAccount) return AccountContext(loggedIn = false)

        return runCatching {
            val response = persistedGraphQl(
                session = session,
                operationName = "GetMeState",
                hash = HASH_ACCOUNT,
                variables = JSONObject(),
                userState = null,
            )
            val me = response.optJSONObject("me")
            val subscriptions = me?.optJSONObject("subscriptionsData")?.optJSONObject("config")
            AccountContext(
                loggedIn = true,
                userState = me?.optString("state")?.takeIf(String::isNotBlank) ?: "code=R_A",
                hasPlus = subscriptions?.optBoolean("hasActivePlus", false) ?: false,
            )
        }.getOrElse {
            AccountContext(loggedIn = true)
        }
    }

    private fun persistedGraphQl(
        session: CollectionSession,
        operationName: String,
        hash: String,
        variables: JSONObject,
        userState: String?,
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
            .header("Accept", "application/json")
            .header("x-api-key", session.apiKey)
            .header("Joyn-Platform", "web")
            .header("Joyn-Country", session.country.name)
            .header("Joyn-Distribution-Tenant", session.country.graphqlTenant)
            .header("Authorization", session.authorization)
            .apply {
                if (!userState.isNullOrBlank() && operationName != "GetMeState") {
                    header("Joyn-User-State", userState)
                }
            }
            .get()
            .build()

        val json = client.newCall(request).execute().use { http ->
            val body = http.body.string()
            if (!http.isSuccessful) {
                throw IOException("Joyn $operationName HTTP ${http.code}: ${body.take(320)}")
            }
            JSONObject(body)
        }
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw IOException("Joyn $operationName: ${errors.toString().take(360)}")
        }
        return json.optJSONObject("data")
            ?: throw IOException("Joyn $operationName lieferte keine Daten")
    }

    private suspend fun ensureSession(): CollectionSession {
        val country = regionSettings.currentCountry()
        val cacheKey = "api_key_${country.name}"
        var token = readToken()
        val nowMs = System.currentTimeMillis()
        val now = nowMs / 1000L
        val keyFresh = !prefs.getString(cacheKey, null).isNullOrBlank() &&
            nowMs - prefs.getLong("${cacheKey}_at", 0L) < API_KEY_TTL_MS
        val tokenFresh = token != null && now < token.createdAt + token.expiresIn - 1800L

        if (!keyFresh || !tokenFresh) {
            core.loadCatalogue("/neu-beliebt")
            token = readToken()
        }

        return CollectionSession(
            country = country,
            apiKey = prefs.getString(cacheKey, null)?.takeIf(String::isNotBlank)
                ?: error("Joyn API-Key ist nicht verfügbar"),
            authorization = token?.let { "${it.tokenType} ${it.accessToken}" }
                ?: error("Joyn Sitzung ist nicht verfügbar"),
            hasAccount = token?.hasAccount == true,
        )
    }

    private fun readToken(): CollectionToken? {
        val raw = prefs.getString("auth_token", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CollectionToken(
                accessToken = json.getString("accessToken"),
                tokenType = json.optString("tokenType").takeIf(String::isNotBlank) ?: "Bearer",
                expiresIn = json.optLong("expiresIn").takeIf { it > 0L } ?: 3600L,
                createdAt = json.optLong("createdAt"),
                hasAccount = json.optBoolean("hasAccount", false),
            )
        }.getOrNull()
    }

    private fun JSONArray?.firstMediaItem(): JoynMediaItem? {
        val source = this ?: return null
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.toMediaItem()?.let { return it }
        }
        return null
    }

    private fun JSONArray?.toMediaItems(): List<JoynMediaItem> = buildList {
        val source = this@toMediaItems ?: return@buildList
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.toMediaItem()?.let(::add)
        }
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
            ?: optJSONObject("heroPortrait")?.urlValue()
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
        for (index in 0 until source.length()) {
            val image = source.optJSONObject(index) ?: continue
            val url = image.optString("url").takeIf(String::isNotBlank) ?: continue
            if (fallback == null) fallback = url
            if (image.optString("type") in setOf("LIVE_STILL", "PRIMARY", "HERO_LANDSCAPE")) return url
        }
        return fallback
    }

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val source = this@toStringSet ?: return@buildSet
        for (index in 0 until source.length()) {
            source.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private data class CollectionSession(
        val country: JoynCountry,
        val apiKey: String,
        val authorization: String,
        val hasAccount: Boolean,
    )

    private data class CollectionToken(
        val accessToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val createdAt: Long,
        val hasAccount: Boolean,
    )

    private data class AccountContext(
        val loggedIn: Boolean,
        val userState: String? = null,
        val hasPlus: Boolean = false,
    )

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val API_KEY_TTL_MS = 5L * 24L * 60L * 60L * 1000L
        private const val HASH_COLLECTION = "bf3a273afa54de5a577de160cc82e55824b0e92c87c8bb0b470087eedaeb7c18"
        private const val HASH_ACCOUNT = "55ebb3812b45628017ee6c7f36f0b88a94e9778b9f11ad8a6fc05849182c07ec"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
