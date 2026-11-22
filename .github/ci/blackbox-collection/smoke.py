"""Test the disposable x86 sibling using synthetic in-app audio, not a live provider."""
import hashlib
from contextlib import contextmanager
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request

from websocket import create_connection

import check_blackbox_p11 as p11

p11.PKG = 'com.blackbox.stream.collectionpilot.smoke'
ACTIVITY = p11.PKG + '/com.blackbox.stream.collectionpilot.CollectionActivity'
KWAKU, ESPRESSO = 'Kwaku the Traveller', 'Espresso'
IDS = {title: (video, seconds) for title, video, seconds in p11.SONGS}


def await_ui(check, description, timeout=24):
    result = p11.wait_ui(check, timeout)
    if result is None:
        nodes = p11.ui_nodes()
        visible = [str(p11.label(node)) for node in nodes if node.get('package') == p11.PKG and any(p11.label(node))]
        raise RuntimeError(f'{description}: {" | ".join(visible)}')
    return result


def visible(*labels, timeout=24):
    return await_ui(lambda nodes: nodes if all(p11.find(nodes, word) is not None for word in labels) else None,
                    'Did not see ' + ', '.join(labels), timeout)


def tap(label, timeout=20):
    p11.tap(await_ui(lambda nodes: p11.find(nodes, label), f'No control for {label}', timeout))


def launch():
    print(p11.adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=30))
    visible('Playlists', 'Test audio only · no provider requests')


def stop():
    p11.adb('shell', 'am', 'force-stop', p11.PKG)


def airplane(enabled):
    p11.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable' if enabled else 'disable')
    p11.adb('shell', 'svc', 'wifi', 'disable' if enabled else 'enable')
    p11.adb('shell', 'svc', 'data', 'disable' if enabled else 'enable')
    observed = p11.adb('shell', 'settings', 'get', 'global', 'airplane_mode_on')
    if observed != str(int(enabled)):
        raise RuntimeError('Airplane mode did not change')


def stage(title, data):
    video, _ = IDS[title]
    p11.adb('shell', 'run-as', p11.PKG, 'mkdir', '-p', 'files/media-helper/audio')
    process = subprocess.run(['adb', 'exec-in', f"run-as {p11.PKG} sh -c 'cat > files/media-helper/audio/{video}.m4a'"],
                             input=data, check=True, stdout=subprocess.PIPE, timeout=15)
    if process.stdout:
        raise RuntimeError(process.stdout.decode(errors='replace'))


def saved_matches(title, expected):
    video, _ = IDS[title]
    saved = p11.adb('exec-out', 'run-as', p11.PKG, 'cat', f'files/collection-audio/{video}.m4a', binary=True)
    if hashlib.sha256(saved).digest() != hashlib.sha256(expected).digest():
        raise RuntimeError(f'{title}: the saved file changed bytes')


@contextmanager
def page_devtools():
    # Only available in the debug CI sibling. This observes the live WebView/MediaPlayer,
    # avoiding UiAutomator's idle wait on the continuously updating seek control.
    pid = p11.adb('shell', 'pidof', '-s', p11.PKG)
    port = int(p11.adb('forward', 'tcp:0', f'localabstract:webview_devtools_remote_{pid}'))
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/json', timeout=8) as info:
            tabs = json.load(info)
        tab = next((tab for tab in tabs if tab['url'] == 'https://music.blackbox.invalid/index.html'), None)
        if not tab:
            raise RuntimeError('CI WebView did not expose the packaged UI')
        socket = create_connection(tab['webSocketDebuggerUrl'], timeout=8, suppress_origin=True)
        try:
            sequence = 0

            def read(script):
                nonlocal sequence
                sequence += 1
                socket.send(json.dumps({'id': sequence, 'method': 'Runtime.evaluate',
                                        'params': {'expression': script, 'returnByValue': True}}))
                while True:
                    result = json.loads(socket.recv())
                    if result.get('id') != sequence:
                        continue
                    if 'error' in result or 'exceptionDetails' in result.get('result', {}):
                        raise RuntimeError(f'CI WebView check failed: {result}')
                    return result['result']['result'].get('value')

            yield read
        finally:
            socket.close()
    finally:
        p11.adb('forward', '--remove', f'tcp:{port}', check=False)


def advancing(read, key, title, after):
    until = time.monotonic() + 16
    while time.monotonic() < until:
        payload = read('''(()=>({player:JSON.parse(OfflineMusic.player()),
            open:document.querySelector('#player').open, title:document.querySelector('#now-title').textContent,
            clock:document.querySelector('#clock').textContent}))()''')
        player = payload['player']
        if (payload['open'] and payload['title'] == title and '/' in payload['clock']
                and player['key'] == key and player['playing'] and player['position'] > after
                and abs(player['duration'] - 1000 * IDS[title][1]) <= 2000):
            return player['position'], player['duration']
        time.sleep(.3)
    raise RuntimeError(f'{title} did not advance past {after / 1000:.1f}s in Now Playing: {payload}')


def play_offline(read, key, title):
    tap('Play ' + title)
    if not read("document.getElementById('open-player').click(); document.getElementById('player').open"):
        # The mini-player may be hidden just until the bridge's next render tick.
        until = time.monotonic() + 8
        while not read("document.getElementById('player').open") and time.monotonic() < until:
            time.sleep(.3)
            read("if (!document.getElementById('mini').hidden) document.getElementById('open-player').click()")
    start, duration = advancing(read, key, title, 1000)
    read('''(()=>{const seek = document.getElementById('seek'); seek.value = Number(seek.max) * .72;
        seek.dispatchEvent(new Event('input')); seek.dispatchEvent(new Event('change'));})()''')
    sought, _ = advancing(read, key, title, duration // 2)
    advanced, _ = advancing(read, key, title, sought + 900)
    read("document.getElementById('close-player').click()")
    print(f'::notice::PASS cold offline {title} fixture: {start / 1000:.1f}s → seek {sought / 1000:.1f}s → {advanced / 1000:.1f}s of {duration / 1000:.1f}s')


def smoke(apk, directory):
    tones = {}
    for title, (name, seconds) in IDS.items():
        path = directory / f'{name}.m4a'
        frequency = 440 if title == KWAKU else 660
        p11.run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', f'sine=frequency={frequency}:duration={seconds}',
                '-c:a', 'aac', '-movflags', '+faststart', path, timeout=30)
        tones[title] = path.read_bytes()
    print(p11.adb('install', '--abi', 'x86_64', apk, timeout=85))
    p11.adb('shell', 'wm', 'size', '720x1560')
    p11.adb('shell', 'wm', 'density', '320')
    airplane(True)
    launch()
    stage(KWAKU, tones[KWAKU][:-40000])  # Header/duration present, but the final media box is incomplete.
    stage(ESPRESSO, tones[ESPRESSO])
    tap('Find')
    tap('Queue ' + KWAKU)
    visible(KWAKU, 'Waiting for connection')
    tap(f'Remove {KWAKU} from queue')
    visible('Queue is clear')
    tap('Find')
    tap('Select ' + KWAKU)
    tap('Select ' + ESPRESSO)
    tap('Queue 2 songs')
    visible(KWAKU, ESPRESSO, 'Waiting for connection')
    stop()
    launch()
    tap('Downloads')
    visible(KWAKU, ESPRESSO, 'Waiting for connection')
    print('::notice::PASS single and selected-track enqueue; both jobs survive force-stop before the transfer')

    airplane(False)
    visible('Recording not verified', 'Saved on this device', timeout=115)
    saved_matches(ESPRESSO, tones[ESPRESSO])
    print('::notice::PASS truncated first recording rejected; second recording saved byte-for-byte')

    airplane(True)
    stop()
    stage(KWAKU, tones[KWAKU])
    launch()
    tap('Downloads')
    visible('Recording not verified', 'Saved on this device')
    tap('Retry ' + KWAKU)
    visible('Waiting for connection', 'Saved on this device')
    airplane(False)
    visible('Queue is clear', KWAKU, ESPRESSO, 'Saved on this device', timeout=65)
    saved_matches(KWAKU, tones[KWAKU])
    print('::notice::PASS failure state survived restart; retry saved the verified first recording')

    stop()
    airplane(True)
    p11.adb('logcat', '-c')
    launch()
    visible('2 songs available offline')
    with page_devtools() as read:
        play_offline(read, 'kwaku', KWAKU)
        play_offline(read, 'espresso', ESPRESSO)
    log = p11.adb('logcat', '-d', '-s', 'BlackBoxOfflineMusic', 'BlackBoxStream')
    if 'whole-file helper ready' in log or 'media helper ready' in log:
        raise RuntimeError('Offline playback unexpectedly started the downloader')
    print('::notice::PASS x86_64 deterministic smoke: individual and batch queue, one bad file/one save, retry, and both verified files played after a force-stop in airplane mode (synthetic audio).')


if __name__ == '__main__':
    try:
        with tempfile.TemporaryDirectory() as root:
            smoke(Path(sys.argv[1]), Path(root))
    except Exception as error:
        print('::error::' + p11.diagnostic(str(error)).replace('\n', ' / '))
        raise
    finally:
        print(p11.adb('logcat', '-d', '-s', 'BlackBoxOfflineMusic', 'BlackBoxStream', 'AndroidRuntime', 'chromium', check=False)[-7500:])
