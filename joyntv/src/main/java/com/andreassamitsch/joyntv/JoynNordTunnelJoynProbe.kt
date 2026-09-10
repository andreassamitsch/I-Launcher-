package com.andreassamitsch.joyntv

import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal sealed interface JoynNordTunnelGateResult {
    data class Success(
        val exitIp: String,
        val latencyMs: Long,
    ) : JoynNordTunnelGateResult

    data class Failed(
        val stage: String,
        val detail: String,
        val exitIp: String? = null,
        val vpnDetected: Boolean = false,
    ) : JoynNordTunnelGateResult
}

/**
 * Runs directly over Android networking. When JoynNordOpenVpnService is connected, the app-only
 * VpnService route therefore carries this probe through the selected Nord OpenVPN exit without any
 * java.net/OkHttp proxy layered on top.
 */
internal class JoynNordTunnelJoynProbe {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun run(country: JoynCountry, apiKey: String): JoynNordTunnelGateResult {
        val exit = readExit()
            ?: return JoynNordTunnelGateResult.Failed("EXIT", "Cloudflare-Trace nicht erreichbar")
        if (!exit.country.equals(country.name, ignoreCase = true)) {
            return JoynNordTunnelGateResult.Failed(
                stage = "LAND",
                detail = "Exit=${exit.country.ifBlank { "?" }}, erwartet=${country.name}",
                exitIp = exit.ip,
            )
        }

        val started = System.nanoTime()
        try {
            client.newCall(
                Request.Builder()
                    .url("https://www.joyn.${country.webSuffix}/")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (response.code !in 200..399) {
                    return JoynNordTunnelGateResult.Failed(
                        "JOYN-WEB", "HTTP ${response.code}", exit.ip,
                    )
                }
            }
        } catch (error: Throwable) {
            return JoynNordTunnelGateResult.Failed("JOYN-WEB", summarize(error), exit.ip)
        }

        val tokenResult = createAnonymousToken(country)
        val token = tokenResult.value
            ?: return JoynNordTunnelGateResult.Failed("JOYN-AUTH", tokenResult.error, exit.ip)

        val channelResult = loadFreeLiveChannel(country, apiKey, token)
        val channelId = channelResult.value
            ?: return JoynNordTunnelGateResult.Failed("GRAPHQL", channelResult.error, exit.ip)

        val entitlement = checkEntitlement(channelId, token)
        if (!entitlement.ok) {
            val vpn = entitlement.error.contains("ENT_USER_VPN_DETECTED", ignoreCase = true) ||
                entitlement.error.contains("using a VPN", ignoreCase = true)
            return JoynNordTunnelGateResult.Failed(
                stage = "ENTITLEMENT",
                detail = entitlement.error,
                exitIp = exit.ip,
                vpnDetected = vpn,
            )
        }

        return JoynNordTunnelGateResult.Success(
            exitIp = exit.ip,
            latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        )
    }

    private fun readExit(): ExitInfo? = try {
        client.newCall(
            Request.Builder()
                .url("https://www.cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return null
            var ip = ""
            var country = ""
            response.body.string().lineSequence().forEach { line ->
                when {
                    line.startsWith("ip=") -> ip = line.substringAfter("ip=").trim()
                    line.startsWith("loc=") -> country = line.substringAfter("loc=").trim().uppercase()
                }
            }
            if (ip.isBlank()) null else ExitInfo(ip, country)
        }
    } catch (_: Throwable) {
        null
    }

    private fun createAnonymousToken(country: JoynCountry): StepValue<ProbeToken> = try {
        val payload = JSONObject()
            .put("anon_device_id", UUID.randomUUID().toString())
            .put("client_id", UUID.randomUUID().toString())
            .put("client_name", "web")
        client.newCall(
            Request.Builder()
                .url("${JoynProtocol.authBaseUrl}/anonymous")
                .header("User-Agent", USER_AGENT)
                .header("Joyn-Country", country.name)
                .header("Joyn-Distribution-Tenant", country.authTenant)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) return StepValue(null, "HTTP ${response.code} · ${body.take(140)}")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return StepValue(null, "Antwort ist kein JSON")
            val access = json.optString("access_token").takeIf(String::isNotBlank)
                ?: return StepValue(null, "access_token fehlt")
            StepValue(
                ProbeToken(access, json.optString("token_type").takeIf(String::isNotBlank) ?: "Bearer"),
                "",
            )
        }
    } catch (error: Throwable) {
        StepValue(null, summarize(error))
    }

    private fun loadFreeLiveChannel(
        country: JoynCountry,
        apiKey: String,
        token: ProbeToken,
    ): StepValue<String> = try {
        val url = JoynProtocol.graphQlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", LIVE_PROBE_QUERY)
            .build()
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .header("Joyn-Platform", "web")
                .header("Joyn-Country", country.name)
                .header("Joyn-Distribution-Tenant", country.graphqlTenant)
                .header("Authorization", "${token.type} ${token.access}")
                .get()
                .build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) return StepValue(null, "HTTP ${response.code} · ${body.take(140)}")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return StepValue(null, "Antwort ist kein JSON")
            val errors = json.optJSONArray("errors")
            if ((errors?.length() ?: 0) > 0) {
                return StepValue(null, "GraphQL errors · ${errors.toString().take(140)}")
            }
            val streams = json.optJSONObject("data")?.optJSONArray("liveStreams")
                ?: return StepValue(null, "liveStreams fehlt")
            for (index in 0 until streams.length()) {
                val stream = streams.optJSONObject(index) ?: continue
                val markings = stream.optJSONArray("markings")
                var paid = false
                if (markings != null) {
                    for (m in 0 until markings.length()) {
                        if (markings.optString(m) in setOf("PLUS", "PREMIUM")) {
                            paid = true
                            break
                        }
                    }
                }
                if (!paid) {
                    stream.optString("id").takeIf(String::isNotBlank)?.let {
                        return StepValue(it, "")
                    }
                }
            }
            StepValue(null, "kein freier LINEAR-Sender in ${streams.length()} Streams")
        }
    } catch (error: Throwable) {
        StepValue(null, summarize(error))
    }

    private fun checkEntitlement(channelId: String, token: ProbeToken): StepFlag = try {
        val payload = JSONObject().put("content_id", channelId).put("content_type", "LIVE")
        client.newCall(
            Request.Builder()
                .url(JoynProtocol.entitlementUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "${token.type} ${token.access}")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                return StepFlag(false, "HTTP ${response.code} · ${body.take(220)}")
            }
            val hasToken = runCatching {
                JSONObject(body).optString("entitlement_token").isNotBlank()
            }.getOrDefault(false)
            if (hasToken) StepFlag(true, "")
            else StepFlag(false, "HTTP ${response.code}, entitlement_token fehlt · ${body.take(180)}")
        }
    } catch (error: Throwable) {
        StepFlag(false, summarize(error))
    }

    private fun summarize(error: Throwable): String {
        val text = error.message.orEmpty().replace('\r', ' ').replace('\n', ' ').trim().take(180)
        return if (text.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $text"
    }

    private data class ExitInfo(val ip: String, val country: String)
    private data class ProbeToken(val access: String, val type: String)
    private data class StepValue<T>(val value: T?, val error: String)
    private data class StepFlag(val ok: Boolean, val error: String)

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LIVE_PROBE_QUERY =
            "query TunnelLiveProbe { liveStreams(filterLivestreamsTypes: [LINEAR], first: 30, offset: 0) { id markings } }"
    }
}
