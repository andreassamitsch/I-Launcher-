package com.andreassamitsch.ilauncher.model

data class LiveTvProgram(
    val eventId: Long?,
    val title: String,
    val shortDescription: String? = null,
    val longDescription: String? = null,
    val startUtcMillis: Long,
    val durationMillis: Long,
) {
    val endUtcMillis: Long
        get() = startUtcMillis + durationMillis
}

data class LiveTvChannel(
    val serviceReference: String,
    val name: String,
    val piconUri: String? = null,
    val now: LiveTvProgram? = null,
    val next: LiveTvProgram? = null,
) {
    fun progressFraction(nowUtcMillis: Long = System.currentTimeMillis()): Float? {
        val current = now ?: return null
        if (current.durationMillis <= 0L) return null
        return ((nowUtcMillis - current.startUtcMillis).toFloat() / current.durationMillis.toFloat())
            .coerceIn(0f, 1f)
    }
}
