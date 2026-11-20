#!/usr/bin/env bash
# Retest the standalone candidate after debug media acceptance, using an identically signed test APK.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
adb=${ADB:-adb}
pkg=com.blackboxstream.mobile
apk=${1:?Provide the standalone APK}
hashes=${2:?Provide the runner file containing the reference media hashes}
key=${TEST_KEYSTORE:-$HOME/.android/blackbox-test.jks}
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Install the Android SDK}}
tools=${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}
work=$(mktemp -d)
cleanup() {
    "$adb" shell wm size reset >/dev/null 2>&1 || true
    "$adb" shell wm density reset >/dev/null 2>&1 || true
    "$adb" uninstall "$pkg.test" >/dev/null 2>&1 || true
    "$adb" uninstall "$pkg" >/dev/null 2>&1 || true
    rm -rf -- "$work"
}
trap cleanup EXIT
[[ -s "$apk" && -s "$hashes" && -s "$key" ]]
"$tools/apksigner" sign --ks "$key" --ks-pass pass:android --ks-key-alias androiddebugkey \
    --out "$work/app-test.apk" "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$adb" install --no-streaming "$apk"
"$adb" shell wm size 720x1560
"$adb" shell wm density 320
"$adb" shell input keyevent 82
start=$("$adb" shell am start -W -n "$pkg/.V90Activity")
printf '%s\n' "$start"
if [[ "$start" != *'Status: ok'* || "$start" != *'.V90Activity'* ]]; then
    printf '::error::Standalone APK did not open the v90 launcher.\n'
    exit 1
fi
BBX_APP_APK="$apk" BBX_TEST_APK="$work/app-test.apk" BBX_VERIFY_PUBLIC_ONLY=1 \
    BBX_PUBLIC_MEDIA_HASHES="$(cat "$hashes")" bash "$root/tests/run_device.sh"
printf '::notice::Standalone APK passed the public film/anime download and offline replay checks.\n'
