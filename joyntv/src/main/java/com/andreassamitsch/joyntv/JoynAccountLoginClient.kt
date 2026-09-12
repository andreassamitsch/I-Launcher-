package com.andreassamitsch.joyntv

import android.content.Context
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Performs Joyn account login with the shortest supported 7Pass/Cidaas authorization-code flow.
 *
 * The former implementation mirrored an old browser/Kodi sequence and called registration setup,
 * account existence and verification-list endpoints before every password submission. Those calls
 * are not required to start an authorization-code login and make TV logins much more likely to hit
 * 7Pass anti-abuse/rate limits, especially when AT/DE/CH are authenticated one after another.
 *
 * This client deliberately performs no automatic credential retry:
 *   1. ask Joyn for the current web-login/redeem endpoints,
 *   2. open the authorization request and capture its requestId,
 *   3. submit username/password once,
 *   4. satisfy consent only if 7Pass explicitly asks for it,
 *   5. exchange the returned authorization code for Joyn tokens.
 *
 * Passwords never leave this call and are never persisted. Only Joyn's resulting access/refresh
 * token is saved through JoynRegionSettings for the selected market.
 */
internal class JoynAccountLoginClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val regionSettings = JoynRegionSettings(appContext)

    suspend fun login(country: JoynCountry, email: String, password: String) {
        require(email.contains('@')) { "Bitte eine gültige E-Mail-Adresse eingeben." }
        require(password.length >= 6) { "Das Passwort ist zu kurz." }
        enforceLocalCooldown()

        val cookieJar = LoginCookieJar()
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()

        val deviceClientId = stableUuid(KEY_CLIENT_ID)
        val endpointsUrl = "$JOYN_SSO_ENDPOINTS".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", deviceClientId)
            .addQueryParameter("client_name", "web")
            .build()
        val endpoints = JSONObject(
            execute(
                client = client,
                request = Request.Builder()
                    .url(endpointsUrl)
                    .apply { joynHeaders(country) }
                    .header("Accept", "application/json")
                    .get()
                    .build(),
            ).body,
        )

        val webLogin = endpoints.optString("web-login").takeIf(String::isNotBlank)
            ?: error("Joyn Login-Endpunkt fehlt.")
        val redeemToken = endpoints.optString("redeem-token").takeIf(String::isNotBlank)
            ?: "$JOYN_AUTH_BASE/7pass/token"

        val initialUrl = webLogin.toHttpUrl()
        val sevenPassClientId = initialUrl.queryParameter("client_id")
            ?.takeIf(String::isNotBlank)
            ?: deviceClientId
        val redirectUri = initialUrl.queryParameter("redirect_uri")
            ?.takeIf(String::isNotBlank)
            ?: "https://www.joyn.${country.webSuffix}/oauth"

        val authContext = obtainRequestId(
            client = client,
            country = country,
            initialUrl = initialUrl,
            sevenPassClientId = sevenPassClientId,
            redirectUri = redirectUri,
        )

        val loginResult = execute(
            client = client,
            request = Request.Builder()
                .url(SEVENPASS_LOGIN)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Origin", SEVENPASS_ORIGIN)
                .header("Referer", authContext.referer)
                .post(
                    FormBody.Builder()
                        .add("username", email.trim())
                        .add("password", password)
                        .add("requestId", authContext.requestId)
                        .build(),
                )
                .build(),
            allowRedirect = true,
        )

        var redirect = loginResult.redirectUrl()
            ?: loginResult.body.redirectUrlFromBody()
            ?: throw JoynLoginException(
                "Joyn/7Pass hat die Anmeldung nicht abgeschlossen. Bitte nicht mehrfach hintereinander versuchen.",
            )

        if (redirect.queryParameter("code").isNullOrBlank()) {
            redirect = completeRequiredPrecheck(
                client = client,
                redirect = redirect,
                sevenPassClientId = sevenPassClientId,
            )
        }

        val code = redirect.queryParameter("code")?.takeIf(String::isNotBlank)
            ?: throw JoynLoginException("Joyn/7Pass lieferte keinen Anmeldecode.")

        // Keep the token exchange intentionally minimal. tracking_id/code_verifier are legacy
        // additions and are not needed for Joyn's current web authorization-code exchange.
        val payload = JSONObject()
            .put("client_id", sevenPassClientId)
            .put("code", code)
            .put("redirect_uri", redirectUri)
            .put("tracking_name", "web")

        val tokenResponse = execute(
            client = client,
            request = Request.Builder()
                .url(redeemToken)
                .apply { joynHeaders(country) }
                .header("Accept", "application/json")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val token = JSONObject(tokenResponse.body)
        val accessToken = token.optString("access_token").takeIf(String::isNotBlank)
            ?: throw JoynLoginException("Joyn lieferte nach der Anmeldung keinen Access-Token.")
        val refreshToken = token.optString("refresh_token").takeIf(String::isNotBlank)
            ?: throw JoynLoginException("Joyn lieferte nach der Anmeldung keinen Refresh-Token.")

        val stored = JSONObject()
            .put("accessToken", accessToken)
            .put("refreshToken", refreshToken)
            .put("tokenType", token.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer")
            .put("expiresIn", token.optLong("expires_in").takeIf { it > 0L } ?: 3600L)
            .put("createdAt", System.currentTimeMillis() / 1000L)
            .put("hasAccount", true)
            .put("email", email.trim())
        regionSettings.saveSessionJson(country, stored.toString())
        clearLocalCooldown()
    }

    private fun obtainRequestId(
        client: OkHttpClient,
        country: JoynCountry,
        initialUrl: HttpUrl,
        sevenPassClientId: String,
        redirectUri: String,
    ): LoginAuthorizationContext {
        // First use Joyn's exact current web-login URL. This preserves any scope/state parameters
        // Joyn adds server-side instead of hard-coding a stale browser bundle contract.
        findRequestIdThroughRedirects(client, country, initialUrl)?.let { return it }

        // Defensive fallback for endpoint variants that only expose a client id.
        val direct = "$SEVENPASS_ORIGIN/authz-srv/authz".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", sevenPassClientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .build()
        return findRequestIdThroughRedirects(client, country, direct)
            ?: throw JoynLoginException("Joyn/7Pass konnte keine requestId für die Anmeldung erzeugen.")
    }

    private fun findRequestIdThroughRedirects(
        client: OkHttpClient,
        country: JoynCountry,
        initialUrl: HttpUrl,
    ): LoginAuthorizationContext? {
        var current = initialUrl
        repeat(MAX_AUTH_REDIRECTS) {
            current.queryParameter("requestId")?.takeIf(String::isNotBlank)?.let { requestId ->
                return LoginAuthorizationContext(requestId, current.toString())
            }

            val result = execute(
                client = client,
                request = Request.Builder()
                    .url(current)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .apply {
                        if (current.host.endsWith("joyn.de")) joynHeaders(country)
                    }
                    .get()
                    .build(),
                allowRedirect = true,
            )
            val next = result.redirectUrl() ?: return null
            next.queryParameter("requestId")?.takeIf(String::isNotBlank)?.let { requestId ->
                return LoginAuthorizationContext(requestId, next.toString())
            }
            current = next
        }
        return null
    }

    private fun completeRequiredPrecheck(
        client: OkHttpClient,
        redirect: HttpUrl,
        sevenPassClientId: String,
    ): HttpUrl {
        val subject = redirect.queryParameter("sub")?.takeIf(String::isNotBlank)
        val trackId = redirect.queryParameter("track_id")
            ?.takeIf(String::isNotBlank)
            ?: redirect.queryParameter("cd1")?.takeIf(String::isNotBlank)
            ?: throw JoynLoginException("Joyn Login benötigt eine zusätzliche Bestätigung, aber die Tracking-ID fehlt.")

        // 7Pass does not always return `sub` for accounts that already accepted the current
        // consent. In that case the pending login only needs precheck/continue. Sending another
        // consent request is both unnecessary and can trigger the 7Pass abuse protection.
        if (subject != null) {
            val consentPayload = JSONObject()
                .put("sub", subject)
                .put("client_id", sevenPassClientId)
                .put("scopes", JSONArray().put(JSONObject().put("offline_access", "denied")))
            execute(
                client = client,
                request = Request.Builder()
                    .url(SEVENPASS_CONSENT)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .header("Origin", SEVENPASS_ORIGIN)
                    .header("Referer", redirect.toString())
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .post(consentPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            )
        }

        val continued = execute(
            client = client,
            request = Request.Builder()
                .url("$SEVENPASS_CONTINUE/$trackId")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Origin", SEVENPASS_ORIGIN)
                .header("Referer", redirect.toString())
                .post(ByteArray(0).toRequestBody(null))
                .build(),
            allowRedirect = true,
        )
        return continued.redirectUrl()
            ?: continued.body.redirectUrlFromBody()
            ?: throw JoynLoginException("Joyn Login-Bestätigung wurde nicht abgeschlossen.")
    }

    private fun execute(
        client: OkHttpClient,
        request: Request,
        allowRedirect: Boolean = false,
    ): LoginHttpResult = client.newCall(request).execute().use { response ->
        val body = response.body.string()
        val redirect = response.header("Location")
        if (response.code in 200..299 || (allowRedirect && response.code in 300..399 && !redirect.isNullOrBlank())) {
            return@use LoginHttpResult(
                code = response.code,
                body = body,
                requestUrl = response.request.url,
                location = redirect,
            )
        }
        throw mapHttpFailure(response.code, body, response.header("Retry-After"))
    }

    private fun mapHttpFailure(statusCode: Int, body: String, retryAfter: String?): IOException {
        if (statusCode == 429) {
            val seconds = retryAfterSeconds(retryAfter) ?: DEFAULT_RATE_LIMIT_SECONDS
            setLocalCooldown(seconds)
            return JoynLoginRateLimitException(
                "Joyn/7Pass hat weitere Anmeldungen wegen zu vieler Versuche vorübergehend gesperrt. " +
                    "Bitte ${humanWait(seconds)} keine neue Anmeldung senden; vorhandene Länder-Anmeldungen bleiben erhalten.",
            )
        }
        if (statusCode == 418 || statusCode == 428) {
            setLocalCooldown(SHORT_PROTECTION_SECONDS)
            return JoynLoginProtectionException(
                "Joyn/7Pass hat diesen Login-Versuch durch den Anmeldeschutz abgelehnt (HTTP $statusCode). " +
                    "Das ist nicht automatisch ein falsches Passwort. Bitte kurz warten und nur einmal erneut versuchen.",
            )
        }
        val description = runCatching {
            JSONObject(body).optString("error_description").takeIf(String::isNotBlank)
        }.getOrNull()
        return JoynLoginException(
            description ?: "Joyn Login HTTP $statusCode: ${compact(body)}",
        )
    }

    private fun enforceLocalCooldown() {
        val until = prefs.getLong(KEY_LOGIN_BLOCK_UNTIL_MS, 0L)
        val remainingMs = until - System.currentTimeMillis()
        if (remainingMs <= 0L) return
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        throw JoynLoginRateLimitException(
            "Anmeldeschutz aktiv. Bitte noch ${humanWait(seconds)} warten; es wird bewusst keine weitere Joyn-Anfrage gesendet.",
        )
    }

    private fun setLocalCooldown(seconds: Long) {
        prefs.edit()
            .putLong(KEY_LOGIN_BLOCK_UNTIL_MS, System.currentTimeMillis() + seconds.coerceAtLeast(1L) * 1000L)
            .apply()
    }

    private fun clearLocalCooldown() {
        prefs.edit().remove(KEY_LOGIN_BLOCK_UNTIL_MS).apply()
    }

    private fun retryAfterSeconds(value: String?): Long? {
        val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        return raw.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun humanWait(seconds: Long): String = when {
        seconds >= 3600L -> "ca. ${((seconds + 3599L) / 3600L)} Stunde(n)"
        seconds >= 60L -> "ca. ${((seconds + 59L) / 60L)} Minute(n)"
        else -> "ca. $seconds Sekunden"
    }

    private fun Request.Builder.joynHeaders(country: JoynCountry): Request.Builder =
        header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Joyn-Country", country.name)
            .header("Joyn-Distribution-Tenant", country.authTenant)
            .header("Joyn-Platform", "web")
            .header("Joyn-Request-Id", UUID.randomUUID().toString())
            .header("Origin", "https://www.joyn.${country.webSuffix}")
            .header("Referer", "https://www.joyn.${country.webSuffix}/")

    private fun stableUuid(key: String): String {
        prefs.getString(key, null)?.takeIf(String::isNotBlank)?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(key, value).apply()
        return value
    }

    private fun LoginHttpResult.redirectUrl(): HttpUrl? {
        val raw = location?.takeIf(String::isNotBlank) ?: return null
        return requestUrl.resolve(raw)
    }

    private fun String.redirectUrlFromBody(): HttpUrl? = runCatching {
        val root = JSONObject(this)
        val candidates = buildList {
            listOf("redirect_url", "redirectUrl", "redirect", "url", "location").forEach { key ->
                root.optString(key).takeIf(String::isNotBlank)?.let(::add)
            }
            val data = root.optJSONObject("data")
            if (data != null) {
                listOf("redirect_url", "redirectUrl", "redirect", "url", "location").forEach { key ->
                    data.optString(key).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        candidates.firstNotNullOfOrNull { value -> runCatching { value.toHttpUrl() }.getOrNull() }
    }.getOrNull()

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(260)

    private data class LoginAuthorizationContext(
        val requestId: String,
        val referer: String,
    )

    private data class LoginHttpResult(
        val code: Int,
        val body: String,
        val requestUrl: HttpUrl,
        val location: String?,
    )

    private class LoginCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { incoming ->
                this.cookies.removeAll { existing ->
                    existing.name == incoming.name &&
                        existing.domain == incoming.domain &&
                        existing.path == incoming.path
                }
                if (incoming.expiresAt > System.currentTimeMillis()) this.cookies += incoming
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }

    companion object {
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_LOGIN_BLOCK_UNTIL_MS = "joyn_login_block_until_ms"

        private const val JOYN_SSO_ENDPOINTS = "https://auth.joyn.de/sso/endpoints"
        private const val JOYN_AUTH_BASE = "https://auth.joyn.de/auth"
        private const val SEVENPASS_ORIGIN = "https://auth.7pass.de"
        private const val SEVENPASS_LOGIN = "$SEVENPASS_ORIGIN/login-srv/login"
        private const val SEVENPASS_CONSENT = "$SEVENPASS_ORIGIN/consent-management-srv/consent/scope/accept"
        private const val SEVENPASS_CONTINUE = "$SEVENPASS_ORIGIN/login-srv/precheck/continue"

        private const val MAX_AUTH_REDIRECTS = 6
        private const val SHORT_PROTECTION_SECONDS = 90L
        private const val DEFAULT_RATE_LIMIT_SECONDS = 10L * 60L
        private const val ACCEPT_LANGUAGE = "de-DE,de;q=0.9,en;q=0.8"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal open class JoynLoginException(message: String) : IOException(message)
internal class JoynLoginProtectionException(message: String) : JoynLoginException(message)
internal class JoynLoginRateLimitException(message: String) : JoynLoginException(message)
