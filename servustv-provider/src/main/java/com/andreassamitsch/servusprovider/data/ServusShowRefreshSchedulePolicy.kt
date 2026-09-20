package com.andreassamitsch.servusprovider.data

/** Only successfully refreshed, explicitly selected shows are throttled; opening a show is unaffected. */
internal object ServusShowRefreshSchedulePolicy {
    const val MIN_INTERVAL_MS = 15L * 60L * 1000L

    fun dueIds(showIds: Set<String>, nowMillis: Long, lastSuccessful: (String) -> Long): Set<String> =
        showIds.filterTo(linkedSetOf()) { id ->
            val last = lastSuccessful(id)
            last <= 0L || nowMillis < last || nowMillis - last >= MIN_INTERVAL_MS
        }
}
