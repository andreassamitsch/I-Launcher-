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

/**
 * Category resolver aligned with the current Kodi Joyn reference flow.
 *
 * Kodi does not try to recursively infer category contents from LandingPageClient. It keeps
 * the original lane id and, when a category is opened, requests exactly that id through
 * LandingBlocks. For authenticated accounts it additionally sends Joyn-User-State from
 * GetMeState. That header is important for account-aware persisted GraphQL queries.
 */
internal class JoynCategoryApiClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val core = JoynApiClient(appContext)
    private val proxySettings = JoynProxySettings(appContext)
    private val client = proxySettings.configure(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS),
    ).build()

    private val country: JoynCountry
        get() = JoynCountry.fromIsoCountry(Locale.getDefault().country)

    suspend fun loadCategory(blockId: String, fallbackTitle: String): JoynCataloguePage {
        val session = ensureSession()
        val account = loadAccountContext(session)
        val response = persistedGraphQl(
            session = session,
            operationName = "LandingBlocks",
            hash = HASH_LANDING_BLOCKS,
            variables = JSONObject().put("ids", JSONArray().put(blockId)),
            userState = account.userState,
        )

        val block = response.optJSONArray("blocks").findObjectById(blockId)
            ?: throw IOException(
                "Joyn Kategorie '$fallbackTitle' wurde von LandingBlocks nicht zurückgegeben " +
                    "(Block $blockId, angemeldet=${account.loggedIn}, User-State=${account.userState ?: "-"}).",
            )

        val assets = block.optJSONArray("assets") ?: JSONArray()
        val mapped = assets.toMediaItems()
        val items = mapped
            .filter { it.isFree || account.hasPlus }
            .distinctBy { "${it.type}:${it.id}" }

        if (items.isEmpty()) {
            val types = buildList {
                for (index in 0 until assets.length()) {
                    assets.optJSONObject(index)?.optString("__typename")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }.distinct()
            val fields = block.keys().asSequence().toList().sorted()
            throw IOException(
                "Joyn Kategorie '$fallbackTitle' ist leer " +
                    "(Block $blockId, assets=${assets.length()}, gemappt=${mapped.size}, " +
                    "Typen=${types.joinToString().ifBlank { "keine" }}, " +
                    "angemeldet=${account.loggedIn}, PLUS=${account.hasPlus}, " +
                    "User-State=${account.userState ?: "-"}, " +
                    "Felder=${fields.joinToString().ifBlank { "keine" }}).",
            )
        }

        val title = block.optString("headline").takeIf(String::isNotBlank)
            ?: block.optString("title").takeIf(String::isNotBlank)
            ?: fallbackTitle
        return JoynCataloguePage(
            title = title,
            lanes = listOf(JoynLane("category:$blockId", title, items)),
        )
    }

    /**
     * Kodi calls GetMeState for authenticated accounts and forwards me.state as Joyn-User-State
     * on subsequent GraphQL operations. Anonymous sessions deliberately do not send it.
     */
    private fun loadAccountContext(session: CategorySession): AccountContext {
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
            // Keep browsing possible if account metadata is temporarily unavailable. The detailed
            // category error below will expose that no user state could be obtained.
            AccountContext(loggedIn = true)
        }
    }

    private fun persistedGraphQl(
        session: CategorySession,
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

    private suspend fun ensureSession(): CategorySession {
        val selectedCountry = country
        val cacheKey = "api_key_${selectedCountry.name}"
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

        return CategorySession(
            country = selectedCountry,
            apiKey = prefs.getString(cacheKey, null)?.takeIf(String::isNotBlank)
                ?: error("Joyn API-Key ist nicht verfügbar"),
            authorization = token?.let { "${it.tokenType} ${it.accessToken}" }
                ?: error("Joyn Sitzung ist nicht verfügbar"),
            hasAccount = token?.hasAccount == true,
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
                hasAccount = json.optBoolean("hasAccount", false),
            )
        }.getOrNull()
    }

    private fun JSONArray?.findObjectById(id: String): JSONObject? {
        val source = this ?: return null
        for (index in 0 until source.length()) {
            val value = source.optJSONObject(index) ?: continue
            if (value.optString("id") == id) return value
        }
        return null
    }

    private fun JSONArray.toMediaItems(): List<JoynMediaItem> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.toMediaItem()?.let(::add)
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

    private data class CategorySession(
        val country: JoynCountry,
        val apiKey: String,
        val authorization: String,
        val hasAccount: Boolean,
    )

    private data class CategoryToken(
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
        private const val HASH_LANDING_BLOCKS = "1655591f83b0dc1508ad4d52c5f37f72d410f48ad08c3e5f2de8622f86a21c68"
        private const val HASH_ACCOUNT = "55ebb3812b45628017ee6c7f36f0b88a94e9778b9f11ad8a6fc05849182c07ec"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
