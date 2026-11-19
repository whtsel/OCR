#!/usr/bin/env python3
"""Seed only the bundled original BlackBox clip on a user-selected local interface (libtorrent fixture)."""
from argparse import ArgumentParser
from contextlib import contextmanager
from typing import NamedTuple
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ipaddress import ip_address, ip_network
from pathlib import Path
from urllib.parse import quote, unquote_to_bytes, urlsplit
import shutil
import socket
import struct
import threading
import time

import libtorrent as lt

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/dawn.mp4'
LOCAL = tuple(ip_network(block) for block in ('127.0.0.0/8', '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))


class Tracker(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        url = urlsplit(self.path)
        query = dict(pair.split('=', 1) for pair in url.query.split('&') if '=' in pair)
        if url.path != '/announce' or unquote_to_bytes(query.get('info_hash', '')) != self.server.digest:
            self.send_error(404)
            return
        peer = self.server.peer if unquote_to_bytes(query.get('peer_id', '')).startswith(b'-BB0200-') else b''
        data = lt.bencode({b'interval': 300, b'peers': peer})
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class Seed(NamedTuple):
    descriptor: Path
    magnet: str
    tracker_port: int
    peer_port: int


@contextmanager
def seed(bind, directory, rate=0):
    if not any(ip_address(bind) in network for network in LOCAL):
        raise ValueError('Use an explicitly chosen private IPv4 interface, not a public / wildcard address.')
    directory.mkdir(parents=True, exist_ok=True)
    video = directory / 'BlackBox Signal at Dawn.mp4'
    descriptor = directory / 'BlackBox Signal at Dawn.torrent'
    if video.exists() or descriptor.exists():
        raise ValueError('Choose a fresh --directory so the fixture cannot overwrite anything.')
    shutil.copyfile(SOURCE, video)
    with ThreadingHTTPServer((bind, 0), Tracker) as tracker:
        link = f'http://{bind}:{tracker.server_port}/announce'
        manifest = lt.file_storage(); manifest.add_file(video.name, video.stat().st_size)
        torrent = lt.create_torrent(manifest, 32768, lt.create_torrent.v1_only)
        torrent.add_tracker(link)
        lt.set_piece_hashes(torrent, str(directory.resolve()))
        descriptor.write_bytes(lt.bencode(torrent.generate()))
        info = lt.torrent_info(str(descriptor))
        session = lt.session({'listen_interfaces': f'{bind}:0', 'outgoing_interfaces': bind,
                              'enable_dht': False, 'enable_lsd': False, 'enable_natpmp': False, 'enable_upnp': False,
                              'allow_multiple_connections_per_ip': True,
                              'ignore_limits_on_local_network': False, 'upload_rate_limit': rate,
                              'local_upload_rate_limit': rate})
        params = lt.add_torrent_params(); params.ti = info; params.save_path = str(directory.resolve())
        params.flags |= lt.torrent_flags.seed_mode
        params.flags &= ~(lt.torrent_flags.paused | lt.torrent_flags.auto_managed)
        handle = session.add_torrent(params)
        for _ in range(60):
            if session.listen_port() and handle.status().is_seeding:
                break
            time.sleep(.1)
        if not session.listen_port() or not handle.status().is_seeding:
            raise SystemExit('Could not start the local demo seeder.')
        tracker.peer = socket.inet_aton(bind) + struct.pack('!H', session.listen_port())
        tracker.digest = info.info_hashes().v1.to_bytes()
        magnet = f'magnet:?xt=urn:btih:{info.info_hashes().v1}&tr={quote(link, safe="")}'
        threading.Thread(target=tracker.serve_forever, daemon=True).start()
        try:
            yield Seed(descriptor, magnet, tracker.server_port, session.listen_port())
        finally:
            tracker.shutdown()
            session.remove_torrent(handle)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--bind', required=True, help='This computer’s private Wi-Fi IPv4 address (or 127.0.0.1 for local tests).')
    parser.add_argument('--directory', type=Path, default=Path('blackbox-demo'))
    args = parser.parse_args()
    try:
        with seed(args.bind, args.directory) as live:
            print(f'Open this .torrent file in BlackBox: {live.descriptor.resolve()}\n'
                  f'Or paste this magnet: {live.magnet}\n'
                  f'Allow local traffic only on TCP ports {live.tracker_port} and {live.peer_port}.\n'
                  'Keep this process open until the copy completes. Ctrl+C stops both the tracker and the seed.', flush=True)
            threading.Event().wait()
    except ValueError as invalid:
        parser.error(str(invalid))
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
