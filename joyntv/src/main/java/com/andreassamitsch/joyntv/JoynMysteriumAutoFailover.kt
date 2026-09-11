package com.andreassamitsch.joyntv

import android.content.Context
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Rotates an active Mysterium residential lease after transport failures.
 *
 * Only one background rotation can run at a time. A short cooldown prevents a dead proxy from
 * causing one scan per parallel Joyn request. Every replacement is verified by the same country,
 * exit-IP and Joyn entitlement checks used by the manual Residential test before it is activated.
 */
internal object JoynMysteriumAutoFailover {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rotationMutex = Mutex()
    private val lastTriggerAt = AtomicLong(0L)

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        JoynProxySettings.setConnectFailureHandler { reason ->
            schedule(reason)
        }
    }

    private fun schedule(reason: String) {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val previous = lastTriggerAt.get()
        if (now - previous < FAILURE_COOLDOWN_MS) return
        if (!lastTriggerAt.compareAndSet(previous, now)) return

        scope.launch {
            if (!rotationMutex.tryLock()) return@launch
            try {
                rotate(context, reason)
            } finally {
                rotationMutex.unlock()
            }
        }
    }

    private suspend fun rotate(context: Context, reason: String) {
        val proxySettings = JoynProxySettings(context)
        val active = proxySettings.current()
        if (!active.isUsable || !active.isMysterium) return

        val country = countryFromSource(active.source) ?: JoynRegionSettings(context).currentCountry()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key_${country.name}", null)
        if (apiKey.isNullOrBlank()) return

        val countrySettings = JoynMysteriumSettings(context).countrySettings(country)
        val apiClient = JoynMysteriumApiClient(context)
        val scanner = JoynMysteriumProxyScanner(apiClient)
        val maxAttempts = countrySettings.maxAttempts.coerceAtMost(AUTO_FAILOVER_MAX_ATTEMPTS)

        val result = scanner.findBest(
            country = country,
            apiKey = apiKey,
            maxAttempts = maxAttempts,
            allTraffic = active.allTraffic,
        )
        val replacement = result.config ?: return

        JoynMysteriumSettings(context).saveLastSuccessful(country, replacement, result.expiresAt)
        proxySettings.save(
            replacement.copy(
                source = "Mysterium · Residential · ${country.name}",
            ),
        )

        // Keep a small diagnostic for the settings screen / support without storing credentials.
        prefs.edit()
            .putString(
                KEY_LAST_FAILOVER,
                "${System.currentTimeMillis()}|${country.name}|${reason.take(180)}|${replacement.host}:${replacement.port}",
            )
            .apply()
    }

    private fun countryFromSource(source: String): JoynCountry? {
        val code = source.substringAfterLast('·', "").trim().uppercase()
        return runCatching { JoynCountry.valueOf(code) }.getOrNull()
    }

    private const val PREFS_NAME = "joyn_protocol"
    private const val KEY_LAST_FAILOVER = "mysterium_last_auto_failover"
    private const val FAILURE_COOLDOWN_MS = 20_000L
    private const val AUTO_FAILOVER_MAX_ATTEMPTS = 10
}
