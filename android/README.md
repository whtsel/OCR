# BlackBox Stream v90 for Android

This build ports the **actual v90 desktop interface**, not the previous Android watch shelf. The 41 original HTML, CSS, and JavaScript assets from the supplied v90 installer are in `app/src/main/assets/v90/`. `V90-ASSETS.sha256` verifies the original bytes. The installer and v88 source archive did not contain editable source for the Windows engines, so the port supplies the same-origin catalogue and source API using Android components. It does not run a Windows executable, expose an HTTP server, or require a PC.

## Install and use

Install **BlackBox-Stream-Android-1.4.1.apk**. It targets Android 15, supports Android 7.0+ with an up-to-date Android System WebView, and updates earlier Android ports when signed by the same key. p11 is a separate app. This is a signed sideload, not a Play Store release. As in the PC v90 release, the interface expires after **31 March 2027 (UTC)**.

1. In **Settings**, add your TMDB v3 API key or Read Access Token for v90 movie/series artwork, catalogue, search, and details. **Remember on this device** is optional. **My List** stores your choices locally.
2. No key required: try **Search → Sintel → Play in BlackBox**, **Anime → Playable anime → The Dull Sword**, or **Music → Trending Now → Espresso**. For downloadable sources, use **Download** to save a complete file, then **Library → Offline copy → Play/Resume** to reopen it without a network connection. Use Wi-Fi for large video downloads.
3. For other titles, look under **Sources**, choose an authorized direct file or an indexed release, and select the exact file if there is more than one. Metadata, trailers, and index results do not guarantee playback. You can also supply your own direct HTTPS file, magnet, `.torrent`, M3U or audio file.

| Feature | Android behavior |
| --- | --- |
| **Movies, Series, and Anime** | v90 browsing, detail cards and season/episode/source filters. TMDB supplies movies and series, and Jikan supplies Anime metadata with AniList as a fallback. Regional official watch listings open the provider guide outside the app. |
| **Online sources** | Search the selected title or episode through Knaben and Torrents-CSV. The provider results are candidates, not a promise of playable seeds or download rights. No media starts from a search result until you explicitly choose a source. The selected job, v1 info hash, and file index must agree. |
| **Open/import** | **Settings → Open a torrent directly** accepts a magnet, HTTPS `.torrent`, or direct HTTPS media. **Choose .torrent file** supports local descriptors. **Import M3U or phone media** opens the device library with Android's file picker. Magnet intents fill the picker but do not start downloads. |
| **Player and offline** | Android's native player opens direct HTTPS files, phone media and verified torrent pieces. **Download** queues a saved copy without starting the player. **Library** shows progress, cancellation, and retry; completed copies are reopened locally. Large films can use hundreds of MB. |
| **Music** | The original 14 genres, artist search, album art, ordered tracklists, playlists and queue. A title with a matched official recording opens the visible provider player **inside BlackBox**. Other recordings need an authorized source. Select their exact audio file on your phone or link an authorized direct HTTPS file, and optionally save a private offline copy through Library. |
| **Anime and trailers** | The playable archive shelf uses identified full Wikimedia Commons files. Other anime listings need a working episode source or your own file; Jikan/AniList only supply metadata. Optional **Find official trailer** is labeled separately and opens a browser. Windows-only embedded anime hosts, `yt-dlp`, and browser-cookie access are not included. |
| **Local Share and rooms** | **Local Share** opens the Android secure Wi-Fi sharing protocol. **Settings → Community rooms** supports private encrypted recommendations through your own HTTPS relay. They are not compatible with the Windows binary's network protocols. Sponsored boxes and public peer votes are off in the port. |

## Original Music playback

The bundled index lists **2,620 albums and 35,995 track rows**. It does not include the audio or grant access to all those recordings. The Android port remembers links per selected recording; a missing source never falls back to a different track, cover, or preview. Where an official match is linked, the visible online player plays that published recording when SoundCloud or YouTube permits it in your region. Embeds do not capture audio, play in the background, or save downloads. A matched link is not a guarantee of live playback.

For any unlinked title, **Play** opens its source controls. To use your own authorized recording for a title that already has a link, use **Source for selected track** after selecting it. Choose the matching audio file on your phone, link a direct HTTPS file, or provide the official YouTube link for online listening. Native audio uses normal Android playback and Library; supported direct files and phone files can be copied offline. The app cannot independently verify the identity or rights of files you attach. MusicBrainz is a metadata fallback, not a streaming service. The Windows downloader and automatic genre radio are not part of this port.

The app's catalogue stays in a network-restricted WebView. Only explicit online playback opens a separate, visible Android WebView without the catalogue's native bridge. The [official SoundCloud widget](https://developers.soundcloud.com/docs/api/html5-widget) or [YouTube IFrame player](https://developers.google.com/youtube/iframe_api_reference) receives the selected recording ID. Their respective [SoundCloud](https://soundcloud.com/pages/privacy) and [Google](https://policies.google.com/privacy) privacy policies apply. Leaving that screen stops online playback.

## What the sources support

- **Direct media:** use codecs your phone supports. H.264 MP4 and AAC/MP3 are good first tests. HTTPS HLS is for streaming, not offline file downloads. An M3U file or URL may import up to 500 exact source entries (max 1 MiB). Playable web pages and DRM streams are not download sources.
- **BitTorrent:** v1 and hybrid files, selected audio/video files in multifile torrents, TCP peers, BEP 9 metadata, HTTP(S)/UDP trackers, bounded IPv4 DHT peer lookup, HTTPS BEP 19 `url-list` / magnet `ws` web seeds and hash-matched HTTPS `xs` descriptors. Invalid byte ranges or piece hashes are not published to the player. v2-only and uTP-only/MSE-only swarms and uploading/seeding are not supported. Private `.torrent` downloads do not use DHT. Trackerless magnets use DHT to resolve metadata. Peers/trackers can see the downloader IP; connections happen only after choosing a magnet or torrent. A stopped torrent job may restart incomplete pieces rather than resume from disk. A completed file is always verified before it enters Offline.
- **Privacy and discovery:** the bundled UI runs in a restricted private WebView origin, with no remote JS, unrestricted Chromium navigation, or file-origin access. The Android adapter sends a TMDB credential only to TMDB. A lookup sends its title and episode parameters to the source indexes; index-supplied peer addresses and arbitrary web seeds are not trusted. Torrent peers can see your IP. This product uses the TMDB API but is not endorsed or certified by TMDB. The Blender titles are full films and are distinct from the examples in the native fallback library.
- **Local Share:** fifteen-minute, explicit per-file share. The sender listens only on the selected private IPv4 interface; the receiver uses that local network. TLS checks the sender's fingerprint from a high-entropy invitation before sending its key. No cloud relay, port mapping, implicit access to other files, or public discovery. Only the intended recipient should get the invite. Some guest Wi-Fi networks isolate devices. The new protocol is Android BlackBox-to-BlackBox, not the old Windows LAN protocol. Finished copies can also be exported through the Android share sheet.
- **Storage:** app-private SQLite/library/media, no broad filesystem permission. Finished files only are shareable. Direct HTTPS downloads can resume with a matching strong ETag and byte range; the limit is 4 GiB per file. Android schedules jobs based on battery/network availability. Uninstalling removes the library and private copies, not originals or exported files.

## Open cinema credits

The [WebTorrent open-film collection](https://github.com/webtorrent/webtorrent/blob/master/docs/free-torrents.md) supplies the four pinned torrent descriptors and HTTPS seeds. The APK contains the descriptors and cropped artwork, not the film files. Film and artwork are provided under [Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/).

| Film | Creator / original project |
| --- | --- |
| *Sintel* (2010) | [Blender Foundation / Durian](https://studio.blender.org/projects/sintel/) — artwork is a frame from the film. |
| *Big Buck Bunny* (2008) | [Blender Foundation / Peach](https://studio.blender.org/projects/big-buck-bunny/) — cropped project artwork. |
| *Tears of Steel* (2012) | [Blender Foundation / Mango](https://studio.blender.org/projects/tears-of-steel/) — cropped film still. |
| *Cosmos Laundromat: First Cycle* (2015) | [Blender Institute / Gooseberry](https://studio.blender.org/projects/cosmos-laundromat/) — cropped project artwork. |

## Open anime credits

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
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

There are no external Android library dependencies. `./tools/build_apk.sh` builds and test-signs the standalone release APK using AAPT2, javac, D8 and apksigner. Set `ANDROID_SDK_ROOT` and `JAVA_HOME` if needed. The default output is `build/BlackBox-Stream-Android-1.4.1.apk`. Only an APK signed with the same key can update an installed copy. Use release signing for distribution; no private keys or SDK binaries belong in Git. `python3 tools/make_samples.py` recreates the original samples (Pillow, DejaVu fonts, FFmpeg with libx264/AAC).

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

To test a torrent on a real phone with a controlled source, run `python3 tests/seed_android_demo.py --bind 192.168.1.20` on a computer that has Python libtorrent, replacing the address with the computer's private Wi-Fi IPv4 address. It prints a magnet and creates a `.torrent` for the original bundled sample. Put your phone on the same Wi-Fi and try either input. Only the demo asset is seeded; no DHT/UPnP service or public ports are opened by this fixture.

For Android runtime verification, use an unlocked emulator/phone:

```sh
gradle :app:assembleDebug :app:assembleDebugAndroidTest
./tests/run_device.sh
# With libtorrent and OpenSSL: also forward a controlled seeder and HTTPS relay with adb reverse.
python3 tests/run_device_fixtures.py
# Emulator: play the exact original Music selections in the official player.
# Play and download the full film/anime files, compare the bytes, and reopen offline.
BBX_VERIFY_LIVE_FILM=1 BBX_VERIFY_PUBLIC_MEDIA=1 python3 tests/run_device_fixtures.py
# Independently check the first and last video pieces for all four public films:
python3 tests/check_open_films.py
```

`BlackBoxSmoke` opens the real screens, decodes and seeks the bundled video, schedules a private copy using a debug-only data source, plays that copy after its source is unavailable, streams it from the native TLS LAN byte source, and tests audio playback/background. The forwarded fixtures additionally find an imported magnet in the Library, resolve metadata from an independent libtorrent seed, play while the transfer is incomplete, compare the final offline copy, and send a recommendation through the actual Community relay. The live check opens *Sintel* from the **original v90 search screen and source list**, verifies that the native player renders and advances on the full film, then seeks to 2:30 before the transfer finishes. Public playback acceptance checks the direct *Sintel* film and *The Dull Sword*, comparing every download with an independent full HTTPS copy before switching the emulator offline. Music acceptance opens *Espresso* from the original *Short n’ Sweet* tracklist and *Kwaku the Traveller* from *Ghana Essentials*, requiring the official player to advance on the expected recording and seek beyond a preview. A matched link or a test clip does not count. CI packages only after these tests pass, repeats the same public checks on the signed standalone app, and then retains that build for distribution signing. TMDB auth/catalogue regression tests use controlled HTTPS responses, not a live customer credential. Provider availability, seed counts and supported codecs can vary by phone, network and title. Test two phones on real Wi-Fi for Local Share network routing. Host tests do not establish Android playback performance or compatibility with arbitrary codecs, swarms, providers, or networks.
