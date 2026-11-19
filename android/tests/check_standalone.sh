#!/usr/bin/env bash
# Check the same standalone packaging path used for the signed handoff, then let the
# instrumentation install its debug variant (which has a test-only content provider).
set -euo pipefail
adb=${ADB:-adb}
pkg=com.blackboxstream.mobile
"$adb" install "$1"
trap '"$adb" uninstall "$pkg" >/dev/null' EXIT
"$adb" shell input keyevent 82
start=$("$adb" shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$pkg")
printf '%s\n' "$start"
if [[ "$start" != *'Status: ok'* || "$start" != *'.V90Activity'* ]]; then
    printf '::error::The standalone APK did not open the v90 launcher activity.\n'
    exit 1
fi
printf '::notice::Standalone APK installed and opened the v90 launcher on Android.\n'
