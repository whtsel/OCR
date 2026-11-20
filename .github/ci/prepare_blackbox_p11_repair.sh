#!/usr/bin/env bash
# Test-only copy: restore the omitted ZIP dependency without changing app code or media assets.
set -euo pipefail
original=$1
output=$2
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is required}}
latest="$sdk/build-tools/35.0.0"
jar="$work/commons-compress-1.12.jar"
# Match the version declared by youtubedl-android 0.18.1. This legacy JAR is used
# only to open the known embedded archives, not as a production dependency.
curl -fsSL --max-time 30 -o "$jar" \
  https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.12/commons-compress-1.12.jar
mkdir "$work/dex"
"$latest/d8" --min-api 24 --lib "$sdk/platforms/android-35/android.jar" --output "$work/dex" "$jar"
python3 - "$original" "$work/dex/classes.dex" "$work/base.apk" <<'PY'
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

original, dependency, target = sys.argv[1:]
with ZipFile(original) as archive, ZipFile(target, 'w') as output:
    for info in archive.infolist():
        if info.filename.startswith('META-INF/'):
            continue
        if info.filename == 'classes2.dex':
            raise ValueError('original APK already uses classes2.dex')
        entry = ZipInfo(info.filename, info.date_time)
        entry.compress_type = info.compress_type
        entry.external_attr = info.external_attr
        output.writestr(entry, archive.read(info))
    output.write(dependency, 'classes2.dex', compress_type=ZIP_DEFLATED)
PY
"$latest/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/probe.p12" -storepass test-only -keypass test-only \
  -alias probe -keyalg RSA -keysize 2048 -validity 3 -dname 'CN=BlackBox CI diagnostic' >/dev/null
"$latest/apksigner" sign --ks "$work/probe.p12" --ks-pass pass:test-only --out "$output" "$work/aligned.apk"
"$latest/apksigner" verify "$output"
