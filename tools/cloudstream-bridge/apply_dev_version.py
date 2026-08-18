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

    replace_once(
        build_gradle,
        """        versionCode = libs.versions.versionCode.get().toInt()\n        versionName = libs.versions.versionName.get()""",
        """        versionCode = System.getenv("IL_BRIDGE_VERSION_CODE")?.toIntOrNull()\n            ?: libs.versions.versionCode.get().toInt()\n        versionName = System.getenv("IL_BRIDGE_VERSION_NAME")\n            ?: libs.versions.versionName.get()""",
    )

    text = build_gradle.read_text(encoding="utf-8")
    if "IL_BRIDGE_VERSION_CODE" not in text or "IL_BRIDGE_VERSION_NAME" not in text:
        raise RuntimeError("Bridge development version override was not applied")


if __name__ == "__main__":
    main()
