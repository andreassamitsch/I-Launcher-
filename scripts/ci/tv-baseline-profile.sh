#!/usr/bin/env bash
set -euo pipefail

gradle :app:assembleDebug -PiLauncherProfileCapture=true --stacktrace
PKG=com.andreassamitsch.ilauncher
adb install -r app/build/outputs/apk/debug/app-debug.apk
mkdir -p build/tv-profile
adb shell am broadcast -a androidx.profileinstaller.action.SKIP_FILE -e EXTRA_SKIP_FILE_OPERATION WRITE_SKIP_FILE -n $PKG/androidx.profileinstaller.ProfileInstallReceiver
adb shell am force-stop $PKG
adb shell cmd package compile -f -m verify $PKG
adb shell pm art clear-app-profiles $PKG
adb shell am start -W -n $PKG/.ui.preview.UiPreviewActivity
sleep 4
for round in 1 2 3; do
  for key in 20 22 22 22 22 19 20 21 21 20 22 22 20 19 19 19; do
    adb shell input keyevent "$key"
    sleep 0.35
  done
done
sleep 5
adb shell am broadcast -a androidx.profileinstaller.action.SAVE_PROFILE -n $PKG/androidx.profileinstaller.ProfileInstallReceiver
sleep 1
adb shell am force-stop $PKG
adb shell pm dump-profiles --dump-classes-and-methods $PKG
adb pull /data/misc/profman/$PKG-primary.prof.txt build/tv-profile/raw-art-profile.txt
# Retain application UI methods, omit debug fixture code and runtime inline caches.
sed 's/+.*//' build/tv-profile/raw-art-profile.txt | grep -E '^[HSP]*Lcom/andreassamitsch/ilauncher/ui/(home/|components/|GoogleTvTopNavigation)' | sort -u > build/tv-profile/home-profile-candidate.txt
test -s build/tv-profile/home-profile-candidate.txt
