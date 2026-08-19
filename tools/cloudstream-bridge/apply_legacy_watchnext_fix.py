#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}: found {count}\n--- needle ---\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_legacy_watchnext_fix.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    main_activity = root / "app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
    if not main_activity.exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    # Existing TvProvider rows from pre-fix bridge builds only contain an episode id. If that id is
    # no longer CloudStream's local resume entry, exact resolution can fail. Clear an already-open
    # GeneratorPlayer before doing the async lookup so a failed legacy lookup can never leave an
    # unrelated stale player (for example S1E4) visible behind a Launcher card that showed S1E7.
    replace_once(
        main_activity,
        """                        val target = ILauncherWatchNextIntent.parse(str) ?: return false\n                        val targetActivity = this\n                        ioSafe {\n                            val resumeWatching = HomeViewModel.getResumeWatching().orEmpty()\n                            val resumeWatchingCard =\n                                ILauncherWatchNextIntent.resolve(resumeWatching, target)\n                                    ?: return@ioSafe\n                            main {\n                                ILauncherBridgeNavigation.clearExistingPlayer(targetActivity)\n                                targetActivity.loadSearchResult(\n                                    resumeWatchingCard,\n                                    START_ACTION_RESUME_LATEST\n                                )\n                            }\n                        }""",
        """                        val target = ILauncherWatchNextIntent.parse(str) ?: return false\n                        val targetActivity = this\n                        ILauncherBridgeNavigation.clearExistingPlayer(targetActivity)\n                        ioSafe {\n                            val resumeWatching = HomeViewModel.getResumeWatching().orEmpty()\n                            val resumeWatchingCard =\n                                ILauncherWatchNextIntent.resolve(resumeWatching, target)\n                                    ?: return@ioSafe\n                            main {\n                                targetActivity.loadSearchResult(\n                                    resumeWatchingCard,\n                                    START_ACTION_RESUME_LATEST\n                                )\n                            }\n                        }""",
    )

    patched = main_activity.read_text(encoding="utf-8")
    guard = "ILauncherBridgeNavigation.clearExistingPlayer(targetActivity)\n                        ioSafe"
    if guard not in patched:
        raise RuntimeError("Legacy Watch Next stale-player guard was not applied")


if __name__ == "__main__":
    main()
