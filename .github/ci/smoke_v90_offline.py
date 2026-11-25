"""Drive the full v90 APK and native Music player on a disposable x86 emulator.

Fixtures never request provider audio. The non-debuggable ARM64 build cannot use them.
The separate --live diagnostic requests one catalogue song without a fixture or credentials.
"""
import argparse
import base64
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
from urllib.request import urlopen

from websocket import create_connection

import check_blackbox_p11 as p11

PKG = 'com.blackboxstream.mobile.offline.desktop'
SIDE_PKG = 'com.blackboxstream.mobile.offline.background'
ACTIVITY = PKG + '/com.blackboxstream.mobile.V90Activity'
KWAKU, ESPRESSO = 'Kwaku the Traveller', 'Espresso'
TRACKS = {title: (video, seconds) for title, video, seconds in p11.SONGS}


@contextmanager
def page(package=PKG):
    p11.adb('shell', 'am', 'start', '-W', '-n', package + '/com.blackboxstream.mobile.V90Activity', timeout=35)
    pid = p11.adb('shell', 'pidof', '-s', package)
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


def wait(js, check, note, seconds=20, accept=bool):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        result = js(check)
        if accept(result):
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
def recording_server(payloads, holds=None, unknown=(), before_headers=None, started=None, short_body=()):
    """Real helper subprocesses over adb reverse; never contact a music provider."""
    holds = holds or {}
    before_headers = before_headers or {}
    seen = set()

    class Fixture(BaseHTTPRequestHandler):
        def serve(self, send_body):
            data = payloads.get(self.path)
            if data is None:
                self.send_error(404)
                return
            if started is not None:
                started.add(self.path)
            gate = before_headers.get(self.path)
            if gate is not None and not gate.wait(timeout=18):
                self.send_error(504)
                return
            if isinstance(data, int):
                self.send_error(data)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'audio/mp4')
            if self.path not in unknown:
                self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            if send_body:
                try:
                    seen.add(self.path)
                    if self.path in short_body:
                        self.wfile.write(data[:len(data)//2])
                        return  # The announced Content-Length is larger; the helper must fail.
                    hold = holds.get(self.path)
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
            for hold in set(holds.values()) | set(before_headers.values()):
                hold.set()
            p11.adb('reverse', '--remove', 'tcp:19090', check=False)
            server.shutdown()
            thread.join(timeout=5)


def entries(js):
    return js('JSON.parse(OfflineMusic.library()).tracks')


def write_staged(track, name, data):
    directory = 'files/offline-music/staging/' + track['key']
    p11.adb('shell', 'run-as', PKG, 'mkdir', '-p', directory)
    # Use the shell protocol so ADB waits for the device write to close. A raw exec-in
    # stream can lose its trailing bytes or fail to reflect the device exit code.
    target = f'{directory}/{name}'
    subprocess.run(['adb', 'shell', '-T', f"run-as {PKG} sh -c 'base64 -d > {target}'"],
                   input=base64.b64encode(data), check=True, timeout=14)
    verify_on_device(target, data, 'Staged ' + track['title'])


def stage(track, data):
    write_staged(track, 'audio.m4a', data)

def verify_on_device(path, data, label):
    # Hash the bytes in app storage, rather than reading a large binary buffer through ADB.
    output = p11.adb('shell', 'run-as', PKG, 'sha256sum', path)
    expected = hashlib.sha256(data).hexdigest()
    if not output.startswith(expected + ' '):
        raise RuntimeError(f'{label} bytes differ (expected {expected}, saw {output[:64]})')


def verify_bytes(track, data):
    verify_on_device(f'files/offline-music/saved/{track["key"]}.m4a', data, 'Saved ' + track['title'])


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
        print('Metered test transport: emulator cellular')
    except RuntimeError:
        # Some runners lack an active virtual modem. Set the real Android metering policy
        # before enabling Wi-Fi, so a queued job cannot start on an unmetered connection.
        networks = p11.adb('shell', 'cmd', 'netpolicy', 'list', 'wifi-networks')
        for network in networks.splitlines():
            name, delimiter, _ = network.rpartition(';')
            if delimiter and name:
                p11.adb('shell', 'cmd', 'netpolicy', 'set', 'metered-network', name, 'true')
        p11.adb('shell', 'svc', 'wifi', 'enable')
        wait(js, check, 'Android did not report a metered Internet connection')
        print('Metered test transport: emulator Wi-Fi marked metered by Android')


def check_parallel(js, tone):
    for track in entries(js):
        if track['state'] != 'saved':
            js(f"OfflineMusic.queue('remove',{json.dumps(track['key'])})")
    click(js, '#om-tab-downloads')
    wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'The reconnect test did not begin offline')
    tracks = [dict(ref=f'fixture:parallel:{k}', title=f'Parallel fixture {k}', artist='Fixture artist',
                   album='Synthetic AAC album', seconds=TRACKS[ESPRESSO][1], number=k, total=6) for k in range(1, 7)]
    request = json.dumps(dict(tracks=tracks, label='Parallel fixture downloads'))
    js(f"OfflineMusic.queue('enqueue',{json.dumps(request)})")
    rows = [t for t in entries(js) if t['ref'].startswith('fixture:parallel:')]
    keys = [t['key'] for t in rows]
    if len(keys) != 6 or any(t['state'] != 'offline' for t in rows):
        raise RuntimeError('Six controlled jobs did not wait for an Internet connection')
    if js("/Wi-Fi by default|Use mobile data/.test(document.querySelector('#offline-downloads').textContent)"):
        raise RuntimeError('Obsolete Wi-Fi/mobile download controls still in the UI')
    gate, last_gate = threading.Event(), threading.Event()
    paths = [f'/{key}.m4a' for key in keys]
    holds = {path: gate if i < 5 else last_gate for i, path in enumerate(paths)}
    first, second = threading.Event(), threading.Event()
    started = set()
    with recording_server(dict.fromkeys(paths, tone), holds, unknown=(paths[-1],),
                          before_headers={paths[0]: first, paths[1]: second}, started=started) as seen:
        metered_connection(js)  # Pending jobs must start automatically, without a Resume tap.
        wait(js, f"Object.keys(JSON.parse(OfflineMusic.progress())).sort().join(',')==="
             f"{json.dumps(','.join(sorted(keys[:2])))}", 'Two unresolved jobs did not start on reconnect', 11,
             accept=lambda ready: ready and paths[0] in started and paths[1] in started)
        if started != set(paths[:2]):
            raise RuntimeError('More than two unresolved jobs made a request')
        first.set()
        wait(js, f"JSON.parse(OfflineMusic.progress())[{json.dumps(keys[0])}]?.phase==='downloading'",
             'The next resolver did not start while the first transfer was still in flight', 10,
             accept=lambda ready: ready and paths[2] in started)
        second.set()
        wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.progress()),keys={json.dumps(keys[:5])};"
                 f"return Object.keys(p).length===5 && keys.every(k=>p[k]?.phase==='downloading' && "
                 f"p[k].total==={len(tone)} && p[k].received>0 && p[k].received<p[k].total)}})()",
             'Five real transfers did not overlap with accurate totals on a metered connection', 32)
        wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.progress()),keys={json.dumps(keys[:5])};"
                 "const rings=[...document.querySelectorAll('#om-pending [role=progressbar][aria-valuenow]')];"
                 "return rings.length===5 && rings.every((e,i)=>{const j=p[keys[i]],n=+e.getAttribute('aria-valuenow');"
                 "return j && n>0 && n<100 && n===Math.floor(j.received/j.total*100)})})()",
             'Downloads did not show five rings with the actual transfer percentages', 8)
        if not all(path in seen for path in paths[:5]) or paths[-1] in seen:
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
        wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.progress())[{json.dumps(keys[-1])}];"
                 "const r=document.querySelector('#om-pending [role=progressbar]');"
                 "return p?.received>0 && p.total===0 && p.phase==='downloading' && "
                 "r?.classList.contains('is-indeterminate') && !r.hasAttribute('aria-valuenow') && "
                 "r.getAttribute('aria-valuetext').includes('Downloading')"
                 "})()", 'Unknown-length transfer did not show an indeterminate circle', 22)
        last_gate.set()
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.every(t=>t.state==='saved')", 'The sixth job did not finish')
        if paths[-1] not in seen:
            raise RuntimeError('The last job bypassed the fixture transfer')
        for row in rows:
            verify_bytes(row, tone)
    print('::notice::PASS two unresolved jobs at a time, then five overlapping transfers with byte-accurate rings, pause, and sixth indeterminate transfer')


def check_find(js):
    query(js, 'Joro Wizkid', submit=False)
    wait(js, "!!document.querySelector('#musicInstantV76:not([hidden]) [aria-label=\"Queue Joro\"]')",
         'Curated Joro single missing from instant Find')
    query(js, 'Calm Down Rave & Roses Ultra')
    wait(js, "[...document.querySelectorAll('#musicTorrGrid .music-catalog-track-v63')].filter(row=>"
             "row._omTrack?.title==='Calm Down').map(row=>row._omTrack.seconds).sort().join(',')==='220,239'",
         'Submitted Find hid a Calm Down edition')
    if not js("[...document.querySelectorAll('#musicTorrGrid [data-om-track]')].some(row=>"
              "row._omTrack.ref==='album:d432510347:1:22' && row.textContent.includes('Selena Gomez'))"):
        raise RuntimeError('Verified duet credit did not render on the exact album row')
    query(js, 'Calm Down Selena Gomez', submit=False)
    selector = '#musicInstantV76:not([hidden]) [data-kind="song"]'
    wait(js, f"[...document.querySelectorAll({json.dumps(selector)})].map(row=>row._omTrack?.seconds).join(',')==='239,239'",
         'Instant duet search missed the two 3:59 rows or suggested the solo')
    if not js(f"(()=>{{const row=[...document.querySelectorAll({json.dumps(selector)})].find(item=>"
              "item._omTrack?.ref==='album:d432510347:1:22'); const b=row?.querySelector('[data-om-add]');"
              "if(!b||b.disabled)return false; b.click();return true})()"):
        raise RuntimeError('Cannot queue the verified duet album row')
    row, = entries(js)
    if (row['ref'], row['artist'], row['state']) != ('album:d432510347:1:22', 'Rema', 'offline'):
        raise RuntimeError('Presentation changed the durable queue identity')
    js(f"OfflineMusic.queue('remove',{json.dumps(row['key'])})")
    query(js, 'zzzz no such recording')
    wait(js, "document.querySelector('#musicMsg').textContent.includes('No match in the included v90 catalogue')"
             " && !document.querySelector('#musicTorrGrid [data-om-track]')", 'Find substituted for a missing catalogue entry')
    click(js, '#musicClear')
    print('::notice::PASS instant and submitted Find keep curated singles and both Calm Down editions, without altering Downloads keys')


def check_cold_seek(js, track, seconds):
    title, key = track['title'], track['key']
    click(js, f'#om-saved [aria-label="Play {title}"]')
    wait(js, "!document.querySelector('#om-mini').hidden", 'No mini-player')
    click(js, '#om-now')
    duration = int(wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.key==='{key}'"
                            "&&p.playing&&p.position>700&&p.duration})()", 'Offline clock did not advance'))
    if abs(duration - 1000*seconds) > 1000:
        raise RuntimeError('Wrong duration for ' + title)
    wait(js, f"document.querySelector('#om-player').open && document.querySelector('#om-now-title').textContent==={json.dumps(title)}"
             " && !document.querySelector('#om-seek').disabled", 'Now Playing did not show the ready recording')
    js("(()=>{const el=document.getElementById('om-seek');el.value=Number(el.max)*.72;"
       "el.dispatchEvent(new Event('input'));el.dispatchEvent(new Event('change'))})()")
    jump = wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.position>{duration//2}&&p.position}})()",
                'Offline seek did not reach the far side of the file')
    wait(js, f"JSON.parse(OfflineMusic.player()).position>{jump+800}", 'Clock did not advance after seek')
    click(js, '#om-player-close')
    print(f'::notice::PASS {title} synthetic fixture played and sought after a force-stop in airplane mode ({duration} ms, seek {jump} ms)')


def wait_android(probe, accept, failure, seconds=20):
    deadline = time.monotonic() + seconds
    last = ''
    while time.monotonic() < deadline:
        last = probe()
        if accept(last):
            return last
        time.sleep(.3)
    raise RuntimeError(f'{failure}: {last[-650:]}')


def music_session():
    dump = p11.adb('shell', 'dumpsys', 'media_session')
    for match in re.finditer(r'^( *)BlackBoxOfflineMusic ' + re.escape(PKG) + r'/[^\n]*\n', dump, re.M):
        details = re.match(r'(?:' + match[1] + r'  [^\n]*(?:\n|$))+', dump[match.end():])
        if details:
            return details[0]
    return ''


def check_background():
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Background playback check requires an offline device')
        data = json.loads(js('OfflineMusic.library()'))
        keys, = [p['keys'] for p in data['playlists'] if p['name'] == 'Offline fixture']
        by_key = {t['key']: t for t in data['tracks']}
        first, second = (by_key[key] for key in keys)
        click(js, '[aria-label="Play Offline fixture"]')
        duration = wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.key==='{first['key']}'&&p.playing&&p.duration}})()",
                        'Saved playlist did not start')
        tail = duration - 9000
        js(f"OfflineMusic.playback('seek','{tail}')")
        wait(js, f"JSON.parse(OfflineMusic.player()).position>={tail - 300}", 'Background playlist seek did not land')

    # Leave BlackBox for a different app. From here, use system media state rather than
    # WebView timers, which intentionally stop while the activity is hidden.
    p11.adb('shell', 'am', 'start', '-W', '-a', 'android.settings.SETTINGS', timeout=35)
    settings_on_top = lambda dump: bool(re.search(r'(?:topResumedActivity|mResumedActivity).*com.android.settings/', dump))
    activity_dump = lambda: p11.adb('shell', 'dumpsys', 'activity', 'activities')
    wait_android(activity_dump, settings_on_top, 'Android Settings did not take the foreground')
    service_dump = lambda: p11.adb('shell', 'dumpsys', 'activity', 'services', PKG)
    wait_android(service_dump, lambda d: 'OfflineMusicService' in d and 'isForeground=true' in d,
                 'Saved audio lost its media playback foreground service')

    def in_state(state):
        # Newer Android dumps label the state (e.g. PLAYING(3)) instead of printing only its number.
        pattern = re.compile(rf'\bPlaybackState \{{state=(?:[A-Z_]+\()?{state}\b')
        return lambda dump: ('active=true' in dump and pattern.search(dump)
                             and any(second['title'] in line for line in dump.splitlines() if line.strip().startswith('metadata:')))

    wait_android(music_session, in_state(3), 'Saved playlist did not reach its next track behind Settings')
    p11.adb('shell', 'cmd', 'media_session', 'dispatch', 'pause')
    wait_android(music_session, in_state(2), 'Android media Pause did not pause the background track')
    p11.adb('shell', 'cmd', 'media_session', 'dispatch', 'play')
    wait_android(music_session, in_state(3), 'Android media Play did not resume behind Settings')
    if not settings_on_top(activity_dump()):
        raise RuntimeError('Background transport controls unexpectedly opened the app')
    with page() as js:
        open_music(js)
        wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.key==='{second['key']}' && p.playing && p.position>750}})()",
             'Returning from Settings did not attach to the playing track')
        click(js, '#om-now')
        click(js, '#om-stop')
        wait(js, '!JSON.parse(OfflineMusic.player()).key', 'In-app Stop did not clear background playback')
    wait_android(service_dump, lambda d: 'OfflineMusicService' not in d, 'Stop did not release the media service')
    print('::notice::PASS saved playlist advanced while Settings was open offline, media Pause/Play worked, and Stop released the service')


def check_unpinned_album(tone, tone_seconds):
    title, ref, seconds = 'The Victory Song', 'album:d735721521:1:1', 175
    airplane(True)
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Queue is not offline for album fixture setup')
        if any(t['ref'] == ref or t['state'] != 'saved' for t in entries(js)):
            raise RuntimeError('Unpinned album trial requires a fresh slot with no other pending job')
        click(js, '#om-tab-find')
        query(js, 'Black Sherif The Victory Song')
        control = '#musicTorrGrid [aria-label="Queue The Victory Song"]:not(:disabled)'
        wait(js, f'!!document.querySelector({json.dumps(control)})', 'The exact IRON BOY row is not listed in Find')
        click(js, control)
        row, = [t for t in entries(js) if t['ref'] == ref]
        if (row['title'], row['artist'], row['album'], row['seconds'], row['state']) != (
                title, 'Black Sherif', 'IRON BOY', seconds, 'offline'):
            raise RuntimeError('Find lost the exact album recording identity')
    key = row['key']
    for file in (f'files/offline-music/staging/{key}', f'files/offline-music/saved/{key}.m4a'):
        p11.adb('shell', 'run-as', PKG, 'test', '!', '-e', file)
    # An unpinned PC listing, no separate watch metadata. The completed tone has
    # neither the catalogue duration nor the selected listing's reported duration.
    selected, remix = 'AUDIO000004', 'REMIX000004'
    source = dict(id=selected, title=title, channel='Black Sherif - Topic', duration=201)
    alternate = dict(source, id=remix, title=title + ' (Remix)', duration=seconds)
    metadata = json.dumps(dict(entries=[alternate, source])).encode('utf8')
    write_staged(row, 'pc-fixture.json', metadata)
    route = f'/{selected}.m4a'
    with recording_server({route: tone, f'/{remix}.m4a': tone}) as requests, page() as js:
        airplane(False)
        wait(js, f"JSON.parse(OfflineMusic.library()).tracks.some(t=>t.key==='{key}'&&t.state==='saved')",
             'The completed helper output was not published without verification', 75)
        if requests != {route}:
            raise RuntimeError('The desktop selector did not transfer just the chosen source')
        verify_bytes(row, tone)
    force_stop()
    airplane(True)
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Cold album playback was not offline')
        check_cold_seek(js, row, tone_seconds)
    print('PASS a PC-style unpinned source saved and played without a second metadata or duration check')


def check_provider_refusal():
    airplane(True)
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Refusal trial started too early')
        record = dict(ref='fixture:provider-refused', title='Synthetic refusal', artist='Test artist',
                      album='Test album', seconds=172)
        request = json.dumps(dict(tracks=[record], label=''))
        js(f"OfflineMusic.queue('enqueue',{json.dumps(request)})")
        row, = [t for t in entries(js) if t['ref'] == record['ref']]
        if row['state'] != 'offline':
            raise RuntimeError('Synthetic refusal row did not wait for the fixture server')
        key = row['key']
        route = f'/{key}.m4a'
        started = set()
        click(js, '#om-tab-downloads')
        with recording_server({route: 403}, started=started):
            airplane(False)
            wait(js, f"(()=>{{const t=JSON.parse(OfflineMusic.library()).tracks.find(t=>t.key==='{key}');"
                     "return t.state==='failed' && t.reason==='refused' && !t.retryable})()",
                 'A provider refusal was treated as a retryable lookup or transfer error', 35)
            if started != {route}:
                raise RuntimeError('The helper did not reach the synthetic refusal')
        wait(js, "document.querySelector('#om-issues').textContent.includes('Provider refused this recording')"
                 " && !document.querySelector('[aria-label=\"Retry Synthetic refusal\"]')",
             'Downloads did not show a non-retryable refusal')
        p11.adb('shell', 'run-as', PKG, 'test', '!', '-e', f'files/offline-music/saved/{key}.m4a')
        print('PASS explicit HTTP refusal stayed unsaved and appeared separately from interruptions')


def check_update(apk, tone, seconds):
    """Use two debug builds with the same signer to check the existing 1.5.4 Collection."""
    records = [dict(ref=f'fixture:update:{i}', title=title, artist='Test artist', album='Update test',
                    seconds=duration) for i, (title, duration) in enumerate(
                        [('Saved before update', seconds), ('Flagged before update', seconds + 26)])]
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Update fixture requires offline setup')
        if entries(js) or js('JSON.parse(OfflineMusic.library()).playlists.length'):
            raise RuntimeError('The update check requires a fresh 1.5.4 debug installation')
        request = json.dumps(dict(tracks=records, label=''))
        js(f"OfflineMusic.queue('enqueue',{json.dumps(request)})")
        saved, flagged = entries(js)
        route = f'/{flagged["key"]}.m4a'
        routes = {f'/{saved["key"]}.m4a': tone, route: tone}
        with recording_server(routes) as requests:
            airplane(False)
            wait(js, "JSON.parse(OfflineMusic.library()).tracks.map(t=>t.state).join(',')==='saved,failed'",
                 '1.5.4 did not flag the duration-mismatched fixture', 90)
            if requests != set(routes) or entries(js)[1]['reason'] != 'incomplete':
                raise RuntimeError('The control did not reach the extra file check')
            verify_bytes(saved, tone)
            airplane(True)
            wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Update trial is not offline')
        playlist = json.dumps(dict(name='Before update', keys=[saved['key']]))
        before = json.loads(js(f"OfflineMusic.queue('playlist-add',{json.dumps(playlist)})"))
        list_id = before['playlists'][0]['id']

    force_stop()
    p11.adb('install', '-r', apk, timeout=100)
    with page() as js:
        open_music(js)
        after = json.loads(js('OfflineMusic.library()'))
        if after['connected'] or (after['tracks'], after['playlists']) != (before['tracks'], before['playlists']):
            raise RuntimeError('An in-place update changed the saved, flagged, or playlist records')
        verify_bytes(saved, tone)
        check_cold_seek(js, saved, seconds)
        click(js, '#om-now')
        click(js, '#om-stop')
        click(js, '#om-tab-downloads')
        retry = '#om-issues [aria-label="Retry Flagged before update"]'
        wait(js, f'!!document.querySelector({json.dumps(retry)})', 'Old verification flag lost its Retry control')
        with recording_server({route: tone}) as requests:
            click(js, retry)
            if entries(js)[1]['state'] != 'offline':
                raise RuntimeError('Retry did not wait for the connection')
            airplane(False)
            wait(js, "JSON.parse(OfflineMusic.library()).tracks.every(t=>t.state==='saved')",
                 'The same completed audio was flagged again after an update', 65)
            if requests != {route}:
                raise RuntimeError('Retry did not fetch exactly the flagged recording')
            verify_bytes(flagged, tone)
            airplane(True)
            wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Fixture cleanup is not offline')
        js(f"OfflineMusic.queue('playlist-remove',{json.dumps(list_id)})")
        for row in (saved, flagged):
            js(f"OfflineMusic.queue('delete',{json.dumps(row['key'])})")
        if entries(js) or js('JSON.parse(OfflineMusic.library()).playlists.length'):
            raise RuntimeError('Update fixtures were not removed before the rest of the smoke test')
    print('::notice::PASS same-signer 1.5.4 update kept saved bytes, playlist and Retry; the same audio previously flagged now saved')


def smoke(apk, root, upgrade_from=None, side_by_side=None):
    tones = {}
    for i, (title, (video, seconds)) in enumerate(TRACKS.items()):
        target = root / f'{video}.m4a'
        p11.run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', f'sine=frequency={440 + 220*i}:duration={seconds}',
                '-c:a', 'aac', '-movflags', '+faststart', target, timeout=30)
        tones[title] = target.read_bytes()
    p11.adb('install', upgrade_from or apk, timeout=100)
    p11.adb('shell', 'wm', 'size', '720x1560')
    p11.adb('shell', 'wm', 'density', '320')
    p11.adb('shell', 'run-as', PKG, 'mkdir', '-p', 'files/offline-music')
    p11.adb('shell', 'run-as', PKG, 'touch', 'files/offline-music/fixture-only')
    airplane(True)
    if upgrade_from:
        check_update(apk, tones[ESPRESSO], TRACKS[ESPRESSO][1])
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
        check_find(js)

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
    # A complete-looking file in staging is not a completed helper run in this attempt.
    stage(pending[0], tones[KWAKU])
    kwaku_route = f'/{TRACKS[KWAKU][0]}.m4a'
    short = {kwaku_route}
    routes = {f'/{video}.m4a': tones[title] for title, (video, _) in TRACKS.items()}
    with recording_server(routes, short_body=short) as received, page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Android reconnected before queue recovery')
        if [t['state'] for t in entries(js)] != ['offline', 'offline']:
            raise RuntimeError('Queue did not survive force-stop')
        airplane(False)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.map(t=>t.state).join(',')==='failed,saved'",
             'A failed transfer was published or stopped the independent job', 115)
        failed, complete = entries(js)
        if not failed['retryable'] or received != set(routes):
            raise RuntimeError('Stale staging bypassed the intended interrupted transfer')
        p11.adb('shell', 'run-as', PKG, 'test', '!', '-e', f'files/offline-music/saved/{failed["key"]}.m4a')
        verify_bytes(complete, tones[ESPRESSO])
        airplane(True)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Android did not drop its active network before retry')
        click(js, '#om-tab-downloads')
        retry = f'#om-issues [aria-label="Retry {KWAKU}"]'
        wait(js, f'!!document.querySelector({json.dumps(retry)})', 'The exact failed recording did not appear in Needs attention')
        click(js, retry)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks[0].state==='offline'", 'Retry did not respect offline mode')
        short.clear()
        received.clear()
        airplane(False)
        wait(js, "JSON.parse(OfflineMusic.library()).tracks.every(t=>t.state==='saved')", 'Retry did not save the completed file', 65)
        if kwaku_route not in received:
            raise RuntimeError('Retry did not request the full recording')
        verify_bytes(failed, tones[KWAKU])
        click(js, '#om-tab-collection')
        click(js, '#om-new-playlist')
        js("document.getElementById('om-playlist-name').value='Offline fixture'; document.querySelectorAll('#om-playlist-tracks input').forEach(i=>i.checked=true)")
        click(js, '#om-playlist-save')
        if js("document.querySelector('#om-playlist-dialog').open") or not js("JSON.parse(OfflineMusic.library()).playlists.some(l=>l.name==='Offline fixture' && l.keys.length===2)"):
            raise RuntimeError('The playlist did not commit both exact saved songs')
        print('::notice::PASS durable queue, stale staging discarded, interrupted helper transfer not published, verified retry bytes')
    force_stop()
    airplane(True)
    p11.adb('logcat', '-c')
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'The cold playback test phone is not offline')
        wait(js, "document.querySelector('#om-collection-count').textContent==='2 songs available offline' && !!document.querySelector('[aria-label=\"Play Offline fixture\"]')", 'Offline collection or playlist did not persist')
        for track in pending:
            check_cold_seek(js, track, TRACKS[track['title']][1])
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
    if 'Initializing packaged v90 helper' in p11.adb('logcat', '-d', '-s', 'BlackBoxMusic'):
        raise RuntimeError('Cold offline listening unexpectedly started the downloader')
    print('::notice::PASS cold offline listening did not initialize the download helper')
    check_background()
    with page() as js:
        open_music(js)
        check_parallel(js, tones[ESPRESSO])
    album_tone = root / 'album-control.m4a'
    p11.run('ffmpeg', '-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=960:duration=213',
            '-c:a', 'aac', '-movflags', '+faststart', album_tone, timeout=30)
    check_unpinned_album(album_tone.read_bytes(), 213)
    check_provider_refusal()
    if side_by_side:
        force_stop()
        airplane(True)
        p11.adb('install', side_by_side, timeout=100)
        with page(SIDE_PKG) as js:
            open_music(js)
            if entries(js):
                raise RuntimeError('A separate install shared existing private Collection data')
        with page() as js:
            open_music(js)
            for track in pending:
                verify_bytes(track, tones[track['title']])
        print('::notice::PASS separate install opened the Offline app and left the original Collection intact')


def live_trial(apk):
    """One cold request from the original Find. A refusal is a result, not a retry trigger."""
    title = 'Goodness of God (Live)'
    p11.adb('install', apk, timeout=100)
    p11.adb('shell', 'run-as', PKG, 'mkdir', '-p', 'files/offline-music')
    p11.adb('shell', 'run-as', PKG, 'touch', 'files/offline-music/provider-probe')
    airplane(False)
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===true', 'No Android Internet transport for live trial')
        click(js, '#om-tab-find')
        chip = '#musicGenreChips button[title="Open 30 Gospel & Inspirational picks"]'
        wait(js, f'!!document.querySelector({json.dumps(chip)})', 'Original Gospel shelf missing')
        click(js, chip)
        queue = f'#viewMusicGenre [data-genre-catalog] [aria-label="Queue {title}"]:not(:disabled)'
        wait(js, f'!!document.querySelector({json.dumps(queue)})', 'Reported CeCe Winans row not in Find')
        started = time.monotonic()
        click(js, queue)
        row, = entries(js)
        if (row['ref'], row['title'], row['artist'], row['album'], row['seconds']) != (
                'pick:1550189769', title, 'CeCe Winans', 'Believe For It', 298):
            raise RuntimeError('The live trial did not select the original catalogue recording')
        first_byte = None

        def finished(status):
            nonlocal first_byte
            if first_byte is None and status['bytes'] > 0:
                first_byte = time.monotonic() - started
            return status['row']['state'] in ('saved', 'failed')

        completed = wait(js, "(()=>{const r=JSON.parse(OfflineMusic.library()).tracks[0];"
                             "return {row:r,bytes:JSON.parse(OfflineMusic.progress())[r.key]?.received||0}})()",
                         'Live catalogue transfer did not finish within the helper budget', 265, finished)['row']
        elapsed = time.monotonic() - started
        if completed['state'] != 'saved':
            log = p11.adb('logcat', '-d', '-s', 'BlackBoxMusic')
            probe = '\n'.join(l.split('BBX_PUBLIC_PROBE:', 1)[1] for l in log.splitlines() if 'BBX_PUBLIC_PROBE:' in l)
            raise RuntimeError(f"Live provider trial: {title} not saved after {elapsed:.1f}s ({completed['reason']}). "
                               f"No fallback. Publisher sample: {probe[:620] or 'No metadata.'}")
        print(f'::notice::Live provider file verified after {elapsed:.1f}s' +
              (f'; first observed bytes after {first_byte:.1f}s' if first_byte is not None else ''))
    force_stop()
    airplane(True)
    with page() as js:
        open_music(js)
        wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Live playback check is not offline')
        play = f'#om-saved [aria-label="Play {title}"]'
        wait(js, f'!!document.querySelector({json.dumps(play)})', 'Live recording did not survive restart')
        click(js, play)
        wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.key==='{row['key']}' && p.playing && p.position>700}})()",
             'The live recording did not play offline')
        print('::notice::PASS real catalogue lookup, verified save, and cold offline playback of Goodness of God (Live)')


if __name__ == '__main__':
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('apk', type=Path)
    mode = args.add_mutually_exclusive_group()
    mode.add_argument('--live', action='store_true')
    mode.add_argument('--upgrade-from', type=Path, help='1.5.4 debug APK signed with the same test key')
    args.add_argument('--side-by-side', type=Path, help='x86 sibling of the separately installable release')
    request = args.parse_args()
    try:
        if request.live:
            live_trial(request.apk)
        else:
            with tempfile.TemporaryDirectory() as directory:
                smoke(request.apk, Path(directory), request.upgrade_from, request.side_by_side)
    except Exception as error:
        print('::error::' + p11.diagnostic(str(error)).replace('\n', ' / '))
        raise
    finally:
        print(p11.adb('logcat', '-d', '-s', 'BlackBoxOfflineMusic', 'BlackBoxMusic', 'AndroidRuntime', 'chromium', check=False)[-8500:])
