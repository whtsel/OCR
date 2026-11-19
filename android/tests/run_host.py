#!/usr/bin/env python3
"""Compile the production transport with javac (or ECJ) and test against a loopback HTTPS fixture."""
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import ssl
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/blackboxstream/mobile'
RAW = ROOT / 'app/src/main/res/raw'
FILES = {name: (RAW / name).read_bytes() for name in ('dawn.mp4', 'orbit.m4a')}


class Fixture(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    receives = {}

    def log_message(self, *args):
        pass

    def respond(self, status, data=b'', mime='video/mp4', etag=None, length=None, **fields):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', len(data) if length is None else length)
        self.send_header('Connection', 'close')
        if etag is not None:
            self.send_header('ETag', etag)
        for key, value in fields.items():
            self.send_header(key, str(value))
        self.end_headers()
        if data:
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLEOFError):
                # The client may reject headers (e.g. an unsolicited 206) before reading the body.
                pass

    def do_GET(self):
        path = self.path
        video, audio = FILES.values()
        etag = '"dawn-v1"'
        if path == '/redirect':
            self.respond(302, Location='/catalog/dawn.mp4')
        elif path == '/playlist-redirect':
            self.respond(302, Location='/catalog/list.m3u')
        elif path == '/catalog/list.m3u':
            self.respond(200, b'#EXTM3U\n#EXTINF:14,Signal at Dawn\ndawn.mp4\n#EXTINF:24,Low Orbit\n/orbit.m4a\nhttp://localhost/invalid.mp4\n', mime='audio/x-mpegurl')
        elif path == '/catalog/dawn.mp4':
            self.respond(200, video, etag=etag)
        elif path == '/orbit.m4a':
            self.respond(200, audio, mime='audio/mp4', etag='"orbit-v1"')
        elif path in ('/drop', '/changed', '/stale'):
            history = self.receives.setdefault(path, [])
            received = self.headers.get('Range')
            history.append(received)
            if len(history) == 1:
                assert received is None
                self.respond(200, video[:65536], etag=etag, length=len(video))
            elif len(history) == 2:
                assert received == 'bytes=65536-' and self.headers.get('If-Range') == etag, received
                if path == '/changed':
                    self.respond(200, audio, mime='audio/mp4', etag='"replacement-v2"')
                elif path == '/stale':
                    self.respond(416, **{'Content-Range': 'bytes */0'})
                else:
                    self.respond(206, video[65536:], etag=etag, **{'Content-Range': f'bytes 65536-{len(video)-1}/{len(video)}'})
            else:
                assert path == '/stale' and received is None
                self.respond(200, video, etag=etag)
        elif path == '/html':
            self.respond(200, b'<html>not media</html>', mime='text/html')
        elif path == '/manifest':
            self.respond(200, b'#EXTM3U\n#EXT-X-TARGETDURATION:1\n', mime='application/vnd.apple.mpegurl')
        elif path == '/downgrade':
            self.respond(302, Location='http://127.0.0.1:9/not-allowed')
        elif path == '/huge':
            self.respond(200, length=4294967297)
        elif path == '/unsolicited':
            self.respond(206, video[65536:], etag=etag, **{'Content-Range': f'bytes 65536-{len(video)-1}/{len(video)}'})
        else:
            self.respond(404)


def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def main():
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('.')
    java = os.environ.get('JAVA', str(jdk / 'java'))
    keytool = os.environ.get('KEYTOOL', str(jdk / 'keytool'))
    with ExitStack() as cleanup:
        temp = Path(cleanup.enter_context(tempfile.TemporaryDirectory(prefix='blackbox-checks-')))
        classes = temp / 'classes'
        classes.mkdir()
        sources = [str(MAIN / nm) for nm in ('MediaEntry.java', 'MediaSources.java', 'OfflineTransfer.java')]
        sources.append(str(ROOT / 'tests/HostChecks.java'))
        if os.environ.get('ECJ_JAR'):
            boot = os.environ['ANDROID_JAR']
            if os.environ.get('LAMBDA_STUBS'):
                boot += os.pathsep + os.environ['LAMBDA_STUBS']
            run(java, '-jar', os.environ['ECJ_JAR'], '-1.8', '-proc:none', '-nowarn', '-encoding', 'UTF-8',
                '-bootclasspath', boot, '-d', str(classes), *sources)
        else:
            run(os.environ.get('JAVAC', str(jdk / 'javac')), '--release', '8', '-encoding', 'UTF-8', '-d', str(classes), *sources)
        run('openssl', 'req', '-x509', '-nodes', '-days', '1', '-newkey', 'rsa:2048', '-subj', '/CN=localhost',
            '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-keyout', str(temp / 'key.pem'), '-out', str(temp / 'cert.pem'),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        trust = temp / 'trust.p12'
        run(keytool, '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(temp / 'cert.pem'), '-keystore', str(trust),
            '-storetype', 'PKCS12', '-storepass', 'fixture-password', stderr=subprocess.DEVNULL)
        server = cleanup.enter_context(ThreadingHTTPServer(('127.0.0.1', 0), Fixture))
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(temp / 'cert.pem', temp / 'key.pem')
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        cleanup.callback(server.shutdown)
        run(java, '-Djavax.net.ssl.trustStore=' + str(trust), '-Djavax.net.ssl.trustStorePassword=fixture-password',
            '-cp', str(classes), 'com.blackboxstream.mobile.HostChecks', f'https://127.0.0.1:{server.server_port}', str(RAW), str(temp), timeout=60)


if __name__ == '__main__':
    main()
