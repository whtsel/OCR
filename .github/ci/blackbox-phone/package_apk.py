"""Assemble and verify the test shell without editing the archived p11 bytecode."""
import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

import check_blackbox_p11 as p11


ROOT = Path(__file__).resolve().parent
WEB = 'assets/web/'


def package(original, helper, dependency, manifest, output, native_x86, fixture):
    p11.verify_apk(original)
    helper = helper.read_bytes()
    p11.verify_refreshed_helper(helper)
    with ZipFile(manifest) as compiled:
        manifest = compiled.read('AndroidManifest.xml')
    replacements = {
        'AndroidManifest.xml': manifest,
        'res/raw/ytdlp': helper,
        WEB + 'index.html': (ROOT / 'index.html').read_bytes(),
    }
    additions = {
        'classes2.dex': dependency.read_bytes(),
        **{WEB + name: (ROOT / name).read_bytes() for name in ('helper-phone.js', 'helper-phone.css', 'stream.py')},
    }
    if fixture:
        if not native_x86:
            raise ValueError('Playback fixtures may not be packaged in a phone APK')
        additions[WEB + 'fixture-orbit.m4a'] = fixture.read_bytes()
    with ZipFile(original) as archive:
        entries = {i.filename: i for i in archive.infolist() if not i.filename.startswith('META-INF/')}
        assert replacements.keys() <= entries.keys()
        if native_x86:
            with ZipFile(native_x86) as sibling:
                for name in entries:
                    if name.startswith('lib/arm64-v8a/'):
                        x86 = name.replace('arm64-v8a', 'x86_64', 1)
                        additions[x86] = sibling.read(x86)
        assert additions.keys().isdisjoint(entries)
        with ZipFile(output, 'w', compression=ZIP_DEFLATED) as result:
            for name, info in entries.items():
                result.writestr(name, replacements[name] if name in replacements else archive.read(info),
                                compress_type=info.compress_type)
            for name, data in additions.items():
                result.writestr(name, data)
        with ZipFile(output) as result:
            assert len(result.namelist()) == len(entries) + len(additions)
            assert set(result.namelist()) == entries.keys() | additions.keys()
            for name, info in entries.items():
                expected = replacements[name] if name in replacements else archive.read(info)
                if result.read(name) != expected:
                    raise RuntimeError(f'unexpected APK bytes in {name}')
            for name, data in additions.items():
                if result.read(name) != data:
                    raise RuntimeError(f'bad test payload in {name}')
    print('Verified: original code, ARM64 runtime, security config, and resource IDs preserved; only test overlay added.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('original', 'helper', 'dependency', 'manifest', 'output'):
        parser.add_argument(arg, type=Path)
    parser.add_argument('--native-x86', type=Path, help='CI smoke-test variant only')
    args = parser.parse_args()
    package(**vars(args))


if __name__ == '__main__':
    main()
