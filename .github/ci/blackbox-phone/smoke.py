"""Exercise the same Android screen with a throttled fixture and optionally a fresh YouTube search."""
import argparse
import os
from pathlib import Path
import re

import check_blackbox_p11 as p11


p11.PKG = 'com.blackbox.stream.helperstream'
ACTIVITY = p11.PKG + '.HelperStreamActivity'


def visible_text(nodes):
    return sorted({value for node in nodes if node.get('package') == p11.PKG
                   for value in p11.label(node)[:2] if value})


def status(nodes, *prefixes):
    return next((value for value in visible_text(nodes) if value.startswith(prefixes)), None)


def clock(nodes):
    for value in visible_text(nodes):
        found = re.fullmatch(r'(\d+):(\d{2}) / (\d+):(\d{2})', value)
        if found:
            m, s, dm, ds = map(int, found.groups())
            return m * 60 + s, dm * 60 + ds
    return None


def fallback(nodes, kind, label=lambda value: True):
    for node in nodes:
        if node.get('package') == p11.PKG and node.get('class') == kind and any(map(label, p11.label(node)[:2])):
            left, top, right, bottom = p11.bounds(node)
            if right > left and bottom > top:
                return node
    return None


def query_field(nodes):
    direct = p11.find(nodes, 'query', 'Enter a song or artist')
    return direct if direct is not None else fallback(nodes, 'android.widget.EditText')


def first_result(nodes):
    direct = p11.find(nodes, 'play-result-1')
    return direct if direct is not None else fallback(nodes, 'android.widget.Button', lambda text: text.startswith('Play '))


def smoke(apk):
    print(p11.adb('install', '--abi', 'x86_64', apk, timeout=65))
    p11.adb('shell', 'wm', 'size', '720x1560')
    p11.adb('shell', 'wm', 'density', '320')
    p11.adb('shell', 'input', 'keyevent', '82')
    return launch()


def launch():
    print(p11.adb('shell', 'am', 'start', '-W', '-n', f'{p11.PKG}/{ACTIVITY}'))
    screen = p11.wait_ui(lambda nodes: nodes if p11.find(nodes, 'Helper ready · no account session') is not None else None, 100)
    if screen is None:
        raise RuntimeError('Did not see helper readiness: ' + ' | '.join(visible_text(p11.ui_nodes())))
    if (query_field(screen) is None or p11.find(screen, 'Search') is None
            or p11.find(screen, 'Nothing searched yet.') is None or first_result(screen) is not None):
        raise RuntimeError('Expected a blank search with no preselected recording')
    return screen


def submit(screen, query):
    p11.tap(query_field(screen))
    p11.adb('shell', 'input', 'text', query.replace(' ', '%s'))
    button = p11.wait_ui(lambda nodes: p11.find(nodes, 'search', 'Search'), 12)
    if button is None:
        raise RuntimeError('Could not submit the typed search')
    p11.tap(button)
    searched = p11.wait_ui(lambda nodes: status(nodes, 'Search returned ', 'Search failed ', 'Search timed out ', 'Could not read the helper'), 45)
    print('::notice::' + p11.diagnostic(searched or 'No terminal search status.'))
    if not re.match(r'Search returned [1-8] YouTube result', searched or ''):
        raise RuntimeError('The helper search did not return results to try')
    first = p11.wait_ui(first_result, 12)
    if first is None:
        raise RuntimeError('Search succeeded but no result Play control was accessible')
    return first


def first_played(button):
    p11.tap(button)
    played = p11.wait_ui(lambda nodes: status(nodes, 'Audio playing ·', 'Audio lookup failed ', 'Audio lookup timed out',
                                             'The helper did not confirm ', 'The audio length did not match ', 'Could not open',
                                             'This upload', 'The phone could not ', 'The audio transfer ', 'Audio is ready',
                                             'Player opened, but ', 'Buffering the start of the audio timed out'), 65)
    if not played or not played.startswith('Audio playing ·'):
        raise RuntimeError('Search succeeded; selected result did not play: ' + (played or ' | '.join(visible_text(p11.ui_nodes()))))
    print('::notice::' + played)
    measured = re.search(r'First audio in ([\d.]+) s', played)
    if not measured:
        raise RuntimeError('The helper did not measure timing from tap to the advancing clock')
    return float(measured[1])


def check_seek():
    reading = p11.wait_ui(clock, 12)
    if reading is None:
        raise RuntimeError('No full playback clock appeared')
    _, duration = reading
    seek = p11.wait_ui(lambda nodes: p11.find(nodes, 'seek', 'Seek past halfway'), 9)
    if seek is None:
        raise RuntimeError('No seek control for the played audio')
    p11.tap(seek)

    observation = p11.wait_ui(lambda nodes: status(nodes, 'Seek verified · '), 18)
    result = re.fullmatch(r'Seek verified · (\d+):(\d{2}) → (\d+):(\d{2})', observation or '')
    if result is None:
        raise RuntimeError('Audio clock did not advance after seeking')
    a, b, c, d = map(int, result.groups())
    after, later = 60 * a + b, 60 * c + d
    if after < duration * 0.6 or later <= after:
        raise RuntimeError('The decoded clock did not advance past the halfway point')
    return duration, after, later


def controlled_progressive(screen):
    # Available only in CI's x86_64 package. The response takes about 12 s to finish.
    elapsed = first_played(submit(screen, 'Fixture Orbit'))
    if elapsed >= 5:
        raise RuntimeError(f'Throttled fixture was not playing inside the 5 s target ({elapsed} s)')
    before_full = p11.wait_ui(lambda nodes: p11.find(nodes, 'Started before the full audio was buffered.'), 12)
    if before_full is None:
        raise RuntimeError('Did not prove playback before transfer completion')
    # Wait for complete byte coverage before reusing any local data as a complete file.
    complete = p11.wait_ui(lambda nodes: p11.find(nodes, 'Whole audio buffered for replay in this session.'), 25)
    if complete is None:
        raise RuntimeError('Only a partial buffer arrived in the controlled test')
    duration, after, later = check_seek()
    if duration != 24:
        raise RuntimeError(f'Test recording duration was not 24 s ({duration} s)')
    try:
        p11.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
        p11.adb('shell', 'svc', 'wifi', 'disable')
        p11.adb('shell', 'svc', 'data', 'disable')
        first = p11.wait_ui(first_result, 10)
        if first is None:
            p11.adb('shell', 'input', 'swipe', '300', '1360', '300', '620', '350')
            first = p11.wait_ui(first_result, 10)
        if first is None:
            raise RuntimeError('Could not select the same recording again')
        replay = first_played(first)
        if replay >= 5 or p11.wait_ui(lambda nodes: p11.find(nodes, 'Started from a complete buffer.'), 10) is None:
            raise RuntimeError('The same selected recording did not replay from the complete local buffer')
    finally:
        p11.adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
        p11.adb('shell', 'svc', 'data', 'enable')
        p11.adb('shell', 'svc', 'wifi', 'enable')
    print(f'::notice::PASS Android throttled fixture: first audio {elapsed} s before full transfer, seek {after}s to {later}s, local replay {replay} s.')


def live_search():
    # Start with an empty session so this cannot reuse the fixture's cache or any previous recording.
    p11.adb('shell', 'am', 'force-stop', p11.PKG)
    screen = launch()
    elapsed = first_played(submit(screen, 'Kevin MacLeod Carefree'))
    duration, after, later = check_seek()
    print(f'::notice::PASS live YouTube: first audio {elapsed} s, full duration {duration} s, seek {after}s to {later}s.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--live', action='store_true')
    args = parser.parse_args()
    try:
        controlled_progressive(smoke(args.apk))
        output = os.environ.get('GITHUB_OUTPUT')
        if output:
            with Path(output).open('a', encoding='utf-8') as result:
                result.write('fixtures_ok=true\n')
        if args.live:
            live_search()
    except Exception as error:
        print('::error::' + p11.diagnostic(str(error)).replace('%', '%25').replace('\n', ' / '))
        return 1
    finally:
        log = p11.adb('logcat', '-d', '-s', 'BlackBoxHelperStream', 'AndroidRuntime', check=False)
        print(p11.diagnostic(log))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
