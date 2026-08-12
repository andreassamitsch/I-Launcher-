package com.andreassamitsch.ilauncher.ui

import androidx.compose.ui.focus.FocusRequester

/**
 * Shared D-Pad destination for returning from the top Home content row to the selected Home tab.
 *
 * The top navigation intentionally overlays the Hero, so geometric focus search alone is ambiguous:
 * the large Hero focus rectangle overlaps the navigation. A stable explicit requester keeps the
 * return path deterministic without changing Home layout or row keyline geometry.
 */
internal val HomeTopNavigationFocusRequester = FocusRequester()
