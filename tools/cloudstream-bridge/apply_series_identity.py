#!/usr/bin/env python3
"""Publish one resume row for verified cross-provider duplicates, without deleting history."""
from pathlib import Path
import shutil
import sys


def patch(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"{path}: expected one identity anchor: {old}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


def main(root: Path) -> None:
    base = root / "app/src/main/java/com/lagradost/cloudstream3"
    shutil.copyfile(Path(__file__).with_name("ILauncherSeriesIdentity.kt"), base / "ILauncherSeriesIdentity.kt")
    test = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherSeriesIdentityTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).with_name(test.name), test)
    patch(base / "ui/result/ResultViewModel2.kt",
          "        currentResponse = loadResponse\n        postPage(loadResponse, apiRepository)",
          "        currentResponse = loadResponse\n        com.lagradost.cloudstream3.ILauncherSeriesIdentity.remember(mainId, loadResponse)\n        postPage(loadResponse, apiRepository)")
    patch(base / "ILauncherDirectPlay.kt",
          "        val parentId = response.getId()\n        val results = series.episodes.mapIndexed",
          "        val parentId = response.getId()\n        ILauncherSeriesIdentity.remember(parentId, series)\n        val results = series.episodes.mapIndexed")
    patch(base / "ui/player/ILauncherPendingResumeGuard.kt",
          "            val displaySeasons = series.seasonNames?.associate",
          "            com.lagradost.cloudstream3.ILauncherSeriesIdentity.remember(parentId, series)\n            val displaySeasons = series.seasonNames?.associate")
    patch(base / "utils/AppContextUtils.kt",
          "val currentProgramIds = data.mapNotNull",
          "val currentProgramIds = com.lagradost.cloudstream3.ILauncherSeriesIdentity.forWatchNext(data).mapNotNull")
    patch(base / "ILauncherWatchNextIntent.kt",
          """        if (isOlderEpisode(target.season, target.episode, base.season, base.episode)) return base
        val exactEpisodeId = target.episodeId ?: base.id""",
          """        val preferred = ILauncherSeriesIdentity.preferredResume(base, cards)
        if (preferred !== base &&
            !isOlderEpisode(preferred.season, preferred.episode, target.season, target.episode)) {
            return preferred
        }
        if (isOlderEpisode(target.season, target.episode, base.season, base.episode)) return base
        val exactEpisodeId = target.episodeId ?: base.id""")


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
