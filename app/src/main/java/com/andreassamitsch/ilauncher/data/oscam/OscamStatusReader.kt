package com.andreassamitsch.ilauncher.data.oscam

import android.content.Context
import com.andreassamitsch.ilauncher.data.openwebif.OpenWebifStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads the local OSCam WebIf status API and correlates the latest DVBAPI ECM with the Enigma2 SID.
 *
 * OSCam's WebIf uses Digest authentication when httpuser/httppwd are configured. Credentials are
 * kept local and are never logged. The WebIf host is deliberately inherited from the configured
 * OpenWebif receiver; only OSCam's port and optional credentials are separate settings.
 */
internal class OscamStatusReader(context: Context) {
    private val appContext = context.applicationContext
    private val oscamStore = OscamStore(appContext)
    private val openWebifStore = OpenWebifStore(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    fun configuredEndpointLabel(): String? {
        val receiver = openWebifStore.loadConfig() ?: return null
        val host = receiver.baseUrl.toHttpUrlOrNull()?.host ?: return null
        return "$host:${oscamStore.load().port}"
    }

    suspend fun read(serviceReference: String): OscamReadResult = withContext(Dispatchers.IO) {
        readBlocking(serviceReference)
    }

    private fun readBlocking(serviceReference: String): OscamReadResult {
        val serviceId = serviceIdFromReference(serviceReference)
            ?: return OscamReadResult.Error(OscamError.INVALID_SERVICE, "Service-ID fehlt")
        val receiver = openWebifStore.loadConfig()
            ?: return OscamReadResult.Error(OscamError.NOT_CONFIGURED, "Receiver nicht konfiguriert")
        val host = receiver.baseUrl.toHttpUrlOrNull()?.host
            ?: return OscamReadResult.Error(OscamError.NOT_CONFIGURED, "Receiver-Adresse ungültig")
        val config = oscamStore.load()
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(config.port)
            .addPathSegment("oscamapi.json")
            .addQueryParameter("part", "status")
            .build()

        return try {
            execute(url, config).use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        OscamReadResult.Error(OscamError.AUTH, "Anmeldung fehlgeschlagen")
                    !response.isSuccessful ->
                        OscamReadResult.Error(OscamError.HTTP, "HTTP ${response.code}")
                    else -> parseStatus(response.body?.string().orEmpty(), serviceId)
                }
            }
        } catch (_: ConnectException) {
            OscamReadResult.Error(OscamError.UNREACHABLE, "nicht erreichbar")
        } catch (_: SocketTimeoutException) {
            OscamReadResult.Error(OscamError.TIMEOUT, "Zeitüberschreitung")
        } catch (_: IOException) {
            OscamReadResult.Error(OscamError.UNREACHABLE, "Verbindungsfehler")
        } catch (_: Throwable) {
            OscamReadResult.Error(OscamError.INVALID_RESPONSE, "API-Antwort ungültig")
        }
    }

    private fun execute(url: HttpUrl, config: OscamConfig): Response {
        val firstRequest = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        val first = client.newCall(firstRequest).execute()
        if (first.code != 401 || config.username.isBlank()) return first

        val challenge = first.header("WWW-Authenticate").orEmpty()
        first.close()
        val authorization = when {
            challenge.startsWith("Digest", ignoreCase = true) ->
                digestAuthorization(challenge, url, config.username, config.password)
            challenge.startsWith("Basic", ignoreCase = true) ->
                Credentials.basic(config.username, config.password)
            else -> null
        } ?: return client.newCall(firstRequest).execute()

        return client.newCall(
            firstRequest.newBuilder()
                .header("Authorization", authorization)
                .build(),
        ).execute()
    }

    private fun parseStatus(body: String, serviceId: Int): OscamReadResult {
        if (body.isBlank() || body.length > MAX_STATUS_BYTES) {
            return OscamReadResult.Error(OscamError.INVALID_RESPONSE, "API-Antwort leer/zu groß")
        }
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return OscamReadResult.Error(OscamError.INVALID_RESPONSE, "JSON ungültig")
        val oscam = root.objectOrNull("oscam") ?: root
        val status = oscam.objectOrNull("status")
            ?: return OscamReadResult.Error(OscamError.INVALID_RESPONSE, "Status fehlt")
        val clients = status.get("client")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return OscamReadResult.NoMatchingEcm(serviceId = serviceId)

        val candidates = clients.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::clientFromJson)
        }.filter { client -> client.serviceId == serviceId }

        val best = candidates.minWithOrNull(
            compareBy<OscamClientStatus> { if (it.protocol.contains("dvbapi", ignoreCase = true)) 0 else 1 }
                .thenBy { it.idleSeconds ?: Int.MAX_VALUE },
        ) ?: return OscamReadResult.NoMatchingEcm(serviceId = serviceId)

        return OscamReadResult.Match(best)
    }

    private fun clientFromJson(client: JsonObject): OscamClientStatus? {
        val request = client.objectOrNull("request") ?: return null
        val serviceId = request.string("srvid").hexIntOrNull() ?: return null
        val times = client.objectOrNull("times")
        return OscamClientStatus(
            serviceId = serviceId,
            caid = request.string("caid").cleanOscamHex(),
            providerId = request.string("provid").cleanOscamHex(),
            ecmTimeMs = request.string("ecmtime").cleanValue(),
            answered = request.string("answered").cleanValue(),
            channelName = request.string("chname").cleanValue(),
            providerName = request.string("chprovider").cleanValue(),
            protocol = client.string("protocol").cleanValue().orEmpty(),
            idleSeconds = times?.string("idle")?.toIntOrNull(),
            connectionStatus = client.objectOrNull("connection")?.string("status").cleanValue(),
        )
    }

    companion object {
        private const val MAX_STATUS_BYTES = 512 * 1024
        private val ERROR_ANSWERS = setOf(
            "not found", "timeout", "sleeping", "fake", "invalid", "corrupt",
            "no card", "expdate", "disabled", "stopped",
        )

        internal fun serviceIdFromReference(reference: String): Int? =
            reference.split(':').getOrNull(3)?.trim()?.toIntOrNull(16)

        internal fun isFailureAnswer(answered: String?): Boolean =
            answered?.trim()?.lowercase(Locale.US) in ERROR_ANSWERS

        private fun digestAuthorization(
            challenge: String,
            url: HttpUrl,
            username: String,
            password: String,
        ): String? {
            val values = parseChallenge(challenge.removePrefixIgnoreCase("Digest").trim())
            val realm = values["realm"] ?: return null
            val nonce = values["nonce"] ?: return null
            val algorithm = values["algorithm"]?.uppercase(Locale.US) ?: "MD5"
            if (algorithm != "MD5") return null
            val qop = values["qop"]
                ?.split(',')
                ?.map(String::trim)
                ?.firstOrNull { it.equals("auth", ignoreCase = true) }
            val uri = buildString {
                append(url.encodedPath)
                url.encodedQuery?.let { append('?').append(it) }
            }
            val ha1 = md5Hex("$username:$realm:$password")
            val ha2 = md5Hex("GET:$uri")
            val cnonce = randomHex(16)
            val nc = "00000001"
            val response = if (qop != null) {
                md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            } else {
                md5Hex("$ha1:$nonce:$ha2")
            }
            return buildString {
                append("Digest username=\"").append(escapeQuoted(username)).append("\"")
                append(", realm=\"").append(escapeQuoted(realm)).append("\"")
                append(", nonce=\"").append(escapeQuoted(nonce)).append("\"")
                append(", uri=\"").append(escapeQuoted(uri)).append("\"")
                append(", response=\"").append(response).append("\"")
                if (qop != null) {
                    append(", qop=").append(qop)
                    append(", nc=").append(nc)
                    append(", cnonce=\"").append(cnonce).append("\"")
                }
                values["opaque"]?.let { append(", opaque=\"").append(escapeQuoted(it)).append("\"") }
                if (values.containsKey("algorithm")) append(", algorithm=MD5")
            }
        }

        private fun parseChallenge(value: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            val regex = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|([^,\\s]+))")
            regex.findAll(value).forEach { match ->
                result[match.groupValues[1].lowercase(Locale.US)] =
                    match.groupValues[2].ifEmpty { match.groupValues[3] }
            }
            return result
        }

        private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }

        private fun randomHex(bytes: Int): String {
            val value = ByteArray(bytes)
            SecureRandom().nextBytes(value)
            return value.joinToString("") { "%02x".format(it) }
        }

        private fun escapeQuoted(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        private fun String.removePrefixIgnoreCase(prefix: String): String =
            if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
    }
}

internal sealed interface OscamReadResult {
    data class Match(val status: OscamClientStatus) : OscamReadResult
    data class NoMatchingEcm(val serviceId: Int) : OscamReadResult
    data class Error(val type: OscamError, val message: String) : OscamReadResult
}

internal enum class OscamError {
    NOT_CONFIGURED,
    INVALID_SERVICE,
    AUTH,
    HTTP,
    UNREACHABLE,
    TIMEOUT,
    INVALID_RESPONSE,
}

internal data class OscamClientStatus(
    val serviceId: Int,
    val caid: String? = null,
    val providerId: String? = null,
    val ecmTimeMs: String? = null,
    val answered: String? = null,
    val channelName: String? = null,
    val providerName: String? = null,
    val protocol: String = "",
    val idleSeconds: Int? = null,
    val connectionStatus: String? = null,
) {
    val failed: Boolean get() = OscamStatusReader.isFailureAnswer(answered)
    val fresh: Boolean get() = idleSeconds == null || idleSeconds <= 10
}

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun String?.cleanValue(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

private fun String?.cleanOscamHex(): String? = cleanValue()?.uppercase(Locale.US)

private fun String?.hexIntOrNull(): Int? = this?.trim()?.takeIf(String::isNotEmpty)?.toIntOrNull(16)
