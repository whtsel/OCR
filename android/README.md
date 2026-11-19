# BlackBox Stream for Android

BlackBox is a new native Android player and library, independent of p11 and the Windows app. This version includes direct links/files, offline copies, BitTorrent, discovery, encrypted Local Share, and invite-only Community rooms. There is no WebView, desktop engine or account requirement.

## Install and try it

Install **BlackBox-Stream-Android-1.1.0.apk** on Android 7.0 or newer. This is a test-signed build, not a Play Store release. It can update the previous native BlackBox test build if the signing key matches; the app package remains separate from p11. Android may ask you to allow the app opening the APK to install it.

| Feature | How to try it |
| --- | --- |
| **Player and offline** | Tap **Play sample** for the bundled 14-second film. Audio sample *Low Orbit* is also built in. Open your own phone file or direct HTTPS media link, tap **Keep offline**, then play the finished copy from **Offline**. Local copies and playback position persist. Audio can continue in the background. |
| **Torrents and magnets** | Tap **Torrents**. Paste a v1/hybrid `magnet:?xt=urn:btih:…` link or pick a `.torrent` document. Choose the exact media file. Verified pieces can be read by the Android player while the job runs; a completed copy stays in **Offline**. The peer must be reachable and have the needed pieces. Use content you own or are authorized to download. |
| **Discover** | Tap **Discover** for playable open media and offline samples. Add your own TMDB key in **Catalogue key** for Movies, Series and Anime discovery/search. In **Music**, search MusicBrainz tracks, albums and artists without a key. Save catalogue metadata in **Saved titles**. Metadata results do not provide the film/song; open a matching source from your own Library or visit the official listing. |
| **Local Share** | Put two BlackBox devices on the same private Wi-Fi/Ethernet. On the sender choose **Local Share → Choose media to share**, select a bundled sample or completed offline file, and send its invitation privately. On the receiver paste it under **Receive** and tap **Connect securely**. Select a title to stream or **Keep offline** to save a private copy. **Stop sharing** revokes the invitation; rotating it changes both its secret and its TLS identity. |
| **Community** | On **Community**, create a room on a configured HTTPS relay or paste a `BBX-ROOM-1` invitation. Share the invitation privately, then post encrypted messages or exact HTTPS media recommendations. A second invited client with the key can read them. [The relay setup is below.](#community-relay) |

## What the sources support

- **Direct media:** use codecs your phone supports. H.264 MP4 and AAC/MP3 are good first tests. HTTPS HLS is for streaming, not offline file downloads. An M3U file or URL may import up to 500 HTTPS items (max 1 MiB). Playable web pages and DRM streams are not download sources.
- **BitTorrent:** v1 and hybrid files, selected audio/video files in multifile torrents, TCP peers, BEP 9 metadata, HTTP(S)/UDP trackers, and bounded IPv4 DHT peer lookup. v2-only, uTP-only/MSE-only swarms, web seeds and uploading/seeding are not implemented. Private `.torrent` downloads do not use DHT. Trackerless magnets use DHT to resolve metadata. Peers/trackers can see the downloader IP; connections happen only after choosing a magnet or torrent. A stopped torrent job may restart incomplete pieces rather than resume from disk. A completed file is always verified before it enters Offline.
- **Discovery:** open assets are exact pinned MDN examples (Blender Foundation's *Big Buck Bunny* and *Sintel* excerpts, CC BY 3.0, and the MDN CC0 *Flower* clip) plus BlackBox's original samples. Metadata comes from TMDB and MusicBrainz; it isn't a built-in source of copyrighted streams. Keys are kept in app-private storage, not sent to a BlackBox server. This product uses the TMDB API but is not endorsed or certified by TMDB.
- **Local Share:** fifteen-minute, explicit per-file share. The sender listens only on the selected private IPv4 interface; the receiver uses that local network. TLS checks the sender's fingerprint from a high-entropy invitation before sending its key. No cloud relay, port mapping, implicit access to other files, or public discovery. Only the intended recipient should get the invite. Some guest Wi-Fi networks isolate devices. The new protocol is Android BlackBox-to-BlackBox, not the old Windows LAN protocol. Finished copies can also be exported through the Android share sheet.
- **Storage:** app-private SQLite/library/media, no broad filesystem permission. Finished files only are shareable. Direct HTTPS downloads can resume with a matching strong ETag and byte range; the limit is 4 GiB per file. Android schedules jobs based on battery/network availability. Uninstalling removes the library and private copies, not originals or exported files.

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

There are no external Android library dependencies. `./tools/build_apk.sh` builds and test-signs the standalone release APK using AAPT2, javac, D8 and apksigner. Set `ANDROID_SDK_ROOT` and `JAVA_HOME` if needed. The default output is `build/BlackBox-Stream-Android-1.1.0.apk`. Only an APK signed with the same key can update an installed copy. Use release signing for distribution; no private keys or SDK binaries belong in Git. `python3 tools/make_samples.py` recreates the original samples (Pillow, DejaVu fonts, FFmpeg with libx264/AAC).

## Tests

From `android/` with JDK 17, Python 3 and OpenSSL:

```sh
python3 tests/run_host.py
# Requires Python libtorrent 2.1.1 (fixture only; the APK is pure Java):
python3 tests/run_peers.py
# Requires a host-only org.json implementation, e.g. org.json:json:20240303:
JSON_JAR=/path/to/json-20240303.jar python3 tests/run_services.py
```

The HTTPS host checks cover original direct links/playlists and offline transfers. The peer tests use an independent real libtorrent seeder and local HTTP/UDP/DHT fixtures to verify magnet metadata, progressive seek reads, and exact selected files. They also exercise TLS-pinned LAN streaming and copying, unauthorized IDs/keys and revocation. Service checks exercise two actual encrypted Community clients through the SQLite relay and controlled TMDB/MusicBrainz API responses; live provider credentials are not part of the tests.

To test a torrent on a real phone with a controlled source, run `python3 tests/seed_android_demo.py --bind 192.168.1.20` on a computer that has Python libtorrent, replacing the address with the computer's private Wi-Fi IPv4 address. It prints a magnet and creates a `.torrent` for the original bundled sample. Put your phone on the same Wi-Fi and try either input. Only the demo asset is seeded; no DHT/UPnP service or public ports are opened by this fixture.

For Android runtime verification, use an unlocked emulator/phone:

```sh
gradle :app:assembleDebug :app:assembleDebugAndroidTest
./tests/run_device.sh
# With libtorrent and OpenSSL: also forward a controlled seeder and HTTPS relay with adb reverse.
python3 tests/run_device_fixtures.py
```

`BlackBoxSmoke` opens the real screens, decodes and seeks the bundled video, schedules a private copy using a debug-only data source, plays that copy after its source is unavailable, streams it from the native TLS LAN byte source, and tests audio playback/background. The forwarded fixtures additionally resolve a magnet from a real independent seed, play the selected file and compare the offline copy, and send a recommendation through the actual Community relay. They use controlled HTTPS catalogue responses, not live provider credentials. Test two phones on real Wi-Fi for network routing and select your own authorized Internet media for a live network test. Host tests do not establish Android playback performance or compatibility with arbitrary codecs, swarms, providers, or networks.
