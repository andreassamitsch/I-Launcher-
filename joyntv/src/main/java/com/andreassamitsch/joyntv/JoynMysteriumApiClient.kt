package com.andreassamitsch.joyntv

import android.content.Context
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class JoynMysteriumProxyLease(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val expiresAt: String,
)

internal data class JoynMysteriumApiStatus(
    val reachable: Boolean,
    val authenticated: Boolean?,
    val subscriptionText: String = "",
    val residentialCountryCount: Int? = null,
    val resolvedBaseUrl: String = PRODUCTION_BASE_URL,
    val accountEmail: String = "",
    val message: String,
) {
    companion object {
        const val PRODUCTION_BASE_URL = "https://api.mysteriumvpn.com/api/v1"
    }
}

internal data class JoynMysteriumMagicLinkResult(
    val authenticated: Boolean,
    val codeReturnedDirectly: Boolean,
    val message: String,
)

/**
 * Client for Mysterium VPN's consumer API used by the current official app.
 *
 * Mysterium provides short-lived HTTP proxy credentials through connect-proxy. Joyn TV requests
 * residential leases directly from the consumer API; no localhost node, external Mysterium app or
 * Android VPN tunnel is required. Account tokens and proxy passwords are never logged.
 */
internal class JoynMysteriumApiClient(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val directClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun savedEmail(): String = prefs.getString(KEY_EMAIL, "").orEmpty()
    fun hasStoredAccessToken(): Boolean = accessToken().isNotBlank()

    fun saveManualAccessToken(token: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, token.trim().removePrefix("Bearer ").trim())
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_PKCE_VERIFIER)
            .apply()
    }

    suspend fun status(): JoynMysteriumApiStatus = withContext(Dispatchers.IO) {
        val config = executeGet("$PRODUCTION_BASE_URL/auth/config")
            ?: return@withContext JoynMysteriumApiStatus(
                reachable = false,
                authenticated = null,
                accountEmail = savedEmail(),
                message = "Mysterium API nicht erreichbar · $PRODUCTION_BASE_URL",
            )
        if (config.first !in 200..299) {
            return@withContext JoynMysteriumApiStatus(
                reachable = false,
                authenticated = null,
                accountEmail = savedEmail(),
                message = "Mysterium API HTTP ${config.first}",
            )
        }

        var token = accessToken()
        var auth = if (token.isBlank()) null else executeAuthorizedGet("/auth/check")
        if (token.isNotBlank() && (auth?.first == 401 || auth?.first == 403) && refreshToken().isNotBlank()) {
            if (refreshAccessToken()) {
                token = accessToken()
                auth = executeAuthorizedGet("/auth/check")
            }
        }
        val authenticated = when {
            token.isBlank() -> false
            auth == null -> null
            auth.first == 401 || auth.first == 403 -> false
            auth.first in 200..299 -> parseAuthenticated(auth.second) ?: true
            else -> null
        }

        val subscription = if (authenticated == true) executeAuthorizedGet("/subscription") else null
        val subscriptionText = subscription
            ?.takeIf { it.first in 200..299 }
            ?.second
            ?.let(::summarizeSubscription)
            .orEmpty()

        val locations = if (authenticated == true) {
            executeAuthorizedGet("/connection/config/locations?ip_type=residential")
        } else null
        val residentialCountries = locations
            ?.takeIf { it.first in 200..299 }
            ?.second
            ?.let(::countLocations)

        JoynMysteriumApiStatus(
            reachable = true,
            authenticated = authenticated,
            subscriptionText = subscriptionText,
            residentialCountryCount = residentialCountries,
            accountEmail = savedEmail(),
            message = buildString {
                append("Mysterium API erreichbar")
                when (authenticated) {
                    true -> append(" · angemeldet")
                    false -> append(" · nicht angemeldet")
                    null -> append(" · Loginstatus unbekannt")
                }
                if (residentialCountries != null) append(" · Residential-Länder=$residentialCountries")
                if (subscriptionText.isNotBlank()) append(" · $subscriptionText")
            },
        )
    }

    suspend fun requestMagicLink(email: String): Result<JoynMysteriumMagicLinkResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanEmail = email.trim()
            require(cleanEmail.contains('@')) { "Bitte eine gültige Mysterium-E-Mail-Adresse eingeben." }

            val verifier = generatePkceVerifier()
            val challenge = pkceChallenge(verifier)
            prefs.edit()
                .putString(KEY_EMAIL, cleanEmail)
                .putString(KEY_PKCE_VERIFIER, verifier)
                .apply()

            val payload = JSONObject()
                .put("email", cleanEmail)
                .put("client_id", "app")
                .put("code_challenge", challenge)
                .put("code_challenge_method", "s256")

            val response = executeJsonPost("/magic-link", payload, authorized = false)
                ?: error("Keine Antwort von Mysterium")
            if (response.first !in 200..299) {
                error("Magic-Link HTTP ${response.first}: ${extractError(response.second).ifBlank { compact(response.second) }}")
            }
            val json = runCatching { JSONObject(response.second) }.getOrElse { JSONObject() }
            val code = json.optString("code").trim()
            if (code.isNotBlank()) {
                exchangeAuthorizationCode(code, verifier)
                JoynMysteriumMagicLinkResult(
                    authenticated = true,
                    codeReturnedDirectly = true,
                    message = "Mysterium-Anmeldung erfolgreich.",
                )
            } else {
                JoynMysteriumMagicLinkResult(
                    authenticated = false,
                    codeReturnedDirectly = false,
                    message = "Magic-Link wurde gesendet. Öffne die Mail auf Handy/PC und füge die Link-Adresse hier ein.",
                )
            }
        }
    }

    suspend fun completeMagicLink(linkOrCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val verifier = prefs.getString(KEY_PKCE_VERIFIER, "").orEmpty()
            if (verifier.isBlank()) error("Kein offener Mysterium-Login. Bitte zuerst Magic-Link senden.")
            val code = extractMagicLinkCode(linkOrCode)
                ?: error("Kein gültiger Mysterium-Code im eingegebenen Link gefunden.")
            exchangeAuthorizationCode(code, verifier)
        }
    }

    suspend fun requestResidentialProxy(
        country: JoynCountry,
        resetConnection: Boolean = true,
    ): Result<JoynMysteriumProxyLease> = withContext(Dispatchers.IO) {
        runCatching {
            ensureAccessToken()
            val payload = JSONObject()
                .put("country", country.name)
                .put("ip_type", "residential")
                .put("reset_connection", resetConnection)
                .put("os_type", "android")

            var response = executeJsonPost("/connection/connect-proxy", payload, authorized = true)
                ?: error("Keine Antwort von Mysterium")
            if (response.first == 401 && refreshAccessToken()) {
                response = executeJsonPost("/connection/connect-proxy", payload, authorized = true)
                    ?: error("Keine Antwort von Mysterium nach Token-Erneuerung")
            }
            val body = response.second
            if (response.first !in 200..299) {
                val detail = extractError(body).ifBlank { compact(body) }
                error("HTTP ${response.first}${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }
            val root = JSONObject(body)
            if (root.optBoolean("limit_exceeded", false) || root.optBoolean("limitExceeded", false)) {
                error("Mysterium hat das Proxy-/IP-Wechsel-Limit erreicht.")
            }
            val proxy = root.optJSONObject("proxy_config")
                ?: root.optJSONObject("proxyConfig")
                ?: error("Mysterium-Antwort enthält kein proxy_config.")
            val host = proxy.optString("host").trim()
            val port = proxy.optString("port").toIntOrNull() ?: proxy.optInt("port", 0)
            val username = proxy.optString("username")
            val password = proxy.optString("password")
            val expiresAt = proxy.optString("expires_at").ifBlank { proxy.optString("expiresAt") }
            if (host.isBlank() || port !in 1..65535) error("Ungültige Mysterium-Proxyadresse")
            JoynMysteriumProxyLease(host, port, username, password, expiresAt)
        }
    }

    suspend fun residentialLocationSummary(): String = withContext(Dispatchers.IO) {
        if (accessToken().isBlank()) return@withContext "Locations: nicht angemeldet"
        var response = executeAuthorizedGet("/connection/config/locations?ip_type=residential")
            ?: return@withContext "Locations: API nicht erreichbar"
        if (response.first == 401 && refreshAccessToken()) {
            response = executeAuthorizedGet("/connection/config/locations?ip_type=residential")
                ?: return@withContext "Locations: API nicht erreichbar"
        }
        if (response.first !in 200..299) return@withContext "Locations: HTTP ${response.first}"
        summarizeLocations(response.second)
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String) {
        val payload = JSONObject()
            .put("grant_type", "authorization_code")
            .put("client_id", "app")
            .put("device", deviceJson())
            .put("code_verifier", verifier)
            .put("code", code)
        val response = executeJsonPost("/oauth/token", payload, authorized = false)
            ?: error("Keine Antwort vom Mysterium-Token-Endpunkt")
        if (response.first !in 200..299) {
            error("Mysterium Login HTTP ${response.first}: ${extractError(response.second).ifBlank { compact(response.second) }}")
        }
        saveTokenResponse(response.second)
        prefs.edit().remove(KEY_PKCE_VERIFIER).apply()
    }

    private fun refreshAccessToken(): Boolean {
        val refresh = refreshToken()
        if (refresh.isBlank()) return false
        val payload = JSONObject()
            .put("grant_type", "refresh_token")
            .put("client_id", "app")
            .put("device", deviceJson())
            .put("refresh_token", refresh)
        val response = executeJsonPost("/oauth/token", payload, authorized = false) ?: return false
        if (response.first !in 200..299) return false
        return runCatching { saveTokenResponse(response.second); true }.getOrDefault(false)
    }

    private fun saveTokenResponse(body: String) {
        val json = JSONObject(body)
        val access = json.optString("access_token").ifBlank { json.optString("accessToken") }
        if (access.isBlank()) error("Mysterium-Antwort enthält kein access_token.")
        val refresh = json.optString("refresh_token").ifBlank { json.optString("refreshToken") }
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .apply {
                if (refresh.isNotBlank()) putString(KEY_REFRESH_TOKEN, refresh)
            }
            .apply()
    }

    private fun ensureAccessToken() {
        if (accessToken().isNotBlank()) return
        if (refreshAccessToken()) return
        error("Nicht bei Mysterium angemeldet. Bitte zuerst Magic-Link verwenden.")
    }

    private fun executeAuthorizedGet(path: String): Pair<Int, String>? =
        executeGet("$PRODUCTION_BASE_URL$path", bearer = accessToken().takeIf(String::isNotBlank))

    private fun executeGet(url: String, bearer: String? = null): Pair<Int, String>? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-client-version", CLIENT_VERSION)
            .header("x-client-platform", "android")
        if (!bearer.isNullOrBlank()) builder.header("Authorization", "Bearer $bearer")
        directClient.newCall(builder.get().build()).execute().use { response -> response.code to response.body.string() }
    }.getOrNull()

    private fun executeJsonPost(
        path: String,
        payload: JSONObject,
        authorized: Boolean,
    ): Pair<Int, String>? = runCatching {
        val builder = Request.Builder()
            .url("$PRODUCTION_BASE_URL$path")
            .header("Accept", "application/json")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("User-Agent", USER_AGENT)
            .header("x-client-version", CLIENT_VERSION)
            .header("x-client-platform", "android")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (authorized) accessToken().takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        directClient.newCall(builder.build()).execute().use { response -> response.code to response.body.string() }
    }.getOrNull()

    private fun parseAuthenticated(body: String): Boolean? = runCatching {
        val json = JSONObject(body)
        when {
            json.has("authenticated") -> json.optBoolean("authenticated")
            json.has("is_authenticated") -> json.optBoolean("is_authenticated")
            json.has("authorized") -> json.optBoolean("authorized")
            json.has("user_id") || json.has("userId") || json.has("user") || json.has("account") -> true
            else -> null
        }
    }.getOrNull()

    private fun summarizeSubscription(body: String): String = runCatching {
        val json = JSONObject(body)
        val status = listOf("status", "state", "plan_status")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        val plan = json.optJSONObject("plan")?.let { p -> p.optString("name").ifBlank { p.optString("id") } }.orEmpty()
        buildString {
            if (!status.isNullOrBlank()) append("Abo=$status")
            if (plan.isNotBlank()) {
                if (isNotEmpty()) append("/")
                append(plan)
            }
        }
    }.getOrDefault("")

    private fun summarizeLocations(body: String): String = runCatching {
        val array = when {
            body.trimStart().startsWith("[") -> JSONArray(body)
            else -> JSONObject(body).optJSONArray("locations") ?: JSONArray()
        }
        val countries = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val code = item.optString("country").ifBlank { item.optString("country_code") }.uppercase()
                val total = item.optInt("total", item.optInt("node_count", 0))
                if (code.isNotBlank()) add(if (total > 0) "$code=$total" else code)
            }
        }
        "Residential-Locations: ${countries.joinToString(", ")}".take(1500)
    }.getOrElse { "Locations: JSON nicht lesbar (${it.javaClass.simpleName})" }

    private fun countLocations(body: String): Int? = runCatching {
        if (body.trimStart().startsWith("[")) JSONArray(body).length()
        else JSONObject(body).optJSONArray("locations")?.length()
    }.getOrNull()

    private fun extractError(body: String): String = runCatching {
        val json = JSONObject(body)
        json.optString("message")
            .ifBlank { json.optString("error_description") }
            .ifBlank { json.optString("error") }
            .ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
    }.getOrDefault("")

    private fun accessToken(): String = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty().trim()
    private fun refreshToken(): String = prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty().trim()

    private fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("os_type", "android")
        .put("id", deviceId())
        .put("title", "Joyn TV")

    private fun generatePkceVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun extractMagicLinkCode(input: String): String? {
        val value = input.trim()
        if (UUID_REGEX.matches(value)) return value
        val queryCode = runCatching {
            URI(value).rawQuery?.split('&')?.firstOrNull { it.startsWith("code=") }?.substringAfter("code=")
        }.getOrNull()
        if (!queryCode.isNullOrBlank()) return queryCode
        return CODE_IN_TEXT.find(value)?.groupValues?.getOrNull(1)
    }

    private fun compact(value: String): String = value.replace('\n', ' ').replace('\r', ' ').take(240)

    companion object {
        const val PRODUCTION_BASE_URL = "https://api.mysteriumvpn.com/api/v1"
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_EMAIL = "mysterium_account_email"
        private const val KEY_ACCESS_TOKEN = "mysterium_access_token"
        private const val KEY_REFRESH_TOKEN = "mysterium_refresh_token"
        private const val KEY_PKCE_VERIFIER = "mysterium_pkce_verifier"
        private const val KEY_DEVICE_ID = "mysterium_device_id"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CLIENT_VERSION = "joyntv-1"
        private const val USER_AGENT = "JoynTV/AndroidTV Mysterium-Residential-Integration"
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}$")
        private val CODE_IN_TEXT = Regex("(?:[?&]code=|\\bcode=)([^&\\s]+)")
    }
}
