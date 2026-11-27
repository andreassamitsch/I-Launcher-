#!/usr/bin/env python3
"""Write the normal CloudStream resume header before launching direct playback."""
from pathlib import Path
import shutil
import sys

def main(root):
    base = root / "app/src/main/java/com/lagradost/cloudstream3"
    direct = base / "ILauncherDirectPlay.kt"
    source = direct.read_text(encoding="utf-8")
    old = """    private fun launchPreparedPlayback(activity: FragmentActivity, prepared: PreparedPlayback, launchToken: Long) {
        ILauncherBridgeNavigation.replacePlayer("""
    new = """    private fun launchPreparedPlayback(activity: FragmentActivity, prepared: PreparedPlayback, launchToken: Long) {
        if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) return
        val page = prepared.generator.page
        val episode = prepared.generator.videos.getOrNull(prepared.index)
        if (page != null && episode != null) {
            // The native result screen normally saves this header. Both automatic and
            // chosen-provider direct play bypass it, so do the same before navigation.
            runCatching { ILauncherDirectResumeHeader.persist(episode.parentId, page) }
                .onFailure { Log.w(TAG, "resume header write failed (${it.javaClass.simpleName})") }
        }
        ILauncherBridgeNavigation.replacePlayer("""
    if source.count(old) != 1:
        raise RuntimeError("Unexpected direct playback launch anchor")
    direct.write_text(source.replace(old, new, 1), encoding="utf-8")
    here = Path(__file__).parent
    shutil.copyfile(here / "ILauncherDirectResumeHeader.kt", base / "ILauncherDirectResumeHeader.kt")
    test = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherDirectResumeHeaderTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(here / test.name, test)

if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
