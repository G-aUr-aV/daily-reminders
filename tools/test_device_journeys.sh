#!/usr/bin/env bash
# Run on a fresh disposable emulator. The test configures a PIN and installs two same-signed apps.
set -euo pipefail

previous_apk=${1:?Path to the same-signed 0.7.0 APK}
evidence=${2:?Directory for screenshots and device logs}
mkdir -p "$evidence"

collect_evidence() {
    adb pull /sdcard/Android/data/dev.gaurav.dailyrhythm/files/. "$evidence/" >/dev/null 2>&1 || true
    adb logcat -d -t 1500 > "$evidence/device.log" || true
}
trap collect_evidence EXIT

run_device_test() {
    local name=$1
    shift
    adb shell am instrument -w "$@" dev.gaurav.dailyrhythm.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence/$name.log"
    grep -Fq 'OK (1 test)' "$evidence/$name.log"
}

adb shell wm size 720x1280
adb shell wm density 280
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install -g "$previous_apk"
adb install app/build/outputs/apk/androidTest/hardened/app-hardened-androidTest.apk
run_device_test seed -e class dev.gaurav.dailyrhythm.AgendaUpgradeTest -e upgradeStage seed
adb install -r -g app/build/outputs/apk/hardened/app-hardened.apk
run_device_test upgrade -e class dev.gaurav.dailyrhythm.AgendaUpgradeTest -e upgradeStage verify
adb shell appops set dev.gaurav.dailyrhythm SCHEDULE_EXACT_ALARM allow
adb shell appops set dev.gaurav.dailyrhythm USE_FULL_SCREEN_INTENT allow
adb shell settings put secure lock_screen_lock_after_timeout 0
adb shell locksettings set-pin 2468
run_device_test locked -e class dev.gaurav.dailyrhythm.LockedReminderTest
