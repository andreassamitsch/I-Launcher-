package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ILauncherBridgePreferences

/** Source labels are distinct from audio track languages and quality profiles. */
internal object ILauncherSourceLanguage {
    private val german = Regex("""(?i)(?<![\p{L}\p{N}])(deutsch|german|ger|deu|de)(?![\p{L}\p{N}])""")
    private val english = Regex("""(?i)(?<![\p{L}\p{N}])(englisch|english|eng|en)(?![\p{L}\p{N}])""")

    internal fun rank(name: String?): Int {
        val label = name.orEmpty()
        val de = german.containsMatchIn(label) || "🇩🇪" in label || "🇦🇹" in label
        val en = english.containsMatchIn(label) || "🇬🇧" in label || "🇺🇸" in label
        return when {
            de && !en -> 2
            en && !de -> 0
            else -> 1
        }
    }

    fun mode(provider: String?): String =
        if (provider.equals("Serienstream", ignoreCase = true)) {
            CloudStreamApp.context?.let(ILauncherBridgePreferences::serienstreamLanguage) ?: "prefer"
        } else "all"

    fun rank(link: VideoLink): Int {
        val source = link.first ?: return 1
        val named = rank(source.name)
        return if (named != 1) named else rank(source.source)
    }

    internal fun allowed(mode: String, rank: Int): Boolean = mode != "only" || rank == 2
    internal fun earlyAllowed(mode: String, rank: Int): Boolean = mode == "all" || rank == 2
}
