#!/usr/bin/env bash
# Requires a booted, unlocked emulator/device and assembled Debug and DebugAndroidTest APKs.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
adb=${ADB:-adb}
"$adb" install -r "$root/app/build/outputs/apk/debug/app-debug.apk"
"$adb" install -r -t "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$adb" shell input keyevent 82
args=()
if [[ -n "${BBX_FIXTURE_MAGNET:-}" ]]; then
    args=(-e fixture_magnet "$BBX_FIXTURE_MAGNET" -e fixture_torrent "$BBX_FIXTURE_TORRENT"
          -e fixture_relay "$BBX_FIXTURE_RELAY" -e fixture_cert "$BBX_FIXTURE_CERT")
fi
if [[ "${BBX_VERIFY_LIVE_FILM:-0}" == 1 ]]; then
    args+=(-e live_cinema yes)
fi
out=$("$adb" shell am instrument -w -r "${args[@]}" com.blackboxstream.mobile.test/com.blackboxstream.mobile.BlackBoxSmoke)
printf '%s\n' "$out"
if [[ "$out" != *'PASS BlackBox Android smoke'* || "$out" != *'INSTRUMENTATION_CODE: -1'* ]]; then
    device_log=$("$adb" logcat -d -v brief -s AndroidRuntime:E BlackBoxSmoke:I MediaPlayer:E | tail -65 || true)
    printf '%s\n' "$device_log"
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        diagnostic=$(printf '%s\n%s\n' "$out" "$device_log" | tail -c 4300)
        diagnostic=${diagnostic//'%'/'%25'}
        diagnostic=${diagnostic//$'\r'/'%0D'}
        diagnostic=${diagnostic//$'\n'/'%0A'}
        printf '::error::Android smoke failed: %s\n' "${diagnostic:-no result or application logs}"
    fi
    exit 1
fi
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
    printf '%s\n' "$out" | sed -n -e 's/^PASS /::notice::PASS /p' -e 's/^v90 provider /::notice::v90 provider /p'
fi
