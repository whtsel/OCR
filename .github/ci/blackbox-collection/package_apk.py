"""Assemble the isolated pilot while proving that the p11 code/runtime were not patched."""
import argparse
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

import check_blackbox_p11 as p11

ROOT = Path(__file__).resolve().parent
ASSETS = ('index.html', 'music.js', 'music.css', 'kwaku.svg', 'espresso.svg', 'mix.svg', 'icon.svg')


def package(original, helper, overlay, manifest, output, native_x86=None, live=False):
    if live and not native_x86:
        raise ValueError('Live comparison mode is only for the disposable x86 pilot')
    p11.verify_apk(original)
    helper_bytes = helper.read_bytes()
    p11.verify_refreshed_helper(helper_bytes)
    with ZipFile(manifest) as built:
        manifest_bytes = built.read('AndroidManifest.xml')
    profile = json.loads((ROOT / 'catalogue.json').read_text(encoding='utf-8'))
    tracks = profile['tracks']
    if len(tracks) != 2 or {tuple(row[k] for k in ('title', 'id', 'seconds')) for row in tracks} != set(p11.SONGS):
        raise ValueError('Pilot catalogue changed the two tested recordings')
    if native_x86:
        profile['fixtures'] = not live
    elif profile.get('fixtures'):
        raise ValueError('Fixtures are not allowed in the ARM64 phone package')
    replacements = {'AndroidManifest.xml': manifest_bytes, 'res/raw/ytdlp': helper_bytes}
    additions = {
        'classes2.dex': overlay.read_bytes(),
        'assets/collection/catalogue.json': json.dumps(profile, ensure_ascii=False).encode('utf-8'),
        **{'assets/collection/' + name: (ROOT / 'ui' / name).read_bytes() for name in ASSETS},
    }
    with ZipFile(original) as source:
        entries = {row.filename: row for row in source.infolist() if not row.filename.startswith('META-INF/')}
        if native_x86:
            with ZipFile(native_x86) as companion:
                for name in entries:
                    if name.startswith('lib/arm64-v8a/'):
                        native = name.replace('arm64-v8a', 'x86_64', 1)
                        additions[native] = companion.read(native)
        if not replacements.keys() <= entries.keys() or additions.keys() & entries.keys():
            raise ValueError('Unexpected p11 APK layout')
        with ZipFile(output, 'w', compression=ZIP_DEFLATED) as apk:
            for name, row in entries.items():
                apk.writestr(name, replacements[name] if name in replacements else source.read(row),
                             compress_type=row.compress_type)
            for name, data in additions.items():
                apk.writestr(name, data)
        with ZipFile(output) as apk:
            if len(apk.namelist()) != len(entries) + len(additions) or set(apk.namelist()) != entries.keys() | additions.keys():
                raise ValueError('Unexpected output APK layout')
            for name in entries:
                if apk.read(name) != (replacements[name] if name in replacements else source.read(name)):
                    raise ValueError(f'Archive member changed: {name}')
            for name, data in additions.items():
                if apk.read(name) != data:
                    raise ValueError(f'Archive member missing or changed: {name}')
    print('Verified p11 DEX, resources, ARM64 runtime and exact pinned helper overlay.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('original', 'helper', 'overlay', 'manifest', 'output'):
        parser.add_argument(arg, type=Path)
    parser.add_argument('--native-x86', type=Path)
    parser.add_argument('--live', action='store_true')
    package(**vars(parser.parse_args()))
