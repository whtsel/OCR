"""Find one exact v90 recording using the byte-pinned extractor.

Catalogue row IDs and provider video IDs are deliberately separate. Search hints
never bypass recording validation, even after an earlier successful download.
"""
import itertools
import json
import os
import re
import sys
import unicodedata
from collections import namedtuple
from pathlib import Path


VIDEO_ID = re.compile(r'[A-Za-z0-9_-]{11}\Z')
# Presentation labels, not edits, live versions, lyric versions or remixes.
PRESENTATION = re.compile(r'\s*[\[(]\s*(?:official\s+(?:music\s+)?(?:audio|video)|audio)\s*[\])]\s*$', re.I)
CREDIT_SPLIT = re.compile(r'\s*[,;]\s*|\s+(?:&|×|feat(?:uring)?\.?|ft\.?)\s+', re.I)
FEATURE = re.compile(r'\s*[\[(]\s*(?:feat(?:uring)?|ft)\.?\s+([^()\[\]]+)[\])]', re.I)
LIVE = re.compile(r'\s*[\[(]\s*live(?: version)?\s*[\])]\s*$', re.I)
STUDIO = re.compile(r'\s*[\[(]\s*studio(?: version)?\s*[\])]\s*$', re.I)
RELEASE_FORMAT = re.compile(r'\s+[-–—]\s+(?:ep|single)\s*$', re.I)
TOPIC = re.compile(r'\s*[-–—]\s*Topic\s*$', re.I)
SEARCH_COUNT = 10
INSPECTIONS = 4
DURATION_SLOP = 6


def norm(value):
    text = unicodedata.normalize('NFKC', value) if isinstance(value, str) else ''
    return ' '.join(''.join(c if c.isalnum() else ' ' for c in text.casefold()).split())


def close_length(value, seconds):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and abs(value - seconds) <= DURATION_SLOP


# Public distributor listings for reported v90 rows. These are lookup hints, not pins:
# even a hinted upload must pass the full recording, publisher and duration checks.
Published = namedtuple('Published', 'video channel seconds release extra_credits', defaults=('', ()))
PUBLISHED = {
    ('someday live', 'kirk franklin', 'i am what i am'):
        Published('vrVJVB5IH0Q', 'UCpUGMpTEADS3dSY8DVQ6jgg', 396),
    ('goodness of god live', 'cece winans', 'believe for it'):
        Published('tO3OLKg8Eww', 'UCDAPd3S5CBIEKXn-tvy57Lg', 298),
    ('breathe feat chandler moore jonathan mcreynolds doe mav city gospel choir studio version',
     'maverick city music', 'breathe feat mav city gospel choir ep'):
        Published('gRPRYPMZsXI', 'UCcQaSpIMPptu9RLgqIbviqw', 326, extra_credits=('doe jones', 'pat barrett')),
    ('gracefully broken', 'tasha cobbs leonard', 'heart passion pursuit deluxe edition'):
        # The fourth track on both editions, not a rule that all deluxe albums share audio.
        Published('aXyVXhU13sI', 'UCprgXS1VcxyAfFvQXZTjfdA', 377, release='Heart. Passion. Pursuit.'),
}


def published(title, artist, album, seconds):
    hint = PUBLISHED.get((norm(title), norm(artist), norm(album)))
    return hint if hint and close_length(seconds, hint.seconds) else None


def credit_parts(value):
    return [name for part in CREDIT_SPLIT.split(value) if (name := norm(part))]


def song_title(value):
    text = unicodedata.normalize('NFKC', value) if isinstance(value, str) else ''
    guests = []

    def featured(match):
        guests.extend(credit_parts(match[1]))
        return ''

    title = FEATURE.sub(featured, text).strip()
    title = LIVE.sub(' (Live)', title)
    return STUDIO.sub(' (Studio Version)', title), guests


def bare_version(value):
    return STUDIO.sub('', LIVE.sub('', value)).strip()


def same_release(value, expected, live):
    def identity(text):
        title, _ = song_title(text)
        title = RELEASE_FORMAT.sub('', title)
        return norm(LIVE.sub('', title) if live else title)

    return bool(expected and norm(expected) and identity(value) == identity(expected))


def same_credits(values, expected):
    if not isinstance(values, list) or not values or not all(isinstance(value, str) and norm(value) for value in values):
        return False
    if norm(' '.join(values)) == norm(expected):
        return True
    expected_parts = credit_parts(expected)
    # Only interchange explicitly separated credits, not arbitrary words within a stage name.
    return len(expected_parts) > 1 and sorted(expected_parts) == sorted(norm(value) for value in values)


def same_video_title(value, title, artist):
    name, _ = song_title(PRESENTATION.sub('', value) if isinstance(value, str) else '')
    wanted, actual = norm(song_title(title)[0]), norm(name)
    if actual in (wanted, norm(f'{artist} {title}'), norm(f'{title} {artist}')):
        return True
    # A visible credit prefix can use different separators or order of collaborating artists.
    # Require a real title/credit separator; never strip arbitrary trailing words.
    pair = re.split(r'\s+[-–—]\s+', name, maxsplit=1)
    parts = credit_parts(artist)
    if len(pair) == 2 and len(parts) > 1:
        return any(norm(song) == wanted and sorted(credit_parts(credit)) == sorted(parts)
                   for credit, song in (pair, pair[::-1]))
    return False


def channel_artist(info):
    name = info.get('channel') or info.get('uploader') or ''
    return norm(TOPIC.sub('', name)) if isinstance(name, str) else ''


def prerecorded(info, live_recording=False):
    status = info.get('live_status')
    if info.get('is_live'):
        return False
    if status == 'was_live' or info.get('was_live'):
        # An ended broadcast is never a substitute for a studio recording. Album, track
        # and duration still have to match before accepting an explicitly live selection.
        return live_recording and status in (None, 'not_live', 'was_live')
    return status in (None, 'not_live')


def release_fields(info, trusted_publisher):
    channel = info.get('channel') or info.get('uploader') or ''
    desc = info.get('description') or ''
    if not ((trusted_publisher or isinstance(channel, str) and TOPIC.search(channel)) and isinstance(desc, str)
            and desc.startswith('Provided to YouTube by ') and desc.rstrip().endswith('\nAuto-generated by YouTube.')):
        return None
    lines = [line.strip() for line in desc[:8000].splitlines() if line.strip()]
    if len(lines) < 5 or ' · ' not in lines[1]:
        return '', [], ''  # Trust structured tags, but do not invent fields from a short description.
    title, *credits = lines[1].split(' · ')
    return title, credits, lines[2]


def matches(info, expected_id, title, artist, album, seconds):
    wanted, guests = song_title(title)
    is_live = bool(LIVE.search(wanted))
    if (not isinstance(info, dict) or info.get('id') != expected_id or info.get('_type', 'video') != 'video'
            or not prerecorded(info, is_live) or info.get('availability') not in (None, 'public', 'unlisted')
            or not close_length(info.get('duration'), seconds)):
        return False
    hint = published(title, artist, album, seconds)
    known_publisher = hint is not None and expected_id == hint.video and info.get('channel_id') == hint.channel
    verified = info.get('channel_is_verified') is True
    generated = release_fields(info, verified or known_publisher)
    track_tag, credits_hint, release_hint = generated if generated is not None else ('', [], '')
    track, tag_guests = song_title(info.get('track') or track_tag)
    release = info.get('album') or release_hint
    exact_release = same_release(release, album, is_live) or (
        known_publisher and generated is not None and same_release(release, hint.release, is_live)
        and same_release(release_hint, hint.release, is_live))
    # An upload name may omit a version label which the actual track tag supplies.
    # It may not name a different version; neither a matching length nor title alone is enough.
    label = info.get('title')
    if (norm(track) != norm(wanted) or not exact_release
            or not (same_video_title(label, wanted, artist) or same_video_title(label, bare_version(wanted), artist))):
        return False
    credits = info.get('artists') or ([info['artist']] if info.get('artist') else credits_hint)
    if not isinstance(credits, list) or not all(isinstance(credit, str) and norm(credit) for credit in credits):
        return False
    _, label_guests = song_title(label)
    if guests or tag_guests or label_guests:
        primary = set(credit_parts(artist))
        expected = primary | set(guests)
        named_guests = set(tag_guests) | set(label_guests)
        observed = {credit for value in credits for credit in credit_parts(value)}
        if known_publisher and track_tag and release_hint:
            # Some label art tracks append writers to the flat list of performing artists.
            # Only omit known supplemental credits for this specific published source,
            # never explicitly featured artists or arbitrary additional names.
            observed -= set(hint.extra_credits)
        if not primary.issubset(observed) or expected != (observed | named_guests):
            return False
    elif not same_credits(credits, artist):
        return False
    named = {norm(artist), *credit_parts(artist), *guests}
    channel = channel_artist(info)
    if channel not in named and not (verified and any(channel.replace(' ', '') == name.replace(' ', '') + 'vevo' for name in named)):
        return False
    return verified or generated is not None


def candidates(entries, title, artist, seconds, inspected=()):
    ranked = []
    seen = set(inspected)
    credits = {norm(artist), *credit_parts(artist)}
    title, _ = song_title(title)
    short = bare_version(title)
    for item in itertools.islice(entries or (), SEARCH_COUNT):
        if not isinstance(item, dict):
            continue
        video = item.get('id')
        duration = item.get('duration')
        if (not isinstance(video, str) or not VIDEO_ID.fullmatch(video) or video in seen
                or not prerecorded(item, bool(LIVE.search(title))) or duration is not None and not close_length(duration, seconds)
                or not (same_video_title(item.get('title'), title, artist) or same_video_title(item.get('title'), short, artist))):
            continue
        seen.add(video)
        ranked.append((2 * (channel_artist(item) in credits) + (duration is not None), video))
    ranked.sort(key=lambda pair: pair[0], reverse=True)
    return [video for _, video in ranked]


def provider_refused(problem):
    text = str(problem).casefold()
    return any(reason in text for reason in ('http error 429', 'http error 403', 'too many requests', 'not a bot', 'sign in'))


def prepare(engine, title, artist, album, seconds, mark, lookup_error, remembered='', stage=lambda _: None, download=True):
    checked = set()
    rejected = None
    inspected = False
    hint = published(title, artist, album, seconds)
    probe = hint and os.environ.get('BBX_PUBLIC_PROBE') == '1'

    def verify(video):
        nonlocal rejected, inspected
        checked.add(video)
        stage('BBX_CATALOGUE_VERIFY')
        try:
            info = engine.extract_info(f'https://www.youtube.com/watch?v={video}', download=False, process=False)
        except lookup_error as problem:
            if probe and video == hint.video:
                message = re.sub(r'https?://\S+', '[url]', str(problem))
                stage('BBX_PUBLIC_PROBE:' + json.dumps({'error': message[-400:]}, ensure_ascii=True))
            if provider_refused(problem):
                raise
            rejected = problem
            return False
        inspected = True
        accepted = matches(info, video, title, artist, album, seconds)
        if probe and video == hint.video and isinstance(info, dict):
            view = {key: info.get(key) for key in ('id', '_type', 'channel', 'channel_id', 'channel_is_verified',
                    'title', 'track', 'album', 'artists', 'artist', 'duration', 'availability', 'live_status', 'was_live')}
            desc = info.get('description') or ''
            view.update(label=desc.startswith('Provided to YouTube by '), generated=desc.rstrip().endswith('Auto-generated by YouTube.'),
                        formats=len(info.get('formats') or []), accepted=accepted)
            stage('BBX_PUBLIC_PROBE:' + json.dumps(view, ensure_ascii=True)[:900])
        if not accepted:
            return False
        mark(video)
        # Use the resolved formats immediately instead of running a second watch-page extraction.
        if download:
            engine.process_ie_result(info, download=True)
        return True

    if isinstance(remembered, str) and VIDEO_ID.fullmatch(remembered) and verify(remembered):
        return True
    if hint and hint.video not in checked and verify(hint.video):
        return True
    # Search only after a tap/preparation request. Reserve a candidate check for the
    # edition-aware alternate wording; both searches share the same overall deadline.
    query_title, _ = song_title(title)
    for index, query in enumerate((f'{artist} {query_title}', f'{query_title} {artist} {album} official audio')):
        stage('BBX_CATALOGUE_ALTERNATE' if index else 'BBX_CATALOGUE_QUERY')
        rows = engine.extract_info(f'ytsearch{SEARCH_COUNT}:{query}', download=False, process=False)
        limit = INSPECTIONS - len(checked) - (1 if index == 0 else 0)
        for video in candidates((rows or {}).get('entries'), title, artist, seconds, checked)[:limit]:
            if verify(video):
                return True
    if rejected and not inspected:
        raise rejected
    return False


def main():
    pin, title, artist, album, seconds, remembered, *options = sys.argv[1:]
    sys.path.insert(0, pin)
    from yt_dlp import YoutubeDL, parse_options
    from yt_dlp.utils import DownloadError, ExtractorError

    params = parse_options(options).ydl_opts
    try:
        with YoutubeDL(params) as engine:
            found = prepare(engine, title, artist, album, int(seconds),
                            lambda video: Path('source-id').write_text(video, encoding='ascii'),
                            (DownloadError, ExtractorError), remembered, lambda marker: print(marker, flush=True),
                            download=not params.get('simulate'))
    except (DownloadError, ExtractorError) as problem:
        # The Android adapter maps this output to small, sanitized failure reasons.
        print(str(problem), file=sys.stderr)
        return 2
    if not found:
        print('BBX_NO_EXACT_RECORDING', file=sys.stderr)
        return 3
    return 0


if __name__ == '__main__':
    sys.exit(main())
