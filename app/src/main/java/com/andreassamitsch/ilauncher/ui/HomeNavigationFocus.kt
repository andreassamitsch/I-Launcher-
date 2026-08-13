package com.andreassamitsch.ilauncher.ui

import androidx.compose.ui.focus.FocusRequester

/**
 * Stable D-Pad destinations for returning from the top content row to the selected top-navigation
 * destination. The overlay Hero overlaps geometric focus search, so Home, Movies and Series all use
 * explicit requesters for a deterministic one-UP return path.
 */
internal val HomeTopNavigationFocusRequester = FocusRequester()
internal val MoviesTopNavigationFocusRequester = FocusRequester()
internal val SeriesTopNavigationFocusRequester = FocusRequester()