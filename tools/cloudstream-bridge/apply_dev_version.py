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
        raise SystemExit("usage: apply_dev_version.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    build_gradle = root / "app/build.gradle.kts"
    if not build_gradle.exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    # Keep stock CloudStream metadata when these environment variables are absent. The bridge CI
    # supplies both values so every published APK has a monotonically increasing Android version.
    replace_once(
        build_gradle,
        """        versionCode = libs.versions.versionCode.get().toInt()\n        versionName = libs.versions.versionName.get()""",
        """        versionCode = System.getenv("IL_BRIDGE_VERSION_CODE")?.toIntOrNull()\n            ?: libs.versions.versionCode.get().toInt()\n        versionName = System.getenv("IL_BRIDGE_VERSION_NAME")\n            ?: libs.versions.versionName.get()""",
    )

    # Upstream deliberately gives prerelease builds a minute-based versionCode and appends -PRE.
    # That happens after defaultConfig and therefore overrides the bridge values above. Preserve
    # the upstream behavior unless a bridge build explicitly supplies its own version metadata.
    replace_once(
        build_gradle,
        """            versionNameSuffix = "-PRE"\n            versionCode = (System.currentTimeMillis() / 60000).toInt()""",
        """            if (System.getenv("IL_BRIDGE_VERSION_NAME") == null) {\n                versionNameSuffix = "-PRE"\n            }\n            versionCode = System.getenv("IL_BRIDGE_VERSION_CODE")?.toIntOrNull()\n                ?: (System.currentTimeMillis() / 60000).toInt()""",
    )

    text = build_gradle.read_text(encoding="utf-8")
    if text.count("IL_BRIDGE_VERSION_CODE") < 2 or text.count("IL_BRIDGE_VERSION_NAME") < 2:
        raise RuntimeError("Bridge development version override was not applied to defaultConfig and prerelease flavor")


if __name__ == "__main__":
    main()
