#!/usr/bin/env python3
"""Exercise the diagnostic on a disposable x86 sibling. Never contact the provider."""
import base64
import hashlib
import http.server
from contextlib import contextmanager
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

import check_blackbox_p11 as p11

PKG = 'com.blackbox.stream.savecheck.smoke'
ACTIVITY = f'{PKG}/com.blackboxstream.mobile.MusicSaveCheckActivity'
VIDEO = '51zjlMhdSTE'
EXPECTED = f'/{VIDEO}.m4a'
PREFS = 'shared_prefs/save-check.xml'
ARM = ('pilot', 'full')


def note(message):
    print(f'::notice::{message}', flush=True)


def local(*args, **kwargs):
    return p11.adb('shell', 'run-as', PKG, *args, **kwargs)


def airplane(enabled):
    p11.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable' if enabled else 'disable')
    p11.adb('shell', 'svc', 'wifi', 'disable' if enabled else 'enable')
    p11.adb('shell', 'svc', 'data', 'disable' if enabled else 'enable')
    if p11.adb('shell', 'settings', 'get', 'global', 'airplane_mode_on') != str(int(enabled)):
        raise RuntimeError('Airplane mode did not change')


def prefs():
    xml = local('cat', PREFS, check=False)
    if not xml.startswith('<?xml'):
        return {}
    return {node.get('name'): node.get('value', node.text or '') for node in ET.fromstring(xml)}


def until(check, label, seconds=45):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.25)
    raise RuntimeError(f'{label}: {prefs()}')


def node(nodes, name, test=lambda _: True):
    for element in nodes:
        if element.get('resource-id', '').split('/')[-1] == name and element.get('package') == PKG and test(element):
            l, t, r, b = p11.bounds(element)
            if l < r and t < b:
                return element
    return None


def wait_node(name, test=lambda _: True, timeout=22):
    result = p11.wait_ui(lambda nodes: node(nodes, name, test), timeout)
    if result is None:
        screen = [p11.label(e) for e in p11.ui_nodes() if e.get('package') == PKG and any(p11.label(e))]
        raise RuntimeError(f'Missing or unavailable {name}: {screen}; {prefs()}')
    return result


def tap(name):
    p11.tap(wait_node(name, lambda n: n.get('enabled') == 'true'))


def launch():
    print(p11.adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=35))
    wait_node('pilot_save')


def stop():
    p11.adb('shell', 'am', 'force-stop', PKG)


def empty_test_data():
    # Tests run only on a disposable emulator; never clear data in a user's install.
    assert p11.adb('shell', 'pm', 'clear', PKG) == 'Success'
    launch()
    assert not prefs()


def stage_pilot(data):
    local('mkdir', '-p', 'files/save-check-fixtures')
    path = 'files/save-check-fixtures/pilot.m4a'
    command = f"run-as {PKG} sh -c 'base64 -d > {path}'"
    subprocess.run(['adb', 'shell', '-T', command], input=base64.b64encode(data), check=True, timeout=24)
    if local('sha256sum', path).split()[0] != hashlib.sha256(data).hexdigest():
        raise RuntimeError('Pilot synthetic staging changed bytes')


@contextmanager
def fixture_server(data, *, refuse=False):
    seen, release = [], threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, _format, *args):
            pass

        def do_GET(self):
            seen.append(self.path)
            if self.path != EXPECTED:
                self.send_error(404)
                return
            if refuse:
                self.send_error(429)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'audio/mp4')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data[:65536])
                self.wfile.flush()
                release.wait(25)
                self.wfile.write(data[65536:])
            except (BrokenPipeError, ConnectionResetError):
                pass  # The generic extractor may stop its media probe after the header.

    with http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        p11.adb('reverse', 'tcp:19090', f'tcp:{server.server_port}')
        try:
            yield seen, release
        finally:
            release.set()
            p11.adb('reverse', '--remove', 'tcp:19090', check=False)
            server.shutdown()


def wait_result(arm, desired):
    state = until(lambda: read_result(arm), f'{arm} did not finish', 100)
    if state[arm + '.state'] != desired:
        raise RuntimeError(f'{arm} ended unexpectedly: {state}')
    return state


def read_result(arm):
    result = prefs()
    return result if result.get(arm + '.state') in ('saved', 'failed') else None


def verify_saved(arm, tone, root):
    data = p11.adb('exec-out', 'run-as', PKG, 'cat', f'files/save-check/{arm}.m4a', binary=True)
    if data != tone:
        raise RuntimeError(f'{arm}: saved audio bytes differed from the synthetic source')
    export = root / f'{arm}.m4a'
    export.write_bytes(data)
    p11.verify_audio(export, 175, 123)


def enabled(nodes, arm):
    item = node(nodes, arm + '_save')
    return item is not None and item.get('enabled') == 'true'


def smoke(apk, root):
    path = root / 'tone.m4a'
    p11.run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=730:duration=175',
            '-c:a', 'aac', '-movflags', '+faststart', path, timeout=40)
    data = path.read_bytes()
    print(p11.adb('install', '--abi', 'x86_64', apk, timeout=100))
    p11.PKG = PKG
    p11.adb('shell', 'wm', 'size', '800x1800')
    p11.adb('shell', 'wm', 'density', '320')
    airplane(True)
    launch()
    wait_node('connection', lambda n: n.get('text') == 'Airplane mode · offline')
    assert not prefs() and not enabled(p11.ui_nodes(), 'pilot') and not enabled(p11.ui_nodes(), 'full')
    for path in ('files/media-helper/audio', 'files/save-check', 'no_backup/youtubedl-android'):
        assert local('test', '!', '-e', path) == ''
    stage_pilot(data)
    airplane(False)
    with fixture_server(data) as (requests, release):
        try:
            tap('full_save')
            until(lambda: requests, 'Full runner did not request the loopback fixture', 60)
            full = wait_node('full_save', lambda n: n.get('enabled') == 'false')
            pilot = wait_node('pilot_save', lambda n: n.get('enabled') == 'false')
            p11.tap(pilot)  # Non-UI guard also prevents a second trial starting.
            assert full is not None and prefs().get('pilot.state') is None
            release.set()
            wait_result('full', 'saved')
        finally:
            release.set()
        assert set(requests) == {EXPECTED}
    verify_saved('full', data, root)
    tap('pilot_save')
    wait_result('pilot', 'saved')
    verify_saved('pilot', data, root)
    assert local('sha256sum', 'no_backup/youtubedl-android/yt-dlp/yt-dlp').split()[0] == p11.P19_SHA
    note('PASS one job at a time; empty test paths; Full v90 loopback transfer and archived pilot local-file branch both published verified synthetic audio')

    stop()
    airplane(True)
    # Delete the helper ONLY from the fresh debug test sandbox. Playback has no downloader dependency.
    local('rm', '-rf', 'no_backup/youtubedl-android')
    launch()
    wait_node('connection', lambda n: n.get('text') == 'Airplane mode · offline')
    for arm in ARM:
        tap(arm + '_play')
        tap('offline_seek')
        until(lambda: prefs().get(arm + '.offline') == 'true', f'{arm} failed to advance past halfway offline')
        wait_node(arm + '_result', lambda n: 'Cold offline seek passed' in n.get('text', ''))
    assert local('test', '!', '-e', 'no_backup/youtubedl-android') == ''
    note('PASS both saved files played and advanced after a real seek, force-stop and airplane mode; helper was not initialized')

    empty_test_data()
    stage_pilot(data[:-32000])
    airplane(False)
    tap('pilot_save')
    bad = wait_result('pilot', 'failed')
    assert bad.get('pilot.reason') == 'incomplete' and bad.get('pilot.phase') == 'verify'
    assert local('test', '!', '-e', 'files/save-check/pilot.m4a') == ''
    note('PASS incomplete staged audio was rejected at verification and never published')

    empty_test_data()
    with fixture_server(data, refuse=True) as (requests, _):
        tap('full_save')
        stopped = wait_result('full', 'failed')
        assert stopped.get('full.reason') == 'limited' and stopped.get('full.phase') == 'source'
        assert requests and set(requests) == {EXPECTED}
        request_count = len(requests)
        stop()
        launch()
        wait_node('full_result', lambda n: 'provider rate limit' in n.get('text', ''))
        pilot = wait_node('pilot_save', lambda n: n.get('enabled') == 'false')
        p11.tap(pilot)
        assert prefs().get('pilot.state') is None and len(requests) == request_count
    assert local('test', '!', '-e', 'files/save-check/full.m4a') == ''
    note('PASS simulated HTTP 429 reported before transfer, stopped the comparison across restart, and published nothing')


if __name__ == '__main__':
    try:
        if p11.adb('shell', 'getprop', 'ro.kernel.qemu') != '1' or p11.adb('shell', 'getprop', 'ro.product.cpu.abi') != 'x86_64':
            raise RuntimeError('Use a disposable x86 Android emulator for fixture tests')
        with tempfile.TemporaryDirectory() as root:
            smoke(Path(sys.argv[1]), Path(root))
    except Exception as error:
        print('::error::' + p11.diagnostic(str(error)).replace('\n', ' / '))
        raise
    finally:
        print(p11.adb('logcat', '-d', '-s', 'BlackBoxMusic', 'BlackBoxStream', 'AndroidRuntime', check=False)[-6500:])
