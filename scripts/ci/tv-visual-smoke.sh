#!/usr/bin/env bash
set -euo pipefail

gradle :app:assembleDebug -PiLauncherOptimizedDevelopment=true --stacktrace
adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p build/tv-visual

# Android 14 dumpsys uses topResumedActivity/ResumedActivity. If Home unexpectedly
# loses the foreground, fail immediately and preserve logs instead of uploading a
# misleading screenshot of the system launcher.
adb logcat -c
adb shell am start -W -n com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity
sleep 4
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-nav-selected.png

# New top-navigation contract: Home is personal recommendations; deliberate discovery
# lives next to it as Movies and Series. Branding and Apps are no longer top-level tabs.
# Walk the focus through both discovery tabs and back to Home before entering content.
adb shell input keyevent 22
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-nav-movies-focus.png
adb shell input keyevent 22
sleep 1
adb exec-out screencap -p > build/tv-visual/home-nav-series-focus.png
adb shell input keyevent 21
sleep 1
adb shell input keyevent 21
sleep 1
adb exec-out screencap -p > build/tv-visual/home-initial.png
adb shell input keyevent 20
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-first-rail-horizontal-0.png

# Hardware regression from dev.306: one UP from the top Home rail must return directly
# to the selected navigation destination. The collapsed bar must therefore become
# visible again without routing through the large overlapping Hero focus surface.
adb shell input keyevent 19
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-nav-restored-from-first-rail.png
adb shell input keyevent 20
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-first-rail-after-nav-restore.png

# Horizontal stability + dynamic glow regression. The fixture deliberately alternates
# blue/gold/purple artwork so the focus halo must visibly follow the focused card.
adb shell input keyevent 22
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-first-rail-horizontal-1.png
adb shell input keyevent 22
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-first-rail-horizontal-2.png
adb shell input keyevent 22
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-first-rail-horizontal-3.png

# Verify identity, not merely that the UI is alive or that some card is focused.
# The preview rail uses the same WatchNextCard and focusRestorer as Watch Next.
adb shell uiautomator dump /sdcard/home-focus-before.xml
adb pull /sdcard/home-focus-before.xml build/tv-visual/home-focus-before.xml
adb shell input keyevent 19
sleep 1
adb shell input keyevent 20
sleep 1
adb shell uiautomator dump /sdcard/home-focus-after.xml
adb pull /sdcard/home-focus-after.xml build/tv-visual/home-focus-after.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
def focused_card(path):
    nodes = ET.parse(path).findall(".//node[@focused='true']")
    assert len(nodes) == 1, (path, nodes)
    # TV Material can expose the focusable surface and its semantics in separate
    # accessibility nodes. Inspect only the focused surface's own subtree.
    labels = {
        node.get("content-desc", "").strip()
        for node in nodes[0].iter("node")
        if node.get("content-desc", "").strip()
    }
    assert len(labels) == 1, (path, "expected one focused card title", labels)
    return next(iter(labels)), nodes[0].get("bounds")
before = focused_card("build/tv-visual/home-focus-before.xml")
after = focused_card("build/tv-visual/home-focus-after.xml")
assert before == after, (before, after)
print("Restored exact card and position:", after)
PY
adb exec-out screencap -p > build/tv-visual/home-first-rail-restored-later-card.png

# Restore the later first-rail item after scrolling a different rail horizontally.
for pass in 1 2; do
    adb shell input keyevent 20
    sleep 1
    adb shell input keyevent 22
    adb shell input keyevent 22
    sleep 1
    adb shell uiautomator dump /sdcard/home-focus-lower.xml
    adb pull /sdcard/home-focus-lower.xml build/tv-visual/home-focus-lower-${pass}.xml
    adb shell input keyevent 19
    sleep 1
    adb shell uiautomator dump /sdcard/home-focus-vertical.xml
    adb pull /sdcard/home-focus-vertical.xml build/tv-visual/home-focus-vertical-${pass}.xml
done
python3 - <<'PY'
import xml.etree.ElementTree as ET
def focused_card(path):
    nodes = ET.parse(path).findall(".//node[@focused='true']")
    assert len(nodes) == 1, (path, nodes)
    labels = {n.get("content-desc", "").strip() for n in nodes[0].iter("node")
              if n.get("content-desc", "").strip()}
    assert len(labels) == 1, (path, labels)
    return next(iter(labels)), nodes[0].get("bounds")
before = focused_card("build/tv-visual/home-focus-before.xml")
for n in (1, 2):
    lower = focused_card(f"build/tv-visual/home-focus-lower-{n}.xml")
    lower_root = ET.parse(f"build/tv-visual/home-focus-lower-{n}.xml")
    assert lower_root.findall(".//node[@text='Für dich']"), ("second row not visible", lower)
    assert not lower_root.findall(".//node[@text='Empfehlungen']"), ("first row still visible", lower)
    after = focused_card(f"build/tv-visual/home-focus-vertical-{n}.xml")
    assert before == after, (before, after)
    print("Restored exact card after vertical navigation:", after)
PY

adb shell input keyevent 21
sleep 1
adb exec-out screencap -p > build/tv-visual/home-first-rail-horizontal-4.png
adb shell input keyevent 21
sleep 1
adb exec-out screencap -p > build/tv-visual/home-first-rail-horizontal-5.png

adb shell input keyevent 20
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-second-rail.png
adb shell input keyevent 22
sleep 1
adb exec-out screencap -p > build/tv-visual/home-second-rail-horizontal-1.png
adb shell input keyevent 22
sleep 1
adb exec-out screencap -p > build/tv-visual/home-second-rail-horizontal-2.png
adb shell input keyevent 20
sleep 1
adb exec-out screencap -p > build/tv-visual/home-third-rail.png
adb shell input keyevent 20
sleep 1
adb exec-out screencap -p > build/tv-visual/home-fourth-rail.png
adb shell input keyevent 20
sleep 1
adb exec-out screencap -p > build/tv-visual/home-bottom.png

# Second pass: record horizontal + vertical navigation without concurrent screencaps.
adb shell am force-stop com.andreassamitsch.ilauncher
adb shell am start -W -n com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity
sleep 3
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb shell rm -f /sdcard/home-scroll.mp4
adb shell 'screenrecord --time-limit 22 /sdcard/home-scroll.mp4 >/dev/null 2>&1 &'
sleep 1
adb shell input keyevent 20
sleep 1
adb shell input keyevent 19
sleep 1
adb shell input keyevent 20
sleep 1
adb shell input keyevent 22
sleep 1
adb shell input keyevent 22
sleep 1
adb shell input keyevent 22
sleep 1
adb shell input keyevent 21
sleep 1
adb shell input keyevent 21
sleep 1
adb shell input keyevent 20
sleep 1
adb shell input keyevent 22
sleep 1
adb shell input keyevent 22
sleep 1
adb shell input keyevent 20
sleep 1
adb shell input keyevent 20
sleep 1
adb shell input keyevent 20
sleep 1
adb shell input keyevent 19
sleep 1
adb shell input keyevent 19
sleep 5
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb pull /sdcard/home-scroll.mp4 build/tv-visual/home-scroll.mp4 || true

adb shell am force-stop com.andreassamitsch.ilauncher
adb shell am start -W -n com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity --es screen home-epg-fallback
sleep 3
adb shell input keyevent 20
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/home-epg-fallback.png

adb shell am force-stop com.andreassamitsch.ilauncher
adb shell am start -W -n com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity --es screen search-discover
sleep 3
adb exec-out screencap -p > build/tv-visual/search-discover.png

adb shell am force-stop com.andreassamitsch.ilauncher
adb shell am start -W -n com.andreassamitsch.ilauncher/.ui.preview.UiPreviewActivity --es screen search-results
sleep 3
adb exec-out screencap -p > build/tv-visual/search-results.png
adb shell input keyevent 20
sleep 1
adb exec-out screencap -p > build/tv-visual/search-results-focus.png

# Settings visual contract: capture the actual route composition including the two
# launcher-level shortcuts, then move focus into the split settings navigation.
adb shell am force-stop com.andreassamitsch.ilauncher
adb shell am start -W -n com.andreassamitsch.ilauncher/.ui.preview.SettingsPreviewActivity
sleep 3
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | grep -q 'com.andreassamitsch.ilauncher/.ui.preview.SettingsPreviewActivity' || (adb logcat -d -v time > build/tv-visual/logcat-failure.txt; adb shell dumpsys activity activities > build/tv-visual/activity-failure.txt; exit 1)
adb exec-out screencap -p > build/tv-visual/settings-setup.png
adb shell input keyevent 20
sleep 1
adb exec-out screencap -p > build/tv-visual/settings-focus-next.png
