#!/usr/bin/env python3
"""Seed only the bundled original BlackBox clip on a user-selected local interface (libtorrent fixture)."""
from argparse import ArgumentParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ipaddress import ip_address, ip_network
from pathlib import Path
from urllib.parse import quote, unquote_to_bytes, urlsplit
import shutil
import socket
import struct
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
        peer = self.server.peer if unquote_to_bytes(query.get('peer_id','')).startswith(b'-BB0200-') else b''
        data = lt.bencode({b'interval': 300, b'peers': peer})
        self.send_response(200)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--bind', required=True, help='This computer’s private Wi-Fi IPv4 address (or 127.0.0.1 for local tests).')
    parser.add_argument('--directory', type=Path, default=Path('blackbox-demo'))
    args = parser.parse_args()
    ip = ip_address(args.bind)
    if not any(ip in network for network in LOCAL):
        parser.error('Use an explicitly chosen private IPv4 interface, not a public / wildcard address.')
    args.directory.mkdir(parents=True, exist_ok=True)
    video = args.directory / 'BlackBox Signal at Dawn.mp4'
    descriptor = args.directory / 'BlackBox Signal at Dawn.torrent'
    if video.exists() or descriptor.exists():
        parser.error('Choose a fresh --directory so the fixture cannot overwrite anything.')
    shutil.copyfile(SOURCE, video)
    with ThreadingHTTPServer((args.bind, 0), Tracker) as tracker:
        link = f'http://{args.bind}:{tracker.server_port}/announce'
        manifest = lt.file_storage(); manifest.add_file(video.name, video.stat().st_size)
        torrent = lt.create_torrent(manifest, 32768, lt.create_torrent.v1_only)
        torrent.add_tracker(link)
        lt.set_piece_hashes(torrent, str(args.directory.resolve()))
        descriptor.write_bytes(lt.bencode(torrent.generate()))
        info = lt.torrent_info(str(descriptor))
        session = lt.session({'listen_interfaces': f'{args.bind}:0', 'outgoing_interfaces': args.bind,
                              'enable_dht': False, 'enable_lsd': False, 'enable_natpmp': False, 'enable_upnp': False,
                              'allow_multiple_connections_per_ip': True})
        params = lt.add_torrent_params(); params.ti = info; params.save_path = str(args.directory.resolve())
        params.flags |= lt.torrent_flags.seed_mode
        params.flags &= ~(lt.torrent_flags.paused | lt.torrent_flags.auto_managed)
        handle = session.add_torrent(params)
        for _ in range(60):
            if session.listen_port() and handle.status().is_seeding:
                break
            time.sleep(.1)
        if not session.listen_port() or not handle.status().is_seeding:
            raise SystemExit('Could not start the local demo seeder.')
        tracker.peer = socket.inet_aton(args.bind) + struct.pack('!H', session.listen_port())
        tracker.digest = info.info_hashes().v1.to_bytes()
        print(f'Open this .torrent file in BlackBox: {descriptor.resolve()}\n'
              f'Or paste this magnet: magnet:?xt=urn:btih:{info.info_hashes().v1}&tr={quote(link, safe="")}\n'
              f'Allow local traffic only on TCP ports {tracker.server_port} and {session.listen_port()}.\n'
              'Keep this process open until the copy completes. Ctrl+C stops both the tracker and the seed.', flush=True)
        try:
            tracker.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            session.remove_torrent(handle)


if __name__ == '__main__':
    main()
