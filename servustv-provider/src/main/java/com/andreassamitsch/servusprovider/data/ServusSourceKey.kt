package com.andreassamitsch.servusprovider.data

/** Stable local identity for either a complete show or one of its editorial content collections. */
object ServusSourceKey {
    private const val COLLECTION_PREFIX = "collection:"
    private const val SEPARATOR = ":"

    fun show(showId: String): String = showId

    fun collection(showId: String, collectionId: String): String =
        "$COLLECTION_PREFIX$showId$SEPARATOR$collectionId"

    fun isCollection(key: String): Boolean = key.startsWith(COLLECTION_PREFIX)

    fun parentShowId(key: String): String? {
        if (!isCollection(key)) return key.takeIf { it.isNotBlank() }
        val body = key.removePrefix(COLLECTION_PREFIX)
        val separator = body.indexOf(SEPARATOR)
        return body.takeIf { separator > 0 }?.substring(0, separator)
    }

    fun collectionId(key: String): String? {
        if (!isCollection(key)) return null
        val body = key.removePrefix(COLLECTION_PREFIX)
        val separator = body.indexOf(SEPARATOR)
        return body.takeIf { separator in 1 until body.lastIndex }?.substring(separator + 1)
    }

    fun validKeys(categories: List<ServusCategory>): Set<String> = buildSet {
        categories.flatMap { it.shows }.distinctBy { it.id }.forEach { show ->
            add(show(show.id))
            show.collections
                .filter { it.role == ServusCollectionRole.CONTENT }
                .forEach { collection -> add(collection(show.id, collection.id)) }
        }
    }

    fun collectionParentShowIds(keys: Set<String>): Set<String> = keys.asSequence()
        .filter(::isCollection)
        .mapNotNull(::parentShowId)
        .toCollection(linkedSetOf())
}
