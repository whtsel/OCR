# BlackBox Music — offline pilot

The two-recording pilot uses the **whole-file** p11 helper that powered `BlackBox Helper Test`
(`8854adc1a129717ea6b5053d4d174f2cb7e0919e`). It is not the `.helperstream` adapter or the
v90 fast-start pipeline. `build.sh` checks the archived p11, preserves its DEX and ARM64
native bytes, and uses the same Commons Compress addition and verified `2026.10.26`
zipapp. The shell calls the original `BundledHelper.audio(id)` directly. There is no
loopback server, dynamically resolved ID, cookie import, or alternate recording.

- **Collection:** saved songs, a device-only rotation, and saved tracks grouped by release.
  Play opens only verified files from private storage using Android's player.
- **Find:** filters the two exact recordings by song, artist, or release. Queue either
  recording or select a group. Broad search, custom playlists, and full-album queues are
  intentionally not in the first trial.
- **Downloads:** one active helper request, distinct waiting, failure, retry and saved states.
  New requests start on unmetered Wi-Fi unless the user opts in for a group. A running
  request is not cancelled on a network switch or by Pause after current. There is no
  progress percentage and no promise of jobs running when Android stops the app.

Queue intent and failures are stored in app-private preferences. Verified, complete M4A
files are moved atomically from staging to private collection storage (not cache). A
cold offline launch and playback do not initialize the downloader. The package is a
separate, test-signed install (`com.blackbox.stream.collectionpilot`). It doesn't touch
`com.blackbox.stream.phonetest`, `.helperstream`, or the original install/data. Signatures
from separate builds are not an upgrade path.

## Phone acceptance test

1. Install `BlackBox-Music-Offline-Pilot-ARM64.apk` next to the old tester. Open **BlackBox Music**.
2. On Wi-Fi, open **Find → Queue both**. Watch Kwaku the Traveller and Espresso in **Downloads**.
   Wait for each to say **Saved on this device**. Report any **Needs attention** message as
   written; a provider refusal isn't a download or a substitute.
3. Turn on airplane mode and make sure Wi-Fi is off. Force-stop **BlackBox Music** in Android
   Settings, then open it again. The two tracks should still be under **Collection → Saved songs**.
4. Play each track and tap the mini-player to seek beyond halfway. Confirm the clock advances
   and the real track is audible on each.

The Actions job builds the ARM64 APK, then tests the same shell with an x86_64 debug sibling.
Its deterministic synthetic audio checks persistent intent, a rejected truncated file without
blocking a second save, retry, byte-identical saves, and seeking in both files after force-stop
in airplane mode. This does **not** prove a fresh provider download on a real phone or grant
any provider access.
