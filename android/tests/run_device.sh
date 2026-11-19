#!/usr/bin/env bash
# Requires a booted, unlocked emulator/device and assembled Debug and DebugAndroidTest APKs.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
adb=${ADB:-adb}
"$adb" install -r "$root/app/build/outputs/apk/debug/app-debug.apk"
"$adb" install -r -t "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$adb" shell input keyevent 82
out=$("$adb" shell am instrument -w -r com.blackboxstream.mobile.test/com.blackboxstream.mobile.BlackBoxSmoke)
printf '%s\n' "$out"
if [[ "$out" != *'PASS BlackBox Android smoke'* || "$out" != *'INSTRUMENTATION_CODE: -1'* ]]; then
    "$adb" logcat -d -t 500 -s BlackBoxSmoke AndroidRuntime JobScheduler MediaPlayer || true
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        brief=$(printf '%s\n' "$out" | grep -m1 -E 'FAIL BlackBox Android|INSTRUMENTATION_FAILED' || true)
        printf '::error::%s\n' "${brief:-Android instrumentation did not pass}"
    fi
    exit 1
fi
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
    printf '::notice::%s\n' "$(printf '%s\n' "$out" | grep -m1 'PASS BlackBox Android smoke')"
fi
