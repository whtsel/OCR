# BlackBox Stream for Android

BlackBox is a native Android counterpart to the PC app's browse → title → source → player flow. It does not run a WebView or require the PC engine. The built-in **Open cinema** shelf contains four complete, licensed films; it does not reproduce the v90 title/source inventory or automatic source search. Add your own authorized sources to expand the catalogue.

## Install and watch

Install **BlackBox-Stream-Android-1.2.0.apk** on Android 7.0 or newer. This is a signed sideload build, not a Play Store release. It updates the previous native BlackBox build when installed with the same signer; it is a different package from p11. Android may ask you to allow installs from the app opening the APK.

Open **Watch** and tap **Watch now** on *Sintel*, or pick any title in **Open cinema** and tap **Watch torrent**. **Sources** lets you choose the verified torrent or its original HTTPS file. Playback can begin as verified pieces arrive, and the completed torrent file appears in **Offline**. Film transfers use Internet data and may be large (roughly 129–571 MB each); use Wi-Fi if you prefer. You can cancel an unfinished copy from **Offline** or the player.

| Feature | How to use it |
| --- | --- |
| **Find a title** | Search the included films and your saved Library. **Add media → Import an M3U playlist** brings in your own titles with exact HTTPS files, HTTPS `.torrent` links or v1/hybrid magnets. Search the imported titles in **Watch** or **Library**. |
| **Torrents and magnets** | Tap **Torrents** under **Your sources**, use **Add media**, or open a magnet from another Android app. Paste a magnet / HTTPS `.torrent` link or pick a `.torrent` document, then select the exact media file. A peer or web seed must have the required pieces. |
| **Player and offline** | Open a film, phone file or direct HTTPS link. Tap **Keep offline** for supported non-torrent sources; torrents keep a copy if the transfer completes. Offline media, watch position and Library entries persist. Music can continue in the background. The samples at the bottom of **Watch** require no connection. |
| **Discover** | Add your TMDB credential in **Catalogue key** for film, series and anime metadata browsing. **Music** searches MusicBrainz tracks, albums and artists without a key. Keep metadata in **Saved titles**. Apart from exact Open cinema films, a metadata result is not a playable source. Use **Find my sources** to match a saved source or open the official listing. |
| **Local Share** | Put two BlackBox devices on the same private Wi-Fi/Ethernet. On the sender choose **Local Share → Choose media to share**, select a bundled sample or completed offline file, and send its invitation privately. On the receiver paste it under **Receive** and tap **Connect securely**. Select a title to stream or **Keep offline** to save a private copy. **Stop sharing** revokes the invitation; rotating it changes both its secret and its TLS identity. |
| **Community** | On **Community**, create a room on a configured HTTPS relay or paste a `BBX-ROOM-1` invitation. Share the invitation privately, then post encrypted messages or exact HTTPS media recommendations. A second invited client with the key can read them. [The relay setup is below.](#community-relay) |

## What the sources support

- **Direct media:** use codecs your phone supports. H.264 MP4 and AAC/MP3 are good first tests. HTTPS HLS is for streaming, not offline file downloads. An M3U file or URL may import up to 500 exact source entries (max 1 MiB). Playable web pages and DRM streams are not download sources.
- **BitTorrent:** v1 and hybrid files, selected audio/video files in multifile torrents, TCP peers, BEP 9 metadata, HTTP(S)/UDP trackers, bounded IPv4 DHT peer lookup, HTTPS BEP 19 `url-list` / magnet `ws` web seeds and hash-matched HTTPS `xs` descriptors. Invalid byte ranges or piece hashes are not published to the player. v2-only and uTP-only/MSE-only swarms and uploading/seeding are not supported. Private `.torrent` downloads do not use DHT. Trackerless magnets use DHT to resolve metadata. Peers/trackers can see the downloader IP; connections happen only after choosing a magnet or torrent. A stopped torrent job may restart incomplete pieces rather than resume from disk. A completed file is always verified before it enters Offline.
- **Discovery:** the full films are separate from the MDN examples (Blender Foundation's *Big Buck Bunny* and *Sintel* excerpts and the CC0 *Flower* clip) and BlackBox's original samples. Metadata comes from TMDB and MusicBrainz. Credentials are kept in app-private storage, not sent to a BlackBox server. This product uses the TMDB API but is not endorsed or certified by TMDB.
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

There are no external Android library dependencies. `./tools/build_apk.sh` builds and test-signs the standalone release APK using AAPT2, javac, D8 and apksigner. Set `ANDROID_SDK_ROOT` and `JAVA_HOME` if needed. The default output is `build/BlackBox-Stream-Android-1.2.0.apk`. Only an APK signed with the same key can update an installed copy. Use release signing for distribution; no private keys or SDK binaries belong in Git. `python3 tools/make_samples.py` recreates the original samples (Pillow, DejaVu fonts, FFmpeg with libx264/AAC).

## Tests

From `android/` with JDK 17, Python 3 and OpenSSL:

```sh
python3 tests/run_host.py
# Requires Python libtorrent 2.1.1 (fixture only; the APK is pure Java):
python3 tests/run_peers.py
# Requires a host-only org.json implementation, e.g. org.json:json:20240303:
JSON_JAR=/path/to/json-20240303.jar python3 tests/run_services.py
```

The HTTPS host checks cover direct links, magnet/M3U imports, offline transfers, exact descriptor hashes, incorrect web-seed ranges/bytes and cross-file pieces. The peer tests use an independent real libtorrent seeder and local HTTP/UDP/DHT fixtures to verify magnet metadata, progressive seek reads, and exact selected files. They also exercise TLS-pinned LAN streaming and copying, unauthorized IDs/keys and revocation. Service checks exercise two actual encrypted Community clients through the SQLite relay and controlled TMDB/MusicBrainz API responses; live provider credentials are not part of the tests.

To test a torrent on a real phone with a controlled source, run `python3 tests/seed_android_demo.py --bind 192.168.1.20` on a computer that has Python libtorrent, replacing the address with the computer's private Wi-Fi IPv4 address. It prints a magnet and creates a `.torrent` for the original bundled sample. Put your phone on the same Wi-Fi and try either input. Only the demo asset is seeded; no DHT/UPnP service or public ports are opened by this fixture.

For Android runtime verification, use an unlocked emulator/phone:

```sh
gradle :app:assembleDebug :app:assembleDebugAndroidTest
./tests/run_device.sh
# With libtorrent and OpenSSL: also forward a controlled seeder and HTTPS relay with adb reverse.
python3 tests/run_device_fixtures.py
# Also exercise native playback and seek of the full public Sintel torrent:
BBX_VERIFY_LIVE_FILM=1 python3 tests/run_device_fixtures.py
# Independently check the first and last video pieces for all four public films:
python3 tests/check_open_films.py
```

`BlackBoxSmoke` opens the real screens, decodes and seeks the bundled video, schedules a private copy using a debug-only data source, plays that copy after its source is unavailable, streams it from the native TLS LAN byte source, and tests audio playback/background. The forwarded fixtures additionally find an imported magnet in the Library, resolve metadata from an independent libtorrent seed, play while the transfer is incomplete, compare the final offline copy, and send a recommendation through the actual Community relay. The live check launches the featured title from **Watch**, requires a rendered frame and advancing playback of the full *Sintel*, then seeks to 2:30 before the download finishes. TMDB and MusicBrainz checks use controlled HTTPS responses, not live provider credentials. Test two phones on real Wi-Fi for Local Share network routing. Host tests do not establish Android playback performance or compatibility with arbitrary codecs, swarms, providers, or networks.
