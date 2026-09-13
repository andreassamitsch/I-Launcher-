package com.andreassamitsch.ilauncher.data.openwebif

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class OpenWebifRepository(context: Context) {
    private val store = OpenWebifStore(context.applicationContext)
    private val streamResolver = OpenWebifStreamResolver()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OpenWebifState> = _state.asStateFlow()

    fun updateConnection(rawBaseUrl: String, username: String, password: String): Boolean {
        val normalized = OpenWebifUrl.normalize(rawBaseUrl)
        if (normalized == null) {
            _state.update { it.copy(errorMessage = "Ungültige Receiver-Adresse. Beispiel: 192.168.1.20 oder http://gigablue/") }
            return false
        }

        store.saveConnection(
            baseUrl = normalized,
            username = username.trim(),
            password = password,
        )
        val config = store.loadConfig() ?: return false
        _state.value = stateFromLocal(config)
        return true
    }

    fun selectBouquet(serviceReference: String) {
        store.saveSelectedBouquet(serviceReference)
        _state.update { current ->
            current.copy(selectedBouquetRef = serviceReference, errorMessage = null)
        }
    }

    internal suspend fun resolveStream(channel: com.andreassamitsch.ilauncher.model.LiveTvChannel): OpenWebifResolvedStream =
        withContext(Dispatchers.IO) {
            val config = store.loadConfig()
                ?: throw OpenWebifStreamException("No OpenWebif receiver configured")
            val stream = streamResolver.resolve(
                config = config,
                serviceReference = channel.serviceReference,
                channelName = channel.name,
            )

            /*
             * OpenWebif's stream.m3u endpoint intentionally does not create a foreground service
             * while Enigma2 is in standby. /api/signal reads session.nav.getCurrentService(), so in
             * exactly that common streaming case it returns empty tuner values although port 8001
             * is receiving satellite data. A normal zap to the SAME service after stream.m3u has
             * been resolved gives OpenWebif a current frontend to inspect. OpenWebif's standby zap
             * implementation calls session.nav.playService() without leaving standby, so HDMI/CEC
             * is not woken. When the box is already awake stream.m3u has already zapped to the same
             * service, making this effectively idempotent.
             *
             * Diagnostics must never make playback fail, therefore old/vendor OpenWebif images that
             * do not support the JSON zap endpoint simply continue with the already resolved stream.
             */
            runCatching {
                OpenWebifNetworkClient.create(config).zap(
                    serviceReference = channel.serviceReference,
                    title = channel.name,
                )
            }

            stream
        }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val config = store.loadConfig()
        if (config == null) {
            _state.value = OpenWebifState()
            return@withContext
        }

        _state.update {
            it.copy(
                configured = true,
                receiverLabel = OpenWebifUrl.receiverLabel(config.baseUrl),
                baseUrl = config.baseUrl,
                username = config.username,
                hasPassword = config.password.isNotEmpty(),
                isRefreshing = true,
                errorMessage = null,
            )
        }

        try {
            val api = OpenWebifNetworkClient.create(config)
            val bouquetResponse = api.getServices()
            if (!bouquetResponse.result) error("OpenWebif rejected bouquet request")

            val bouquets = OpenWebifMapper.bouquets(bouquetResponse.services)
            val selectedBouquet = bouquets.firstOrNull {
                it.serviceReference == config.selectedBouquetRef
            } ?: bouquets.firstOrNull()

            if (selectedBouquet == null) {
                _state.update {
                    it.copy(
                        bouquets = emptyList(),
                        channels = emptyList(),
                        selectedBouquetRef = null,
                        isRefreshing = false,
                        lastUpdatedUtcMillis = System.currentTimeMillis(),
                        errorMessage = "OpenWebif liefert keine TV-Bouquets.",
                    )
                }
                return@withContext
            }

            if (config.selectedBouquetRef != selectedBouquet.serviceReference) {
                store.saveSelectedBouquet(selectedBouquet.serviceReference)
            }

            val servicesResponse = api.getServices(
                serviceReference = selectedBouquet.serviceReference,
                picon = 1,
            )
            if (!servicesResponse.result) error("OpenWebif rejected service request")

            val epgResponse = api.getNowNext(selectedBouquet.serviceReference)
            if (!epgResponse.result) error("OpenWebif rejected EPG request")

            val now = System.currentTimeMillis()
            val channels = OpenWebifMapper.channels(
                baseUrl = config.baseUrl,
                services = servicesResponse.services,
                events = epgResponse.events,
                nowUtcMillis = now,
            )
            val snapshot = OpenWebifCachedSnapshot(
                baseUrl = config.baseUrl,
                bouquets = bouquets,
                selectedBouquetRef = selectedBouquet.serviceReference,
                channels = channels,
                updatedAtUtcMillis = now,
            )
            store.saveSnapshot(snapshot)

            _state.value = OpenWebifState(
                configured = true,
                receiverLabel = OpenWebifUrl.receiverLabel(config.baseUrl),
                baseUrl = config.baseUrl,
                username = config.username,
                hasPassword = config.password.isNotEmpty(),
                bouquets = bouquets,
                selectedBouquetRef = selectedBouquet.serviceReference,
                channels = channels,
                isRefreshing = false,
                lastUpdatedUtcMillis = now,
                errorMessage = null,
            )
        } catch (throwable: Throwable) {
            _state.update { current ->
                current.copy(
                    isRefreshing = false,
                    errorMessage = friendlyError(throwable),
                )
            }
        }
    }

    private fun initialState(): OpenWebifState {
        val config = store.loadConfig() ?: return OpenWebifState()
        return stateFromLocal(config)
    }

    private fun stateFromLocal(config: OpenWebifConfig): OpenWebifState {
        val snapshot = store.loadSnapshot(config)
        return OpenWebifState(
            configured = true,
            receiverLabel = OpenWebifUrl.receiverLabel(config.baseUrl),
            baseUrl = config.baseUrl,
            username = config.username,
            hasPassword = config.password.isNotEmpty(),
            bouquets = snapshot?.bouquets.orEmpty(),
            selectedBouquetRef = config.selectedBouquetRef ?: snapshot?.selectedBouquetRef,
            channels = snapshot?.channels.orEmpty(),
            isRefreshing = false,
            lastUpdatedUtcMillis = snapshot?.updatedAtUtcMillis,
            errorMessage = null,
        )
    }

    private fun friendlyError(throwable: Throwable): String = when (throwable) {
        is HttpException -> when (throwable.code()) {
            401, 403 -> "OpenWebif-Authentifizierung fehlgeschlagen (HTTP ${throwable.code()})."
            else -> "OpenWebif antwortet mit HTTP ${throwable.code()}."
        }

        is UnknownHostException -> "Gigablue-Hostname konnte im lokalen Netzwerk nicht aufgelöst werden."
        is ConnectException -> "Gigablue ist im lokalen Netzwerk nicht erreichbar."
        is SocketTimeoutException -> "Zeitüberschreitung bei der Verbindung zur Gigablue."
        is SSLException -> "HTTPS-Verbindung zur Gigablue konnte nicht aufgebaut werden."
        else -> "OpenWebif-Aktualisierung fehlgeschlagen (${throwable.javaClass.simpleName})."
    }
}
