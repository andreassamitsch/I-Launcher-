#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path

BASE_COMMIT = "a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}: found {count}\n--- needle ---\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_patch.py <cloudstream-source-root>")

    root = Path(sys.argv[1]).resolve()
    if not (root / "app" / "src" / "main").exists():
        raise RuntimeError(f"Not a CloudStream checkout: {root}")

    # 1) Reproduce the Watch Next fix from CloudStream-WatchNext-Test-a72f9e6.apk.
    app_context = root / "app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt"
    replace_once(
        app_context,
        """    private fun buildWatchNextProgramUri(\n        context: Context,\n        card: DataStoreHelper.ResumeWatchingResult,\n        resumeWatching: DownloadObjects.ResumeWatching?\n    ): WatchNextProgram {""",
        """    private fun buildWatchNextProgramUri(\n        context: Context,\n        card: DataStoreHelper.ResumeWatchingResult,\n        resumeWatching: DownloadObjects.ResumeWatching?,\n        customId: String,\n    ): WatchNextProgram {""",
    )
    replace_once(app_context, ".setInternalProviderId(card.url)", ".setInternalProviderId(customId)")
    replace_once(
        app_context,
        """                    val nextProgram = buildWatchNextProgramUri(\n                        context,\n                        episodeInfo,\n                        timeStampHashMap[episodeInfo.id]\n                    )""",
        """                    val nextProgram = buildWatchNextProgramUri(\n                        context,\n                        episodeInfo,\n                        timeStampHashMap[episodeInfo.id],\n                        customId,\n                    )""",
    )

    # 2) Add the I Launcher direct-play resolver and its pure protocol tests to CloudStream.
    bridge_source = Path(__file__).with_name("ILauncherDirectPlay.kt")
    bridge_target = root / "app/src/main/java/com/lagradost/cloudstream3/ILauncherDirectPlay.kt"
    shutil.copyfile(bridge_source, bridge_target)
    bridge_test_source = Path(__file__).with_name("ILauncherDirectPlayTest.kt")
    bridge_test_target = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherDirectPlayTest.kt"
    bridge_test_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(bridge_test_source, bridge_test_target)

    # 3) Let MainActivity consume cloudstreamplay://v1 before the raw cloudstreamplayer URL path.
    main_activity = root / "app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt"
    replace_once(
        main_activity,
        """                    } else if (safeURI(str)?.scheme == APP_STRING_PLAYER) {""",
        """                    } else if (safeURI(str)?.scheme == ILauncherDirectPlay.SCHEME) {\n                        return ILauncherDirectPlay.handle(this, str)\n                    } else if (safeURI(str)?.scheme == APP_STRING_PLAYER) {""",
    )

    # 4) Export only the explicit VIEW entry through the existing AccountSelectActivity gate.
    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(
        manifest,
        """            <!-- Allow searching with intents: cloudstreamsearch://Your%20Name -->\n            <intent-filter>""",
        """            <!-- I Launcher direct provider/playback handoff. -->\n            <intent-filter>\n                <action android:name=\"android.intent.action.VIEW\" />\n\n                <category android:name=\"android.intent.category.DEFAULT\" />\n                <category android:name=\"android.intent.category.BROWSABLE\" />\n\n                <data android:scheme=\"cloudstreamplay\" android:host=\"v1\" />\n            </intent-filter>\n\n            <!-- Allow searching with intents: cloudstreamsearch://Your%20Name -->\n            <intent-filter>""",
    )

    # 5) The uploaded APK was signed by an ephemeral Android debug key. Its private key is not
    # recoverable from the APK. For reproducible future bridge updates, make prereleaseDebug use
    # the explicitly supplied stable development signing config when CI provides one.
    build_gradle = root / "app/build.gradle.kts"
    replace_once(
        build_gradle,
        """        debug {\n            isDebuggable = true\n            applicationIdSuffix = \".debug\"\n            proguardFiles(""",
        """        debug {\n            isDebuggable = true\n            applicationIdSuffix = \".debug\"\n            if (signingConfigs.names.contains(\"prerelease\")) {\n                signingConfig = signingConfigs.getByName(\"prerelease\")\n            }\n            proguardFiles(""",
    )

    print(f"Applied I Launcher CloudStream bridge patch on upstream {BASE_COMMIT}")


if __name__ == "__main__":
    main()
