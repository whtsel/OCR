#!/usr/bin/env python3
"""Run the production Java peer transports against an independent libtorrent seed on loopback."""
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote_to_bytes, urlsplit
import os
import shutil
import socket
import socketserver
import struct
import subprocess
import tempfile
import threading
import time

import libtorrent as lt

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/blackboxstream/mobile'
RAW = ROOT / 'app/src/main/res/raw'


def deps(classes, names, test):
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('.')
    java = os.environ.get('JAVA', str(jdk / 'java'))
    sources = [str(SOURCE / (n + '.java')) for n in names] + [str(ROOT / 'tests' / (test + '.java'))]
    if os.environ.get('ECJ_JAR'):
        boot = os.environ['ANDROID_JAR']
        if os.environ.get('LAMBDA_STUBS'):
            boot += os.pathsep + os.environ['LAMBDA_STUBS']
        tool = [java, '-jar', os.environ['ECJ_JAR'], '-1.8', '-proc:none', '-nowarn', '-bootclasspath', boot]
    else:
        tool = [os.environ.get('JAVAC', str(jdk / 'javac')), '--release', '8']
    subprocess.run(tool + ['-encoding', 'UTF-8', '-d', str(classes), *sources], check=True)
    return java


class HttpTracker(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        params = dict(t.split('=', 1) for t in urlsplit(self.path).query.split('&') if '=' in t)
        if unquote_to_bytes(params.get('info_hash', '')) != self.server.torrent_hash:
            self.send_error(400)
            return
        other = b'peers' if unquote_to_bytes(params.get('peer_id', '')).startswith(b'-BB0200-') else b''
        response = lt.bencode({b'interval': 900, b'peers': self.server.compact_peer if other else b''})
        self.send_response(200)
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)


class UdpTracker(socketserver.BaseRequestHandler):
    def handle(self):
        data, udp = self.request
        if len(data) == 16 and data[:8] == struct.pack('!Q', 0x41727101980):
            reply = struct.pack('!i', 0) + data[12:16] + struct.pack('!Q', 71684)
        elif len(data) == 98 and data[16:36] == self.server.torrent_hash:
            reply = struct.pack('!i', 1) + data[12:16] + struct.pack('!iii', 900, 0, 1) + (self.server.compact_peer if data[36:44] == b'-BB0200-' else b'')
        else:
            return
        udp.sendto(reply, self.client_address)


class DhtSeed(socketserver.BaseRequestHandler):
    def handle(self):
        data, udp = self.request
        query = lt.bdecode(data)
        if query.get(b'q') != b'get_peers' or query[b'a'][b'info_hash'] != self.server.torrent_hash:
            return
        reply = {b't': query[b't'], b'y': b'r', b'r': {b'id': b'abcdefghijklmno12345', b'values': [self.server.compact_peer]}}
        udp.sendto(lt.bencode(reply), self.client_address)


def port(server):
    return server.server_address[1]


def main():
    with ExitStack() as cleanup:
        root = Path(cleanup.enter_context(tempfile.TemporaryDirectory(prefix='bbx-peer-test-')))
        classes = root / 'classes'
        classes.mkdir()
        java = deps(classes, ['MediaEntry', 'MediaSources', 'OfflineTransfer', 'MediaBytes', 'Bencode', 'TorrentMeta',
                              'TorrentNetwork', 'TorrentWebSeed', 'TorrentTransfer', 'LanIdentity', 'LanShare'], 'PeerChecks')
        http = cleanup.enter_context(ThreadingHTTPServer(('127.0.0.1', 0), HttpTracker))
        udp = cleanup.enter_context(socketserver.UDPServer(('127.0.0.1', 0), UdpTracker))
        dht = cleanup.enter_context(socketserver.UDPServer(('127.0.0.1', 0), DhtSeed))
        links = [f'http://127.0.0.1:{port(http)}/announce', f'udp://127.0.0.1:{port(udp)}/announce']
        folder = root / 'share'
        folder.mkdir()
        files = lt.file_storage()
        files.add_file('share/a-padding.txt', 7921)
        (folder / 'a-padding.txt').write_bytes(b'x' * 7921)
        shutil.copyfile(RAW / 'dawn.mp4', folder / 'dawn.mp4')
        files.add_file('share/dawn.mp4', (folder / 'dawn.mp4').stat().st_size)
        files.add_file('share/z-padding.txt', 5553)
        (folder / 'z-padding.txt').write_bytes(b'z' * 5553)
        generated = lt.create_torrent(files, 32768, lt.create_torrent.v1_only)
        for i, link in enumerate(links):
            generated.add_tracker(link, i)
        lt.set_piece_hashes(generated, str(root))
        meta = root / 'selection.torrent'
        meta.write_bytes(lt.bencode(generated.generate()))
        info = lt.torrent_info(str(meta))
        session = lt.session({'listen_interfaces': '127.0.0.1:0', 'outgoing_interfaces': '127.0.0.1',
                              'enable_dht': False, 'enable_lsd': False, 'enable_natpmp': False, 'enable_upnp': False,
                              'announce_to_all_tiers': False, 'allow_multiple_connections_per_ip': True, 'alert_mask': int(lt.alert.category_t.all_categories)})
        params = lt.add_torrent_params()
        params.ti, params.save_path = info, str(root)
        params.flags |= lt.torrent_flags.seed_mode
        params.flags &= ~(lt.torrent_flags.paused | lt.torrent_flags.auto_managed)
        handle = session.add_torrent(params)
        for _ in range(70):
            if session.listen_port() and handle.status().is_seeding:
                break
            time.sleep(.1)
        assert session.listen_port() and handle.status().is_seeding, 'libtorrent could not seed the controlled files'
        for server in (http, udp, dht):
            server.compact_peer = socket.inet_aton('127.0.0.1') + struct.pack('!H', session.listen_port())
            server.torrent_hash = info.info_hashes().v1.to_bytes()
            threading.Thread(target=server.serve_forever, daemon=True).start()
            cleanup.callback(server.shutdown)
        try:
            subprocess.run([java, '-cp', str(classes), 'com.blackboxstream.mobile.PeerChecks', str(root), str(RAW),
                        str(info.info_hashes().v1), *links, f'127.0.0.1:{port(dht)}'], check=True, timeout=75)
        except subprocess.CalledProcessError:
            for alert in session.pop_alerts():
                if 'peer' in type(alert).__name__ or 'announce' in type(alert).__name__:
                    print(type(alert).__name__, alert.message(), flush=True)
            raise
        finally:
            session.remove_torrent(handle)


if __name__ == '__main__':
    main()
