"""Drive the full v90 APK and native Music player on a disposable x86 emulator.

Fixtures never request provider audio. The non-debuggable ARM64 deliverable cannot use them.
"""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen

from websocket import create_connection

import check_blackbox_p11 as p11

PKG = 'com.blackboxstream.mobile.offline'
ACTIVITY = PKG + '/com.blackboxstream.mobile.V90Activity'
KWAKU, ESPRESSO = 'Kwaku the Traveller', 'Espresso'
TRACKS = {title: (video, seconds) for title, video, seconds in p11.SONGS}


@contextmanager
def page():
    p11.adb('shell', 'am', 'start', '-W', '-n', ACTIVITY, timeout=35)
    pid = p11.adb('shell', 'pidof', '-s', PKG)
    port = int(p11.adb('forward', 'tcp:0', f'localabstract:webview_devtools_remote_{pid}'))
    try:
        until = time.monotonic() + 30
        tab = None
        while time.monotonic() < until:
            try:
                with urlopen(f'http://127.0.0.1:{port}/json', timeout=3) as response:
                    tab = next((t for t in json.load(response) if t.get('url') == 'https://blackbox.invalid/'), None)
                if tab:
                    break
            except (OSError, ValueError):
                pass
            time.sleep(.3)
        if tab is None:
            raise RuntimeError('No original v90 page in the emulator WebView')
        socket = create_connection(tab['webSocketDebuggerUrl'], suppress_origin=True, timeout=10)
        try:
            request = 0

            def js(expression):
                nonlocal request
                request += 1
                socket.send(json.dumps({'id': request, 'method': 'Runtime.evaluate', 'params': {
                    'expression': expression, 'returnByValue': True}}))
                while True:
                    reply = json.loads(socket.recv())
                    if reply.get('id') == request:
                        break
                if 'error' in reply or 'exceptionDetails' in reply.get('result', {}):
                    raise RuntimeError(f'{expression[:140]}: {reply}')
                return reply['result']['result'].get('value')

            yield js
        finally:
            socket.close()
    finally:
        p11.adb('forward', '--remove', f'tcp:{port}', check=False)


def wait(js, check, note, seconds=20):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        result = js(check)
        if result:
            return result
        time.sleep(.28)
    state = js("JSON.stringify({view:window.__BBX_STATE__?.view,menu:document.documentElement.className,"
               "msg:document.querySelector('#musicMsg')?.textContent,queue:window.OfflineMusic&&JSON.parse(OfflineMusic.library()).tracks?.map(t=>[t.title,t.state,t.reason])})")
    raise RuntimeError(f'{note}: {state}')


def click(js, selector):
    if not js(f"(()=>{{const b=document.querySelector({json.dumps(selector)}); if(!b||b.disabled)return false;b.click();return true}})()"):
        raise RuntimeError('Unavailable control ' + selector)


def query(js, text, submit=True):
    js(f"document.getElementById('musicQuery').value={json.dumps(text)};"
       "document.getElementById('musicQuery').dispatchEvent(new Event('input',{bubbles:true}));")
    if submit:
        click(js, '#musicGo')


def airplane(enabled):
    p11.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable' if enabled else 'disable')
    p11.adb('shell', 'svc', 'wifi', 'disable' if enabled else 'enable')
    p11.adb('shell', 'svc', 'data', 'disable' if enabled else 'enable')
    if p11.adb('shell', 'settings', 'get', 'global', 'airplane_mode_on') != str(int(enabled)):
        raise RuntimeError('Android did not change airplane mode')


def release_app():
    p11.adb('shell', 'am', 'force-stop', PKG)


def entries(js):
    return js('JSON.parse(OfflineMusic.library()).tracks')


def stage(track, data):
    video, _ = TRACKS[track['title']]
    directory = 'files/offline-music/staging/' + track['key']
    p11.adb('shell', 'run-as', PKG, 'mkdir', '-p', directory)
    subprocess.run(['adb', 'exec-in', f"run-as {PKG} sh -c 'cat > {directory}/{video}.m4a'"],
                   input=data, check=True, timeout=14)


def verify_bytes(track, data):
    payload = p11.adb('exec-out', 'run-as', PKG, 'cat', f'files/offline-music/saved/{track["key"]}.m4a', binary=True)
    if hashlib.sha256(payload).digest() != hashlib.sha256(data).digest():
        raise RuntimeError('Saved bytes differ for ' + track['title'])


def open_music(js):
    wait(js, "!!document.querySelector('#engineDot.ok') && !!document.querySelector('.bbx-offline-shell')", 'Full v90 shell failed')
    click(js, '#navMusic')
    wait(js, "!document.querySelector('#offline-collection').hidden", 'Collection did not open from v90 Music')


def smoke(apk, root):
    tones = {}
    for i, (title, (video, seconds)) in enumerate(TRACKS.items()):
        target = root / f'{video}.m4a'
        p11.run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', f'sine=frequency={440 + 220*i}:duration={seconds}',
                '-c:a', 'aac', '-movflags', '+faststart', target, timeout=30)
        tones[title] = target.read_bytes()
    p11.adb('install', apk, timeout=100)
    p11.adb('shell', 'wm', 'size', '720x1560')
    p11.adb('shell', 'wm', 'density', '320')
    p11.adb('shell', 'run-as', PKG, 'mkdir', '-p', 'files/offline-music')
    p11.adb('shell', 'run-as', PKG, 'touch', 'files/offline-music/fixture-only')
    airplane(True)
    with page() as js:
        open_music(js)
        click(js, '[data-nav=movies]')
        wait(js, "!document.querySelector('#viewBrowse').hidden && document.querySelector('#browseGrid').textContent.includes('TMDB key') && document.querySelector('#om-tabs').hidden", 'Movies/TMDB navigation regressed')
        click(js, '[data-nav=library]')
        wait(js, "!document.querySelector('#viewLibrary').hidden && document.querySelector('.android-library-tools')", 'Original Library navigation regressed')
        if not js("!!document.querySelector('#navShare') && !!document.querySelector('#settingsWrap[data-platform=android]')"):
            raise RuntimeError('Local Share or native settings missing')
        click(js, '#navMusic')
        click(js, '#om-tab-find')
        wait(js, "!document.querySelector('#viewMusic').hidden && document.querySelector('#viewMusic h1').textContent==='Find your sound.' && document.querySelectorAll('#musicGenreChips button').length===14", 'Find does not show the original v90 Music')
        if not js("!document.querySelector('#music-shelf-trending-now').hidden"):
            raise RuntimeError('Missing v90 trending shelf')
        print('::notice::PASS full v90 launch, Movies, Library and Settings; Find uses the original Music hero, 14 genres and shelves')

        query(js, 'Sabrina Carpenter')
        wait(js, "!!document.querySelector('.music-artist-card-v63') && !!document.querySelector('[data-album-open-v63=\"1752214909\"]')", 'Structured v90 artist and album results missing')
        click(js, '[data-album-open-v63="1752214909"]')
        ordered = js("[...document.querySelectorAll('.music-album-tracklist-v63 strong')].map(n=>n.textContent)")
        if len(ordered) != 12 or ordered[6] != ESPRESSO:
            raise RuntimeError(f'Wrong v90 Short n’ Sweet tracklist: {ordered}')
        click(js, '.music-album-detail-v63 [data-album-download-v63]')
        album = entries(js)
        if [track['title'] for track in album] != ordered or len({t['batch'] for t in album}) != 1:
            raise RuntimeError(f'The complete ordered album was not queued in one batch: {album}')
        for track in album:
            js(f"OfflineMusic.queue('remove',{json.dumps(track['key'])})")
        # Wait for the API-driven status to reach the cards again after direct fixture cleanup.
        click(js, '#musicClear')
        click(js, '#om-select-tools button:first-child')
        query(js, KWAKU)
        first = f'[aria-label="Select {KWAKU}"]'
        wait(js, f'!!document.querySelector("#musicTorrGrid " + {json.dumps(first)})', 'v90 search did not expose Kwaku')
        click(js, '#musicTorrGrid ' + first)
        query(js, ESPRESSO, submit=False)
        second = f'#musicInstantV76 [aria-label="Select {ESPRESSO}"]'
        wait(js, f'!!document.querySelector({json.dumps(second)})', 'Instant v90 results did not expose Espresso')
        click(js, second)
        click(js, '#om-select-tools button:last-child')
        pending = entries(js)
        if [track['title'] for track in pending] != [KWAKU, ESPRESSO] or any(t['state'] != 'offline' for t in pending):
            raise RuntimeError(f'Selected exact tracks are not both queued offline: {pending}')
        print('::notice::PASS v90 artist/album/instant search, complete 12-track album batch, and two exact tracks selected across searches')
    release_app()
    for track in pending:
        audio = tones[track['title']]
        stage(track, audio[:-40000] if track['title'] == KWAKU else audio)
    with page() as js:
        open_music(js)
        if [t['state'] for t in entries(js)] != ['offline', 'offline']:
            raise RuntimeError('Queue did not survive force-stop')
        airplane(False)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.map(t=>t.state).join(',')==='failed,saved'", 'Incomplete first file did not let the next recording finish', 100)
        failed, complete = entries(js)
        if failed['reason'] != 'incomplete':
            raise RuntimeError('First exact file failed for a different reason: ' + failed['reason'])
        verify_bytes(complete, tones[ESPRESSO])
        airplane(True)
        stage(failed, tones[KWAKU])
        click(js, '#om-tab-downloads')
        click(js, f'#om-issues [aria-label="Retry {KWAKU}"]')
        wait(js, "JSON.parse(OfflineMusic.library()).tracks[0].state==='offline'", 'Retry did not respect offline mode')
        airplane(False)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.every(t=>t.state==='saved')", 'Retry did not save the verified file', 65)
        verify_bytes(failed, tones[KWAKU])
        click(js, '#om-tab-collection')
        click(js, '#om-new-playlist')
        js("document.getElementById('om-playlist-name').value='Offline fixture'; document.querySelectorAll('#om-playlist-tracks input').forEach(i=>i.checked=true)")
        click(js, '#om-playlist-save')
        print('::notice::PASS durable queue, bad-file rejection, independent second save, retry and byte-identical verified files')
    release_app()
    airplane(True)
    p11.adb('logcat', '-c')
    with page() as js:
        open_music(js)
        wait(js, "document.querySelector('#om-collection-count').textContent==='2 songs available offline' && !!document.querySelector('[aria-label=\"Play Offline fixture\"]')", 'Offline collection or playlist did not persist')
        for track in pending:
            title, key = track['title'], track['key']
            click(js, f'#om-saved [aria-label="Play {title}"]')
            wait(js, "!document.querySelector('#om-mini').hidden", 'No mini-player')
            click(js, '#om-now')
            duration = int(wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.key==='{key}'&&p.playing&&p.position>700&&p.duration}})()", 'Offline clock did not advance'))
            if abs(duration - 1000*TRACKS[title][1]) > 1000:
                raise RuntimeError('Wrong duration for ' + title)
            js("(()=>{const el=document.getElementById('om-seek');el.value=Number(el.max)*.72;el.dispatchEvent(new Event('input'));el.dispatchEvent(new Event('change'))})()")
            jump = wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.position>{duration//2}&&p.position}})()", 'Offline seek did not reach the far side of the file')
            wait(js, f"JSON.parse(OfflineMusic.player()).position>{jump+800}", 'Clock did not advance after seek')
            click(js, '#om-player-close')
            print(f'::notice::PASS {title} synthetic fixture played and sought after a force-stop in airplane mode')
    if 'pin verified' in p11.adb('logcat', '-d', '-s', 'BlackBoxOfflineMusic'):
        raise RuntimeError('Cold offline listening unexpectedly started the downloader')


if __name__ == '__main__':
    try:
        with tempfile.TemporaryDirectory() as directory:
            smoke(Path(sys.argv[1]), Path(directory))
    except Exception as error:
        print('::error::' + p11.diagnostic(str(error)).replace('\n', ' / '))
        raise
    finally:
        print(p11.adb('logcat', '-d', '-s', 'BlackBoxOfflineMusic', 'AndroidRuntime', 'chromium', check=False)[-8500:])
