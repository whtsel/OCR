#!/usr/bin/env python3
"""Independently hash the complete public files the Android UI must really download."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

import libtorrent as lt
from check_open_films import RAW


def references():
    manifest = lt.torrent_info(str(RAW / 'cinema_sintel.torrent'))
    file = next(f for f in manifest.files() if f.path.endswith('/Sintel.mp4'))
    movie = manifest.web_seeds()[0]['url'] + quote(file.path)
    return {
        'film:sintel': (movie, file.size, file.size),
        'anime:dull-sword': ('https://upload.wikimedia.org/wikipedia/commons/1/1d/Namakura_Gatana_with_music.webm', 44_458_914, 44_458_914),
    }


def check(item):
    key, (url, minimum, maximum) = item
    request = Request(url, headers={'Accept-Encoding': 'identity',
        'User-Agent': 'BlackBoxStream-Android/1.4 (https://github.com/whtsel/OCR) verification'})
    count = 0
    sha = hashlib.sha256()
    with urlopen(request, timeout=15) as response:
        if response.status != 200 or urlsplit(response.url).scheme != 'https':
            raise ValueError(f'{key}: no full HTTPS file response (HTTP {response.status})')
        for block in iter(lambda: response.read(512 * 1024), b''):
            if not count and (not block.startswith((b'ID3', b'\x1a\x45\xdf\xa3', b'\xff')) and block[4:8] != b'ftyp'):
                raise ValueError(f'{key}: not media bytes')
            count += len(block)
            if count > maximum:
                raise ValueError(f'{key}: oversized response')
            sha.update(block)
    if count < minimum:
        raise ValueError(f'{key}: incomplete file ({count:,} of at least {minimum:,} bytes)')
    return key, {'bytes': count, 'sha256': sha.hexdigest()}


def verify():
    with ThreadPoolExecutor(max_workers=2) as pool:
        return dict(pool.map(check, references().items()))


if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
