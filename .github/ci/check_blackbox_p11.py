#!/usr/bin/env python3
"""Check archived p11 or its CI-only ZIP dependency probe. Publish no APKs or audio."""

import argparse
from functools import partial
import hashlib
import http.server
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


APK_SHA = '3e4b8d54f0aa11b8c5c2b193af9a52fbfe5051b6c187c55bdc4d50dd2c873a38'
HELPER_SHA = '89a0d9058ea9018e380b7771898ff46e393a1986dcd13fef331693c87ce1fca4'
DRIVE_ID = '1ROpCFQXoRk6T8mOomPEyRQcZkGwxgsqy'
PKG = 'com.blackbox.stream'
# Known official album audio IDs and catalogue durations, not search substitutes.
SONGS = (('Espresso', '51zjlMhdSTE', 175), ('Kwaku the Traveller', 'Hb6I7GoTvzI', 185))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def diagnostic(text):
    # Keep media and redirect URLs out of CI logs. Preserve the failure category.
    return re.sub(r'https?://[^\s\]\"<>]+', '[url]', text).strip()[-900:]


def run(*cmd, timeout=30, check=True, binary=False):
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'{Path(cmd[0]).name} exit {result.returncode}: '
                           f'{diagnostic(result.stdout.decode(errors="replace"))}')
    return result.stdout if binary else result.stdout.decode(errors='replace').strip()


def verify_apk(path):
    if digest(path.read_bytes()) != APK_SHA:
        raise RuntimeError('APK bytes do not match the archived p11 checksum')


def download(path):
    urls = (f'https://drive.usercontent.google.com/download?id={DRIVE_ID}&export=download&confirm=t',
            f'https://drive.google.com/uc?export=download&id={DRIVE_ID}&confirm=t')
    for url in urls:
        try:
            run('curl', '-fsSL', '--max-time', '90', '--max-filesize', '56000000', '-o', path, url, timeout=100)
            verify_apk(path)
            print('Drive archive: p11 SHA-256 verified')
            return
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            print(f'Download attempt: {diagnostic(str(error))}')
    raise RuntimeError('Could not obtain the unmodified p11 artifact on this runner')


def verify_audio(path, duration, seek):
    data = json.loads(run('ffprobe', '-v', 'error', '-show_entries', 'stream=codec_type:format=duration', '-of', 'json', path))
    measured = float(data.get('format', {}).get('duration', 0))
    if not any(s['codec_type'] == 'audio' for s in data['streams']) or abs(measured - duration) > min(6, duration / 10):
        raise RuntimeError(f'wrong or missing audio stream; duration {measured:.2f}s (expected {duration}s)')
    for offset in (0.25, seek):
        pcm = run('ffmpeg', '-nostdin', '-v', 'error', '-ss', str(offset), '-i', path, '-t', '1',
                  '-vn', '-ac', '1', '-ar', '16000', '-f', 's16le', '-', binary=True)
        if len(pcm) < 30000 or not any(pcm):
            raise RuntimeError(f'no decoded non-silent audio at {offset}s')
    return measured


def host(work, apk=None):
    if apk is None:
        apk = work / 'p11.apk'
        download(apk)
    else:
        verify_apk(apk)
    helper = work / 'yt-dlp'
    with zipfile.ZipFile(apk) as archive:
        helper.write_bytes(archive.read('res/raw/ytdlp'))
    if digest(helper.read_bytes()) != HELPER_SHA:
        raise RuntimeError('APK resource does not match the bundled helper checksum')
    command = (sys.executable, str(helper), '--ignore-config', '--no-cache-dir', '--socket-timeout', '12',
               '--retries', '0', '--fragment-retries', '0', '--no-playlist', '--no-progress')
    node = shutil.which('node')
    if node:
        command += ('--js-runtimes', f'node:{node}')
    print(f'Exact bundled helper on host Python: {run(*command, "--version")}')
    originals = work / 'fixture'
    originals.mkdir()
    fixture = originals / 'tone.m4a'
    run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=4',
        '-c:a', 'aac', fixture)
    handler = partial(http.server.SimpleHTTPRequestHandler, directory=originals)
    with http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            copy = work / 'control.m4a'
            run(*command, '-o', copy, f'http://127.0.0.1:{server.server_port}/tone.m4a')
            if digest(copy.read_bytes()) != digest(fixture.read_bytes()):
                raise RuntimeError('control audio did not match source bytes')
            seconds = verify_audio(copy, 4, 2)
            print(f'::notice::PASS host control: identical audio bytes, {seconds:.2f}s, decoded at start and 2s (not a catalogue test)')
        finally:
            server.shutdown()
    failures = []
    for title, video_id, duration in SONGS:
        try:
            url = f'https://www.youtube.com/watch?v={video_id}'
            # Match the original application's audio extraction mode, using host FFmpeg.
            output = work / f'{video_id}.m4a'
            run(*command, '-x', '--audio-format', 'm4a', '--audio-quality', '0', '--max-filesize', '24M',
                '-o', work / f'{video_id}.%(ext)s', url, timeout=80)
            seconds = verify_audio(output, duration, duration * 2 // 3)
            print(f'PASS host-only: {title} ({video_id}), {seconds:.2f}s, non-silent audio at start and after seek')
        except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
            reason = f'{title} ({video_id}): {diagnostic(str(error))}'
            failures.append(reason)
            print(f'::warning::{reason.replace(chr(10), " / ")}')
    if failures:
        raise RuntimeError('Bundled script did not supply both original recordings on this network')


def adb(*args, **kwargs):
    return run('adb', *args, **kwargs)


def ui_nodes():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/bbx-window.xml', timeout=15)
    raw = adb('exec-out', 'cat', '/data/local/tmp/bbx-window.xml')
    return list(ET.fromstring(raw).iter('node'))


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def label(node):
    return (node.get('text', ''), node.get('content-desc', ''), node.get('resource-id', '').split('/')[-1])


def tap(node, fraction=0.5):
    left, top, right, bottom = bounds(node)
    adb('shell', 'input', 'tap', str(int(left + (right - left) * fraction)), str((top + bottom) // 2))


def wait_ui(predicate, seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            match = predicate(ui_nodes())
            if match is not None:
                return match
        except (RuntimeError, ET.ParseError, subprocess.TimeoutExpired):
            pass
        time.sleep(0.8)
    return None


def find(nodes, *phrases):
    for node in nodes:
        if node.get('package') == PKG and any(phrase in label(node) for phrase in phrases):
            left, top, right, bottom = bounds(node)
            if right > left and bottom > top:
                return node
    return None


def player_time(nodes, duration):
    for node in nodes:
        for text in label(node)[:2]:
            match = re.fullmatch(r'(\d+):(\d{2})\s*/\s*(\d+):(\d{2})', text.strip())
            if node.get('package') == PKG and match:
                m, s, dm, ds = map(int, match.groups())
                if abs(duration - (dm * 60 + ds)) <= 6:
                    return m * 60 + s
    return None


def try_player(track, duration):
    music = wait_ui(lambda nodes: find(nodes, 'navMusic', 'Music'), 30)
    if music is None:
        raise RuntimeError('could not locate the Music tab via Android accessibility')
    tap(music)
    time.sleep(6)  # The stock first-use Music source explainer closes automatically.
    def play_control(nodes):
        exact = find(nodes, f'Play {track}')
        if exact is not None:
            return exact
        # Catalogue result rows use the same generic 'Play this exact track' button.
        title = find(nodes, track)
        if title is not None and label(title)[2] != 'bgTitle':
            l, t, r, b = bounds(title)
            return next((n for n in nodes if n.get('package') == PKG and
                         ('Play this exact track' in label(n) or ('▶' in label(n) and label(n)[2] != 'bgPlayBtn'))
                         and bounds(n)[2] >= r and bounds(n)[1] <= (t + b) // 2 <= bounds(n)[3]), None)
        return None

    control = wait_ui(play_control, 5)
    if control is None:
        query = wait_ui(lambda nodes: find(nodes, 'musicQuery', 'Search a song, artist or album'), 12)
        if query is None:
            raise RuntimeError('could not find the original Music card or search field')
        clear = find(ui_nodes(), 'musicClear')
        if clear is not None:
            tap(clear)
            query = wait_ui(lambda nodes: find(nodes, 'musicQuery', 'Search a song, artist or album'), 5)
            if query is None:
                raise RuntimeError('search field disappeared after resetting the query')
        tap(query)
        adb('shell', 'input', 'text', track.replace(' ', '%s'))
        adb('shell', 'input', 'keyevent', 'KEYCODE_ENTER')
        time.sleep(1)
        control = wait_ui(play_control, 12)
        if control is None:
            adb('shell', 'input', 'swipe', '900', '1320', '900', '720', '300')
            control = wait_ui(play_control, 8)
    if control is None:
        raise RuntimeError(f'could not select {track} in the original Music screen')
    tap(control)
    print(f'Android UI: selected {track}; checking the visible player clock')

    def moving_after(previous):
        def check(nodes):
            value = player_time(nodes, duration)
            return value if value is not None and value >= previous + 2 else None
        return check

    first = wait_ui(moving_after(0), 80)
    if first is None:
        raise RuntimeError(f'{track}: player did not advance on full-length audio')
    second = wait_ui(moving_after(first), 12)
    if second is None:
        raise RuntimeError(f'{track}: clock stalled at {first}s')
    def slider_node(nodes):
        by_id = find(nodes, 'bgSeek')
        return by_id if by_id is not None else next(
            (n for n in nodes if n.get('package') == PKG and n.get('class') == 'android.widget.SeekBar'), None)

    slider = wait_ui(slider_node, 8)
    if slider is None:
        raise RuntimeError('player started but the seek slider was not accessible')
    tap(slider, 0.67)
    after_seek = wait_ui(moving_after(duration * 3 // 5), 16)
    if after_seek is None or wait_ui(moving_after(after_seek), 12) is None:
        raise RuntimeError('player did not keep advancing after seeking beyond a preview')
    print(f'Android UI: {track} advanced {first}s→{second}s and resumed after {after_seek}s seek')


def request(base, path, timeout=8, body=None, headers=None):
    req = urllib.request.Request(base + path, body, headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, response.read(24 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as error:
        return error.code, error.read(4096)


def find_server(pid):
    end = time.monotonic() + 20
    while time.monotonic() < end:
        entries = adb('shell', 'ss', '-ltnp', check=False)
        for line in entries.splitlines():
            match = re.search(r'(?:127\.0\.0\.1\b\]?):(\d+)', line)
            if not match or f'pid={pid},' not in line:
                continue
            port = adb('forward', 'tcp:0', f'tcp:{match[1]}')
            base = f'http://127.0.0.1:{port}'
            try:
                code, body = request(base, '/api/ytdlp/status', timeout=3)
                if code == 200 and isinstance(json.loads(body).get('installed'), bool):
                    return port, base
            except (OSError, ValueError):
                pass
            adb('forward', '--remove', f'tcp:{port}')
        time.sleep(1)
    raise RuntimeError('could not reach p11’s own loopback helper status endpoint')


def check_native_control(work):
    # This executes the installed fixture helper in the disposable emulator's shell.
    # The real recording probes below separately exercise the app's own backend.
    root = '/data/user/0/' + PKG + '/no_backup/youtubedl-android'
    prefix = root + '/packages'
    apk_path = adb('shell', 'pm', 'path', PKG).splitlines()[0].removeprefix('package:')
    native = str(Path(apk_path).parent / 'lib/x86_64')
    tmp = '/data/local/tmp/bbx-helper-control'
    adc = work / 'fixture'
    adc.mkdir()
    run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=4',
        '-c:a', 'pcm_s16le', adc / 'tone.wav')
    handler = partial(http.server.SimpleHTTPRequestHandler, directory=adc)
    with http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = f'tcp:{server.server_port}'
        try:
            adb('reverse', port, port)
            adb('shell', 'mkdir', '-p', tmp)
            runtime = ['env', f'LD_LIBRARY_PATH={prefix}/python/usr/lib:{prefix}/ffmpeg/usr/lib',
                       f'PYTHONHOME={prefix}/python/usr', f'HOME={tmp}', f'TMPDIR={tmp}',
                       f'SSL_CERT_FILE={prefix}/python/usr/etc/tls/cert.pem',
                       f'{native}/libpython.so', f'{root}/yt-dlp/yt-dlp']
            version = adb('shell', shlex.join(runtime + ['--ignore-config', '--version']))
            opts = ['--ignore-config', '--no-cache-dir', '--no-progress', '--socket-timeout', '6',
                    '--retries', '0', '--ffmpeg-location', f'{native}/libffmpeg.so',
                    '-x', '--audio-format', 'm4a', '-o', f'{tmp}/control.%(ext)s',
                    f'http://127.0.0.1:{server.server_port}/tone.wav']
            adb('shell', shlex.join(runtime + opts), timeout=45)
            output = work / 'android-control.m4a'
            adb('pull', f'{tmp}/control.m4a', output)
            seconds = verify_audio(output, 4, 2)
            print(f'::notice::PASS Android x86_64 control: yt-dlp {version} and bundled FFmpeg '
                  f'converted and decoded {seconds:.2f}s of fixture audio (not a catalogue track)')
        finally:
            adb('reverse', '--remove', port, check=False)
            adb('shell', 'rm', '-rf', tmp, check=False)
            server.shutdown()


def verify_repair(original, probe, native_x86):
    verify_apk(original)
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(probe) as after:
        original_names = {name for name in before.namelist() if not name.startswith('META-INF/')}
        probe_names = [name for name in after.namelist() if not name.startswith('META-INF/')]
        expected = original_names | {'classes2.dex'}
        if native_x86:
            expected |= {name.replace('arm64-v8a', 'x86_64', 1) for name in original_names
                         if name.startswith('lib/arm64-v8a/')}
        if len(probe_names) != len(expected) or set(probe_names) != expected:
            raise RuntimeError('unexpected entries in test repaired APK')
        for name in sorted(original_names):
            if before.read(name) != after.read(name):
                raise RuntimeError(f'test repaired APK modified app bytes in {name}')
    description = 'ZIP dependency plus x86_64 runtime siblings' if native_x86 else 'ZIP dependency alone'
    print(f'Diagnostic APK: original app bytes match; added {description} and a test signature')


def android(apk, work, original=None, native_x86=False):
    if original is None:
        verify_apk(apk)
    else:
        verify_repair(original, apk, native_x86)
    adb('root')  # Isolated, disposable emulator only; needed for process-owned socket diagnostics.
    adb('wait-for-device')
    props = {prop: adb('shell', 'getprop', prop) for prop in
             ('ro.build.version.sdk', 'ro.product.cpu.abilist', 'ro.dalvik.vm.native.bridge')}
    print(f'Android test environment: {props}')
    flags = ('--abi', 'x86_64') if native_x86 else ()
    print(adb('install', '-g', *flags, apk, timeout=65))
    # Keep all of p11's desktop-style navigation visible on the test display.
    adb('shell', 'wm', 'size', '1280x1800')
    adb('shell', 'wm', 'density', '160')
    print(adb('shell', 'am', 'start', '-W', '-n', f'{PKG}/.MainActivity'))
    pid = adb('shell', 'pidof', PKG)
    try:
        port, base = find_server(pid)
        try:
            end = time.monotonic() + 95
            status = {}
            while time.monotonic() < end:
                _, body = request(base, '/api/ytdlp/status')
                status = json.loads(body)
                if status.get('installed') or status.get('state') not in ('', 'starting', None):
                    break
                time.sleep(2)
            print(f'::notice::Android helper status: {diagnostic(json.dumps(status, ensure_ascii=False))}')
            if not status.get('installed'):
                raise RuntimeError(f'Android API {props["ro.build.version.sdk"]} helper startup did not pass: '
                                   f'{status.get("state")} — {status.get("message")}')

            control_ok = True
            if native_x86:
                try:
                    check_native_control(work)
                except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
                    control_ok = False
                    print(f'::warning::Android x86_64 fixture: {diagnostic(str(error)).replace(chr(10), " / ")}')
            ui_failures = []
            for title, video_id, duration in (() if native_x86 else SONGS):
                try:
                    try_player(title, duration)
                    # The app saves the selected recording in the background; confirm the expected ID
                    # before any direct fetch, so a substitute or independently fetched file cannot pass.
                    end = time.monotonic() + 25
                    while time.monotonic() < end:
                        code, data = request(base, f'/api/music/youtube/file/{video_id}?kind=audio',
                                             headers={'Range': 'bytes=0-63'})
                        if code == 206 and len(data) == 64:
                            print(f'PASS Android: {title} plays, seeks and serves its expected saved recording ID')
                            break
                        time.sleep(2)
                    else:
                        raise RuntimeError(f'{title} played but expected recording ID {video_id} was not verified')
                except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                    ui_failures.append(title)
                    print(f'::warning::Android UI {title}: {diagnostic(str(error)).replace(chr(10), " / ")}')

            # When the UI test fails, distinguish provider failure from UI/selection issues.
            backend_failures = []
            for title, video_id, duration in SONGS:
                if not native_x86 and title not in ui_failures:
                    continue
                try:
                    code, body = request(base, '/api/music/youtube/fetch', timeout=80,
                                         body=json.dumps({'id': video_id}).encode(),
                                         headers={'Content-Type': 'application/json'})
                    answer = json.loads(body)
                    if code != 200 or answer.get('id') != video_id or not answer.get('ok'):
                        raise RuntimeError(f'HTTP {code}: {diagnostic(json.dumps(answer))}')
                    code, media = request(base, f'/api/music/youtube/file/{video_id}?kind=audio', timeout=30)
                    if code != 200 or not 0 < len(media) <= 24 * 1024 * 1024:
                        raise RuntimeError(f'no complete, bounded audio response (HTTP {code})')
                    output = work / f'{video_id}.m4a'
                    output.write_bytes(media)
                    seconds = verify_audio(output, duration, duration * 2 // 3)
                    print(f'::notice::PASS Android backend only: {title} ({video_id}), {seconds:.2f}s, decoded after seek')
                except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
                    backend_failures.append(title)
                    print(f'::warning::Android backend {title} ({video_id}): '
                          f'{diagnostic(str(error)).replace(chr(10), " / ")}')
            if ui_failures:
                raise RuntimeError('Android in-app playback not verified: ' + ', '.join(ui_failures))
            if native_x86 and (backend_failures or not control_ok):
                raise RuntimeError('Android x86_64 diagnostic did not pass the fixture and both exact recordings')
        finally:
            adb('forward', '--remove', f'tcp:{port}', check=False)
    finally:
        log = adb('logcat', '-d', '-s', 'BlackBoxStream', 'AndroidRuntime', check=False)
        print(f'Android helper startup diagnostics:\n{diagnostic(log)}')
        useful = [line for line in log.splitlines()
                  if any(key in line for key in ('failed to start', 'Caused by:', 'media helper ready'))]
        if useful:
            print('::notice::' + diagnostic(' | '.join(useful[-6:])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('download', 'host', 'android'))
    parser.add_argument('apk', type=Path, nargs='?')
    parser.add_argument('--original', type=Path, help='verified original for comparison with a test repair')
    parser.add_argument('--native-x86', action='store_true',
                        help='backend-only probe with the x86_64 sibling of the ARM helper')
    args = parser.parse_args()
    if args.mode != 'host' and args.apk is None:
        parser.error('APK path required')
    if args.native_x86 and (args.mode != 'android' or not args.original):
        parser.error('--native-x86 requires android mode and --original')
    try:
        with tempfile.TemporaryDirectory(prefix='bbx-p11-') as work:
            if args.mode == 'download':
                download(args.apk)
            elif args.mode == 'host':
                host(Path(work), args.apk)
            else:
                android(args.apk, Path(work), args.original, args.native_x86)
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f'::error::{diagnostic(str(error)).replace(chr(10), " ")}')
        sys.exit(1)


if __name__ == '__main__':
    main()
