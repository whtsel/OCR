#!/usr/bin/env python3
"""Verify first and last film pieces against the exact published WebTorrent HTTPS seeds."""
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha1
from pathlib import Path
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

import libtorrent as lt

RAW = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
HASHES = {
    'sintel': '08ada5a7a6183aae1e09d831df6748d566095a10',
    'big_buck_bunny': 'dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c',
    'tears_of_steel': '209c8226b299b308beaf2b9cd3fb49212dbd13ec',
    'cosmos_laundromat': 'c9e15763f722f23e98a29decdfae341b98d53056',
}


def check(slug):
    manifest = lt.torrent_info(str(RAW / f'cinema_{slug}.torrent'))
    assert str(manifest.info_hashes().v1) == HASHES[slug]
    assert len(manifest.web_seeds()) == 1
    base = manifest.web_seeds()[0]['url']
    assert base == 'https://webtorrent.io/torrents/'
    entries = list(manifest.files())
    video = next(f for f in entries if f.path.lower().endswith(('.mp4', '.webm')))
    one = manifest.piece_length()
    first, last = video.offset // one, (video.offset + video.size - 1) // one
    for piece in {first, last}:
        start, length = piece * one, manifest.piece_size(piece)
        actual = bytearray()
        for f in entries:
            lo = max(start, f.offset) - f.offset
            hi = min(start + length, f.offset + f.size) - f.offset
            if hi <= lo:
                continue
            url = base + quote(f.path)
            request = Request(url, headers={'Range': f'bytes={lo}-{hi-1}', 'Accept-Encoding': 'identity',
                                            'User-Agent': 'BlackBoxStream/1.2 open-film verification'})
            with urlopen(request, timeout=9) as response:
                assert urlsplit(response.url).scheme == 'https'
                assert (response.status == 206 and response.headers.get('Content-Range') == f'bytes {lo}-{hi-1}/{f.size}'
                        or response.status == 200 and lo == 0 and hi == f.size), f'{url} does not serve the exact byte range'
                portion = response.read(hi - lo + 1)
                assert len(portion) == hi - lo, f'Truncated or oversized range in {f.path}'
                actual.extend(portion)
        assert len(actual) == length and sha1(actual).digest() == manifest.hash_for_piece(piece), f'Piece {piece} did not match'
    return f'{manifest.name()}: first and last video pieces verified against the public HTTPS seed ({video.size:,} media bytes)'


def main():
    failed = False
    with ThreadPoolExecutor(max_workers=4) as pool:
        jobs = {title: pool.submit(check, title) for title in HASHES}
        for title, job in jobs.items():
            try:
                print('::notice::' + job.result(), flush=True)
            except Exception as error:
                print(f'::warning::{title}: {type(error).__name__}: {error}', flush=True)
                failed = True
    return int(failed)


if __name__ == '__main__':
    raise SystemExit(main())
