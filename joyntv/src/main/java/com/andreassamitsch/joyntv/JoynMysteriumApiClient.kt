package com.andreassamitsch.joyntv

import java.net.Proxy
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
    val message: String,
)

internal class JoynMysteriumApiClient {
    private val directClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun status(baseUrl: String): JoynMysteriumApiStatus = withContext(Dispatchers.IO) {
        val base = normalize(baseUrl)
        val health = executeGet("$base/healthcheck")
        if (health == null) {
            return@withContext JoynMysteriumApiStatus(
                reachable = false,
                authenticated = null,
                message = "Mysterium API auf $base ist nicht erreichbar.",
            )
        }
        if (health.first !in 200..299) {
            return@withContext JoynMysteriumApiStatus(
                reachable = true,
                authenticated = null,
                message = "Mysterium API antwortet auf Healthcheck mit HTTP ${health.first}.",
            )
        }

        val auth = executeGet("$base/auth/check")
        val authenticated = auth?.let { (code, body) ->
            when {
                code == 401 || code == 403 -> false
                code in 200..299 -> parseAuthenticated(body)
                else -> null
            }
        }

        val subscription = executeGet("$base/subscription")
        val subscriptionText = subscription
            ?.takeIf { it.first in 200..299 }
            ?.second
            ?.let(::summarizeSubscription)
            .orEmpty()

        val locations = executeGet("$base/connection/config/locations?ip_type=residential")
        val residentialCountries = locations
            ?.takeIf { it.first in 200..299 }
            ?.second
            ?.let(::countLocations)

        JoynMysteriumApiStatus(
            reachable = true,
            authenticated = authenticated,
            subscriptionText = subscriptionText,
            residentialCountryCount = residentialCountries,
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

    suspend fun requestResidentialProxy(
        baseUrl: String,
        country: JoynCountry,
        resetConnection: Boolean,
    ): Result<JoynMysteriumProxyLease> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalize(baseUrl)
            val payload = JSONObject()
                .put("country", country.name)
                .put("ip_type", "residential")
                .put("reset_connection", resetConnection)
                .put("os_type", "android")

            directClient.newCall(
                Request.Builder()
                    .url("$base/connection/connect-proxy")
                    .header("Accept", "application/json")
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .header("User-Agent", USER_AGENT)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            ).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val detail = extractError(body).ifBlank { body.take(300) }
                    error("HTTP ${response.code}${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
                }
                val root = JSONObject(body)
                if (root.optBoolean("limit_exceeded", false)) {
                    error("Mysterium hat das Proxy-/IP-Wechsel-Limit erreicht.")
                }
                val proxy = root.optJSONObject("proxy_config")
                    ?: root.optJSONObject("proxyConfig")
                    ?: error("Mysterium-Antwort enthält kein proxy_config.")
                val host = proxy.optString("host").trim()
                val port = proxy.optString("port").toIntOrNull()
                    ?: proxy.optInt("port", 0)
                val username = proxy.optString("username")
                val password = proxy.optString("password")
                val expiresAt = proxy.optString("expires_at")
                    .ifBlank { proxy.optString("expiresAt") }
                if (host.isBlank() || port !in 1..65535) {
                    error("Ungültige Mysterium-Proxyadresse: $host:$port")
                }
                JoynMysteriumProxyLease(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    expiresAt = expiresAt,
                )
            }
        }
    }

    suspend fun residentialLocationSummary(baseUrl: String): String = withContext(Dispatchers.IO) {
        val base = normalize(baseUrl)
        val response = executeGet("$base/connection/config/locations?ip_type=residential")
            ?: return@withContext "Locations: API nicht erreichbar"
        if (response.first !in 200..299) {
            return@withContext "Locations: HTTP ${response.first}"
        }
        runCatching {
            val arr = JSONArray(response.second)
            val countries = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val code = item.optString("country").uppercase()
                    val total = item.optInt("total", 0)
                    if (code.isNotBlank()) add("$code=$total")
                }
            }
            "Residential-Locations: ${countries.joinToString(", ")}".take(1500)
        }.getOrElse { "Locations: JSON nicht lesbar (${it.javaClass.simpleName})" }
    }

    private fun executeGet(url: String): Pair<Int, String>? = runCatching {
        directClient.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        ).execute().use { response -> response.code to response.body.string() }
    }.getOrNull()

    private fun parseAuthenticated(body: String): Boolean? = runCatching {
        val json = JSONObject(body)
        when {
            json.has("authenticated") -> json.optBoolean("authenticated")
            json.has("is_authenticated") -> json.optBoolean("is_authenticated")
            json.has("authorized") -> json.optBoolean("authorized")
            json.has("user") || json.has("account") -> true
            else -> null
        }
    }.getOrNull()

    private fun summarizeSubscription(body: String): String = runCatching {
        val json = JSONObject(body)
        val status = listOf("status", "state", "plan_status")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        val plan = json.optJSONObject("plan")?.let { p ->
            p.optString("name").ifBlank { p.optString("id") }
        }.orEmpty()
        buildString {
            if (!status.isNullOrBlank()) append("Abo=$status")
            if (plan.isNotBlank()) {
                if (isNotEmpty()) append("/")
                append(plan)
            }
        }
    }.getOrDefault("")

    private fun countLocations(body: String): Int? = runCatching { JSONArray(body).length() }.getOrNull()

    private fun extractError(body: String): String = runCatching {
        val json = JSONObject(body)
        json.optString("message")
            .ifBlank { json.optString("error") }
            .ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
    }.getOrDefault("")

    private fun normalize(baseUrl: String): String =
        JoynMysteriumSettings.normalizeApiBaseUrl(baseUrl)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT =
            "JoynTV/AndroidTV Mysterium-Residential-Integration"
    }
}
