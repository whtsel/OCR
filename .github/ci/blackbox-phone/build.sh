#!/usr/bin/env bash
# Build a side-by-side, no-account diagnostic, not a production APK or upgrade.
set -euo pipefail
original=${1:?Expected archived p11 APK}
helper=${2:?Expected pinned yt-dlp zipapp}
output=${3:?Expected diagnostic APK output path}
abi=${4:-arm64-v8a}
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
curl -fsSL --max-time 45 --max-filesize 500000 -o "$work/commons-compress.jar" \
  https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.12/commons-compress-1.12.jar
mkdir "$work/dex"
"$tools/d8" --min-api 24 --lib "$platform" --output "$work/dex" "$work/commons-compress.jar"
"$tools/dexdump" "$work/dex/classes.dex" > "$work/dependency.txt"
grep -q "Class descriptor .*Lorg/apache/commons/compress/archivers/zip/ZipFile;" "$work/dependency.txt"

"$tools/aapt2" link -I "$platform" -I "$original" --manifest "$dir/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name '0.2-helper-search' -o "$work/manifest.apk"
extra=()
case "$abi" in
  arm64-v8a) ;;
  x86_64)
    curl -fsSL --max-time 120 --max-filesize 70500000 -o "$work/x86.apk" \
      https://github.com/yausername/youtubedl-android/releases/download/0.18.1/app-x86_64-debug.apk
    echo "c6c8ece57600fe9022b91c026ddb3489fef80496b5627586e3e0fc46e0dcd866  $work/x86.apk" | sha256sum -c
    extra+=(--native-x86 "$work/x86.apk")
    ;;
  *) echo 'Choose arm64-v8a (phone) or x86_64 (emulator smoke only)' >&2; exit 2 ;;
esac
python3 -B "$dir/package_apk.py" "$original" "$helper" "$work/dex/classes.dex" \
  "$work/manifest.apk" "$work/base.apk" "${extra[@]}"
"$tools/zipalign" -P 16 -f 4 "$work/base.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/diagnostic.p12" -storepass test-only -keypass test-only \
  -alias diagnostic -keyalg RSA -keysize 2048 -validity 365 -dname 'CN=BlackBox ARM64 Helper Search' >/dev/null
"$tools/apksigner" sign --ks "$work/diagnostic.p12" --ks-pass pass:test-only --v1-signing-enabled false \
  --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "$output" "$work/aligned.apk"
"$tools/apksigner" verify --verbose --print-certs "$output"
"$tools/zipalign" -P 16 -c 4 "$output"
"$tools/aapt2" dump badging "$output" > "$work/badging.txt"
cat "$work/badging.txt"
grep -q "^package: name='com.blackbox.stream.helpersearch'" "$work/badging.txt"
grep -q "^application-label:'BlackBox Helper Search'$" "$work/badging.txt"
grep -q "^launchable-activity: name='com.blackbox.stream.MainActivity'" "$work/badging.txt"
if [[ "$abi" == arm64-v8a ]]; then grep -q "^native-code: 'arm64-v8a'$" "$work/badging.txt"; fi
python3 - "$output" "$work/dex/classes.dex" "$helper" <<'PY'
from zipfile import ZipFile
from pathlib import Path
import sys
from check_blackbox_p11 import verify_refreshed_helper
apk, dex, _ = map(Path, sys.argv[1:])
with ZipFile(apk) as archive:
    verify_refreshed_helper(archive.read('res/raw/ytdlp'))
    assert archive.read('classes2.dex') == dex.read_bytes()
PY
sha256sum "$output"
