#!/usr/bin/env python3
"""Pin the phone-tested zipapp and native binaries even after Gradle packaging."""
import hashlib
from pathlib import Path
import sys
import zipfile

from check_blackbox_p11 import P19_BYTES, verify_refreshed_helper

ARM64 = {
    'libffmpeg.so': '1b8d5c371ea79e6e7a1930b31f2ad6a2731e48d75ceddc9c36dffda6e0092401',
    'libffmpeg.zip.so': '4664a895283f9f31786be39b0c484c1f96631f02c7c7f9c70a4a4a2da324a092',
    'libffprobe.so': 'a4d4a6d1562aa87934e99ee1a43f8090ec1040659cedb667d6edf3aa08408c5e',
    'libpython.so': '58ba29cfc5a6d88281c07b2b480fb1d8cce1b78f280b002347f792cfd9cf5901',
    'libpython.zip.so': '7edecb6c9bd3a94456168d44911d8aea9f917bedef90c320a29500d92ac3eb62',
    'libqjs.so': 'a0fe160a8cd76eec34132f945664856e96687d701e3b1ff28dff44ea1fad13bc',
}

offline = '--offline' in sys.argv[2:]
with zipfile.ZipFile(Path(sys.argv[1])) as apk:
    pin = 'res/raw/ytdlp'
    if offline and pin not in apk.namelist():
        # aapt2 may shorten the in-ZIP resource path in release APKs.
        resources = [member.filename for member in apk.infolist()
                     if member.filename.startswith('res/') and member.file_size == P19_BYTES]
        assert len(resources) == 1, f'Expected one release resource of the pinned helper size, got {resources}'
        pin = resources[0]
    verify_refreshed_helper(apk.read(pin))
    for member in apk.namelist():
        assert not member.startswith('assets/fixtures/') and not member.endswith(('/dawn.mp4', '/orbit.m4a')), f'Test-only media shipped: {member}'
    for line in Path('android/V90-ASSETS.sha256').read_text().splitlines():
        expected, asset = line.split(maxsplit=1)
        assert hashlib.sha256(apk.read('assets/v90/' + asset)).hexdigest() == expected, f'Original asset was changed: {asset}'

    for name, expected in ARM64.items():
        data = apk.read('lib/arm64-v8a/' + name)
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(f'Unexpected ARM64 runtime bytes in {name}')
    dexes = [apk.read(name) for name in apk.namelist() if name.startswith('classes') and name.endswith('.dex')]
    for name in (b'Lcom/blackboxstream/mobile/PreparedMusic;', b'Lcom/blackboxstream/mobile/BundledMusicRuntime;',
                 b'Lcom/blackboxstream/mobile/ProgressiveMusicAudio;',
                 b'Lorg/apache/commons/compress/archivers/zip/ZipFile;'):
        if not any(name in dex for dex in dexes):
            raise SystemExit(f'Missing class in package: {name.decode()}')
    if offline:
        for name in (b'OfflineMusicLibrary', b'OfflineMusicPlayer', b'WholeFileMusicRuntime'):
            assert any(b'Lcom/blackboxstream/mobile/' + name + b';' in dex for dex in dexes), name
        for name in ('offline-music.js', 'offline-music.css', 'music-panels.html', 'icon.svg', 'mix.svg', 'kwaku.svg', 'espresso.svg'):
            assert 'assets/v90-offline/' + name in apk.namelist(), name
    assert not any(b'Lcom/blackboxstream/mobile/MusicVideoActivity;' in dex for dex in dexes), 'Song video player shipped'
    assert apk.testzip() is None
print('::notice::Verified the v90 APK contains the exact phone-tested zipapp and all six matching ARM64 files.')
