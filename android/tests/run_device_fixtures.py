#!/usr/bin/env python3
"""Forward controlled fixtures to adb; no public trackers, media or relay accounts needed."""
from base64 import b64encode
from contextlib import ExitStack
from pathlib import Path
import os
import ssl
import subprocess
import tempfile
import threading

from run_services import Relay, ServiceFixture
from seed_android_demo import seed

ROOT = Path(__file__).resolve().parent


def base64(data):
    return b64encode(data).decode('ascii')


def main():
    adb = os.environ.get('ADB', 'adb')
    with ExitStack() as cleanup:
        root = Path(cleanup.enter_context(tempfile.TemporaryDirectory(prefix='bbx-device-')))
        live = cleanup.enter_context(seed('127.0.0.1', root))
        cert, key = root / 'cert.pem', root / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-nodes', '-days', '1', '-newkey', 'rsa:2048',
                        '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1',
                        '-keyout', str(key), '-out', str(cert)], check=True, capture_output=True)
        relay = cleanup.enter_context(Relay(('127.0.0.1', 0), root / 'events.sqlite3'))
        relay.RequestHandlerClass = ServiceFixture
        relay.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        relay.tls.load_cert_chain(cert, key)
        threading.Thread(target=relay.serve_forever, daemon=True).start()
        cleanup.callback(relay.shutdown)
        for port in (live.tracker_port, live.peer_port, relay.server_port):
            name = f'tcp:{port}'
            subprocess.run([adb, 'reverse', '--no-rebind', name, name], check=True)
            cleanup.callback(subprocess.run, [adb, 'reverse', '--remove', name], check=False)
        env = dict(os.environ,
                   BBX_FIXTURE_MAGNET=base64(live.magnet.encode('ascii')),
                   BBX_FIXTURE_TORRENT=base64(live.descriptor.read_bytes()),
                   BBX_FIXTURE_RELAY=f'https://127.0.0.1:{relay.server_port}',
                   BBX_FIXTURE_CERT=base64(cert.read_bytes()))
        subprocess.run([str(ROOT / 'run_device.sh')], env=env, check=True, timeout=240)


if __name__ == '__main__':
    main()
