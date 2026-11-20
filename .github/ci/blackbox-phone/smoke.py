"""Launch the phone diagnostics UI on the disposable x86_64 sibling (no provider calls)."""
from pathlib import Path
import sys

import check_blackbox_p11 as p11


def visible_text(nodes):
    return sorted({text for node in nodes if node.get('package') == p11.PKG
                   for text in p11.label(node)[:2] if text})


def smoke(apk):
    p11.PKG = 'com.blackbox.stream.phonetest'
    print(p11.adb('install', '--abi', 'x86_64', apk, timeout=65))
    p11.adb('shell', 'wm', 'size', '720x1560')
    p11.adb('shell', 'wm', 'density', '320')
    print(p11.adb('shell', 'am', 'start', '-W', '-n', f'{p11.PKG}/com.blackbox.stream.MainActivity'))
    screen = p11.wait_ui(lambda nodes: nodes if p11.find(nodes, 'Helper ready · no account session') is not None else None, 100)
    if screen is None:
        raise RuntimeError('Did not see the ready state: ' + ' | '.join(visible_text(p11.ui_nodes())))
    if any(p11.find(screen, text) is None for text in ('Try Espresso', 'Try Kwaku')):
        raise RuntimeError('Ready, but a recording control was missing: ' + ' | '.join(visible_text(screen)))
    print('::notice::PASS x86_64 startup smoke: test app opens and shows no-account readiness and both recording controls.')


try:
    smoke(Path(sys.argv[1]))
except Exception as error:
    print('::error::' + p11.diagnostic(str(error)).replace('\n', ' / '))
    raise SystemExit(1)
finally:
    log = p11.adb('logcat', '-d', '-s', 'BlackBoxStream', 'AndroidRuntime', check=False)
    print(log[-5000:])
    summary = [line for line in log.splitlines() if any(s in line for s in
               ('media helper ready', 'failed to start', 'Caused by:', 'start failed', 'ffmpeg init failed'))]
    if summary:
        print('::notice::' + p11.diagnostic(' | '.join(summary[-5:])))
