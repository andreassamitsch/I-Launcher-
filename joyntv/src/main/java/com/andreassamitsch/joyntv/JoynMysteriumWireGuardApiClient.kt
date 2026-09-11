package com.andreassamitsch.joyntv

import android.content.Context
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class JoynMysteriumWireGuardLease(
    val id: String,
    val config: String,
    val providerHash: String,
    val exitIp: String,
    val country: String,
    val ipType: String,
)

/**
 * Requests the same WireGuard connection template used by Mysterium's current VPN client.
 *
 * Mysterium exposes more location controls than just a country. In particular, Residential
 * locations contain cities, the connect request accepts a city and the API exposes a server-side
 * disconnect endpoint. We deliberately use those controls while scanning so repeated requests do
 * not merely recycle the same country-level lease and burn the Refresh-IP quota.
 */
internal class JoynMysteriumWireGuardApiClient(
    context: Context,
    private val sessionClient: JoynMysteriumApiClient,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cityCache = ConcurrentHashMap<JoynCountry, List<String>>()
    private val cityCursors = ConcurrentHashMap<JoynCountry, AtomicInteger>()
    private val requestPacingMutex = Mutex()

    @Volatile
    private var lastConnectRequestAtMs = 0L

    suspend fun requestResidential(
        country: JoynCountry,
        publicKey: String,
        resetConnection: Boolean = true,
    ): Result<JoynMysteriumWireGuardLease> = withContext(Dispatchers.IO) {
        runCatching {
            require(publicKey.isNotBlank()) { "WireGuard Public Key fehlt." }
            var token = accessToken()
            if (token.isBlank()) error("Nicht bei Mysterium angemeldet.")

            // The official client exposes country + city selection. Cycle available Residential
            // cities before asking Mysterium to rotate an IP inside the same city. This gives the
            // scanner genuinely different location candidates without consuming Refresh-IP quota
            // for every HTTP request.
            var cityTarget = nextResidentialCity(country, token)

            // Mysterium's Refresh-IP flow disconnects the active server-side connection first and
            // then creates another one. Android's local WireGuard teardown alone does not clear
            // Mysterium's prepared connection, which can cause the same exit to be returned again.
            if (resetConnection) {
                disconnectServerSide(publicKey, token)
            }

            paceConnectRequests()

            val effectiveReset = resetConnection && (cityTarget == null || cityTarget.round > 0)
            val payload = JSONObject()
                .put("public_key", publicKey)
                .put("country", country.name)
                .put("ip_type", "residential")
                .put("reset_connection", effectiveReset)
                .put("os_type", "android")
                .apply {
                    cityTarget?.city?.takeIf(String::isNotBlank)?.let { put("city", it) }
                }

            var response = execute(payload, token)
            if (response.first == 401 || response.first == 403) {
                // status() uses the existing refresh-token path. Re-read the access token afterwards.
                sessionClient.status()
                token = accessToken()
                if (token.isBlank()) error("Mysterium-Sitzung ist abgelaufen.")

                // A refreshed account token can see a slightly different availability set. Drop the
                // cached locations once before retrying so we do not keep targeting stale cities.
                cityCache.remove(country)
                cityTarget = nextResidentialCity(country, token)
                val retryPayload = JSONObject(payload.toString()).apply {
                    if (cityTarget == null) remove("city")
                    else put("city", cityTarget.city)
                    put("reset_connection", resetConnection && (cityTarget == null || cityTarget.round > 0))
                }
                paceConnectRequests()
                response = execute(retryPayload, token)
            }

            val body = response.second
            if (response.first == 429) {
                // Mysterium intentionally applies a temporary cooldown after too many IP refreshes.
                // Surface this as a hard limit so the scanner stops instead of hammering #70..#100.
                error(
                    "Mysterium IP-Wechsel-Limit/Cooldown erreicht (HTTP 429). " +
                        "Keine weiteren Refresh-Anfragen senden; später erneut versuchen.",
                )
            }
            if (response.first !in 200..299) {
                val detail = extractError(body).ifBlank { compact(body) }
                error("WireGuard HTTP ${response.first}${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }

            val root = JSONObject(body)
            if (root.optBoolean("limit_exceeded", false) || root.optBoolean("limitExceeded", false)) {
                error("Mysterium hat das IP-Wechsel-Limit erreicht.")
            }

            val config = root.optString("wg_config").ifBlank { root.optString("wgConfig") }
            if (config.isBlank()) error("Mysterium-Antwort enthält kein wg_config.")

            val responseIpType = root.optString("ip_type").ifBlank { root.optString("ipType") }
            if (responseIpType.isNotBlank() && !responseIpType.equals("residential", ignoreCase = true)) {
                error("Mysterium lieferte '$responseIpType' statt eines Residential-Exits.")
            }

            JoynMysteriumWireGuardLease(
                id = root.optString("id"),
                config = config,
                providerHash = root.optString("hash"),
                exitIp = root.optString("exit_ip").ifBlank { root.optString("exitIp") },
                country = root.optString("country").uppercase(),
                ipType = responseIpType.ifBlank { "residential" },
            )
        }
    }

    /**
     * Returns a different advertised Residential city on successive requests. The first complete
     * city pass does not require reset_connection because each request targets another location;
     * subsequent passes may explicitly request a fresh IP within that city.
     */
    private fun nextResidentialCity(country: JoynCountry, token: String): CityTarget? {
        val cities = cityCache[country]
            ?: loadResidentialCities(country, token).also { loaded ->
                if (loaded.isNotEmpty()) cityCache[country] = loaded
            }
        if (cities.isEmpty()) return null

        val cursor = cityCursors.computeIfAbsent(country) { AtomicInteger(0) }.getAndIncrement()
        val safeCursor = cursor.coerceAtLeast(0)
        val index = safeCursor % cities.size
        return CityTarget(
            city = cities[index],
            round = safeCursor / cities.size,
        )
    }

    private fun loadResidentialCities(country: JoynCountry, token: String): List<String> = runCatching {
        val url = "$BASE_URL/connection/config/locations".toHttpUrl().newBuilder()
            .addQueryParameter("ip_type", "residential")
            .build()
        val response = executeGet(url, token)
        if (response.first !in 200..299) return@runCatching emptyList()

        val locations = when {
            response.second.trimStart().startsWith("[") -> JSONArray(response.second)
            else -> JSONObject(response.second).optJSONArray("locations") ?: JSONArray()
        }

        val candidates = mutableListOf<Pair<String, Int>>()
        for (i in 0 until locations.length()) {
            val location = locations.optJSONObject(i) ?: continue
            val locationCountry = location.optString("country")
                .ifBlank { location.optString("country_code") }
                .uppercase()
            if (locationCountry != country.name) continue

            val cities = location.optJSONArray("cities") ?: continue
            for (j in 0 until cities.length()) {
                val city = cities.optJSONObject(j) ?: continue
                val name = city.optString("city").trim()
                if (name.isBlank()) continue
                val available = if (city.has("is_available")) city.optBoolean("is_available") else true
                if (!available) continue
                val total = city.optInt("total", 0)
                if (total == 0 && city.has("total")) continue
                candidates += name to total
            }
        }

        // Prefer cities with more advertised Residential nodes, but still visit every available
        // city before requesting another IP from the same city.
        candidates
            .distinctBy { it.first.lowercase() }
            .sortedByDescending { it.second }
            .map { it.first }
    }.getOrDefault(emptyList())

    private fun disconnectServerSide(publicKey: String, token: String) {
        runCatching {
            val url = "$BASE_URL/connection/disconnect".toHttpUrl().newBuilder()
                .addQueryParameter("public_key", publicKey)
                .build()
            executeGet(url, token)
        }
    }

    private suspend fun paceConnectRequests() {
        requestPacingMutex.withLock {
            val now = System.currentTimeMillis()
            val remaining = MIN_CONNECT_INTERVAL_MS - (now - lastConnectRequestAtMs)
            if (remaining > 0L) delay(remaining)
            lastConnectRequestAtMs = System.currentTimeMillis()
        }
    }

    private fun execute(payload: JSONObject, token: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("$BASE_URL/connection/connect")
            .header("Accept", "application/json")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("x-client-version", CLIENT_VERSION)
            .header("x-client-platform", "android")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            response.code to response.body.string()
        }
    }

    private fun executeGet(url: HttpUrl, token: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("x-client-version", CLIENT_VERSION)
            .header("x-client-platform", "android")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            response.code to response.body.string()
        }
    }

    private fun accessToken(): String = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty().trim()

    private fun extractError(body: String): String = runCatching {
        val json = JSONObject(body)
        json.optString("message")
            .ifBlank { json.optString("error_description") }
            .ifBlank { json.optString("error") }
            .ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
    }.getOrDefault("")

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(280)

    private data class CityTarget(
        val city: String,
        val round: Int,
    )

    companion object {
        private const val BASE_URL = "https://api.mysteriumvpn.com/api/v1"
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_ACCESS_TOKEN = "mysterium_access_token"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CLIENT_VERSION = "joyntv-1"
        private const val USER_AGENT = "JoynTV/AndroidTV Mysterium-WireGuard-Integration"
        private const val MIN_CONNECT_INTERVAL_MS = 1_750L
    }
}
