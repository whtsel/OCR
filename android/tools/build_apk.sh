#!/usr/bin/env bash
# Build with the Android SDK directly when Gradle is unavailable. No third-party app dependencies.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
main="$root/app/src/main"
work="${BUILD_WORK_DIR:-$root/build/standalone}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
tools="${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
aapt2="${AAPT2:-$tools/aapt2}"
android="${ANDROID_JAR:-$sdk/platforms/android-35/android.jar}"
java="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"
keytool="${KEYTOOL:-${JAVA_HOME:+$JAVA_HOME/bin/}keytool}"
key="${TEST_KEYSTORE:-$HOME/.android/blackbox-test.jks}"

if [[ ! -x "$aapt2" || ! -f "$android" ]]; then
    echo 'Install Android 35 + Build Tools 35.0.0 or set AAPT2 and ANDROID_JAR.' >&2
    exit 2
fi
mkdir -p "$work" "$(dirname "$key")"
work=$(mktemp -d "$work/apk.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work"/{r,java,dex}
readarray -t specs < <(python3 - "$root/app/build.gradle" <<'PY'
import re, sys
from pathlib import Path
s = Path(sys.argv[1]).read_text()
for attr in ['applicationId','minSdk','targetSdk','versionCode','versionName']:
    print(re.search(r'(?m)^\s*' + attr + r"\s+'?([^'\s]+)", s).group(1))
PY
)
pkg=${specs[0]}; min=${specs[1]}; target=${specs[2]}; code=${specs[3]}; version=${specs[4]}
apk="${1:-$root/build/BlackBox-Stream-Android-$version.apk}"
mkdir -p "$(dirname "$apk")"
sed 's|<manifest xmlns|<manifest package="'"$pkg"'" xmlns|; s/\${applicationId}/'"$pkg"'/g' "$main/AndroidManifest.xml" > "$work/AndroidManifest.xml"
"$aapt2" compile --dir "$main/res" -o "$work/compiled.zip"
"$aapt2" link -I "$android" --manifest "$work/AndroidManifest.xml" --java "$work/r" -o "$work/resources.apk" \
    --min-sdk-version "$min" --target-sdk-version "$target" --version-code "$code" --version-name "$version" \
    -0 mp4 -0 m4a --no-compile-sdk-metadata "$work/compiled.zip"
readarray -d '' -t sources < <(find "$main/java" "$work/r" -name '*.java' -print0)
if [[ -n "${ECJ_JAR:-}" ]]; then
    "$java" -jar "$ECJ_JAR" -1.8 -proc:none -encoding UTF-8 -nowarn \
        -bootclasspath "$android${LAMBDA_STUBS:+:$LAMBDA_STUBS}" -d "$work/java" "${sources[@]}"
else
    "${JAVAC:-${JAVA_HOME:+$JAVA_HOME/bin/}javac}" -source 8 -target 8 -encoding UTF-8 \
        -bootclasspath "$android:$tools/core-lambda-stubs.jar" -d "$work/java" "${sources[@]}"
fi
readarray -d '' -t classes < <(find "$work/java" -name '*.class' -print0)
if [[ -n "${D8_JAR:-}" ]]; then
    # Some mirrored public stub jars contain stripped MethodParameters; D8 can desugar our own Java 8 code without them.
    "$java" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api "$min" --release --output "$work/dex" "${classes[@]}"
else
    "$tools/d8" --lib "$android" --min-api "$min" --release --output "$work/dex" "${classes[@]}"
fi
python3 "$root/tools/pack_apk.py" "$work/resources.apk" "$work/dex/classes.dex" "$work/unsigned.apk"
if [[ ! -f "$key" ]]; then
    "$keytool" -genkeypair -noprompt -keystore "$key" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=BlackBox Test,OU=Android,O=BlackBox'
fi
if [[ -n "${APKSIGNER_JAR:-}" ]]; then
    sign=("$java" -jar "$APKSIGNER_JAR")
else
    sign=("$tools/apksigner")
fi
"${sign[@]}" sign --ks "$key" --ks-pass pass:android --ks-key-alias androiddebugkey \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
    --out "$apk" "$work/unsigned.apk"
"${sign[@]}" verify --verbose --print-certs "$apk"
python3 "$root/tools/pack_apk.py" --check "$apk"
printf '\nInstallable APK: %s\n' "$apk"
