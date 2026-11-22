"""Keep only the verified p11 code and runtime, not its manifest, web UI or user data."""
import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from check_blackbox_p11 import digest, verify_apk, verify_refreshed_helper

LIBRARIES = {'libffmpeg.so', 'libffmpeg.zip.so', 'libffprobe.so',
             'libpython.so', 'libpython.zip.so', 'libqjs.so'}
X86_SHA = 'c6c8ece57600fe9022b91c026ddb3489fef80496b5627586e3e0fc46e0dcd866'


def members(archive, abi):
    prefix = f'lib/{abi}/'
    return {name for name in archive.namelist() if name.startswith(prefix) and name.endswith('.so')}


def assemble(original, pin, linked, overlay, output, x86):
    verify_apk(original)
    verify_refreshed_helper(pin.read_bytes())
    abi = 'x86_64' if x86 else 'arm64-v8a'
    if x86 and digest(x86.read_bytes()) != X86_SHA:
        raise ValueError('Unexpected x86 runtime archive')
    expect = {f'lib/{abi}/{name}' for name in LIBRARIES}
    with ZipFile(original) as p11, ZipFile(x86 or original) as runtime, ZipFile(linked) as resources:
        if not x86 and members(runtime, abi) != expect:
            raise ValueError('Unexpected archived ARM64 libraries')
        if not expect <= members(runtime, abi):
            raise ValueError('Missing required runtime files')
        if any(name.startswith(('lib/', 'assets/', 'classes')) for name in resources.namelist()):
            raise ValueError('Unexpected code or media in the resource APK')
        verify_refreshed_helper(resources.read('res/raw/ytdlp'))
        companion = resources.read('res/raw/catalogue_music.py')
        if not 1 <= len(companion) <= 24 * 1024:
            raise ValueError('The supplied v90 matcher does not fit the app loader')
        with ZipFile(output, 'w', compression=ZIP_DEFLATED) as apk:
            for info in resources.infolist():
                apk.writestr(info.filename, resources.read(info), compress_type=info.compress_type)
            apk.writestr('classes.dex', p11.read('classes.dex'))
            apk.writestr('classes2.dex', overlay.read_bytes())
            for name in sorted(expect):
                apk.writestr(name, runtime.read(name), compress_type=runtime.getinfo(name).compress_type)
    verify(original, pin, overlay, output, x86)


def verify(original, pin, overlay, output, x86):
    abi = 'x86_64' if x86 else 'arm64-v8a'
    expect = {f'lib/{abi}/{name}' for name in LIBRARIES}
    with ZipFile(original) as p11, ZipFile(x86 or original) as runtime, ZipFile(output) as apk:
        names = apk.namelist()
        if apk.testzip() is not None or len(set(names)) != len(names):
            raise ValueError('Broken or duplicate contents in diagnostic APK')
        if {n for n in names if n.startswith('lib/')} != expect or any(n.startswith('assets/') for n in names):
            raise ValueError('Incorrect native files or unexpected assets in diagnostic')
        if apk.read('classes.dex') != p11.read('classes.dex') or apk.read('classes2.dex') != overlay.read_bytes():
            raise ValueError('Diagnostic DEX did not match the archived/compiled inputs')
        if apk.read('res/raw/ytdlp') != pin.read_bytes():
            raise ValueError('Helper pin changed during packaging')
        if any(apk.read(name) != runtime.read(name) for name in expect):
            raise ValueError(f'Archived {abi} library bytes changed')
        if not x86 and b'save-check-fixtures/pilot.m4a' in apk.read('classes2.dex'):
            raise ValueError('Test-only pilot staging code was included in the ARM64 package')
    print(f'Verified {abi}: unchanged p11 DEX, six pinned libraries, and shared 2026.10.26 helper.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('original', 'pin', 'linked', 'overlay', 'output'):
        parser.add_argument(arg, type=Path)
    parser.add_argument('--x86', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    if args.verify_only:
        verify(args.original, args.pin, args.overlay, args.output, args.x86)
    else:
        assemble(args.original, args.pin, args.linked, args.overlay, args.output, args.x86)
