"""Boot the helper-only Android APK; optionally exercise a fresh live search in its own screen."""
from pathlib import Path
import argparse
import re

import check_blackbox_p11 as p11


p11.PKG = 'com.blackbox.stream.helpersearch'
ACTIVITY = 'com.blackbox.stream.MainActivity'


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


def smoke(apk):
    print(p11.adb('install', '--abi', 'x86_64', apk, timeout=65))
    p11.adb('shell', 'wm', 'size', '720x1560')
    p11.adb('shell', 'wm', 'density', '320')
    p11.adb('shell', 'input', 'keyevent', '82')
    print(p11.adb('shell', 'am', 'start', '-W', '-n', f'{p11.PKG}/{ACTIVITY}'))
    screen = p11.wait_ui(lambda nodes: nodes if p11.find(nodes, 'Helper ready · no account session') is not None else None, 100)
    if screen is None:
        raise RuntimeError('Did not see helper readiness: ' + ' | '.join(visible_text(p11.ui_nodes())))
    if any(p11.find(screen, *locators) is None for locators in (
            ('query', 'Enter a song or artist'), ('search', 'Search'), ('Nothing searched yet.',))):
        raise RuntimeError('Blank search controls missing: ' + ' | '.join(visible_text(screen)))
    if p11.find(screen, 'Try Espresso', 'Try Kwaku', 'play-result-1') is not None:
        raise RuntimeError('Test screen should not contain any preselected recording')
    print('::notice::PASS Android startup: the pinned helper is ready with a blank search form and no account session.')
    return screen


def live_search(screen):
    # An independent query for an openly licensed recording, not a baked-in result or ID.
    p11.tap(p11.find(screen, 'query', 'Enter a song or artist'))
    p11.adb('shell', 'input', 'text', 'Kevin%sMacLeod%sCarefree')
    search_button = p11.wait_ui(lambda nodes: p11.find(nodes, 'search', 'Search'), 10)
    if search_button is None:
        raise RuntimeError('Could not submit the typed search')
    p11.tap(search_button)
    searched = p11.wait_ui(lambda nodes: status(nodes, 'Search returned ', 'Search failed ', 'Search timed out ', 'Could not read the helper'), 56)
    if not searched:
        raise RuntimeError('No terminal search status: ' + ' | '.join(visible_text(p11.ui_nodes())))
    print('::notice::' + p11.diagnostic(searched))
    if not re.match(r'Search returned [1-8] YouTube result', searched):
        raise RuntimeError('Live helper search did not return results to try')
    def first_result(nodes):
        direct = p11.find(nodes, 'play-result-1')
        if direct is not None:
            return direct
        for node in nodes:
            if node.get('package') == p11.PKG and node.get('class') == 'android.widget.Button' and any(
                    value.startswith('Play ') for value in p11.label(node)[:2]):
                left, top, right, bottom = p11.bounds(node)
                if right > left and bottom > top:
                    return node
        return None

    first = p11.wait_ui(first_result, 12)
    if first is None:
        raise RuntimeError('Search succeeded but no result Play control was accessible')
    p11.tap(first)
    played = p11.wait_ui(lambda nodes: status(nodes, 'Audio playing ·', 'Audio preparation failed ', 'Audio preparation timed out ',
                                             'The helper did not confirm ', 'The audio length did not match ', 'Could not open the selected audio',
                                             'The phone could not play ', 'Audio is ready'), 116)
    if not played or not played.startswith('Audio playing ·'):
        raise RuntimeError('Live search succeeded; selected result did not play: ' + (played or 'no terminal audio status'))
    reading = p11.wait_ui(clock, 9)
    if reading is None:
        raise RuntimeError('No full media clock appeared')
    start, duration = reading
    seek = p11.wait_ui(lambda nodes: p11.find(nodes, 'seek', 'Seek past halfway'), 8)
    if seek is None:
        raise RuntimeError('No seek control for the played audio')
    p11.tap(seek)

    def past_half(nodes):
        measured = clock(nodes)
        if measured is not None and measured[1] == duration and measured[0] > duration * 0.6 and measured[0] > start:
            return measured[0]
        return None

    after = p11.wait_ui(past_half, 14)
    if after is None:
        raise RuntimeError('Audio clock did not reach the full recording after seeking')

    def advancing(nodes):
        value = past_half(nodes)
        return value if value is not None and value > after else None

    later = p11.wait_ui(advancing, 12)
    if later is None:
        raise RuntimeError('Audio clock did not advance after seeking')
    print(f'::notice::PASS live helper search: selected upload played for real ({duration} s), seek advanced {after}s→{later}s.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--live', action='store_true')
    args = parser.parse_args()
    try:
        screen = smoke(args.apk)
        if args.live:
            live_search(screen)
    except Exception as error:
        print('::error::' + p11.diagnostic(str(error)).replace('%', '%25').replace('\n', ' / '))
        return 1
    finally:
        log = p11.adb('logcat', '-d', '-s', 'BlackBoxStream', 'AndroidRuntime', check=False)
        print(p11.diagnostic(log))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
