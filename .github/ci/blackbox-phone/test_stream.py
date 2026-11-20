"""Controlled byte-range, session and provider-selection checks. No real provider calls."""
from contextlib import contextmanager
from email.message import Message
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from stream import AudioError, BufferedAudio, Loopback, Provider, RemoteAudio, permitted_url, requested_range


VIDEO = 'TestSong123'


class Source:
    mime = 'audio/mp4'
    data = b'controlled audio bytes' * 500
    size = len(data)

    def __init__(self):
        self.requests = []

    @contextmanager
    def read(self, first, last):
        self.requests.append((first, last))
        yield io.BytesIO(self.data[first:last + 1])


class TestStream(unittest.TestCase):
    def test_range_limits(self):
        self.assertEqual(requested_range(None, 40), (0, 39))
        self.assertEqual(requested_range('bytes=5-99', 40), (5, 39))
        self.assertEqual(requested_range('bytes=-8', 40), (32, 39))
        for value in ('bytes=40-', 'bytes=3-2', 'bytes=-0', 'bytes=0-1,2-3', 'bytes=-'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                requested_range(value, 40)

    def test_never_cache_holes_and_replays_without_origin(self):
        source = Source()
        with TemporaryDirectory() as folder:
            audio = BufferedAudio(source, folder)
            try:
                tail = []
                audio.pipe(9000, source.size - 1, tail.append)
                self.assertEqual(b''.join(tail), source.data[9000:])
                self.assertFalse(audio.status()['complete'])
                whole, coverage = [], []

                def write(data):
                    whole.append(data)
                    coverage.append(audio.status()['complete'])

                audio.pipe(0, source.size - 1, write)
                self.assertFalse(coverage[0], 'first bytes are sent before all bytes arrive')
                self.assertTrue(audio.status()['complete'])
                self.assertEqual(b''.join(whole), source.data)
                source.requests.clear()
                whole.clear()
                audio.pipe(0, source.size - 1, whole.append)
                self.assertEqual(b''.join(whole), source.data)
                self.assertEqual(source.requests, [])
            finally:
                audio.file.close()

    def test_short_transfer_is_not_a_complete_cache(self):
        source = Source()
        source.data = b'short'
        with TemporaryDirectory() as folder:
            audio = BufferedAudio(source, folder)
            try:
                with self.assertRaises(AudioError):
                    audio.pipe(0, source.size - 1, lambda _: None)
                self.assertFalse(audio.status()['complete'])
            finally:
                audio.file.close()

    def test_local_route_reuses_exact_stream_and_does_not_publish_origin(self):
        source = Source()
        opens = []

        class Backend:
            def resolve(self, *recording):
                opens.append(recording)
                return source

        with TemporaryDirectory() as folder, Loopback(Backend(), Path(folder), folder) as server:
            thread = Thread(target=server.serve_forever)
            thread.start()
            url = f'{server.origin}/{server.token}/'

            def choose(id=VIDEO, seconds=24):
                data = json.dumps(dict(id=id, seconds=seconds, webm=True)).encode()
                with urlopen(Request(url + 'api/open', data=data, headers={'Content-Type': 'application/json'}), timeout=5) as response:
                    return json.load(response)

            try:
                first = choose()
                self.assertEqual(set(first), {'ok', 'id', 'stream', 'cached'})
                self.assertFalse(first['cached'])
                self.assertEqual(first['id'], VIDEO)
                with urlopen(Request(url + 'api/audio/' + first['stream'], headers={'Range': 'bytes=0-511'}), timeout=5) as response:
                    self.assertEqual(response.status, 206)
                    self.assertEqual(response.read(), source.data[:512])
                self.assertFalse(choose()['cached'], 'a partial stream must never be treated as local replay')
                with urlopen(url + 'api/audio/' + first['stream'], timeout=5) as response:
                    self.assertEqual(response.read(), source.data)
                self.assertEqual(choose(), dict(first, cached=True))
                self.assertFalse(choose(seconds=23)['cached'], 'duration is part of the selection identity')
                self.assertEqual(opens, [(VIDEO, 24, True), (VIDEO, 24, True), (VIDEO, 23, True)])
                for req in (Request(server.origin + '/api/status'), Request(url + 'api/status', headers={'Origin': 'https://example.test'})):
                    with self.assertRaises(HTTPError) as error:
                        urlopen(req, timeout=5)
                    error.exception.close()
                    self.assertEqual(error.exception.code, 404)
                with self.assertRaises(HTTPError) as error:
                    choose(id='not/a/video')
                error.exception.close()
                self.assertEqual(error.exception.code, 400)
            finally:
                server.shutdown()
                thread.join(5)
                for _, audio in server.streams.values():
                    audio.file.close()

    def test_explicit_supported_https_audio_only(self):
        url = 'https://rr1.googlevideo.com/videoplayback?id=private'
        for bad in ('http://rr1.googlevideo.com/videoplayback', 'https://googlevideo.com.example.test/',
                    'https://googlevideo.com@127.0.0.1/', 'https://name@googlevideo.com/', 'https://googlevideo.com:3000/'):
            self.assertFalse(permitted_url(bad))
        self.assertTrue(permitted_url(url))
        format_ = dict(url=url, protocol='https', ext='m4a', acodec='mp4a.40.2', vcodec='none')
        opus = dict(format_, ext='webm', acodec='opus')
        info = dict(id=VIDEO, duration=24.4, live_status='not_live', formats=[dict(format_, has_drm=True),
                    dict(format_, protocol='m3u8_native'), dict(format_, vcodec='h264'), opus, format_])
        provider = Provider(Path('pin'), None)
        with patch.object(provider, 'call', return_value=[info]) as call, patch('stream.RemoteAudio') as audio:
            provider.resolve(VIDEO, 24, True)
            self.assertEqual(call.call_args.args[-1], f'https://www.youtube.com/watch?v={VIDEO}')
            audio.assert_called_once_with(format_, 'audio/mp4')
        with patch.object(provider, 'call', return_value=[dict(info, formats=[opus])]), patch('stream.RemoteAudio') as audio:
            with self.assertRaisesRegex(AudioError, 'compatible progressive'):
                provider.resolve(VIDEO, 24, False)
            audio.assert_not_called()
        with patch.object(provider, 'call', return_value=[dict(info, id='WrongResult')]), patch('stream.RemoteAudio') as audio:
            with self.assertRaisesRegex(AudioError, 'selected full recording'):
                provider.resolve(VIDEO, 24, True)
            audio.assert_not_called()

    def test_rejects_malformed_upstream_ranges(self):
        remote = RemoteAudio.__new__(RemoteAudio)
        remote.mime = 'audio/mp4'

        class Response:
            status = 206
            headers = Message()

        resp = Response()
        for name, value in [('Content-Range', 'bytes 12-14/42'), ('Content-Length', '3'), ('Content-Type', 'audio/mp4')]:
            resp.headers[name] = value
        self.assertEqual(remote.check(resp, 12, 14), 42)
        for header, bad in [('Content-Range', 'bytes 12-14/14'), ('Content-Range', 'bytes 11-13/42'),
                            ('Content-Length', '4'), ('Content-Type', 'text/html')]:
            original = resp.headers[header]
            resp.headers.replace_header(header, bad)
            with self.assertRaises(AudioError):
                remote.check(resp, 12, 14)
            resp.headers.replace_header(header, original)


if __name__ == '__main__':
    unittest.main()
