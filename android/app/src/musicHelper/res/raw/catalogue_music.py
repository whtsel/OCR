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
CREDIT_SPLIT = re.compile(r'\s*[,;]\s*|\s+(?:&|×|·|feat(?:uring)?\.?|ft\.?)\s+', re.I)
CHANNEL_ID = re.compile(r'UC[A-Za-z0-9_-]{22}\Z')
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
    ('me u', 'tems', 'me u'):
        Published('qwpswojKj8M', 'UCXg6YtKpgC59gRKUfxQw8Fw', 192),
    ('calm down', 'rema selena gomez', 'calm down'):
        Published('OkEFHhDBK74', 'UCy6qn2oxmoXA4_gBA5Q7zPw', 239),
    ('calm down', 'rema selena gomez', 'rave roses ultra'):
        Published('DKrelwjugQA', 'UCy6qn2oxmoXA4_gBA5Q7zPw', 239),
    ('joro', 'wizkid', 'joro'):
        Published('l45lgQJZWt0', 'UC25DGtgYk5TwdksvtpeWYvA', 262),
    ('water', 'tyla', 'water'):
        Published('Z_4uvlzSlgE', 'UCnZBreGKMYsBpE5Vqxevewg', 200),
}


def published(title, artist, album, seconds):
    hint = PUBLISHED.get((norm(title), norm(artist), norm(album)))
    return hint if hint and close_length(seconds, hint.seconds) else None


# The label art tracks for these two releases use the artist's official channel ID,
# displayed as "Black Sherif Music", not the bare credit or a Topic channel.
LABEL_CHANNELS = {('black sherif', album): ('UC1IZ-tDYT2IrA-__AMqNOHA', 'Provided to YouTube by EMPIRE Distribution')
                  for album in ('the villain i never was', 'iron boy')}


def label_channel(info, artist, album):
    channel, label = LABEL_CHANNELS.get((norm(artist), norm(album)), ('', ''))
    return label if channel and info.get('channel_id') == channel else ''


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
    return len(expected_parts) > 1 and sorted(expected_parts) == sorted(
        credit for value in values for credit in credit_parts(value))


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


def verified_listing(info, listing):
    # The pinned extractor can receive an owner badge in search but not on the watch
    # page. Keep that evidence only when both responses identify the same upload AND channel.
    channel_id = info.get('channel_id')
    return (info.get('channel_is_verified') is None and isinstance(channel_id, str)
            and bool(CHANNEL_ID.fullmatch(channel_id)) and isinstance(listing, dict)
            and listing.get('channel_is_verified') is True and listing.get('ie_key') == 'Youtube'
            and listing.get('channel_id') == channel_id and listing.get('id') == info.get('id'))


def matches(info, expected_id, title, artist, album, seconds, listing=None):
    wanted, guests = song_title(title)
    is_live = bool(LIVE.search(wanted))
    if (not isinstance(info, dict) or info.get('id') != expected_id or info.get('_type', 'video') != 'video'
            or not prerecorded(info, is_live) or info.get('availability') not in (None, 'public', 'unlisted')
            or not close_length(info.get('duration'), seconds)):
        return False
    hint = published(title, artist, album, seconds)
    known_publisher = hint is not None and expected_id == hint.video and info.get('channel_id') == hint.channel
    verified = info.get('channel_is_verified') is True or verified_listing(info, listing)
    generated = release_fields(info, verified or known_publisher)
    distributor = label_channel(info, artist, album)
    description = info.get('description')
    label_album = False
    if distributor and isinstance(description, str) and description.startswith(distributor + '\n'):
        label_fields = release_fields(info, True)
        if label_fields:
            name, credits, release_name = label_fields
            label_album = (norm(song_title(name)[0]) == norm(wanted) and same_credits(credits, artist)
                           and same_release(release_name, album, is_live))
            if label_album and generated is None:
                generated = label_fields
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
    # A publisher can use a label channel name (e.g. StarBoy TV). Only accept this
    # when the exact published video/channel IDs and distributor music fields agree.
    publisher_channel = known_publisher and track_tag and credits_hint and release_hint and (
        norm(song_title(track_tag)[0]) == norm(wanted) and same_credits(credits_hint, artist)
        and same_release(release_hint, hint.release or album, is_live))
    if channel not in named and not publisher_channel and not label_album and not (
            verified and any(channel.replace(' ', '') == name.replace(' ', '') + 'vevo' for name in named)):
        return False
    return verified or generated is not None


def candidates(entries, title, artist, album, seconds, inspected=()):
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
        on_artist = channel_artist(item) in credits
        owner = item.get('channel') or item.get('uploader') or ''
        # Ranking is not verification. Spend the limited watch-page checks on likely
        # official releases first, not results that merely have a similar length.
        score = (bool(label_channel(item, artist, album)),
                 on_artist and (item.get('channel_is_verified') is True or bool(TOPIC.search(str(owner)))),
                 on_artist, norm(item['title']) == norm(title), duration is not None)
        ranked.append((score, item))
    ranked.sort(key=lambda pair: pair[0], reverse=True)
    return [item for _, item in ranked]


def provider_stop(problem):
    text = str(problem).casefold()
    if any(word in text for word in ('http error 429', 'too many requests')):
        return 'LIMITED'
    if any(word in text for word in ('http error 401', 'not a bot', 'sign in', 'sign-in', 'login_required', 'login required')):
        return 'SIGNIN'
    if any(word in text for word in ('http error 403', 'music premium', 'premium members', 'members-only',
                                     'members only', 'subscriber-only', 'private video', 'video is private',
                                     'not available in your country', 'not available in your region')):
        return 'REFUSED'
    return ''


def prepare(engine, title, artist, album, seconds, mark, lookup_error, remembered='', stage=lambda _: None, download=True):
    checked = set()
    rejected = None
    inspected = False
    hint = published(title, artist, album, seconds)
    probe = hint and os.environ.get('BBX_PUBLIC_PROBE') == '1'

    def verify(video, listing=None):
        nonlocal rejected, inspected
        checked.add(video)
        stage('BBX_CATALOGUE_VERIFY')
        try:
            info = engine.extract_info(f'https://www.youtube.com/watch?v={video}', download=False, process=False)
        except lookup_error as problem:
            if probe and video == hint.video:
                message = re.sub(r'https?://\S+', '[url]', str(problem))
                stage('BBX_PUBLIC_PROBE:' + json.dumps({'error': message[-400:]}, ensure_ascii=True))
            if provider_stop(problem):
                raise
            rejected = problem
            return False
        inspected = True
        accepted = matches(info, video, title, artist, album, seconds, listing)
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
    # Lead with the release. A bare title query often fills the inspection window
    # with music videos, covers or a different edition before the real album appears.
    query_title, _ = song_title(title)
    broad = f'{artist} {query_title}'
    queries = (broad,) if not norm(album) or norm(album) == norm(query_title) else (f'{broad} {album}', broad)
    for index, query in enumerate(queries):
        remaining = INSPECTIONS - len(checked)
        if not remaining:
            break
        stage('BBX_CATALOGUE_ALTERNATE' if index else 'BBX_CATALOGUE_QUERY')
        rows = engine.extract_info(f'ytsearch{SEARCH_COUNT}:{query}', download=False, process=False)
        limit = remaining - (1 if index + 1 < len(queries) else 0)
        for listing in candidates((rows or {}).get('entries'), title, artist, album, seconds, checked)[:limit]:
            if verify(listing['id'], listing):
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
        # Give Android a fixed status for a refusal; never use it to try another source.
        outcome = provider_stop(problem)
        if outcome:
            print('BBX_PROVIDER_' + outcome, file=sys.stderr)
        else:
            print('ERROR: ' + str(problem).removeprefix('ERROR: '), file=sys.stderr)
        return 2
    if not found:
        print('BBX_NO_EXACT_RECORDING', file=sys.stderr)
        return 3
    return 0


if __name__ == '__main__':
    sys.exit(main())
