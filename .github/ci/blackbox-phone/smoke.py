"""Launch the phone diagnostics UI on the disposable x86_64 sibling (no provider calls)."""
from pathlib import Path
import sys

import check_blackbox_p11 as p11


p11.PKG = 'com.blackbox.stream.phonetest'
print(p11.adb('install', '--abi', 'x86_64', Path(sys.argv[1]), timeout=65))
p11.adb('shell', 'wm', 'size', '720x1560')
p11.adb('shell', 'wm', 'density', '320')
print(p11.adb('shell', 'am', 'start', '-W', '-n', f'{p11.PKG}/com.blackbox.stream.MainActivity'))
try:
    screen = p11.wait_ui(lambda nodes: nodes if p11.find(nodes, 'Helper ready · no account session') else None, 100)
    if screen is None or not all(p11.find(screen, text) is not None for text in ('Try Espresso', 'Try Kwaku')):
        raise RuntimeError('test UI did not show both controls and ready status')
    print('PASS: side-by-side test package opens; both exact-recording controls and no-account readiness are visible.')
finally:
    print(p11.adb('logcat', '-d', '-s', 'BlackBoxStream', 'AndroidRuntime')[-4500:])
