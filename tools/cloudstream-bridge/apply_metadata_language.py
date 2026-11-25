#!/usr/bin/env python3
"""Conservative metadata-only TMDB identity fallback and Serienstream source language selection."""
from pathlib import Path
import shutil
import sys

def patch(path, before, after):
    source = path.read_text(encoding="utf-8")
    if source.count(before) != 1:
        raise RuntimeError(f"{path}: expected one anchor: {before[:120]}")
    path.write_text(source.replace(before, after, 1), encoding="utf-8")

def main(root):
    base = root / "app/src/main/java/com/lagradost/cloudstream3"
    lib = root / "library/src/commonMain/kotlin/com/lagradost/cloudstream3/metaproviders/TmdbProvider.kt"
    patch(lib, "    private fun TmdbSearchResult.toSearchResponse() = if (isTv) {", """    // Bridge metadata only; reuse the upstream TMDB client and credentials.
    suspend fun bridgeSearchTvIdentity(query: String): List<TmdbSearchResult> =
        parseJson<TmdbPageResult>(getApi("/search/tv", mapOf(
            "query" to query, "language" to "de-DE", "include_adult" to "false"
        ))).results.orEmpty()

    suspend fun bridgeTvIdentitySeason(id: Int, season: Int, language: String): List<TmdbEpisode> =
        parseJson<TmdbSeasonDetail>(getApi("/tv/$id/season/$season", mapOf(
            "language" to language
        ))).episodes.orEmpty()

    private fun TmdbSearchResult.toSearchResponse() = if (isTv) {""")

    player = base / "ui/player"
    shutil.copyfile(Path(__file__).with_name("ILauncherSourceLanguage.kt"), player / "ILauncherSourceLanguage.kt")
    test = root / "app/src/test/java/com/lagradost/cloudstream3/ui/player/ILauncherSourceLanguageTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).with_name(test.name), test)
    vm = player / "PlayerGeneratorViewModel.kt"
    patch(vm, "    fun sortLinks(qualityProfile: Int): List<DisplayLink> {", """    fun sourceLanguageMode(): String = ILauncherSourceLanguage.mode(
        (generatorState?.meta as? com.lagradost.cloudstream3.ui.result.ResultEpisode)?.apiName
            ?: generatorState?.response?.apiName
    )

    fun sortLinks(qualityProfile: Int): List<DisplayLink> {""")
    patch(vm, """        return links.map { link ->
            // negative because we want to sort highest quality first
            link.toDisplayLink(qualityProfile, hideNegativeSources, hideErrorSources)
        }.sortedBy {
            // negative because we want to sort highest quality first
            -it.priority
        }.also { value -> sortedLinks[qualityProfile] = value }""", """        val mode = sourceLanguageMode()
        return links.map { link ->
            val display = link.toDisplayLink(qualityProfile, hideNegativeSources, hideErrorSources)
            display.copy(shouldUseLink = display.shouldUseLink &&
                ILauncherSourceLanguage.allowed(mode, ILauncherSourceLanguage.rank(link)))
        }.sortedWith(compareByDescending<DisplayLink> {
            if (mode == "all") 0 else ILauncherSourceLanguage.rank(it.link)
        }.thenByDescending { it.priority }).also { value -> sortedLinks[qualityProfile] = value }""")
    gen = player / "GeneratorPlayer.kt"
    patch(gen, """                if (!isPlayerActive.get() && viewModel.state.links.any { link ->
                        getLinkPriority(currentQualityProfile, link.first) >=
                                QualityDataHelper.AUTO_SKIP_PRIORITY
                    }""", """                if (!isPlayerActive.get() && sortedLinks.any { link ->
                        link.shouldUseLink && ILauncherSourceLanguage.earlyAllowed(
                            viewModel.state.sourceLanguageMode(), ILauncherSourceLanguage.rank(link.link)
                        ) && link.priority >= QualityDataHelper.AUTO_SKIP_PRIORITY
                    }""")
    # Do not offer manual early fallback before a German candidate appears while preferred.
    patch(gen, """            val turnVisible = usableLinks > 0 && viewModel.generator?.canSkipLoading == true""", """            val turnVisible = sortedLinks.any {
                it.shouldUseLink && ILauncherSourceLanguage.earlyAllowed(
                    viewModel.state.sourceLanguageMode(), ILauncherSourceLanguage.rank(it.link)
                )
            } && viewModel.generator?.canSkipLoading == true""")
    patch(gen, """        loadLink(firstAvailableLink, false)
        showPlayerMetadata()""", """        val languageMode = viewModel.state.sourceLanguageMode()
        if (languageMode != "all") {
            Log.i("ILauncherSourceLanguage", "mode=$languageMode rank=${ILauncherSourceLanguage.rank(firstAvailableLink)}")
        }
        loadLink(firstAvailableLink, false)
        showPlayerMetadata()""")

if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
