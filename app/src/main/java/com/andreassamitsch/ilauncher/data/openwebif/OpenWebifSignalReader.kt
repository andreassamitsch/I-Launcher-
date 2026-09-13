package com.andreassamitsch.ilauncher.data.openwebif

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight live tuner diagnostics for the active Enigma2 frontend.
 *
 * OpenWebif installations in the wild differ: newer versions expose JSON at /api/signal while
 * older/vendor images can only provide the legacy XML /web/signal endpoint. Try both so the
 * diagnostics are useful on the actual Gigablue instead of depending on one OpenWebif generation.
 */
internal class OpenWebifSignalReader(context: Context) {
    private val store = OpenWebifStore(context.applicationContext)
    private var cachedConfig: OpenWebifConfig? = null
    private var cachedApi: OpenWebifApi? = null

    suspend fun read(): Result<OpenWebifSignalStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val config = store.loadConfig()
                ?: error("No OpenWebif receiver configured")
            val api = apiFor(config)

            val modern = runCatching {
                OpenWebifSignalMapper.fromDto(api.getSignal())
            }
            modern.getOrNull()?.takeIf { it.hasMeasurements }?.let {
                return@runCatching it
            }

            val legacy = runCatching {
                OpenWebifLegacySignalParser.fromXml(api.getLegacySignal().string())
            }
            legacy.getOrNull()?.takeIf { it.hasMeasurements }?.let {
                return@runCatching it
            }

            // At least one endpoint answered successfully but Enigma2 exposed no active frontend.
            // Keep that distinct from a transport/API failure so the overlay can say there are no
            // tuner values instead of reporting a network error.
            modern.getOrNull()?.let { return@runCatching it }
            legacy.getOrNull()?.let { return@runCatching it }

            val failure = modern.exceptionOrNull() ?: legacy.exceptionOrNull()
            if (failure != null) throw failure
            error("OpenWebif signal endpoints unavailable")
        }
    }

    /**
     * Re-align Enigma2's foreground service with the network stream after the stream has connected.
     *
     * On the target Gigablue the OpenWebif stream can briefly expose frontend values while the
     * stream is being prepared, then session.nav.getCurrentService() becomes empty once port 8001
     * owns the streaming service. Both /api/signal and /web/signal read exactly that foreground
     * service, so their values disappear even though the SAT stream keeps playing. A normal zap to
     * the same service restores the inspectable frontend without leaving standby. This operation is
     * diagnostic only: callers must never make playback depend on its success.
     */
    suspend fun alignCurrentService(
        serviceReference: String,
        title: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val config = store.loadConfig()
                ?: error("No OpenWebif receiver configured")
            apiFor(config).zap(
                serviceReference = serviceReference,
                title = title,
            ).result
        }
    }

    private fun apiFor(config: OpenWebifConfig): OpenWebifApi {
        val current = cachedApi
        if (current != null && cachedConfig == config) return current
        return OpenWebifNetworkClient.create(config).also {
            cachedConfig = config
            cachedApi = it
        }
    }
}

internal data class OpenWebifSignalStatus(
    val tunerType: String? = null,
    val tunerNumber: String? = null,
    val snrPercent: Int? = null,
    /** Null when OpenWebif only mirrors the percentage into snr_db instead of reporting real dB. */
    val snrDb: Double? = null,
    val agcPercent: Int? = null,
    /** OpenWebif exposes BER as a driver-dependent value; deliberately do not label it as percent. */
    val ber: String? = null,
) {
    val hasMeasurements: Boolean
        get() = snrPercent != null || snrDb != null || agcPercent != null || !ber.isNullOrBlank()
}

internal object OpenWebifSignalMapper {
    fun fromDto(dto: OpenWebifSignalResponseDto): OpenWebifSignalStatus {
        val snr = dto.snr.cleanNumber()?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)
        val rawSnrDb = dto.snrDb.cleanNumber()
        // OpenWebif falls back to assigning the integer SNR percentage to snr_db when the frontend
        // driver has no real dB measurement. A genuine dB value is formatted with two decimals.
        val snrDb = rawSnrDb
            ?.takeIf { value -> value.contains('.') || value.contains(',') }
            ?.replace(',', '.')
            ?.toDoubleOrNull()
        val agc = dto.agc.cleanNumber()?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)
        val ber = dto.ber.cleanNumber()

        return OpenWebifSignalStatus(
            tunerType = dto.tunertype.cleanText(),
            tunerNumber = dto.tunernumber.cleanText(),
            snrPercent = snr,
            snrDb = snrDb,
            agcPercent = agc,
            ber = ber,
        )
    }

    private fun String?.cleanText(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }

    private fun String?.cleanNumber(): String? = cleanText()
}

/** Parser for OpenWebif's long-standing /web/signal XML response. */
internal object OpenWebifLegacySignalParser {
    private val NUMBER = Regex("[-+]?\\d+(?:[.,]\\d+)?")

    fun fromXml(xml: String): OpenWebifSignalStatus {
        val snrText = tag(xml, "e2snr")
        val snr = number(snrText)?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)

        val snrDbText = tag(xml, "e2snrdb")
        val rawSnrDb = number(snrDbText)
        val snrDb = rawSnrDb
            ?.takeIf { value ->
                // Same compatibility rule as the JSON mapper: integer snr_db values are commonly
                // just the percentage copied into the dB field by OpenWebif.
                (value.contains('.') || value.contains(',')) || value.toDoubleOrNull()?.toInt() != snr
            }
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        // The official legacy template historically spells the tag e2acg, not e2agc. Accept both.
        val agc = number(tag(xml, "e2acg") ?: tag(xml, "e2agc"))
            ?.toDoubleOrNull()
            ?.toInt()
            ?.coerceIn(0, 100)
        val ber = tag(xml, "e2ber")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }

        return OpenWebifSignalStatus(
            snrPercent = snr,
            snrDb = snrDb,
            agcPercent = agc,
            ber = ber,
        )
    }

    private fun tag(xml: String, name: String): String? = Regex(
        "<$name(?:\\s[^>]*)?>(.*?)</$name>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(xml)?.groupValues?.getOrNull(1)?.trim()

    private fun number(value: String?): String? = value?.let { NUMBER.find(it)?.value }
}
