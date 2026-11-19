#!/usr/bin/env bash
# Install and start the production package before the debug-only instrumentation.
set -euo pipefail
adb=${ADB:-adb}
pkg=com.blackboxstream.mobile
apk=$1
fault() {
    local detail=${*//$'\n'/'%0A'}
    printf '::error::Standalone Android launch: %s\n' "${detail:0:1900}"
    exit 1
}
[[ -f "$apk" ]] || fault "No APK at $apk"
trap '"$adb" uninstall "$pkg" >/dev/null 2>&1 || true' EXIT
if ! install=$("$adb" install --no-streaming "$apk" 2>&1); then fault "$install"; fi
printf '%s\n' "$install"
"$adb" shell input keyevent 82
if ! start=$("$adb" shell am start -W -n "$pkg/.V90Activity" 2>&1); then fault "$start"; fi
printf '%s\n' "$start"
if [[ "$start" != *'Status: ok'* || "$start" != *'.V90Activity'* ]]; then fault "$start"; fi
printf '::notice::Standalone APK installed and opened the v90 launcher on Android.\n'
