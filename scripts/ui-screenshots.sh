#!/bin/sh
# Installs the debug APK on a running emulator and captures the main screens.
# Used by CI; also works locally with any adb-visible device.
set -eu
OUT=${1:-build/screenshots}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
PKG=cn.offline.mapper
mkdir -p "$OUT"
adb install -r -g "$APK"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
for tab in 0 1 2; do
  adb shell am start -S -W -n $PKG/.MainActivity --ei tab $tab >/dev/null
  shot "tab-$tab"
done
adb shell am start -S -W -n $PKG/.MainActivity --es open editor >/dev/null
shot editor 4
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell am start -S -W -n $PKG/.TouchLabActivity >/dev/null || adb shell am start -S -W -n $PKG/.MainActivity --es open lab >/dev/null
sleep 2
# Draw a curved finger path and keep it down while capturing.
SIZE=$(adb shell wm size | tail -1 | sed 's/.*: //')
W=${SIZE%x*}; H=${SIZE#*x}
CX=$((W * 30 / 100)); CY=$((H * 60 / 100))
adb shell input motionevent DOWN $CX $CY
i=0
while [ $i -le 20 ]; do
  X=$((CX + i * W / 120)); Y=$((CY - i * i * H / 1600))
  adb shell input motionevent MOVE $X $Y
  i=$((i + 1))
done
adb exec-out screencap -p > "$OUT/touch-lab.png"; echo "captured touch-lab"
adb shell input motionevent UP $X $Y
