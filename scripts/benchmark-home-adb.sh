#!/usr/bin/env bash
# Run with the launcher in the foreground on the connected TV. No APK/data/profile changes.
set -euo pipefail
P=com.andreassamitsch.ilauncher
OUT="${1:-build/tcl-home-benchmark}"
mkdir -p "$OUT"
adb shell dumpsys package "$P" > "$OUT/package.txt"
adb shell dumpsys package dexopt > "$OUT/dexopt.txt"
adb shell dumpsys activity activities > "$OUT/activity.txt"
grep -E 'topResumedActivity|ResumedActivity' "$OUT/activity.txt" | grep -q "$P" || {
  echo "Open I Launcher on Home first." >&2; exit 1;
}
# Same fixed remote sequence each round; no OK, playback or external app activation.
for round in 1 2 3; do
  adb shell 'input keyevent 3; sleep 1; input keyevent 19; input keyevent 19; input keyevent 21; input keyevent 21; sleep 1'
  adb shell dumpsys gfxinfo "$P" reset >/dev/null
  adb shell 'for pass in 1 2 3 4 5; do for k in 20 22 22 22 21 21 21 19 20 19; do input keyevent $k; sleep .35; done; done'
  adb shell dumpsys gfxinfo "$P" > "$OUT/round-$round.txt"
  adb shell cat /proc/meminfo > "$OUT/memory-$round.txt"
  sleep 6
  adb shell dumpsys gfxinfo "$P" > "$OUT/idle-$round.txt"
done
printf 'Saved results to %s\n' "$OUT"
