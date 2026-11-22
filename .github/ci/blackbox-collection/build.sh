#!/usr/bin/env bash
# Package a side-by-side Music pilot, never an upgrade to the existing tester.
set -euo pipefail
original=${1:?Expected archived p11 APK}
helper=${2:?Expected pinned yt-dlp zipapp}
output=${3:?Expected APK output path}
abi=${4:-arm64-v8a}
mode=${5:-fixtures}
if [[ "$mode" != fixtures && !( "$mode" == live && "$abi" == x86_64 ) ]]; then
  echo 'Live comparison is available only for the disposable x86 sibling' >&2
  exit 2
fi
dir=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK required}}
tools="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"
export PYTHONPATH="$dir/..${PYTHONPATH:+:$PYTHONPATH}"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

python3 -B - "$original" "$helper" <<'PY'
from pathlib import Path
import sys
from check_blackbox_p11 import verify_apk, verify_refreshed_helper
verify_apk(Path(sys.argv[1]))
verify_refreshed_helper(Path(sys.argv[2]).read_bytes())
PY
curl -fsSL --retry 2 --max-time 45 --max-filesize 500000 -o "$work/commons-compress.jar" \
  https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.12/commons-compress-1.12.jar
mkdir "$work/classes" "$work/dex"
javac -encoding UTF-8 --release 8 -cp "$platform" -d "$work/classes" \
  "$dir/CollectionActivity.java" "$dir/LocalPlayer.java" "$dir/OfflineLibrary.java" "$dir/DownloadFailure.java"
jar cf "$work/ui.jar" -C "$work/classes" .
"$tools/d8" --min-api 24 --lib "$platform" --output "$work/dex" "$work/commons-compress.jar" "$work/ui.jar"
"$tools/dexdump" "$work/dex/classes.dex" > "$work/dexdump.txt"
grep -q "Class descriptor .*Lorg/apache/commons/compress/archivers/zip/ZipFile;" "$work/dexdump.txt"

package=com.blackbox.stream.collectionpilot
manifest="$dir/AndroidManifest.xml"
extra=()
case "$abi" in
  arm64-v8a) ;;
  x86_64)
    package="$package.smoke"
    manifest="$work/AndroidManifest.xml"
    sed -e "s/package=\"com.blackbox.stream.collectionpilot\"/package=\"$package\"/" \
      -e 's/<application /<application android:debuggable="true" /' "$dir/AndroidManifest.xml" > "$manifest"
    curl -fsSL --retry 2 --max-time 120 --max-filesize 70500000 -o "$work/x86.apk" \
      https://github.com/yausername/youtubedl-android/releases/download/0.18.1/app-x86_64-debug.apk
    echo "c6c8ece57600fe9022b91c026ddb3489fef80496b5627586e3e0fc46e0dcd866  $work/x86.apk" | sha256sum -c
    extra+=(--native-x86 "$work/x86.apk")
    if [[ "$mode" == live ]]; then extra+=(--live); fi
    ;;
  *) echo 'Choose arm64-v8a (phone) or x86_64 (emulator smoke only)' >&2; exit 2 ;;
esac
"$tools/aapt2" link -I "$platform" -I "$original" --manifest "$manifest" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name '0.1-offline-pilot' -o "$work/manifest.apk"
python3 -B "$dir/package_apk.py" "$original" "$helper" "$work/dex/classes.dex" \
  "$work/manifest.apk" "$work/base.apk" "${extra[@]}"
"$tools/zipalign" -P 16 -f 4 "$work/base.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/pilot.p12" -storepass test-only -keypass test-only \
  -alias pilot -keyalg RSA -keysize 2048 -validity 365 -dname 'CN=BlackBox Music Offline Pilot' >/dev/null
"$tools/apksigner" sign --ks "$work/pilot.p12" --ks-pass pass:test-only --v1-signing-enabled false \
  --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "$output" "$work/aligned.apk"
"$tools/apksigner" verify --verbose "$output"
"$tools/zipalign" -P 16 -c 4 "$output"
"$tools/aapt2" dump badging "$output" > "$work/badging.txt"
grep -Fq "package: name='$package'" "$work/badging.txt"
grep -q "^application-label:'BlackBox Music'$" "$work/badging.txt"
grep -q "^launchable-activity: name='com.blackbox.stream.collectionpilot.CollectionActivity'" "$work/badging.txt"
if [[ "$abi" == arm64-v8a ]]; then
  grep -q "^native-code: 'arm64-v8a'$" "$work/badging.txt"
  if grep -q '^application-debuggable' "$work/badging.txt"; then echo 'Debuggable phone build' >&2; exit 1; fi
fi
sha256sum "$output"
