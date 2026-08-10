package com.andreassamitsch.ilauncher.data.search

import com.andreassamitsch.ilauncher.model.SearchItem

data class SearchBrowseSection(
    val key: String,
    val title: String,
    val items: List<SearchItem>,
)
