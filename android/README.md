# BlackBox Stream v90 for Android

This build ports the **actual v90 desktop interface**, not the previous Android watch shelf. The 41 original HTML, CSS, and JavaScript assets from the supplied v90 installer are in `app/src/main/assets/v90/`. `V90-ASSETS.sha256` verifies the original bytes. The installer and v88 source archive did not contain editable source for the Windows engines, so the port supplies the same-origin catalogue and source API using Android components. It does not run a Windows executable, expose an HTTP server, or require a PC.

## Full v90 Android with offline Music

**BlackBox Stream v90 Offline 1.5.1 (ARM64)** is the full v90 Android build, not the Music pilot.
Its test package (`com.blackboxstream.mobile.offline.parallel`) installs beside 1.5.0, the pilot,
and the other v90 builds. It starts with a separate, empty Collection so it does not overwrite or
delete existing downloads. Use Android 7.0 or later with an up-to-date System WebView. The original
v90 expiry still applies (end of 31 March 2027 UTC).

- **Music → Find** is the original v90 Music discovery UI: fourteen genre shelves, instant and
  structured catalogue search, and ordered album tracklists. The download buttons explicitly add a
  song, selected group, or album to Downloads. A partial album offers **Play saved** and **Queue
  missing**. The catalogue includes metadata, not bundled audio.
- **Downloads** starts up to five independent jobs when Android has an Internet connection,
  including mobile data. There is no app-level network choice. Pause lets active jobs finish but
  does not start another; a provider rate limit pauses new jobs briefly. Progress rings show actual
  received bytes against a known transfer size, or spin without a percentage during lookup and
  unknown-length transfers. A downloaded file only enters Collection after verification.
  The bundled v90 Android helper resolves and downloads a catalogue recording in one session,
  preferring AAC when available. The known v90 IDs (including Espresso, bad guy, and On the Low)
  go directly to those recordings. Published source hints for other tracks require an exact metadata
  match. For example, the original 3:59 Calm Down pick omits Selena Gomez from its visible credit;
  that row checks for the duet, not the 3:40 solo master. Missing evidence or a refusal shows in Needs
  attention rather than saving a substitute.
- **Collection** plays its saved, app-private files without the helper or a connection. To check it on
  a phone, save one song, force-stop the app, turn on airplane mode with Wi‑Fi off, reopen, and play
  and seek from Collection. The source must still allow a fresh download before this test.
- Movies, TV Series, Anime, My List, device Library/imports, and Local Share retain their Android
  workflows. The movie/TV/anime catalogue still needs your own TMDB key for online results.

Build the offline package with `cd android && gradle :app:assembleRelease -PbbxOfflineMusic=true`
(Gradle 8.9, JDK 17 and Android SDK 35; allow up to 3 GiB of Java heap for release dependencies).
The [Offline Android workflow](../.github/workflows/blackbox-v90-offline.yml) signs the ARM64 test APK,
checks the bundled v90 assets and pinned Android helper, and tests an x86 sibling with synthetic
media. The suite tests five overlapping real byte transfers on metered reconnect, exact and
unknown-length progress rings, pause and retry, both album queue routes, and playback/seek after a
force-stop in airplane mode. It does not establish live-provider availability. Dispatch the workflow
with `verify_live_catalogue=true` to compare a fresh **Espresso** save from the original Find
with a fresh save in the archived-helper pilot. Both select `51zjlMhdSTE` and run on the same x86
emulator, without preseeded files or account credentials. The pilot calls the archived `audio(id)`
method, whereas full v90 uses its own bounded helper runner. Matching helper hashes are not proof of
matching behavior. A live pass requires a newly published, playable file and a seek beyond halfway
after force-stop with networking off. Refusals are reported, not worked around; this diagnostic
publishes no APK. The test signing key is not a production update key.

The initial [full v90 trial](https://github.com/whtsel/OCR/actions/runs/45016324917) and the
completed [p11 reference trial](https://github.com/whtsel/OCR/actions/runs/45016793772) each reached
a provider sign-in response for Espresso with no save on a fresh emulator. The second run tested just
p11 (`pilot_only=true`); the first run's pilot tap happened before its UI was ready. Neither run
verified a fresh live download, and neither identifies the cause of a failure on a physical phone.

## Earlier Fast Start preview

**BlackBox Music Fast Start (ARM64)** is a side-by-side test build of the full v90 Android port
(`com.blackboxstream.mobile.music.fast`), not the helper diagnostic. Leave the existing 1.4.6 Music
app installed: this preview does not replace it or touch its offline library. Its Downloads are
separate. Android only updates an installed app if the package and signing key match. Use an
up-to-date Android System WebView on Android 7.0 or newer.
As in the PC v90 release, the interface expires after **31 March 2027 (UTC)**.

- **Music:** Tap the existing Play control. Its loading circle stays until the exact audio starts
  on the original player. There is no setup, source picker, progress panel, embed, or video
  fallback. The helper initializes locally when you enter Music. If lookup, download, or opening
  the file fails or reaches its deadline, the ring clears and a message tells you what happened.
  A failed selection never starts another recording. The same 14 genres, searches, albums, ordered
  tracks, queue and library are still used. The catalogue contains metadata, not the songs themselves.
- **Streaming:** Add your **TMDB** v3 API key or Read Access Token in Settings for movies, series,
  and anime. No sample or archive titles are injected into the home page, search, or title details.
  TMDB supplies metadata, artwork and official watch listings, **not movie streams**. Choosing a
  real source is separate: you can use a provider listing, an authorized release, a direct HTTPS
  file, a magnet or `.torrent`, or your own imported media. Existing Library copies stay intact.
- **Library, offline video and sharing:** Browse or open your own sources, save a video from an
  eligible source, then reopen it from Library without a connection. Local Share and private
  community rooms still work through the Android engine. The Android and Windows sharing
  protocols are not interchangeable.

## Music engine and offline listening

The full v90 catalogue has **2,620 albums and 35,995 track rows**. The engine checks an exact
recording's title, credited artists, release, full duration and publisher metadata. A few confirmed
source IDs skip the lookup. For other cards, there are at most two searches (the second includes
release and official-audio wording) and four source inspections in total. Presentation labels and
separator/order differences in credited collaborations don't invalidate an otherwise verified
recording. Missing release evidence, a wrong edition, or a remix still cannot play. After complete
audio is validated, the app remembers the provider ID privately as a lookup hint; it rechecks the
metadata if audio has to be fetched again. Card, queue and Downloads keys do not change. No login,
browser session, remote helper updates or alternate editions are used. Provider availability and exact metadata vary by track
and network; not every catalogue entry can be verified. If the provider rate-limits this device,
BlackBox pauses new requests to it for one minute. Offline copies still play in that time.
Only fetch recordings you may stream and save. On a cold play, v90 can start a direct, audio-only
AAC source as soon as the helper has matched the recording and received a playable MP4 prefix.
The same transfer finishes in the background; an in-progress stream is **not** a Downloads item.
Only the completed, verified file is published to the existing offline cache. Nothing is hosted,
no provider URL or account data is passed to the WebView, and no second audio transfer is started.
If the format needs conversion, its MP4 metadata is at the end, the length is unknown, or the
phone cannot play the partial file, it uses the existing full-file path for that same recording.
Seeking ahead of the received bytes can still buffer.

Finding a recording has up to 80 seconds; a matched source gets up to 145 seconds to transfer
(or 40 seconds without new bytes) and up to 25 seconds to finish. A slow lookup or a provider
refusal is not fixed by streaming earlier: the Play ring clears on failure without substituting
an upload or using a login. A pre-existing offline file is always opened without that lookup.

The selected track takes priority. After it starts and its offline file finishes, the single worker
prepares the next **two actual queue slots** on unmetered connections. In the player’s queue, **Prepare one next song on mobile data**
is off by default. Turning it on allows just the immediate next track (up to six minutes) on a
metered connection; Android Data Saver still takes precedence. An empty, long, or duplicate first
slot does not pull a later song into that mobile slot. A complete queued file plays locally, and
an in-flight file is promoted without restarting when it becomes current. If the next track isn't
ready, the app waits for that same track, not a replacement. Closing the player or changing the queue
cancels irrelevant work. **Download album** remains a separate, lower-priority action on unmetered connections.

Completed files are reused when you play the same recording again, including in airplane mode.
Previously played songs appear in the existing Music downloads, not in a new preparation shelf.
Audio stays in a private cache (64 files or 256 MiB, with least-recently-used eviction outside the
active queue). No cached audio goes to Local Share, the APK, or the Drive helper backup. If a
recording has been evicted, it needs a connection to fetch again.

The earlier `1.4.1` port's provider playback [did not pass acceptance](https://github.com/whtsel/OCR/actions/runs/44453268585).
The diagnostic proved the pinned engine could play and reuse a recording on a phone; it was not
this full app. Fixture tests here verify the one-tap UI, audio decoder/seek, queue, and offline
flow separately from live recording availability.

## What the sources support

- **Direct media:** use codecs your phone supports. H.264 MP4 and AAC/MP3 are good first tests. HTTPS HLS is for streaming, not offline file downloads. An M3U file or URL may import up to 500 exact source entries (max 1 MiB). Playable web pages and DRM streams are not download sources.
- **BitTorrent:** v1 and hybrid files, selected audio/video files in multifile torrents, TCP peers, BEP 9 metadata, HTTP(S)/UDP trackers, bounded IPv4 DHT peer lookup, HTTPS BEP 19 `url-list` / magnet `ws` web seeds and hash-matched HTTPS `xs` descriptors. Invalid byte ranges or piece hashes are not published to the player. v2-only and uTP-only/MSE-only swarms and uploading/seeding are not supported. Private `.torrent` downloads do not use DHT. Trackerless magnets use DHT to resolve metadata. Peers/trackers can see the downloader IP; connections happen only after choosing a magnet or torrent. A stopped torrent job may restart incomplete pieces rather than resume from disk. A completed file is always verified before it enters Offline.
- **Privacy and discovery:** the bundled UI runs in a restricted private WebView origin, with no remote JS, unrestricted Chromium navigation, or file-origin access. The Android adapter sends a TMDB credential only to TMDB. A lookup sends its title and episode parameters to the source indexes; index-supplied peer addresses and arbitrary web seeds are not trusted. Torrent peers can see your IP. This product uses the TMDB API but is not endorsed or certified by TMDB.
- **Local Share:** fifteen-minute, explicit per-file share. The sender listens only on the selected private IPv4 interface; the receiver uses that local network. TLS checks the sender's fingerprint from a high-entropy invitation before sending its key. No cloud relay, port mapping, implicit access to other files, or public discovery. Only the intended recipient should get the invite. Some guest Wi-Fi networks isolate devices. The new protocol is Android BlackBox-to-BlackBox, not the old Windows LAN protocol. Finished copies can also be exported through the Android share sheet.
- **Storage:** app-private SQLite/library/media, no broad filesystem permission. Finished files only are shareable. Direct HTTPS downloads can resume with a matching strong ETag and byte range; the limit is 4 GiB per file. Android schedules jobs based on battery/network availability. Uninstalling removes the library and private copies, not originals or exported files.

## Legacy film credits and import tests

The [WebTorrent open-film collection](https://github.com/webtorrent/webtorrent/blob/master/docs/free-torrents.md) supplies the four pinned torrent descriptors and HTTPS seeds. Descriptors and artwork are retained for compatible older Library records and torrent tests. They are not a Streaming shelf, catalogue fallback, or demo video. Film and artwork are provided under [Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/).

| Film | Creator / original project |
| --- | --- |
| *Sintel* (2010) | [Blender Foundation / Durian](https://studio.blender.org/projects/sintel/) — artwork is a frame from the film. |
| *Big Buck Bunny* (2008) | [Blender Foundation / Peach](https://studio.blender.org/projects/big-buck-bunny/) — cropped project artwork. |
| *Tears of Steel* (2012) | [Blender Foundation / Mango](https://studio.blender.org/projects/tears-of-steel/) — cropped film still. |
| *Cosmos Laundromat: First Cycle* (2015) | [Blender Institute / Gooseberry](https://studio.blender.org/projects/cosmos-laundromat/) — cropped project artwork. |

## Optional public-file import verification

- [*The Dull Sword* (1917)](https://commons.wikimedia.org/wiki/File:Namakura_Gatana_with_music.webm), Jun’ichi Kōuchi. Public-domain film in Japan and the US; “Ishikari Lore” by Kevin MacLeod (incompetech.com), [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
- [*The Animal Olympics* (1928)](https://commons.wikimedia.org/wiki/File:The_Animal_Olympics_(1928).webm), Yasuji Murata. Public-domain silent animation in Japan and the US.

Files downloaded from Incompetech in 1.4.0 remain in Library with their original Kevin MacLeod / [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) credit. They are not part of the restored catalogue.

## Community relay

No public community server is configured or being deployed by this APK. `community/relay.py` is a working, small self-hosted relay, not a dependency of direct playback or Local Share. To use it with phones, run on a host you control and put it behind publicly trusted HTTPS. For example:

```sh
python3 community/relay.py --bind 127.0.0.1 --port 8765 --db /path/to/blackbox-community.sqlite3
```

Example Caddy configuration (replace the hostname with one you control):

```caddyfile
community.example.org {
    reverse_proxy 127.0.0.1:8765
}
```

Use `https://community.example.org` in **Create room**, then send the generated invitation to the second client. The room key stays in the invitation and client memory; the relay stores AES-GCM encrypted envelopes only, retains up to 100 events per room for at most seven days, and applies request/size limits. It still sees IPs, time, room identifiers and ciphertext. Members can forward invitations and impersonate display names, so use rooms only with people you trust. Start a new room to rotate a shared room key. There is no auto-connect, legacy public mesh, subtitle commons, public vote syncing, or compatibility with p11's protocol.

## Build

Open `android/` in Android Studio or use JDK 17, Gradle 8.9, Android SDK 35 and Build Tools 35.0.0:

```sh
cd android
(cd vendor && sha256sum -c ytdlp.sha256)
gradle :app:assembleDebug -PbbxFastStartPreview=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle includes the Music engine by default. Use `-PbbxMusicHelper=false` only for baseline
transport testing. `./tools/build_apk.sh` also builds a dependency-free transport test APK
without the Music engine. Use a consistent release signing key for updates. No private keys or
SDK binaries belong in Git. `python3 tools/make_samples.py` recreates the test-only media
(Pillow, DejaVu fonts, and FFmpeg with libx264/AAC).

`vendor/ytdlp` is the exact official **2026.10.26** zipapp tested on the diagnostic phone. The
helper build includes the youtubedl-android **0.18.1** runtime and Commons Compress. See the
[upstream Android wrapper source](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760)
and the [pinned extractor release](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26) for
source and licensing. The Gradle task, on-device loader, and APK verification check the pin. CI
also compares the six ARM64 runtime files with the exact diagnostic binaries. For a test on an
x86_64 Android emulator, add `-PbbxHelperAbi=x86_64`, build `:app:assembleDebugAndroidTest` as well,
and run `BBX_TEST_PACKAGE=com.blackboxstream.mobile.music.fast BBX_VERIFY_HELPER_FIXTURE=1 ./tests/run_device.sh`.
That test exercises the pinned runtime and conversion. It withholds the second half of a test-only
audio file and requires the original v90 player to audibly advance before the native worker can
finish that file. It also checks byte ranges, the original whole-file fallback, unchanged queue
handoff, cancellation, seek, and offline reuse. Run `python3 -B
tests/test_catalogue_music.py` for the controlled source-selection checks. A properly signed
helper build without a preview flag uses the usual `com.blackboxstream.mobile` app ID. This is a candidate; live catalogue audio and separately imported public video still need device acceptance.

## Tests

From `android/` with JDK 17, Python 3 and OpenSSL:

```sh
python3 tests/run_host.py
# Requires Python libtorrent 2.1.1 (fixture only; the APK is pure Java):
python3 tests/run_peers.py
# Requires a host-only org.json implementation, e.g. org.json:json:20240303:
JSON_JAR=/path/to/json-20240303.jar python3 tests/run_services.py
```

The HTTPS host checks cover direct links, magnet/M3U imports, offline transfers, exact descriptor hashes, incorrect web-seed ranges/bytes and cross-file pieces. The peer tests use an independent real libtorrent seeder and local HTTP/UDP/DHT fixtures to verify magnet metadata, progressive seek reads, and exact selected files. They also exercise TLS-pinned LAN streaming and copying, unauthorized IDs/keys and revocation. Service checks exercise two encrypted Community clients through the SQLite relay, TMDB/MusicBrainz transport, and Knaben, Torrents-CSV, Jikan and AniList response handling and failover. They cover exact title/episode filtering, deduplication, rejection of index-provided peer addresses and untrusted web seed URLs, and parsing and validation of licensed anime catalogue entries.

To test a torrent on a real phone with a controlled source, run `python3 tests/seed_android_demo.py --bind 192.168.1.20` on a computer that has Python libtorrent, replacing the address with the computer's private Wi-Fi IPv4 address. It prints a magnet and creates a `.torrent` for the test-only sample. Put your phone on the same Wi-Fi and try either input. Only the demo asset is seeded; no DHT/UPnP service or public ports are opened by this fixture.

For Android runtime verification, use an unlocked emulator/phone:

```sh
gradle :app:assembleDebug :app:assembleDebugAndroidTest -PbbxMusicHelper=false
./tests/run_device.sh
# With libtorrent and OpenSSL: also forward a controlled seeder and HTTPS relay with adb reverse.
python3 tests/run_device_fixtures.py
# Separate build and fresh emulator/device package for live Music acceptance.
gradle :app:assembleDebug :app:assembleDebugAndroidTest -PbbxFastStartPreview=true -PbbxHelperAbi=x86_64
BBX_TEST_PACKAGE=com.blackboxstream.mobile.music.fast BBX_VERIFY_MUSIC_ONLY=1 ./tests/run_device.sh
# Independently hash two authorized public files, then test explicit imports and offline replay.
BBX_VERIFY_PUBLIC_MEDIA=1 python3 tests/run_device_fixtures.py
```

`BlackBoxSmoke` checks that a keyless Streaming home, search, and Anime page do not show test
media. It decodes and seeks a test-only video from a private fixture provider, saves and reopens
that file with the provider disabled, and checks native audio and LAN streaming. The forwarded
fixtures add exact torrent manifest, progressive playback and complete-file verification, plus
Community relay coverage. APK checks enforce the pin, original v90 assets, and exclusion of the
sample video, audio and song-video activity. TMDB transport tests use controlled HTTPS responses,
not a customer key. The optional public-file check supplies the full films as explicit HTTPS
imports and compares the offline copies with independently collected hashes. It does not put the
films back in Streaming. A live Music pass requires five cold plays from original album and shelf
controls (three unpinned), full duration, audio progression, seeking past the preview window, and
Downloads replay after reopening with all networking off. The GitHub Actions `verify_live_music`
input runs this independent gate; `verify_live_unpinned=true` tests one cold, unpinned card.
`verify_live_known_album=true` runs only the no-account direct-ID probe for Olivia Rodrigo’s
*stupid song*. Instrumentation supplies the [label's album audio ID](https://www.youtube.com/watch?v=P7VgXIZSN_w)
to the original Pop Hits Play control. The standard album and full length agree with the catalogue;
the longer video and the single upload are not probe inputs. This mode uses the existing native
helper and player, without editing the shipped card, installing a new pin, or uploading an APK.
It requires advancing audio, a saved full file, seek, and Downloads replay after reopening offline.
Provider refusals fail the gate, even if separate fixture and APK verification pass. The live gate
measures advancing audio separately from the first observed offline publication; fixture and CI
network timing are not a promise about live tracks or a phone's mobile data. The summary records
sanitized stages and timings; no audio, account data, or temporary media links are saved as results.
