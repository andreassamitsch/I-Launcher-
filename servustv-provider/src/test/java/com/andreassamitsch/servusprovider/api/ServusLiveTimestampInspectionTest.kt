package com.andreassamitsch.servusprovider.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One-off live API inspection. This PR is diagnostic only and must not be merged.
 *
 * The output deliberately prints only content identity, JSON key names and timestamp-like primitive
 * values. It never prints session tokens, playback URLs, cookies or complete response bodies.
 */
class ServusLiveTimestampInspectionTest {
    @Test
    fun inspectTimestampFieldsFromCurrentServusProducts() {
        val queries = listOf(
            "Servus Nachrichten",
            "Servus Nachrichten in 90 Sekunden",
            "Servus am Abend",
            "Servus Wetter",
        )

        var inspectedProducts = 0
        queries.forEach { query ->
            val candidateCards = (listOf(0, 10, 20).flatMap { offset ->
                val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                val root = getJson(
                    "${ServusNetwork.API_BASE_URL}search/v5/stv/de/at/top_results?q=$encoded&offset=$offset",
                )
                root.getAsJsonArray("cards")?.mapNotNull { element ->
                    element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                }.orEmpty()
            })
                .distinctBy { it.string("id") }
                .filter { !it.string("id").isNullOrBlank() }
                .take(16)

            println("[API-TIMESTAMP] QUERY=$query candidates=${candidateCards.size}")

            candidateCards.forEach { card ->
                val id = card.string("id") ?: return@forEach
                val searchTitle = card.string("title").orEmpty()
                val searchShow = card.string("show_name").orEmpty()

                val product = runCatching {
                    getJson("${ServusNetwork.API_BASE_URL}products/v5.3/stv/de/at/$id")
                }.getOrElse { throwable ->
                    println(
                        "[API-TIMESTAMP] PRODUCT-ERROR id=$id title=${safe(searchTitle)} " +
                            "type=${throwable.javaClass.simpleName}",
                    )
                    return@forEach
                }

                inspectedProducts++
                val title = product.string("title") ?: searchTitle
                val showName = product.string("show_name") ?: searchShow
                val type = product.string("type").orEmpty()
                val contentType = product.string("content_type").orEmpty()
                val topLevelTimeKeys = product.entrySet()
                    .map { it.key }
                    .filter(::looksLikeTimeKey)
                    .sorted()

                println(
                    "[API-TIMESTAMP] PRODUCT id=$id type=${safe(type)} contentType=${safe(contentType)} " +
                        "show=${safe(showName)} title=${safe(title)} " +
                        "topLevelTimeKeys=${topLevelTimeKeys.joinToString(",")}",
                )

                val values = mutableListOf<Pair<String, String>>()
                collectTimestampValues(product, path = "", output = values)
                if (values.isEmpty()) {
                    println("[API-TIMESTAMP]   (no timestamp-like primitive fields)")
                } else {
                    values.distinct().take(80).forEach { (path, value) ->
                        println("[API-TIMESTAMP]   $path=${safe(value)}")
                    }
                }
            }
        }

        assertTrue("Expected at least one live ServusTV product to be inspected", inspectedProducts > 0)
    }

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder().url(url).get().build()
        return ServusNetwork.httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = requireNotNull(response.body).string()
            ServusNetwork.gson.fromJson(body, JsonObject::class.java)
        }
    }

    private fun collectTimestampValues(
        element: JsonElement,
        path: String,
        output: MutableList<Pair<String, String>>,
        depth: Int = 0,
    ) {
        if (depth > 8) return
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                val nextPath = if (path.isBlank()) key else "$path.$key"
                if (looksLikeTimeKey(key) && value.isJsonPrimitive) {
                    output += nextPath to value.asJsonPrimitive.toString().trim('"')
                }
                if (value.isJsonObject || value.isJsonArray) {
                    collectTimestampValues(value, nextPath, output, depth + 1)
                }
            }

            element.isJsonArray -> element.asJsonArray.take(30).forEachIndexed { index, value ->
                collectTimestampValues(value, "$path[$index]", output, depth + 1)
            }
        }
    }

    private fun looksLikeTimeKey(key: String): Boolean {
        val normalized = key.lowercase()
        return listOf(
            "time",
            "date",
            "created",
            "updated",
            "modified",
            "publish",
            "release",
            "available",
            "availability",
            "sunrise",
            "sunset",
            "expire",
            "expiry",
        ).any(normalized::contains)
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun safe(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .take(180)
}
