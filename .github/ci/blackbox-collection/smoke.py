"""Test the disposable x86 sibling using synthetic in-app audio, not a live provider."""
import hashlib
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import time

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


def read_clock(nodes):
    for node in nodes:
        if node.get('package') != p11.PKG:
            continue
        for text in p11.label(node)[:2]:
            match = re.fullmatch(r'(\d+):(\d\d) / (\d+):(\d\d)', text)
            if match:
                m, s, dm, ds = map(int, match.groups())
                return 60 * m + s, 60 * dm + ds
    return None


def advancing(title, after):
    def okay(nodes):
        clock = read_clock(nodes)
        if clock and clock[0] > after and abs(clock[1] - IDS[title][1]) <= 2 and p11.find(nodes, title) is not None:
            return clock
        return None
    return await_ui(okay, f'{title} did not advance past {after}s in Now Playing', 20)


def play_offline(title):
    tap('Play ' + title)
    visible('Playing offline')
    tap('Open now playing')
    start, full = advancing(title, 0)
    tap_target = await_ui(lambda nodes: p11.find(nodes, 'Seek playback', 'seek'), 'Seek control is missing')
    p11.tap(tap_target, fraction=0.72)
    sought, _ = advancing(title, full // 2)
    advanced, _ = advancing(title, sought)
    tap('Close now playing')
    print(f'PASS cold offline {title}: {start}s → seek {sought}s → {advanced}s of {full}s')


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
    print('PASS single and selected-track enqueue; both jobs survive force-stop before the transfer')

    airplane(False)
    visible('Recording not verified', 'Saved on this device', timeout=115)
    saved_matches(ESPRESSO, tones[ESPRESSO])
    print('PASS truncated first recording rejected; second recording saved byte-for-byte')

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
    print('PASS failure state survived restart; retry saved the verified first recording')

    stop()
    airplane(True)
    p11.adb('logcat', '-c')
    launch()
    visible('2 songs available offline')
    play_offline(KWAKU)
    play_offline(ESPRESSO)
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
