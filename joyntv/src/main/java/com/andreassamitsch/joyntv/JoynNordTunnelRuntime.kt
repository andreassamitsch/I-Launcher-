package com.andreassamitsch.joyntv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface JoynNordTunnelState {
    data object Idle : JoynNordTunnelState
    data class Connecting(val host: String) : JoynNordTunnelState
    data class Connected(val host: String, val peerAddress: String) : JoynNordTunnelState
    data class Stopping(val host: String?) : JoynNordTunnelState
    data class Error(val host: String?, val message: String) : JoynNordTunnelState
}

/**
 * Process-local state for the experimental app-scoped NordVPN tunnel.
 *
 * The actual tunnel is owned by JoynNordOpenVpnService. This object only exposes enough state for
 * the TV settings UI/scanner to await transitions and show useful diagnostics. Credentials are
 * never written to this state or to the log buffer.
 */
internal object JoynNordTunnelRuntime {
    private val mutableState = MutableStateFlow<JoynNordTunnelState>(JoynNordTunnelState.Idle)
    val state: StateFlow<JoynNordTunnelState> = mutableState.asStateFlow()

    private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = mutableLogs.asStateFlow()

    fun update(state: JoynNordTunnelState) {
        mutableState.value = state
    }

    fun log(line: String) {
        val cleaned = line.replace('\r', ' ').replace('\n', ' ').trim()
        if (cleaned.isBlank()) return
        mutableLogs.value = (mutableLogs.value + cleaned.take(300)).takeLast(MAX_LOG_LINES)
    }

    fun clearLogs() {
        mutableLogs.value = emptyList()
    }

    private const val MAX_LOG_LINES = 80
}
