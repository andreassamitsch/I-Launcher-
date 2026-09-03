package com.andreassamitsch.servusprovider.data

/** Decides which catalogue shows need episode traffic during a periodic refresh. */
object ServusShowRefreshPolicy {
    fun periodicShowIds(
        categories: List<ServusCategory>,
        currentSelectionConfigured: Boolean,
        currentSelectedIds: Set<String>,
        tvChannelSelectedIds: Set<String>,
    ): Set<String> {
        val shows = categories.flatMap { it.shows }.distinctBy { it.id }
        val validIds = shows.mapTo(hashSetOf()) { it.id }
        val result = linkedSetOf<String>()

        if (currentSelectionConfigured) {
            shows.asSequence()
                .filter { it.id in currentSelectedIds }
                .filterNot { ServusCurrentChannelPolicy.isLegacyDefaultTitle(it.title) }
                .mapTo(result) { it.id }
        }

        tvChannelSelectedIds.asSequence()
            .filter { it in validIds }
            .forEach(result::add)
        return result
    }
}
