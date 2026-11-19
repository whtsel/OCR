#!/usr/bin/env python3
"""BlackBox Community v1, an invite-only room relay. Stores opaque encrypted envelopes, not room keys."""
from argparse import ArgumentParser
from collections import OrderedDict
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import BoundedSemaphore, Lock
from urllib.parse import urlsplit
import re
import sqlite3
import ssl
import time

ROUTE = re.compile(r'/v1/rooms/([0-9a-f]{48})/events\Z')
CURSOR = re.compile(r'after=([0-9]{1,18})\Z')
MAX_BYTES = 4096
KEEP_SECONDS = 7 * 86400
PER_ROOM = 100
TOTAL = 12000


class Relay(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, db):
        self.db = str(db)
        self.lock = Lock()
        self.limits = OrderedDict()
        self.slots = BoundedSemaphore(32)
        self.tls = None
        with closing(sqlite3.connect(self.db)) as conn, conn:
            conn.execute('PRAGMA journal_mode=WAL')
            conn.execute('CREATE TABLE IF NOT EXISTS event(id INTEGER PRIMARY KEY AUTOINCREMENT, room TEXT NOT NULL, '
                         'data BLOB NOT NULL, created INTEGER NOT NULL)')
            conn.execute('CREATE INDEX IF NOT EXISTS by_room ON event(room,id)')
            conn.execute('CREATE INDEX IF NOT EXISTS by_age ON event(created)')
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            request.settimeout(10)
            if self.tls:
                try:
                    request = self.tls.wrap_socket(request, server_side=True)
                except (OSError, ssl.SSLError):
                    self.shutdown_request(request)
                    return
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def allow(self, ip, post):
        now = int(time.monotonic()) // 60
        with self.lock:
            key = (ip, post)
            minute, used = self.limits.get(key, (now, 0))
            used = 0 if minute != now else used
            if used >= (30 if post else 180):
                return False
            self.limits[key] = (now, used + 1)
            self.limits.move_to_end(key)
            if len(self.limits) > 2048:
                self.limits.popitem(last=False)
            return True


class Handler(BaseHTTPRequestHandler):
    server_version = 'BlackBoxRelay/1'
    protocol_version = 'HTTP/1.1'

    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def log_message(self, *args):
        pass  # Avoid logging unguessable room IDs or invitations.

    def respond(self, code, data=b''):
        self.close_connection = True
        self.send_response(code)
        self.send_header('Content-Type', 'text/plain; charset=us-ascii')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        if data:
            self.wfile.write(data)

    def locate(self, write):
        if len(self.path) > 170:
            self.respond(400)
            return None
        url = urlsplit(self.path)
        match = ROUTE.fullmatch(url.path)
        cursor = CURSOR.fullmatch(url.query)
        if not match or url.netloc or url.scheme or url.fragment or write and url.query or not write and not cursor:
            self.respond(404)
            return None
        if not self.server.allow(self.client_address[0], write):
            self.respond(429)
            return None
        return match[1], 0 if write else int(cursor[1])

    def do_GET(self):
        target = self.locate(False)
        if not target:
            return
        room, after = target
        try:
            with closing(sqlite3.connect(self.server.db, timeout=3)) as db:
                # Latest sixty, in chronological order, to bound every response.
                rows = db.execute('SELECT id,data FROM event WHERE room=? AND id>? AND created>=? ORDER BY id DESC LIMIT 60',
                                  (room, after, int(time.time()) - KEEP_SECONDS)).fetchall()
            payload = ''.join(f'{seq}\t{body.hex()}\n' for seq, body in reversed(rows)).encode('ascii')
            self.respond(200, payload)
        except sqlite3.Error:
            self.respond(503)

    def do_POST(self):
        target = self.locate(True)
        if not target:
            return
        room, _ = target
        content_length = self.headers.get('Content-Length', '')
        if self.headers.get('Transfer-Encoding') or not content_length.isdecimal() or len(content_length) > 5:
            self.respond(400)
            return
        length = int(content_length)
        if not 29 <= length <= MAX_BYTES or self.headers.get_content_type() != 'application/octet-stream':
            self.respond(413)
            return
        envelope = self.rfile.read(length)
        if len(envelope) != length or envelope[0] != 1:
            self.respond(400)
            return
        now = int(time.time())
        try:
            with closing(sqlite3.connect(self.server.db, timeout=3)) as db, db:
                event = db.execute('INSERT INTO event(room,data,created) VALUES(?,?,?)', (room, envelope, now)).lastrowid
                db.execute('DELETE FROM event WHERE created<? OR id<=?', (now - KEEP_SECONDS, event - TOTAL))
                db.execute('DELETE FROM event WHERE room=? AND id IN '
                           '(SELECT id FROM event WHERE room=? ORDER BY id DESC LIMIT -1 OFFSET ?)', (room, room, PER_ROOM))
            self.respond(201, f'{event}\n'.encode('ascii'))
        except sqlite3.Error:
            self.respond(503)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--bind', default='127.0.0.1', help='Listen behind an HTTPS reverse proxy by default')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--db', type=Path, default=Path('blackbox-community.sqlite3'))
    parser.add_argument('--tls-cert', type=Path)
    parser.add_argument('--tls-key', type=Path)
    args = parser.parse_args()
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error('Use both --tls-cert and --tls-key or neither behind HTTPS.')
    if not args.tls_cert and args.bind not in ('127.0.0.1', 'localhost'):
        parser.error('Bind cleartext relay to loopback only, or supply TLS.')
    with Relay((args.bind, args.port), args.db) as server:
        if args.tls_cert:
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.minimum_version = ssl.TLSVersion.TLSv1_2
            tls.load_cert_chain(args.tls_cert, args.tls_key)
            server.tls = tls
        print(f'BlackBox Community relay listening on {args.bind}:{server.server_address[1]}', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
