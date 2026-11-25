package com.lagradost.cloudstream3

/** A partially loaded list is not proof of missing extensions. */
internal object ILauncherProviderStartup {
    enum class Decision { Wait, Ready, NoExtensions, Unavailable }

    fun decide(
        pinned: String?,
        active: Set<String>,
        initialLoadComplete: Boolean,
        installedCount: Int,
        deadlineReached: Boolean = false,
        safeMode: Boolean = false,
    ): Decision {
        if (safeMode) return Decision.Unavailable
        if (pinned != null && pinned in active) return Decision.Ready
        if (!initialLoadComplete && !deadlineReached) return Decision.Wait
        if (pinned == null && active.isNotEmpty()) return Decision.Ready
        if (initialLoadComplete && installedCount == 0 && active.isEmpty()) return Decision.NoExtensions
        return Decision.Unavailable
    }
}
