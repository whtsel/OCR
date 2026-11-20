"""Private loopback for the helper-only tester. Resolve once, then serve audio by range.

The provider supplies an existing audio-only representation. This does not download and
transcode the upload before playback, or expose the provider's temporary URL to WebView.
"""
import argparse
from collections import OrderedDict
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import re
import secrets
import subprocess
import sys
from tempfile import TemporaryFile
from threading import BoundedSemaphore, Lock
import time
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, ProxyHandler, Request
from weakref import finalize


PIN = 'e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3'
ID = re.compile(r'[A-Za-z0-9_-]{11}\Z')
MAX_BYTES = 24 * 1024 * 1024
MAX_SECONDS = 1800
BLOCK = 8192
ASSETS = {'': ('index.html', 'text/html; charset=utf-8'),
          'helper-phone.js': ('helper-phone.js', 'text/javascript; charset=utf-8'),
          'helper-phone.css': ('helper-phone.css', 'text/css; charset=utf-8')}


class AudioError(Exception):
    pass


def provider_error(text):
    value = text.lower()
    if '429' in value or 'too many requests' in value:
        return AudioError('YouTube rate-limited this request.')
    if 'sign in' in value or 'not a bot' in value or 'cookies' in value:
        return AudioError('YouTube asked for sign-in or a bot check.')
    if '403' in value or 'not available' in value or 'private video' in value:
        return AudioError('YouTube did not allow this upload.')
    if 'timed out' in value or 'resolve' in value or 'connection' in value:
        return AudioError('Connection to YouTube did not complete.')
    return AudioError('The helper did not get a usable response from YouTube.')


def length_matches(value, expected):
    return (type(value) in (int, float) and 0 < value <= MAX_SECONDS + 6
            and abs(value - expected) <= min(6, max(1, expected * .04)))


def permitted_url(url):
    if not isinstance(url, str) or len(url) > 12000 or any(ord(c) <= 32 or ord(c) == 127 for c in url):
        return False
    try:
        parts = urlsplit(url)
        host = parts.hostname or ''
        return (parts.scheme == 'https' and (host == 'googlevideo.com' or host.endswith('.googlevideo.com'))
                and parts.port in (None, 443) and parts.username is None and parts.password is None and not parts.fragment)
    except ValueError:
        return False


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class RemoteAudio:
    def __init__(self, format_, mime):
        self.url = format_['url']
        self.mime = mime
        # Copy only the extractor's ordinary UA, not cookies, auth or arbitrary headers.
        agent = (format_.get('http_headers') or {}).get('User-Agent', '')
        self.headers = {'Accept-Encoding': 'identity'}
        if isinstance(agent, str) and re.fullmatch(r'[\x20-\x7e]{1,512}', agent):
            self.headers['User-Agent'] = agent
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        with self.open(0, 0) as probe:
            self.size = self.check(probe, 0, 0)
            if len(probe.read(1)) != 1:
                raise AudioError('The audio server did not return the requested bytes.')

    def open(self, first, last):
        try:
            return self.opener.open(Request(self.url, headers={**self.headers, 'Range': f'bytes={first}-{last}'}), timeout=8)
        except HTTPError as problem:
            problem.close()
            raise provider_error(str(problem.code)) from None
        except OSError:
            raise AudioError('Connection to the audio server did not complete.') from None

    def check(self, response, first, last):
        span = re.fullmatch(r'bytes (\d{1,9})-(\d{1,9})/(\d{1,9})', response.headers.get('Content-Range', ''))
        media = response.headers.get_content_type()
        if (response.status != 206 or not span or tuple(map(int, span.groups()[:2])) != (first, last)
                or response.headers.get('Content-Length') != str(last - first + 1)
                or response.headers.get('Content-Encoding', 'identity').lower() != 'identity'
                or media not in (self.mime, 'application/octet-stream')):
            raise AudioError('The provider did not offer seekable audio by byte range.')
        size = int(span[3])
        if not last < size <= MAX_BYTES:
            raise AudioError('This audio file is outside the helper size limit.')
        return size

    @contextmanager
    def read(self, first, last):
        with self.open(first, last) as response:
            if self.check(response, first, last) != self.size:
                raise AudioError('The provider changed this audio file during playback.')
            yield response


class Provider:
    def __init__(self, pin, quickjs):
        self.command = (sys.executable, str(pin), '--ignore-config', '--no-update', '--no-cache-dir',
                        '--no-plugin-dirs', '--no-remote-components', '--no-warnings', '--no-progress',
                        '--socket-timeout', '8', '--retries', '0', '--extractor-retries', '0')
        if quickjs:
            self.command += ('--js-runtimes', 'quickjs:' + str(quickjs))

    def call(self, how, template, url):
        try:
            result = subprocess.run((*self.command, *how, '--skip-download', '--print', template, '--', url),
                                    timeout=22, capture_output=True, encoding='utf-8', errors='replace')
        except subprocess.TimeoutExpired:
            raise AudioError('Finding audio on YouTube timed out (22 s).') from None
        if result.returncode:
            raise provider_error(result.stderr)
        if len(result.stdout) > 512 * 1024:
            raise AudioError('YouTube returned too much metadata for this request.')
        try:
            return [json.loads(line) for line in result.stdout.split('\n') if line]
        except ValueError:
            raise AudioError('The helper could not read the provider metadata.') from None

    def search(self, query):
        rows = self.call(('--flat-playlist',), '%(.{id,title,channel,uploader,duration})j', f'ytsearch8:{query}')
        return [dict(ytId=row.get('id'), title=row.get('title'), channel=row.get('channel') or row.get('uploader'),
                     duration=row.get('duration')) for row in rows[:8] if isinstance(row, dict)]

    def resolve(self, video, seconds, webm):
        rows = self.call(('--no-playlist',), '%(.{id,duration,availability,live_status,is_live,was_live,formats})j',
                         f'https://www.youtube.com/watch?v={video}')
        info = rows[0] if len(rows) == 1 and isinstance(rows[0], dict) else {}
        if (info.get('id') != video or not length_matches(info.get('duration'), seconds)
                or info.get('availability') not in (None, 'public', 'unlisted')
                or info.get('live_status') not in (None, 'not_live') or info.get('is_live') or info.get('was_live')):
            raise AudioError('The provider did not confirm the selected full recording.')
        choices = []
        for item in info.get('formats') or ():
            if not isinstance(item, dict) or item.get('has_drm') or item.get('protocol') != 'https' or item.get('vcodec') != 'none':
                continue
            if not permitted_url(item.get('url')) or item.get('fragments') or item.get('manifest_url'):
                continue
            size = item.get('filesize')
            if type(size) is int and not 0 < size <= MAX_BYTES:
                continue
            ext, codec = item.get('ext'), item.get('acodec')
            if not isinstance(codec, str):
                continue
            mime = ('audio/mp4' if ext == 'm4a' and codec.startswith('mp4a.') else
                    'audio/webm' if webm and ext == 'webm' and codec == 'opus' else None)
            if mime:
                # Prefer a moderate bitrate without conversion; AAC first for Android compatibility.
                abr = item.get('abr')
                abr = abr if type(abr) in (int, float) and math.isfinite(abr) else 128
                choices.append(((mime == 'audio/mp4', -abs(128 - abr)), item, mime))
        if not choices:
            raise AudioError('This upload did not offer compatible progressive audio. Nothing played.')
        _, format_, mime = max(choices, key=lambda choice: choice[0])
        return RemoteAudio(format_, mime)


class FixtureAudio:
    """Slow, fast-start AAC fixture for the emulator only; never packaged for phones."""
    mime = 'audio/mp4'

    def __init__(self, path):
        self.path = path
        self.size = path.stat().st_size

    @contextmanager
    def read(self, first, last):
        class SlowReader:
            def read(self, amount):
                time.sleep(.55)
                return file.read(amount)

        with self.path.open('rb') as file:
            file.seek(first)
            yield SlowReader()


class FixtureProvider:
    video = 'BBXtest0001'
    query = 'Fixture Orbit'

    def __init__(self, provider, path):
        self.provider, self.path = provider, path

    def search(self, query):
        if query == self.query:
            return [dict(ytId=self.video, title='Fixture Orbit', channel='Controlled test only', duration=24)]
        return self.provider.search(query)

    def resolve(self, video, seconds, webm):
        if video == self.video and seconds == 24:
            return FixtureAudio(self.path)
        return self.provider.resolve(video, seconds, webm)


def requested_range(header, size):
    if header is None:
        return 0, size - 1
    match = re.fullmatch(r'bytes=(\d{0,9})-(\d{0,9})', header)
    if not match or not any(match.groups()):
        raise ValueError('bad range')
    first, last = match.groups()
    if not first and int(last) > 0:
        return max(0, size - int(last)), size - 1
    if first:
        lo, hi = int(first), min(int(last), size - 1) if last else size - 1
        if lo <= hi:
            return lo, hi
    raise ValueError('unsatisfiable range')


class BufferedAudio:
    def __init__(self, source, scratch):
        self.source = source
        self.file = TemporaryFile(dir=scratch)
        # Eviction drops the index entry; an in-flight HTTP read still owns this object.
        finalize(self, self.file.close)
        self.spans = []  # Sorted inclusive intervals, with no overlaps or gaps within an interval.
        self.error = ''
        self.lock = Lock()

    def status(self):
        with self.lock:
            count = sum(last - first + 1 for first, last in self.spans)
            return dict(bytes=count, size=self.source.size, complete=count == self.source.size, message=self.error)

    def remember(self, first, data):
        last = first + len(data) - 1
        with self.lock:
            self.file.seek(first)
            self.file.write(data)
            spans = []
            for left, right in self.spans:
                if right + 1 < first:
                    spans.append((left, right))
                elif last + 1 < left:
                    spans.append((first, last))
                    first, last = left, right
                else:
                    first, last = min(first, left), max(last, right)
            spans.append((first, last))
            self.spans = spans

    def pipe(self, first, last, write):
        while first <= last:
            end = last
            with self.lock:
                for lo, hi in self.spans:
                    if lo <= first <= hi:
                        self.file.seek(first)
                        data = self.file.read(min(BLOCK, min(hi, last) - first + 1))
                        break
                    if lo > first:
                        end = min(end, lo - 1)
                        data = None
                        break
                else:
                    data = None
            if data:
                write(data)
                first += len(data)
                continue
            # No lock held across upstream reads or WebView writes. A seek can open its own range.
            with self.source.read(first, end) as response:
                while first <= end:
                    data = response.read(min(BLOCK, end - first + 1))
                    if not data:
                        raise AudioError('The audio transfer ended before the requested range was complete.')
                    self.remember(first, data)
                    write(data)
                    first += len(data)


class Loopback(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, provider, assets, scratch, token=None):
        self.provider, self.assets, self.scratch = provider, assets, scratch
        self.token = token or secrets.token_urlsafe(18)
        self.streams = OrderedDict()
        self.streams_lock = Lock()
        self.lookup = Lock()
        self.slots = BoundedSemaphore(12)
        super().__init__(('127.0.0.1', 0), Handler)
        self.origin = f'http://127.0.0.1:{self.server_port}'

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def handle_error(self, request, address):
        # Exceptions can include temporary provider URLs. Never log their details.
        print('BBX_LOOPBACK_REQUEST_FAILED', flush=True)

    def open_audio(self, video, seconds, webm):
        identity = (video, seconds)
        with self.streams_lock:
            for key, (existing, audio) in reversed(self.streams.items()):
                if existing == identity and (webm or audio.source.mime != 'audio/webm') and audio.status()['complete']:
                    self.streams.move_to_end(key)
                    return dict(ok=True, id=video, stream=key, cached=True)
        source = self.provider.resolve(video, seconds, webm)
        key = secrets.token_urlsafe(18)
        audio = BufferedAudio(source, self.scratch)
        with self.streams_lock:
            self.streams[key] = (identity, audio)
            while len(self.streams) > 2:
                self.streams.popitem(last=False)
        return dict(ok=True, id=video, stream=key, cached=False)


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def setup(self):
        self.request.settimeout(12)
        super().setup()

    def log_message(self, *args):
        pass

    def reply(self, status, mime, size, headers=()):
        self.close_connection = True
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(size))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Connection', 'close')
        for key, value in headers:
            self.send_header(key, value)
        self.end_headers()

    def json(self, status, obj):
        data = json.dumps(obj, ensure_ascii=True).encode('utf-8')
        self.reply(status, 'application/json', len(data))
        if self.command != 'HEAD':
            self.wfile.write(data)

    def do_GET(self):
        self.route()

    def do_HEAD(self):
        self.route()

    def do_POST(self):
        self.route()

    def route(self):
        server = self.server
        prefix = '/' + server.token + '/'
        origin, host = self.headers.get('Origin'), self.headers.get('Host')
        if host != f'127.0.0.1:{server.server_port}' or origin not in (None, server.origin) or not self.path.startswith(prefix):
            self.json(404, {})
            return
        path, _, query = self.path[len(prefix):].partition('?')
        if path in ASSETS and self.command == 'GET':
            file, mime = ASSETS[path]
            data = (server.assets / file).read_bytes()
            self.reply(200, mime, len(data))
            self.wfile.write(data)
            return
        if path == 'api/status' and self.command == 'GET':
            self.json(200, dict(ready=True, cookies=False))
            return
        match = re.fullmatch(r'api/(audio|buffer)/([A-Za-z0-9_-]{24})', path)
        if match and self.command in ('GET', 'HEAD'):
            with server.streams_lock:
                saved = server.streams.get(match[2])
            if saved is None:
                self.json(404, {})
            elif match[1] == 'buffer':
                self.json(200, saved[1].status())
            else:
                self.audio(saved[1])
            return
        search = path == 'api/search' and self.command == 'GET'
        play = path == 'api/open' and self.command == 'POST'
        if not (search or play):
            self.json(404, {})
            return
        try:
            if search:
                value = parse_qs(query).get('q', [''])[0].strip()
                if not value or len(value) > 120 or any(ord(c) < 32 for c in value):
                    raise ValueError('bad search')
            else:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 512 or self.headers.get_content_type() != 'application/json':
                    raise ValueError('bad selection')
                answer = json.loads(self.rfile.read(size))
                video, seconds, webm = answer['id'], answer['seconds'], answer['webm']
                if not isinstance(video, str) or not ID.fullmatch(video) or type(seconds) is not int or not 1 <= seconds <= MAX_SECONDS or type(webm) is not bool:
                    raise ValueError('bad selection')
        except (ValueError, TypeError, KeyError, OSError):
            self.json(400, dict(ok=False, message='Invalid query or recording selection.'))
            return
        if not server.lookup.acquire(blocking=False):
            self.json(409, dict(ok=False, message='A helper request is still finishing. Try again shortly.'))
            return
        try:
            try:
                answer = dict(ok=True, results=server.provider.search(value)) if search else server.open_audio(video, seconds, webm)
                self.json(200, answer)
            except AudioError as problem:
                self.json(502, dict(ok=False, message=str(problem)))
        finally:
            server.lookup.release()

    def audio(self, buffered):
        header = self.headers.get('Range')
        size = buffered.source.size
        try:
            first, last = requested_range(header, size)
        except ValueError:
            self.reply(416, 'text/plain', 0, (('Content-Range', f'bytes */{size}'),))
            return
        headers = [('Accept-Ranges', 'bytes')]
        if header is not None:
            headers.append(('Content-Range', f'bytes {first}-{last}/{size}'))
        try:
            self.reply(206 if header is not None else 200, buffered.source.mime, last - first + 1, headers)
            if self.command != 'HEAD':
                buffered.pipe(first, last, self.wfile.write)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Seeking or choosing another recording can close a cursor.
        except (AudioError, OSError) as problem:
            with buffered.lock:
                buffered.error = str(problem) if isinstance(problem, AudioError) else 'The audio transfer did not complete.'


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('pin', type=Path)
    args.add_argument('assets', type=Path)
    args.add_argument('scratch', type=Path)
    args.add_argument('--quickjs', type=Path)
    args.add_argument('--fixture', type=Path)
    options = args.parse_args()
    if options.pin.stat().st_size != 3073072 or hashlib.sha256(options.pin.read_bytes()).hexdigest() != PIN:
        raise SystemExit('BBX_PIN_MISMATCH')
    provider = Provider(options.pin, options.quickjs)
    if options.fixture:
        provider = FixtureProvider(provider, options.fixture)
    with Loopback(provider, options.assets, options.scratch) as http:
        print(f'BBX_READY {http.server_port}/{http.token}', flush=True)
        http.serve_forever()


if __name__ == '__main__':
    main()
