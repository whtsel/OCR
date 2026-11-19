#!/usr/bin/env python3
"""Add classes.dex to AAPT2's APK and align stored resources; sign only after this step."""
from pathlib import Path
import shutil
import struct
import sys
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo


def check(apk):
    with ZipFile(apk) as z, open(apk, 'rb') as f:
        assert z.testzip() is None, 'APK has a corrupt ZIP entry'
        for name in ('classes.dex', 'AndroidManifest.xml', 'resources.arsc', 'res/raw/dawn.mp4', 'res/raw/orbit.m4a'):
            assert name in z.namelist(), f'Missing {name}'
        for entry in z.infolist():
            if entry.filename in ('resources.arsc', 'res/raw/dawn.mp4', 'res/raw/orbit.m4a'):
                assert entry.compress_type == ZIP_STORED, f'{entry.filename} must be uncompressed'
            if entry.compress_type == ZIP_STORED:
                f.seek(entry.header_offset + 26)
                name_size, extra_size = struct.unpack('<HH', f.read(4))
                assert (entry.header_offset + 30 + name_size + extra_size) % 4 == 0, f'{entry.filename} is misaligned'
    print('APK contents and 4-byte resource alignment verified')


def pack(resources, dex, output):
    with ZipFile(resources) as source, ZipFile(output, 'w') as target:
        for item in source.infolist():
            entry = ZipInfo(item.filename, item.date_time)
            entry.compress_type = item.compress_type
            entry.external_attr = item.external_attr
            if item.compress_type == ZIP_STORED:
                offset = target.fp.tell() + 30 + len(item.filename.encode('utf-8'))
                pad = (-offset) % 4
                if pad:
                    entry.extra = struct.pack('<HH', 0xD935, pad) + bytes(pad)
            with source.open(item) as src, target.open(entry, 'w') as dst:
                shutil.copyfileobj(src, dst)
        target.write(Path(dex), 'classes.dex', compress_type=ZIP_DEFLATED)
    check(output)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--check':
        check(sys.argv[2])
    elif len(sys.argv) == 4:
        pack(*sys.argv[1:])
    else:
        raise SystemExit('Usage: pack_apk.py RESOURCES_APK CLASSES_DEX OUTPUT_APK | --check APK')
