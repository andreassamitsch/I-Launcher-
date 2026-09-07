package com.andreassamitsch.servusprovider.data

/** Decides which catalogue show roots need episode traffic during a periodic refresh. */
object ServusShowRefreshPolicy {
    fun periodicShowIds(
        categories: List<ServusCategory>,
        currentSelectionConfigured: Boolean,
        currentSelectedIds: Set<String>,
        tvChannelSelectedIds: Set<String>,
        currentCollectionParentIds: Set<String> = emptySet(),
        tvCollectionParentIds: Set<String> = emptySet(),
    ): Set<String> {
        val shows = categories.flatMap { it.shows }.distinctBy { it.id }
        val validIds = shows.mapTo(hashSetOf()) { it.id }
        val result = linkedSetOf<String>()

        if (currentSelectionConfigured) {
            shows.asSequence()
                .filter { it.id in currentSelectedIds }
                .filter { show ->
                    show.id in currentCollectionParentIds ||
                        !ServusCurrentChannelPolicy.isLegacyDefaultTitle(show.title)
                }
                .mapTo(result) { it.id }
        }

        tvChannelSelectedIds.asSequence()
            .filter { it in validIds }
            .forEach(result::add)
        currentCollectionParentIds.asSequence()
            .filter { it in validIds }
            .forEach(result::add)
        tvCollectionParentIds.asSequence()
            .filter { it in validIds }
            .forEach(result::add)
        return result
    }
}
