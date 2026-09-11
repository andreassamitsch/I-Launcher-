package com.andreassamitsch.joyntv

import android.content.Context
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class JoynMysteriumWireGuardLease(
    val id: String,
    val config: String,
    val providerHash: String,
    val exitIp: String,
    val country: String,
    val ipType: String,
)

/** Requests the same WireGuard connection template used by Mysterium's current VPN client. */
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

    suspend fun requestResidential(
        country: JoynCountry,
        publicKey: String,
        resetConnection: Boolean = true,
    ): Result<JoynMysteriumWireGuardLease> = withContext(Dispatchers.IO) {
        runCatching {
            require(publicKey.isNotBlank()) { "WireGuard Public Key fehlt." }
            var token = accessToken()
            if (token.isBlank()) error("Nicht bei Mysterium angemeldet.")

            val payload = JSONObject()
                .put("public_key", publicKey)
                .put("country", country.name)
                .put("ip_type", "residential")
                .put("reset_connection", resetConnection)
                .put("os_type", "android")

            var response = execute(payload, token)
            if (response.first == 401 || response.first == 403) {
                // status() uses the existing refresh-token path. Re-read the access token afterwards.
                sessionClient.status()
                token = accessToken()
                if (token.isBlank()) error("Mysterium-Sitzung ist abgelaufen.")
                response = execute(payload, token)
            }

            val body = response.second
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

            JoynMysteriumWireGuardLease(
                id = root.optString("id"),
                config = config,
                providerHash = root.optString("hash"),
                exitIp = root.optString("exit_ip").ifBlank { root.optString("exitIp") },
                country = root.optString("country").uppercase(),
                ipType = root.optString("ip_type").ifBlank { root.optString("ipType") },
            )
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

    private fun accessToken(): String = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty().trim()

    private fun extractError(body: String): String = runCatching {
        val json = JSONObject(body)
        json.optString("message")
            .ifBlank { json.optString("error_description") }
            .ifBlank { json.optString("error") }
            .ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
    }.getOrDefault("")

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(280)

    companion object {
        private const val BASE_URL = "https://api.mysteriumvpn.com/api/v1"
        private const val PREFS_NAME = "joyn_protocol"
        private const val KEY_ACCESS_TOKEN = "mysterium_access_token"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CLIENT_VERSION = "joyntv-1"
        private const val USER_AGENT = "JoynTV/AndroidTV Mysterium-WireGuard-Integration"
    }
}
