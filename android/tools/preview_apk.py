#!/usr/bin/env python3
"""Serve a download page for an existing signed APK, not the workspace tree."""
from argparse import ArgumentParser
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, urlsplit

DEFAULT_APK = Path(__file__).resolve().parents[1] / 'artifacts/BlackBox-Stream-Android-1.2.0.apk'


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=DEFAULT_APK)
    parser.add_argument('--port', type=int, default=8080)
    args = parser.parse_args()
    if not args.apk.is_file() or args.apk.suffix.lower() != '.apk':
        parser.error('Point --apk at an existing Android APK.')
    name = args.apk.name
    route = quote(name, safe='')
    data = args.apk.read_bytes()
    if not data.startswith(b'PK\x03\x04'):
        parser.error('The file is not an APK archive.')
    page = f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Download BlackBox Stream</title>
<style>
*{{box-sizing:border-box}}body{{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;background:#10151c;color:#f5f7fa;font:16px/1.5 system-ui,sans-serif}}main{{max-width:460px;padding:30px;background:#18212b;border:1px solid #31414f;border-radius:18px}}h1{{font-size:clamp(1.75rem,7vw,2.25rem);line-height:1.15;margin:.25em 0}}p{{margin:0 0 20px;color:#bac8d5}}.label{{color:#89f5d0;font-size:.8rem;letter-spacing:.1em;font-weight:700}}a{{display:inline-block;padding:12px 20px;background:#89f5d0;color:#101e1b;border-radius:9px;font-weight:750;text-decoration:none}}a:focus-visible{{outline:2px solid white;outline-offset:4px}}small{{display:block;margin-top:14px;color:#bac8d5;overflow-wrap:anywhere}}
</style><main><span class="label">ANDROID</span><h1>BlackBox Stream</h1><p>Download the APK and open it on your Android phone to install.</p>
<a href="{route}" download="{escape(name)}" target="_blank" rel="noopener">Download APK</a>
<small>{escape(name)} · {len(data) / 1_000_000:.2f} MB</small></main></html>'''.encode('utf-8')
    fallback_name = ''.join(c if c.isascii() and (c.isalnum() or c in '-_.') else '_' for c in name)

    class Download(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def respond(self, head=False):
            path = urlsplit(self.path).path
            headers = {}
            if path == '/':
                status, body, mime = 200, page, 'text/html; charset=utf-8'
                headers['Content-Security-Policy'] = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"
            elif path == '/' + route:
                status, body, mime = 200, data, 'application/vnd.android.package-archive'
                headers['Content-Disposition'] = f'attachment; filename="{fallback_name}"; filename*=UTF-8\'\'{route}'
            elif path == '/health':
                status, body, mime = 200, b'OK\n', 'text/plain; charset=utf-8'
            else:
                status, body, mime = 404, b'Not found\n', 'text/plain; charset=utf-8'
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            if not head:
                self.wfile.write(body)

        def do_GET(self):
            self.respond()

        def do_HEAD(self):
            self.respond(head=True)

    with ThreadingHTTPServer(('0.0.0.0', args.port), Download) as server:
        print(f'BlackBox Stream preview on port {server.server_port}: {name} ({len(data)} bytes)', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
