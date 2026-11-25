package com.andreassamitsch.ilauncher.ui.home

/** Delay only distinct media identities, never updates to the displayed content. */
internal fun homeHeroFocusDelayMillis(displayedKey: String, focusedKey: String): Long =
    if (displayedKey == focusedKey) 0L else 180L
