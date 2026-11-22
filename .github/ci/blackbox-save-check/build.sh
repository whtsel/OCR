#!/usr/bin/env bash
# Build two diagnostic APKs: the separate ARM64 phone install and a fixture-only x86 sibling.
set -euo pipefail
original=${1:?Expected archived p11 APK}
pin=${2:?Expected pinned yt-dlp zipapp}
out=${3:?Expected APK output directory}
dir=$(cd "$(dirname "$0")" && pwd)
app=$(cd "$dir/../../../android/app" && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK required}}
tools="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"
export PYTHONPATH="$dir/..${PYTHONPATH:+:$PYTHONPATH}"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$out" "$work/res/raw"

python3 -B - "$original" "$pin" <<'PY'
from pathlib import Path
from sys import argv
from check_blackbox_p11 import verify_apk, verify_refreshed_helper
verify_apk(Path(argv[1]))
verify_refreshed_helper(Path(argv[2]).read_bytes())
PY
# Only used as compile classpath: at runtime the classes come from the unchanged p11 DEX.
maven=https://repo.maven.apache.org/maven2
for library in library ffmpeg; do
  url="$maven/io/github/junkfood02/youtubedl-android/$library/0.18.1/$library-0.18.1.aar"
  curl -fsSL --retry 2 --max-time 95 --max-filesize 180000000 -o "$work/$library.aar" "$url"
  unzip -p "$work/$library.aar" classes.jar > "$work/$library.jar"
  rm "$work/$library.aar"
done
curl -fsSL --retry 2 --max-time 45 --max-filesize 500000 -o "$work/commons-compress.jar" \
  "$maven/org/apache/commons/commons-compress/1.12/commons-compress-1.12.jar"
curl -fsSL --retry 2 --max-time 120 --max-filesize 70500000 -o "$work/x86.apk" \
  https://github.com/yausername/youtubedl-android/releases/download/0.18.1/app-x86_64-debug.apk

cp -R "$app/src/main/res/." "$work/res/"
cp -R "$dir/res/." "$work/res/"
cp "$pin" "$work/res/raw/ytdlp"
cp "$app/src/musicHelper/res/raw/catalogue_music.py" "$work/res/raw/catalogue_music.py"
"$tools/aapt2" compile --dir "$work/res" -o "$work/resources.zip"
keytool -genkeypair -keystore "$work/check.p12" -storepass test-only -keypass test-only \
  -alias save-check -keyalg RSA -keysize 2048 -validity 365 -dname 'CN=BlackBox Music Save Check (test only)' >/dev/null
base=com.blackbox.stream.savecheck
version=0.1-save-check
for abi in arm64-v8a x86_64; do
  package="$base"
  fixtures=false
  extra=()
  manifest="$dir/AndroidManifest.xml"
  target="$out/BlackBox-Music-Save-Check-ARM64.apk"
  build="$work/$abi"
  mkdir -p "$build/gen" "$build/dex" "$build/classes"
  if [[ "$abi" == x86_64 ]]; then
    package="$base.smoke"
    fixtures=true
    extra+=(--x86 "$work/x86.apk")
    target="$out/save-check-smoke.apk"
    manifest="$build/AndroidManifest.xml"
    sed -e "s/package=\"$base\"/package=\"$package\"/" \
      -e 's/<application /<application android:debuggable="true" /' "$dir/AndroidManifest.xml" > "$manifest"
  fi
  # R.raw.ytdlp has this exact ID in the archived p11 wrapper. Link the diagnostic's
  # own resources at that ID instead of loading the archived manifest or activities.
  printf '%s:raw/ytdlp = 0x7f020000\n' "$package" > "$build/stable.txt"
  "$tools/aapt2" link -I "$platform" --manifest "$manifest" --stable-ids "$build/stable.txt" \
    --java "$build/gen" --custom-package com.blackboxstream.mobile \
    --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name "$version" \
    -o "$build/resources.apk" "$work/resources.zip"
  resources="$build/gen/com/blackboxstream/mobile/R.java"
  grep -Eq 'ytdlp[ ]*=[ ]*0x7f020000' "$resources"
  cat > "$build/SaveCheckBuild.java" <<JAVA
package com.blackboxstream.mobile;
final class SaveCheckBuild {
    static final boolean FIXTURES = $fixtures;
    static final String VERSION = "$version";
}
JAVA
  main="$app/src/main/java"
  helper="$app/src/musicHelper/java"
  api="$work/library.jar:$work/ffmpeg.jar"
  javac -encoding UTF-8 --release 8 -cp "$platform:$api" -sourcepath "$main:$helper" -d "$build/classes" \
    "$resources" "$build/SaveCheckBuild.java" "$dir/MusicSaveCheckActivity.java" "$dir/SaveCheck.java" \
    "$helper/com/blackboxstream/mobile/WholeFileMusicRuntime.java"
  jar cf "$build/check.jar" -C "$build/classes" .
  "$tools/d8" --min-api 24 --lib "$platform" --classpath "$work/library.jar" --classpath "$work/ffmpeg.jar" \
    --output "$build/dex" "$build/check.jar" "$work/commons-compress.jar"
  dex="$build/dex/classes.dex"
  test "$(find "$build/dex" -name 'classes*.dex' | wc -l)" -eq 1
  "$tools/dexdump" "$dex" > "$build/dexdump.txt"
  grep -q "Class descriptor .*Lorg/apache/commons/compress/archivers/zip/ZipFile;" "$build/dexdump.txt"
  grep -q "Class descriptor .*Lcom/blackboxstream/mobile/WholeFileMusicRuntime;" "$build/dexdump.txt"
  if grep -q 'Class descriptor .*Lcom/yausername/' "$build/dexdump.txt"; then
    echo 'Do not shadow the archived wrapper classes' >&2
    exit 1
  fi
  assemble=(python3 -B "$dir/assemble.py" "$original" "$pin" "$build/resources.apk" "$dex")
  "${assemble[@]}" "$build/base.apk" "${extra[@]}"
  "$tools/zipalign" -P 16 -f 4 "$build/base.apk" "$build/aligned.apk"
  "$tools/apksigner" sign --ks "$work/check.p12" --ks-pass pass:test-only --out "$target" "$build/aligned.apk"
  "$tools/apksigner" verify --verbose "$target"
  "$tools/zipalign" -P 16 -c 4 "$target"
  "$tools/aapt2" dump badging "$target" > "$build/badging.txt"
  grep -q "^package: name='$package' versionCode='1' versionName='$version'" "$build/badging.txt"
  grep -q "^application-label:'BlackBox Music Save Check'$" "$build/badging.txt"
  grep -q "^launchable-activity: name='com.blackboxstream.mobile.MusicSaveCheckActivity'" "$build/badging.txt"
  grep -q "^native-code: '$abi'$" "$build/badging.txt"
  if "$fixtures"; then
    grep -q '^application-debuggable' "$build/badging.txt"
  elif grep -q '^application-debuggable' "$build/badging.txt"; then
    echo 'Release diagnostic must not be debuggable' >&2
    exit 1
  fi
  "${assemble[@]}" "$target" "${extra[@]}" --verify-only
  sha256sum "$target"
done
