"""Compare one unseeded Espresso save in the full v90 app and the archived-helper pilot.

Run only on a fresh CI emulator. There are no fixture URLs, accounts, or automatic
reruns. A pass requires private publication, decoded audio and cold offline seek.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import time
from zipfile import ZipFile

import check_blackbox_p11 as p11
import smoke_v90_offline as v90

TITLE = v90.ESPRESSO
VIDEO, SECONDS = v90.TRACKS[TITLE]
PILOT = 'com.blackbox.stream.collectionpilot.smoke'
PIN_PATH = 'no_backup/youtubedl-android/yt-dlp/yt-dlp'


def note(text):
    print(f'::notice::{text}', flush=True)


def install_fresh(apk, package):
    if p11.adb('shell', 'pm', 'path', package, check=False).startswith('package:'):
        raise RuntimeError(f'Expected a fresh install, not existing data in {package}')
    p11.adb('install', '--abi', 'x86_64', apk, timeout=100)
    # Some Android images have backup restore on by default. Reject any restored files.
    files = p11.adb('shell', f"run-as {package} sh -c 'find files no_backup shared_prefs -type f 2>/dev/null; true'")
    if files:
        raise RuntimeError(f'{package}: app data was not empty after installation')


def check_pin(package):
    value = p11.adb('shell', 'run-as', package, 'sha256sum', PIN_PATH)
    if value.split()[0] != p11.P19_SHA:
        raise RuntimeError(f'{package}: installed zipapp did not match the tested pin')


def verify_saved(package, path, directory):
    data = p11.adb('exec-out', 'run-as', package, 'cat', path, binary=True)
    if not 32 <= len(data) <= 24 * 1024 * 1024:
        raise RuntimeError(f'{package}: saved audio is missing or exceeds the file bound')
    target = directory / f'{package}.m4a'
    target.write_bytes(data)
    seconds = p11.verify_audio(target, SECONDS, (2 * SECONDS) // 3)
    note(f'{package}: private file verified, {len(data):,} bytes, {seconds:.2f}s; decoded at start and after seek')


def full_save(apk, directory):
    install_fresh(apk, v90.PKG)
    v90.airplane(False)
    with v90.page() as js:
        v90.open_music(js)
        v90.wait(js, 'JSON.parse(OfflineMusic.library()).connected===true', 'No Internet transport', 35)
        if v90.entries(js):
            raise RuntimeError('Full v90 collection did not start empty')
        v90.click(js, '#om-tab-find')
        v90.query(js, 'Sabrina Carpenter')
        album = '[data-album-open-v63="1752214909"]'
        v90.wait(js, f'!!document.querySelector({json.dumps(album)})', 'Short n’ Sweet not found')
        v90.click(js, album)
        button = '.music-album-tracklist-v63 [aria-label="Queue Espresso"]:not(:disabled)'
        v90.wait(js, f'!!document.querySelector({json.dumps(button)})', 'Original Espresso album row unavailable')
        started = time.monotonic()
        v90.click(js, button)
        row, = v90.entries(js)
        if (row['ref'], row['title'], row['artist'], row['album'], row['seconds']) != (
                'album:1752214909:1:7', TITLE, 'Sabrina Carpenter', "Short n' Sweet", SECONDS):
            raise RuntimeError('Full v90 did not queue the same recording as the pilot')
        key = row['key']
        row = v90.wait(js, 'JSON.parse(OfflineMusic.library()).tracks[0]', 'Full v90 save did not finish', 265,
                       lambda r: r and r['state'] in ('saved', 'failed'))
        note(f"Full v90 Espresso: {row['state']} ({row['reason'] or 'verified'}) in {time.monotonic()-started:.1f}s")
        check_pin(v90.PKG)
        if row['state'] == 'failed':
            if p11.adb('shell', 'run-as', v90.PKG, 'ls', '-A', 'files/offline-music/saved'):
                raise RuntimeError('Full v90 published a file despite a failed job')
            return row['reason']
    verify_saved(v90.PKG, f'files/offline-music/saved/{key}.m4a', directory)
    v90.force_stop()
    v90.airplane(True)
    with v90.page() as js:
        v90.open_music(js)
        v90.wait(js, 'JSON.parse(OfflineMusic.library()).connected===false', 'Cold playback is not offline')
        play = '#om-saved [aria-label="Play Espresso"]'
        v90.wait(js, f'!!document.querySelector({json.dumps(play)})', 'Private publication did not survive restart')
        v90.click(js, play)
        duration = v90.wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.key==='{key}'&&p.playing&&p.position>700&&p.duration}})()",
                            'Cold playback did not advance')
        if abs(duration - SECONDS * 1000) > 6000:
            raise RuntimeError(f'Wrong cold playback duration: {duration} ms')
        v90.click(js, '#om-now')
        v90.wait(js, "document.querySelector('#om-player').open && !document.querySelector('#om-seek').disabled", 'Cold seek unavailable')
        js("(()=>{const s=document.getElementById('om-seek');s.value=Number(s.max)*.72;"
           "s.dispatchEvent(new Event('input'));s.dispatchEvent(new Event('change'))})()")
        sought = v90.wait(js, f"(()=>{{const p=JSON.parse(OfflineMusic.player());return p.position>{duration//2}&&p.position}})()",
                          'Cold seek did not reach the second half')
        v90.wait(js, f'JSON.parse(OfflineMusic.player()).position>{sought+900}', 'Cold playback did not resume after seek')
    note(f'PASS full v90: real {TITLE} saved, restarted offline and played past {sought/1000:.1f}s of {duration/1000:.1f}s')
    return 'pass'


def pilot_save(apk, directory):
    spec = importlib.util.spec_from_file_location('pilot_smoke', Path(__file__).parent / 'blackbox-collection/smoke.py')
    pilot = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pilot)
    install_fresh(apk, PILOT)
    v90.airplane(False)
    p11.adb('shell', 'am', 'start', '-W', '-n', pilot.ACTIVITY, timeout=35)
    with pilot.page_devtools() as js:
        profile = 'JSON.parse(OfflineMusic.library())'
        v90.wait(js, f'{profile}.tracks?.length===2', 'Pilot did not load the two documented controls')
        if js(f'{profile}.fixtures || {profile}.tracks.some(t=>t.saved) || {profile}.jobs.length!==0'):
            raise RuntimeError('Pilot did not start empty and in live mode')
        v90.click(js, '#tab-find')
        button = '[aria-label="Queue Espresso"]'
        v90.wait(js, f'!!document.querySelector({json.dumps(button)})', 'Archive pilot Espresso control missing')
        started = time.monotonic()
        v90.click(js, button)
        state = f"(()=>{{const p={profile};return p.tracks.find(t=>t.key==='espresso')?.saved?'pass':p.jobs.find(t=>t.key==='espresso')?.state}})()"
        v90.wait(js, state, 'Pilot request never entered the queue')
        if js(state) == 'wifi':
            if not js("[...document.querySelectorAll('button')].some(b=>{if(b.textContent!=="
                      "'Use mobile data for this group')return false;b.click();return true})"):
                raise RuntimeError('Pilot mobile data control was unavailable')
        finished = v90.wait(js, state, 'Archive pilot did not finish', 200, lambda s: s in ('pass', 'failed'))
        reason = js(f"{profile}.jobs.find(t=>t.key==='espresso')?.reason") if finished == 'failed' else 'pass'
        note(f'p11 pilot Espresso: {reason} in {time.monotonic()-started:.1f}s')
        check_pin(PILOT)
        if reason != 'pass':
            if js(f"{profile}.tracks.some(t=>t.saved)"):
                raise RuntimeError('Pilot published a file despite a failed request')
            return reason
    verify_saved(PILOT, f'files/collection-audio/{VIDEO}.m4a', directory)
    pilot.stop()
    v90.airplane(True)
    p11.adb('shell', 'am', 'start', '-W', '-n', pilot.ACTIVITY, timeout=35)
    with pilot.page_devtools() as js:
        v90.wait(js, "!!document.querySelector('[aria-label=\"Play Espresso\"]')", 'Pilot publication did not survive restart')
        pilot.play_offline(js, 'espresso', TITLE, 'from the original helper')
    return 'pass'


def main(full, pilot):
    if p11.adb('shell', 'getprop', 'ro.kernel.qemu') != '1' or p11.adb('shell', 'getprop', 'ro.product.cpu.abi') != 'x86_64':
        raise RuntimeError('Live comparison is only for a disposable x86 Android emulator')
    with ZipFile(full) as a, ZipFile(pilot) as b:
        names = {n for n in b.namelist() if n.startswith('lib/x86_64/') and n.endswith('.so')}
        if len(names) != 6 or any(a.read(n) != b.read(n) for n in names):
            raise RuntimeError('The six x86 runtime files are not identical')
    note(f'Cold A/B of Espresso ({VIDEO}), one job per app, same x86 runtime; no fixtures, no credentials')
    p11.adb('shell', 'wm', 'size', '720x1560')
    p11.adb('shell', 'wm', 'density', '320')
    results = {}
    with tempfile.TemporaryDirectory() as tmp:
        for name, function, apk, package in (('full v90', full_save, full, v90.PKG),
                                               ('p11 pilot', pilot_save, pilot, PILOT)):
            if 'limited' in results.values():
                results[name] = 'not tried after rate limit'
                break
            p11.adb('logcat', '-c')
            try:
                results[name] = function(apk, Path(tmp))
            except Exception as error:
                results[name] = 'test error'
                print(f'::warning::{name}: {p11.diagnostic(str(error)).replace(chr(10), " / ")}')
            finally:
                p11.adb('shell', 'am', 'force-stop', package, check=False)
                log = p11.adb('logcat', '-d', '-v', 'raw', '-s', 'BlackBoxMusic', 'BlackBoxStream', 'BlackBoxOfflineMusic', check=False)
                trace = ' / '.join(line for line in log.splitlines() if line.endswith('ms') or 'helper ready' in line)
                if trace:
                    note(f'{name} stages: {p11.diagnostic(trace)}')
        summary = ' / '.join(f'{name}: {outcome}' for name, outcome in results.items())
        print(f'::notice::Cold Espresso A/B: {summary}')
        if set(results.values()) != {'pass'}:
            raise RuntimeError(f'Fresh save and offline seek not verified for both paths. {summary}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('full', type=Path)
    parser.add_argument('pilot', type=Path)
    try:
        main(**vars(parser.parse_args()))
    except Exception as error:
        print(f'::error::{p11.diagnostic(str(error)).replace(chr(10), " / ")}')
        raise
