"""Drive the full v90 APK and native Music player on a disposable x86 emulator.

Fixtures never request provider audio. The non-debuggable ARM64 deliverable cannot use them.
"""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from urllib.request import urlopen

from websocket import create_connection

import check_blackbox_p11 as p11

PKG = 'com.blackboxstream.mobile.offline.parallel'
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
    state = js("(()=>{const l=JSON.parse(window.OfflineMusic?.library()||'{}');return JSON.stringify({"
               "view:window.__BBX_STATE__?.view,connected:l.connected,collection:document.querySelector('#om-collection-count')?.textContent,"
               "lists:l.playlists?.map(p=>p.name),queue:l.tracks?.slice(0,4).map(t=>[t.title,t.state,t.reason]),"
               "player:window.OfflineMusic&&JSON.parse(OfflineMusic.player())})})()")
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


def force_stop():
    p11.adb('shell', 'am', 'force-stop', PKG)


@contextmanager
def recording_server(payloads, hold=None):
    """Real helper subprocesses over adb reverse; never contact a music provider."""
    seen = set()

    class Fixture(BaseHTTPRequestHandler):
        def serve(self, send_body):
            data = payloads.get(self.path)
            if data is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'audio/mp4')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            if send_body:
                try:
                    seen.add(self.path)
                    if hold is None:
                        self.wfile.write(data)
                    else:
                        chunk = 128 * 1024
                        cut = 3 * chunk
                        for start in range(0, cut, chunk):
                            self.wfile.write(data[start:start + chunk])
                            self.wfile.flush()
                            time.sleep(.6)  # Let at least one real progress interval pass before holding.
                        if hold.wait(timeout=32):
                            self.wfile.write(data[cut:])
                except (BrokenPipeError, ConnectionResetError):
                    pass  # The generic extractor may close its initial metadata response.

        def do_GET(self):
            self.serve(True)

        def do_HEAD(self):
            self.serve(False)

        def log_message(self, *_):
            pass

    with ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            p11.adb('reverse', 'tcp:19090', 'tcp:' + str(server.server_port))
            yield seen
        finally:
            if hold:
                hold.set()
            p11.adb('reverse', '--remove', 'tcp:19090', check=False)
            server.shutdown()
            thread.join(timeout=5)


def entries(js):
    return js('JSON.parse(OfflineMusic.library()).tracks')


def stage(track, data):
    directory = 'files/offline-music/staging/' + track['key']
    p11.adb('shell', 'run-as', PKG, 'mkdir', '-p', directory)
    subprocess.run(['adb', 'exec-in', f"run-as {PKG} sh -c 'cat > {directory}/audio.m4a'"],
                   input=data, check=True, timeout=14)


def verify_bytes(track, data):
    payload = p11.adb('exec-out', 'run-as', PKG, 'cat', f'files/offline-music/saved/{track["key"]}.m4a', binary=True)
    if hashlib.sha256(payload).digest() != hashlib.sha256(data).digest():
        raise RuntimeError('Saved bytes differ for ' + track['title'])


def open_music(js):
    wait(js, "!!document.querySelector('#engineDot.ok') && !!document.querySelector('.bbx-offline-shell')", 'Full v90 shell failed')
    click(js, '#navMusic')
    wait(js, "!document.querySelector('#offline-collection').hidden", 'Collection did not open from v90 Music')


def metered_connection(js):
    p11.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
    p11.adb('shell', 'svc', 'wifi', 'disable')
    p11.adb('shell', 'svc', 'data', 'enable')
    check = "(()=>{const q=JSON.parse(OfflineMusic.library());return q.connected&&q.metered})()"
    try:
        wait(js, check, 'No Android metered connection after enabling mobile data', 12)
    except RuntimeError:
        # Some runners lack an active virtual modem. Android's Wi-Fi metering policy also
        # supplies a real metered default network; don't fake the result in JavaScript.
        p11.adb('shell', 'svc', 'wifi', 'enable')
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===true', 'No emulator Internet transport')
        networks = p11.adb('shell', 'cmd', 'netpolicy', 'list', 'wifi-networks')
        for network in networks.splitlines():
            name, delimiter, _ = network.rpartition(';')
            if delimiter and name:
                p11.adb('shell', 'cmd', 'netpolicy', 'set', 'metered-network', name, 'true')
        wait(js, check, 'Android did not report a metered Internet connection')


def check_parallel(js, tone):
    for track in entries(js):
        if track['state'] != 'saved':
            js(f"OfflineMusic.queue('remove',{json.dumps(track['key'])})")
    click(js, '#om-tab-downloads')
    js("OfflineMusic.queue('pause','')")
    tracks = [dict(ref=f'fixture:parallel:{k}', title=f'Parallel fixture {k}', artist='Fixture artist',
                   album='Synthetic AAC album', seconds=TRACKS[ESPRESSO][1], number=k, total=6) for k in range(1, 7)]
    request = json.dumps(dict(tracks=tracks, label='Parallel fixture downloads'))
    js(f"OfflineMusic.queue('enqueue',{json.dumps(request)})")
    rows = [t for t in entries(js) if t['ref'].startswith('fixture:parallel:')]
    keys = [t['key'] for t in rows]
    if len(keys) != 6 or any(t['state'] != 'paused' for t in rows):
        raise RuntimeError('Six controlled jobs were not queued')
    if js("/Wi-Fi by default|Use mobile data/.test(document.querySelector('#offline-downloads').textContent)"):
        raise RuntimeError('Obsolete Wi-Fi/mobile download controls still in the UI')
    gate = threading.Event()
    first = [f'/{key}.m4a' for key in keys[:5]]
    with recording_server({f'/{key}.m4a': tone for key in keys}, gate) as seen:
        metered_connection(js)
        click(js, '#om-pause')
        wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.progress()),keys={json.dumps(keys[:5])};"
                 f"return Object.keys(p).length===5 && keys.every(k=>p[k]?.phase==='downloading' && "
                 f"p[k].total==={len(tone)} && p[k].received>0 && p[k].received<p[k].total)}})()",
             'Five real transfers did not overlap with accurate totals on a metered connection', 32)
        wait(js, "document.querySelectorAll('#om-pending [role=progressbar][aria-valuenow]').length===5 && "
                 "[...document.querySelectorAll('#om-pending [role=progressbar]')].every(e=>+e.getAttribute('aria-valuenow')>0 && +e.getAttribute('aria-valuenow')<100)",
             'Downloads did not show five determinate, partial rings', 8)
        if not all(path in seen for path in first) or f'/{keys[5]}.m4a' in seen:
            raise RuntimeError('Concurrent downloads exceeded the five-job window')
        click(js, '#om-pause')
        click(js, '[data-nav=movies]')
        gate.set()
        wait(js, f"(()=>{{const a=JSON.parse(OfflineMusic.library()).tracks.filter(t=>t.ref.startsWith('fixture:parallel:'));"
                 "return a.filter(t=>t.state==='saved').length===5 && a[5].state==='paused'})()",
             'Active Music downloads did not finish independently of the current page')
        click(js, '#navMusic')
        click(js, '#om-tab-downloads')
        click(js, '#om-pause')
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.every(t=>t.state==='saved')", 'The sixth job did not advance on resume')
        if f'/{keys[5]}.m4a' not in seen:
            raise RuntimeError('The last job bypassed the fixture transfer')
        for row in rows:
            verify_bytes(row, tone)
    print('::notice::PASS all five helper processes transferred bytes concurrently on a metered Internet connection; matching rings, pause, and sixth-job handoff')


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
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Android did not enter offline mode')
        click(js, '[data-nav=movies]')
        wait(js, "!document.querySelector('#viewBrowse').hidden && document.querySelector('#browseGrid').textContent.includes('TMDB key') && document.querySelector('#om-tabs').hidden", 'Movies/TMDB navigation regressed')
        click(js, '[data-nav=library]')
        wait(js, "!document.querySelector('#viewLibrary').hidden && !!document.querySelector('.android-library-tools')", 'Original Library navigation regressed')
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
        click(js, '#musicClear')
        query(js, 'Short n Sweet', submit=False)
        instant = '#musicInstantV76:not([hidden]) [data-kind="album"] [aria-label*="Short n"]:not(:disabled)'
        wait(js, f'!!document.querySelector({json.dumps(instant)})', 'Instant album control missing')
        click(js, instant)
        tracks = entries(js)
        if [t['key'] for t in tracks] != [t['key'] for t in album] or len({t['batch'] for t in tracks}) != 1:
            raise RuntimeError('Instant album control did not queue the same exact ordered batch')
        for track in tracks:
            js(f"OfflineMusic.queue('remove',{json.dumps(track['key'])})")
        click(js, '#musicClear')
        click(js, '#musicGenreChips button:first-child')
        wait(js, "!document.querySelector('#viewMusicGenre').hidden && !!document.querySelector('[data-genre-queue]')", 'Original v90 genre page did not open')
        click(js, '[data-genre-queue]')
        genre = entries(js)
        if len(genre) != 30 or len({t['batch'] for t in genre}) != 1:
            raise RuntimeError('Genre Queue all did not produce one 30-track batch')
        for track in genre:
            js(f"OfflineMusic.queue('remove',{json.dumps(track['key'])})")
        click(js, '#om-tab-find')
        query(js, KWAKU)
        single = f'#musicTorrGrid [aria-label="Queue {KWAKU}"]'
        wait(js, f'!!document.querySelector({json.dumps(single)})', 'v90 single-song control missing')
        click(js, single)
        tracks = entries(js)
        if len(tracks) != 1 or tracks[0]['title'] != KWAKU or tracks[0]['state'] != 'offline':
            raise RuntimeError(f'Single song was not explicitly queued: {tracks}')
        js(f"OfflineMusic.queue('remove',{json.dumps(tracks[0]['key'])})")
        wait(js, f'!!document.querySelector({json.dumps(single)})', 'Single-song status did not refresh')
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
        click(js, '#om-pause')
        if not all(t['state'] == 'paused' for t in entries(js)):
            raise RuntimeError('Pause did not stop pending jobs')
        click(js, '#om-pause')
        if not all(t['state'] == 'offline' for t in entries(js)):
            raise RuntimeError('Resume ignored the network restriction')
        if not js("document.querySelector('#musicInstantV76').hidden"):
            raise RuntimeError('Search suggestions leaked into Downloads')
        print('::notice::PASS v90 artist/album/instant search, direct single-song queue, genre batch, both album paths and grouped selection/pause')
    force_stop()
    stage(pending[0], tones[KWAKU][:-40000])
    with recording_server({f"/{TRACKS[ESPRESSO][0]}.m4a": tones[ESPRESSO]}) as received, page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Android reconnected before queue recovery')
        if [t['state'] for t in entries(js)] != ['offline', 'offline']:
            raise RuntimeError('Queue did not survive force-stop')
        airplane(False)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.map(t=>t.state).join(',')==='failed,saved'", 'Incomplete first file did not let the next recording finish', 115)
        failed, complete = entries(js)
        if failed['reason'] != 'incomplete':
            raise RuntimeError('First exact file failed for a different reason: ' + failed['reason'])
        verify_bytes(complete, tones[ESPRESSO])
        if not received:
            raise RuntimeError('The whole-file runtime did not fetch the loopback AAC fixture')
        airplane(True)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Android did not drop its active network before retry')
        stage(failed, tones[KWAKU])
        click(js, '#om-tab-downloads')
        retry = f'#om-issues [aria-label="Retry {KWAKU}"]'
        wait(js, f'!!document.querySelector({json.dumps(retry)})', 'The exact failed recording did not appear in Needs attention')
        click(js, retry)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks[0].state==='offline'", 'Retry did not respect offline mode')
        airplane(False)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.every(t=>t.state==='saved')", 'Retry did not save the verified file', 65)
        verify_bytes(failed, tones[KWAKU])
        click(js, '#om-tab-collection')
        click(js, '#om-new-playlist')
        js("document.getElementById('om-playlist-name').value='Offline fixture'; document.querySelectorAll('#om-playlist-tracks input').forEach(i=>i.checked=true)")
        click(js, '#om-playlist-save')
        if js("document.querySelector('#om-playlist-dialog').open") or not js("JSON.parse(OfflineMusic.library()).playlists.some(l=>l.name==='Offline fixture' && l.keys.length===2)"):
            raise RuntimeError('The playlist did not commit both exact saved songs')
        print('::notice::PASS durable queue, bad-file rejection, helper transfer of the independent second file, retry and byte-identical saves')
    force_stop()
    airplane(True)
    p11.adb('logcat', '-c')
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'The cold playback test phone is not offline')
        wait(js, "document.querySelector('#om-collection-count').textContent==='2 songs available offline' && !!document.querySelector('[aria-label=\"Play Offline fixture\"]')", 'Offline collection or playlist did not persist')
        for track in pending:
            title, key = track['title'], track['key']
            click(js, f'#om-saved [aria-label="Play {title}"]')
            wait(js, "!document.querySelector('#om-mini').hidden", 'No mini-player')
            click(js, '#om-now')
            duration = int(wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.key==='{key}'&&p.playing&&p.position>700&&p.duration}})()", 'Offline clock did not advance'))
            if abs(duration - 1000*TRACKS[title][1]) > 1000:
                raise RuntimeError('Wrong duration for ' + title)
            wait(js, f"document.querySelector('#om-player').open && document.querySelector('#om-now-title').textContent==={json.dumps(title)} && !document.querySelector('#om-seek').disabled", 'Now Playing did not show the ready recording')
            js("(()=>{const el=document.getElementById('om-seek');el.value=Number(el.max)*.72;el.dispatchEvent(new Event('input'));el.dispatchEvent(new Event('change'))})()")
            jump = wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.position>{duration//2}&&p.position}})()", 'Offline seek did not reach the far side of the file')
            wait(js, f"JSON.parse(OfflineMusic.player()).position>{jump+800}", 'Clock did not advance after seek')
            click(js, '#om-player-close')
            print(f'::notice::PASS {title} synthetic fixture played and sought after a force-stop in airplane mode ({duration} ms, seek {jump} ms)')
        click(js, '#om-now')
        click(js, '#om-stop')
        wait(js, "!JSON.parse(OfflineMusic.player()).key", 'Stop did not clear the local player')
        click(js, '#om-tab-find')
        query(js, 'Sabrina Carpenter')
        wait(js, '!!document.querySelector(\'[data-album-open-v63="1752214909"]\')', 'Original album search missing after restart')
        click(js, '[data-album-open-v63="1752214909"]')
        selector = '.music-album-detail-v63 [data-album-download-v63]'
        if js(f'document.querySelector({json.dumps(selector)}).textContent') != 'Queue missing':
            raise RuntimeError('Partial album did not offer Queue missing')
        click(js, '[data-album-play-v63]')
        wait(js, f"JSON.parse(OfflineMusic.player()).key==='{complete['key']}' && JSON.parse(OfflineMusic.player()).playing", 'Partial album did not play its only saved song')
        click(js, selector)
        remaining = [t['title'] for t in entries(js) if t['state'] == 'offline']
        if remaining != [title for title in ordered if title != ESPRESSO]:
            raise RuntimeError(f'Partial album queued the wrong tracks: {remaining}')
        print('::notice::PASS partial album played its saved recording and queued exactly the remaining tracks')
    if 'pin verified' in p11.adb('logcat', '-d', '-s', 'BlackBoxMusic'):
        raise RuntimeError('Cold offline listening unexpectedly started the downloader')
    with page() as js:
        open_music(js)
        check_parallel(js, tones[ESPRESSO])


if __name__ == '__main__':
    try:
        with tempfile.TemporaryDirectory() as directory:
            smoke(Path(sys.argv[1]), Path(directory))
    except Exception as error:
        print('::error::' + p11.diagnostic(str(error)).replace('\n', ' / '))
        raise
    finally:
        print(p11.adb('logcat', '-d', '-s', 'BlackBoxOfflineMusic', 'BlackBoxMusic', 'AndroidRuntime', 'chromium', check=False)[-8500:])
