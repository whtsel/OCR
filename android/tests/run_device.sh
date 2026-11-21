#!/usr/bin/env bash
# Requires a booted, unlocked emulator/device and assembled Debug and DebugAndroidTest APKs.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
adb=${ADB:-adb}
package=${BBX_TEST_PACKAGE:-com.blackboxstream.mobile}
"$adb" install -r "${BBX_APP_APK:-$root/app/build/outputs/apk/debug/app-debug.apk}"
"$adb" install -r -t "${BBX_TEST_APK:-$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
"$adb" shell input keyevent 82
args=()
if [[ -n "${BBX_FIXTURE_MAGNET:-}" ]]; then
    args=(-e fixture_magnet "$BBX_FIXTURE_MAGNET" -e fixture_torrent "$BBX_FIXTURE_TORRENT"
          -e fixture_relay "$BBX_FIXTURE_RELAY" -e fixture_cert "$BBX_FIXTURE_CERT")
fi
if [[ -n "${BBX_PUBLIC_MEDIA_HASHES:-}" ]]; then
    args+=(-e public_media "$BBX_PUBLIC_MEDIA_HASHES")
fi
if [[ "${BBX_VERIFY_PUBLIC_ONLY:-0}" == 1 ]]; then
    : "${BBX_PUBLIC_MEDIA_HASHES:?Provide the independently collected public file hashes}"
    args+=(-e public_only yes)
fi
if [[ "${BBX_VERIFY_MUSIC_ONLY:-0}" == 1 ]]; then
    args+=(-e music_only yes)
    if [[ "${BBX_VERIFY_UNPINNED_ONLY:-0}" == 1 ]]; then
        args+=(-e unpinned_only yes)
    fi
fi
if [[ "${BBX_VERIFY_PUBLIC_PREVIEW:-0}" == 1 ]]; then
    : "${BBX_PUBLIC_MEDIA_HASHES:?Provide the independently collected public file hashes}"
    args+=(-e public_preview yes)
fi
if [[ "${BBX_VERIFY_HELPER_FIXTURE:-0}" == 1 ]]; then
    args+=(-e helper_fixture yes)
fi
out=$("$adb" shell am instrument -w -r "${args[@]}" "$package.test/com.blackboxstream.mobile.BlackBoxSmoke")
printf '%s\n' "$out"
if [[ "${BBX_VERIFY_HELPER_FIXTURE:-0}" == 1 ]]; then
    trace=$("$adb" logcat -d -v raw -s BlackBoxSmoke:I | grep '^v90 fixture:' | tail -8 || true)
    printf '%s\n' "$trace"
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        printf '%s\n' "$trace" | sed -n 's/^v90 fixture:/::notice::v90 fixture:/p'
    fi
fi
if [[ "${BBX_VERIFY_MUSIC_ONLY:-0}" == 1 ]]; then
    trace=$("$adb" logcat -d -v raw -s BlackBoxMusic:I BlackBoxSmoke:I | grep -e '^v90 provider ' -e ' +[0-9][0-9]*ms$' | tail -100 || true)
    printf '%s\n' "$trace"
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        printf '%s\n' "$trace" | sed -n -e 's/^v90 provider /::notice::v90 provider /p' -e '/^[a-zA-Z0-9 ,()]* +[0-9][0-9]*ms$/s/^/::notice::Music stage: /p'
        if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
            printf '### Cold v90 Music on Android\n\n```text\n%s\n```\n' "$trace" >> "$GITHUB_STEP_SUMMARY"
        fi
    fi
fi
if [[ "$out" != *'PASS BlackBox Android smoke'* || "$out" != *'INSTRUMENTATION_CODE: -1'* ||
    ( -n "${BBX_PUBLIC_MEDIA_HASHES:-}" && "$out" != *'both played with networking off'* ) ||
    ( "${BBX_VERIFY_MUSIC_ONLY:-0}" == 1 && "$out" != *'PASS Original v90 Music:'* ) ]]; then
    device_log=$("$adb" logcat -d -v brief -s AndroidRuntime:E BlackBoxSmoke:I MediaPlayer:E | tail -65 || true)
    printf '%s\n' "$device_log"
    if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
        # Keep the exception and failed control even when a long list of earlier checks passed.
        diagnostic=$(printf '%s\n%s\n' "${out:0:1900}" "$(printf '%s' "$device_log" | tail -c 2300)")
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
