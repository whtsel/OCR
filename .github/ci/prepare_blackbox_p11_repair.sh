#!/usr/bin/env bash
# Test-only copy: restore the omitted ZIP dependency, with an optional pinned helper refresh.
set -euo pipefail
original=$1
output=$2
variant=${3:-zip-dependency}
refresh=${4:-}
if [[ -n "$refresh" && "$variant" != native-x86 ]]; then
  echo "Refreshed helper requires the native x86_64 diagnostic" >&2
  exit 2
fi
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
runtime="$work/upstream.apk"
if [[ "$variant" == native-x86 ]]; then
  curl -fsSL --max-time 120 --max-filesize 70500000 -o "$runtime" \
    https://github.com/yausername/youtubedl-android/releases/download/0.18.1/app-x86_64-debug.apk
  echo "c6c8ece57600fe9022b91c026ddb3489fef80496b5627586e3e0fc46e0dcd866  $runtime" | sha256sum -c
fi
python3 - "$original" "$work/dex/classes.dex" "$work/base.apk" "$runtime" "$refresh" "$(dirname "$0")" <<'PY'
from pathlib import Path
import importlib.util
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

original, dependency, target, runtime, refresh, script_dir = sys.argv[1:]
replacement = None
if refresh:
    spec = importlib.util.spec_from_file_location('probe', Path(script_dir) / 'check_blackbox_p11.py')
    probe = importlib.util.module_from_spec(spec)
    sys.dont_write_bytecode = True
    spec.loader.exec_module(probe)
    replacement = Path(refresh).read_bytes()
    probe.verify_refreshed_helper(replacement)
with ZipFile(original) as archive, ZipFile(target, 'w') as output:
    for info in archive.infolist():
        if info.filename.startswith('META-INF/'):
            continue
        if info.filename == 'classes2.dex':
            raise ValueError('original APK already uses classes2.dex')
        entry = ZipInfo(info.filename, info.date_time)
        entry.compress_type = info.compress_type
        entry.external_attr = info.external_attr
        data = replacement if info.filename == 'res/raw/ytdlp' and replacement is not None else archive.read(info)
        output.writestr(entry, data)
    output.write(dependency, 'classes2.dex', compress_type=ZIP_DEFLATED)
    if Path(runtime).exists():
        # Same source release; add only the x86_64 siblings of p11’s native helper
        # binaries. The separate official sample app's code is not incorporated.
        with ZipFile(runtime) as upstream:
            for name in sorted(archive.namelist()):
                if name.startswith('lib/arm64-v8a/'):
                    x86_name = name.replace('arm64-v8a', 'x86_64', 1)
                    output.writestr(x86_name, upstream.read(x86_name), compress_type=ZIP_DEFLATED)
PY
"$latest/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/probe.p12" -storepass test-only -keypass test-only \
  -alias probe -keyalg RSA -keysize 2048 -validity 3 -dname 'CN=BlackBox CI diagnostic' >/dev/null
"$latest/apksigner" sign --ks "$work/probe.p12" --ks-pass pass:test-only --out "$output" "$work/aligned.apk"
"$latest/apksigner" verify "$output"
