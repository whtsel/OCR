#!/usr/bin/env python3
"""Exercise production catalogue clients and two room members against HTTPS fixtures and the real relay."""
from contextlib import ExitStack, closing
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
import json
import os
import sqlite3
import ssl
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/blackboxstream/mobile'
sys.path.insert(0, str(ROOT / 'community'))
from relay import Handler, Relay


class ServiceFixture(Handler):
    def answer(self, permitted, payload):
        if not permitted:
            self.respond(401)
        else:
            self.respond(200, json.dumps(payload).encode())

    def do_GET(self):
        uri = urlsplit(self.path)
        params = parse_qs(uri.query)
        if uri.path == '/3/search/movie':
            self.answer(params.get('query') == ['A + B'] and params.get('include_adult') == ['false'] and params.get('api_key') == ['aabbccddeeff1100aabbccddeeff1100'],
                        {'results': [{'id': 76, 'title': 'A + B', 'release_date': '2024-08-03', 'overview': 'An exact title.'},
                                     {'id': 89, 'title': 'Excluded', 'adult': True}]})
        elif uri.path == '/3/discover/tv':
            self.answer(self.headers.get('Authorization') == 'Bearer header64.payload64.signature64'
                        and params.get('with_genres') == ['16'] and params.get('with_original_language') == ['ja']
                        and params.get('sort_by') == ['vote_average.desc'] and params.get('page') == ['2'],
                        {'results': [{'id': 102, 'name': 'Festival', 'original_language': 'ja', 'genre_ids': [16]},
                                     {'id': 103, 'name': 'Not animation', 'original_language': 'ja', 'genre_ids': [18]},
                                     {'id': 104, 'name': 'Not Japanese', 'original_language': 'en', 'genre_ids': [16]}]})
        elif uri.path == '/3/tv/on_the_air':
            self.respond(401)
        elif uri.path == '/ws/2/recording/':
            self.answer(params.get('query') == ['recording:"Low Orbit"'] and self.headers.get('User-Agent', '').startswith('BlackBoxStream/'),
                        {'recordings': [{'id': 'a9b2b000-fef0-4444-9999-adababa00002', 'title': 'Low Orbit',
                                         'artist-credit': [{'artist': {'name': 'BlackBox'}}], 'first-release-date': '2024'}]})
        else:
            super().do_GET()


def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def main():
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('.')
    java = os.environ.get('JAVA', str(jdk / 'java'))
    keytool = os.environ.get('KEYTOOL', str(jdk / 'keytool'))
    json_jar = os.environ.get('JSON_JAR', '')
    if not Path(json_jar).is_file():
        raise SystemExit('Set JSON_JAR to org.json:json:20240303 (a test-only host implementation).')
    with ExitStack() as cleanup:
        root = Path(cleanup.enter_context(tempfile.TemporaryDirectory(prefix='bbx-services-')))
        classes = root / 'java'
        classes.mkdir()
        sources = [str(SOURCE / (name + '.java')) for name in ('MediaEntry', 'MediaSources', 'OfflineTransfer', 'Bencode', 'TorrentMeta', 'Catalogue', 'CommunityRoom')]
        sources.append(str(ROOT / 'tests/ServiceChecks.java'))
        if os.environ.get('ECJ_JAR'):
            stubs = os.environ['ANDROID_JAR']
            if os.environ.get('LAMBDA_STUBS'):
                stubs += os.pathsep + os.environ['LAMBDA_STUBS']
            compile_cmd = [java, '-jar', os.environ['ECJ_JAR'], '-1.8', '-proc:none', '-nowarn', '-bootclasspath', stubs]
        else:
            compile_cmd = [os.environ.get('JAVAC', str(jdk / 'javac')), '--release', '8', '-classpath', json_jar]
        run(*compile_cmd, '-encoding', 'UTF-8', '-d', str(classes), *sources)
        cert, secret, trust = (root / f for f in ('cert.pem', 'key.pem', 'trust.p12'))
        run('openssl', 'req', '-x509', '-nodes', '-days', '1', '-newkey', 'rsa:2048', '-subj', '/CN=localhost',
            '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-keyout', str(secret), '-out', str(cert),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        run(keytool, '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(cert), '-keystore', str(trust),
            '-storetype', 'PKCS12', '-storepass', 'fixture-password', stderr=subprocess.DEVNULL)
        db = root / 'relay.sqlite3'
        srv = cleanup.enter_context(Relay(('127.0.0.1', 0), db))
        srv.RequestHandlerClass = ServiceFixture
        ssl_config = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_config.load_cert_chain(cert, secret)
        srv.tls = ssl_config
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        cleanup.callback(srv.shutdown)
        run(java, '-Djavax.net.ssl.trustStore=' + str(trust), '-Djavax.net.ssl.trustStorePassword=fixture-password',
            '-cp', os.pathsep.join((str(classes), json_jar)), 'com.blackboxstream.mobile.ServiceChecks', f'https://127.0.0.1:{srv.server_port}', timeout=55)
        with closing(sqlite3.connect(db)) as conn:
            events = conn.execute('SELECT room,data FROM event ORDER BY id').fetchall()
        assert len(events) == 2 and len({room for room, _ in events}) == 1
        assert all(data[:1] == b'\x01' and b'Found it' not in data and b'Secret title' not in data and b'media.example' not in data for _, data in events)
        print('PASS actual SQLite relay stores opaque encrypted envelopes only')


if __name__ == '__main__':
    main()
