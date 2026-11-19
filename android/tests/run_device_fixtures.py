#!/usr/bin/env python3
"""Forward controlled fixtures to adb and, in release validation, require public media."""
from argparse import ArgumentParser
from base64 import b64encode
from contextlib import ExitStack
from pathlib import Path
import json
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
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--record-public-hashes', type=Path, help='Record the independently collected digests after public Android acceptance passes.')
    args = parser.parse_args()
    if args.record_public_hashes and os.environ.get('BBX_VERIFY_PUBLIC_MEDIA') != '1':
        parser.error('--record-public-hashes requires BBX_VERIFY_PUBLIC_MEDIA=1')
    adb = os.environ.get('ADB', 'adb')
    with ExitStack() as cleanup:
        root = Path(cleanup.enter_context(tempfile.TemporaryDirectory(prefix='bbx-device-')))
        live = cleanup.enter_context(seed('127.0.0.1', root, rate=36 * 1024))
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
        if os.environ.get('BBX_VERIFY_PUBLIC_MEDIA') == '1':
            if subprocess.check_output([adb, 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() != '1':
                raise RuntimeError('Public acceptance requires an emulator: it changes display geometry and disables networking for offline playback.')
            from check_playable_media import verify
            try:
                expected = verify()
            except Exception as error:
                print(f'::error::Independent live media download failed: {type(error).__name__}: {error}', flush=True)
                raise
            print('::notice::Independent full-file hashes collected for ' + ', '.join(expected), flush=True)
            env['BBX_PUBLIC_MEDIA_HASHES'] = base64(json.dumps(expected, separators=(',', ':')).encode('utf-8'))
            # Test touch geometry at a phone-sized viewport, not a desktop layout.
            subprocess.run([adb, 'shell', 'wm', 'size', '720x1560'], check=True)
            subprocess.run([adb, 'shell', 'wm', 'density', '320'], check=True)
            cleanup.callback(subprocess.run, [adb, 'shell', 'wm', 'size', 'reset'], check=False)
            cleanup.callback(subprocess.run, [adb, 'shell', 'wm', 'density', 'reset'], check=False)
        subprocess.run([str(ROOT / 'run_device.sh')], env=env, check=True, timeout=570)
        if args.record_public_hashes:
            args.record_public_hashes.write_text(env['BBX_PUBLIC_MEDIA_HASHES'] + '\n', encoding='ascii')


if __name__ == '__main__':
    main()
