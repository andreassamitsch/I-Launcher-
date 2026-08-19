package com.andreassamitsch.ilauncher.system

internal const val ACTION_RETURN_TO_LAUNCHER_HOME =
    "com.andreassamitsch.ilauncher.action.RETURN_HOME"

internal fun isLauncherHomeRequest(
    action: String?,
    categories: Set<String>,
): Boolean =
    action == ACTION_RETURN_TO_LAUNCHER_HOME ||
        (action == "android.intent.action.MAIN" && "android.intent.category.HOME" in categories)
