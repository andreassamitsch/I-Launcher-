package com.andreassamitsch.ilauncher.data.openwebif

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight live tuner diagnostics for the active Enigma2 frontend.
 *
 * The target Gigablue currently uses one tuner, so OpenWebif's current frontend measurements map to
 * the stream watched in I Launcher. Keep this separate from the five-minute channel/EPG refresh:
 * the signal endpoint is designed for frequent polling and OpenWebif's own sat-finder uses it once
 * per second.
 */
internal class OpenWebifSignalReader(context: Context) {
    private val store = OpenWebifStore(context.applicationContext)
    private var cachedConfig: OpenWebifConfig? = null
    private var cachedApi: OpenWebifApi? = null

    suspend fun read(): Result<OpenWebifSignalStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val config = store.loadConfig()
                ?: error("No OpenWebif receiver configured")
            OpenWebifSignalMapper.fromDto(apiFor(config).getSignal())
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
