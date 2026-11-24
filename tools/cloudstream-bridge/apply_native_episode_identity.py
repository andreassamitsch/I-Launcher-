#!/usr/bin/env python3
"""Use the same episode IDs as the official result screen, migrating legacy data locally."""
from pathlib import Path
import shutil
import sys

def patch(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"{path}: unexpected episode identity anchor: {old[:120]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

def main(root):
    base = root / "app/src/main/java/com/lagradost/cloudstream3"
    shutil.copyfile(Path(__file__).with_name("ILauncherEpisodeIdentity.kt"), base / "ILauncherEpisodeIdentity.kt")
    test = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherEpisodeIdentityTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).with_name(test.name), test)
    direct = base / "ILauncherDirectPlay.kt"
    patch(direct, """        val results = series.episodes.mapIndexed { index, ep ->
            val displaySeason = ep.season?.let { displaySeasons[it] ?: it }""",
          """        val requested = series.episodes[selectedIndex]
        ILauncherEpisodeIdentity.migrate(series, parentId)
        val entries = ILauncherEpisodeIdentity.catalogue(series, parentId).distinctBy { it.native }
        val nativeIndex = entries.indexOfFirst { it.episode === requested }
        if (nativeIndex < 0) return null
        val results = entries.mapIndexed { index, entry ->
            val ep = entry.episode
            val displaySeason = ep.season?.let { displaySeasons[it] ?: it }""")
    patch(direct, "                episode = ep.episode ?: (index + 1),", "                episode = entry.number,")
    patch(direct, "                id = episodeId(response.apiName, ep.data, parentId, index),", "                id = entry.native,")
    patch(direct, "            index = selectedIndex,", "            index = nativeIndex,")
    patch(base / "ui/result/ResultViewModel2.kt",
          "        com.lagradost.cloudstream3.ILauncherSeriesIdentity.remember(mainId, loadResponse)",
          """        com.lagradost.cloudstream3.ILauncherSeriesIdentity.remember(mainId, loadResponse)
        if (loadResponse is TvSeriesLoadResponse) {
            com.lagradost.cloudstream3.ILauncherEpisodeIdentity.migrate(loadResponse, mainId)
        }""")

if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
