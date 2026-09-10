package com.andreassamitsch.joyntv

import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class JoynNordVpnDiagnosticReport(
    val summary: String,
)

/**
 * Intentionally verbose NordVPN API diagnostics for the TV UI.
 * No credentials are sent to these public catalog endpoints and no credentials are included
 * in the returned text. The goal is to make server-discovery failures observable on-device.
 */
internal class JoynNordVpnDiagnostics {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun run(
        country: JoynCountry,
        onProgress: (JoynProxyDiscoveryProgress) -> Unit = {},
    ): JoynNordVpnDiagnosticReport = withContext(Dispatchers.IO) {
        val expectedName = when (country) {
            JoynCountry.DE -> "Germany"
            JoynCountry.AT -> "Austria"
            JoynCountry.CH -> "Switzerland"
        }
        val fallbackCountryId = when (country) {
            JoynCountry.DE -> 81L
            JoynCountry.AT -> 14L
            JoynCountry.CH -> 209L
        }
        val lines = mutableListOf<String>()

        fun progress(step: Int, text: String) {
            lines += text
            onProgress(
                JoynProxyDiscoveryProgress(
                    message = "Nord-Debug $step/4 · $text",
                    attempted = step,
                    total = 4,
                ),
            )
        }

        val countries = get("countries", "https://api.nordvpn.com/v1/servers/countries")
        var countryId = fallbackCountryId
        if (countries.body != null) {
            val parsed = runCatching { JSONArray(countries.body) }.getOrNull()
            val matching = parsed?.let { array ->
                (0 until array.length())
                    .asSequence()
                    .mapNotNull { array.optJSONObject(it) }
                    .firstOrNull {
                        it.optString("code").equals(country.name, true) ||
                            it.optString("name").equals(expectedName, true)
                    }
            }
            matching?.optLong("id")?.takeIf { it > 0L }?.let { countryId = it }
            progress(
                1,
                "countries: ${countries.httpLabel()} · JSON-Array=${parsed?.length() ?: "parse-fehler"} · ${country.name}-ID=$countryId",
            )
        } else {
            progress(1, "countries: ${countries.httpLabel()} · verwende Fallback-ID=$countryId")
        }

        val v2Url = "https://api.nordvpn.com/v2/servers".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "250")
            .addQueryParameter("filters[country_id]", countryId.toString())
            .build().toString()
        val v2 = get("v2", v2Url)
        val v2Detail = analyseV2(v2.body, country, countryId, expectedName)
        progress(2, "v2 filtered: ${v2.httpLabel()} · $v2Detail")

        val v1Url = "https://api.nordvpn.com/v1/servers".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "250")
            .addQueryParameter("filters[country_id]", countryId.toString())
            .build().toString()
        val v1 = get("v1", v1Url)
        val v1Detail = analyseV1Array(v1.body, country, countryId, expectedName)
        progress(3, "v1 filtered: ${v1.httpLabel()} · $v1Detail")

        val recUrl = "https://api.nordvpn.com/v1/servers/recommendations".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "100")
            .addQueryParameter("filters[country_id]", countryId.toString())
            .build().toString()
        val rec = get("recommendations", recUrl)
        val recDetail = analyseV1Array(rec.body, country, countryId, expectedName)
        progress(4, "recommendations: ${rec.httpLabel()} · $recDetail")

        JoynNordVpnDiagnosticReport(
            summary = lines.joinToString("\n"),
        )
    }

    private fun get(label: String, url: String): HttpDiagnostic {
        return try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                val body = runCatching { response.body.string() }.getOrElse { "" }
                HttpDiagnostic(
                    label = label,
                    code = response.code,
                    contentType = response.header("Content-Type").orEmpty(),
                    body = body,
                    error = null,
                )
            }
        } catch (error: Throwable) {
            HttpDiagnostic(
                label = label,
                code = null,
                contentType = "",
                body = null,
                error = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim(),
            )
        }
    }

    private fun analyseV1Array(
        body: String?,
        country: JoynCountry,
        countryId: Long,
        expectedName: String,
    ): String {
        if (body.isNullOrBlank()) return "kein Body"
        val array = runCatching { JSONArray(body) }.getOrNull()
            ?: return "kein JSON-Array · Vorschau=${preview(body)}"
        var matches = 0
        val examples = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val hostname = item.optString("hostname")
            if (serverMatchesCountry(item, hostname, country, countryId, expectedName)) {
                matches++
                if (examples.size < 4 && hostname.isNotBlank()) examples += hostname
            }
        }
        return "Array=${array.length()} · ${country.name}-Matches=$matches · Beispiele=${examples.ifEmpty { listOf("-") }.joinToString()}"
    }

    private fun analyseV2(
        body: String?,
        country: JoynCountry,
        countryId: Long,
        expectedName: String,
    ): String {
        if (body.isNullOrBlank()) return "kein Body"
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return "kein JSON-Objekt · Vorschau=${preview(body)}"
        val servers = root.optJSONArray("servers")
        val locations = root.optJSONArray("locations")
        if (servers == null) {
            return "Root-Keys=${root.keys().asSequence().toList().joinToString()} · servers fehlt · Vorschau=${preview(body)}"
        }

        val targetLocationIds = mutableSetOf<Long>()
        if (locations != null) {
            for (index in 0 until locations.length()) {
                val location = locations.optJSONObject(index) ?: continue
                val countryJson = location.optJSONObject("country") ?: continue
                val matches = countryJson.optLong("id") == countryId ||
                    countryJson.optString("code").equals(country.name, true) ||
                    countryJson.optString("name").equals(expectedName, true)
                if (matches) location.optLong("id").takeIf { it > 0L }?.let(targetLocationIds::add)
            }
        }

        var matches = 0
        val examples = mutableListOf<String>()
        for (index in 0 until servers.length()) {
            val item = servers.optJSONObject(index) ?: continue
            val hostname = item.optString("hostname")
            val locationIds = item.optJSONArray("location_ids")
            var locationMatch = false
            if (locationIds != null && targetLocationIds.isNotEmpty()) {
                for (locationIndex in 0 until locationIds.length()) {
                    if (locationIds.optLong(locationIndex) in targetLocationIds) {
                        locationMatch = true
                        break
                    }
                }
            }
            if (locationMatch || hostname.lowercase().startsWith(country.name.lowercase())) {
                matches++
                if (examples.size < 4 && hostname.isNotBlank()) examples += hostname
            }
        }
        return "servers=${servers.length()} · locations=${locations?.length() ?: 0} · Ziel-LocationIDs=${targetLocationIds.size} · ${country.name}-Matches=$matches · Beispiele=${examples.ifEmpty { listOf("-") }.joinToString()}"
    }

    private fun serverMatchesCountry(
        item: JSONObject,
        hostname: String,
        country: JoynCountry,
        countryId: Long,
        expectedName: String,
    ): Boolean {
        if (hostname.lowercase().startsWith(country.name.lowercase())) return true
        val locations = item.optJSONArray("locations") ?: return false
        for (index in 0 until locations.length()) {
            val countryJson = locations.optJSONObject(index)?.optJSONObject("country") ?: continue
            if (countryJson.optLong("id") == countryId ||
                countryJson.optString("code").equals(country.name, true) ||
                countryJson.optString("name").equals(expectedName, true)
            ) return true
        }
        return false
    }

    private fun preview(body: String): String = body
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(180)

    private data class HttpDiagnostic(
        val label: String,
        val code: Int?,
        val contentType: String,
        val body: String?,
        val error: String?,
    ) {
        fun httpLabel(): String = when {
            error != null -> error
            code != null -> "HTTP $code · ${contentType.ifBlank { "Content-Type ?" }} · ${body?.length ?: 0} Zeichen"
            else -> "keine Antwort"
        }
    }

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
