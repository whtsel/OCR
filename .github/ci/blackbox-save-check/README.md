# BlackBox Music Save Check

This is a one-recording **diagnostic**, not a new release of v90 or an update to the Collection
pilot. Install `BlackBox-Music-Save-Check-ARM64.apk` next to your current apps. Its package is
`com.blackbox.stream.savecheck` (Android 7+, ARM64, test signed, backup disabled). It never reads,
imports or changes another app's collection, and it does not upload anything.

1. Open **BlackBox Music Save Check**. On a stable connection, choose **Pilot save** first for the
   known-good reference. Wait for a result. Then choose **Full v90 save** on the same connection
   if it is enabled. There is one explicit attempt per path, no automatic retries or fallback,
   and no background restart. A sign-in requirement, access refusal or rate limit stops the pair.
2. If either shows **Saved · audio verified**, force-stop **this tester** in Android Settings. Turn
   on airplane mode, turn off Wi-Fi, and reopen the tester. Use **Play pilot file** or **Play v90
   file** and tap **Seek past halfway**. Check that audio is audible and advances. **Cold offline
   seek passed** is recorded only after a cold start, no Internet transport, a completed seek and
   the playing clock advancing on the saved file.
3. Tap **Copy results** and share that text if you want it checked. It contains the fixed public
   recording ID, safe stage/outcome labels, device transport class, elapsed time and observed
   bytes. It does not contain IP addresses, cookies, signed URLs or raw provider responses.

Both arms use the *Short n’ Sweet* **Espresso** control (175 s, `51zjlMhdSTE`). Archive `classes.dex`
is copied verbatim; Pilot calls `MainActivity$BundledHelper.audio(id)` from that DEX. Full invokes
`WholeFileMusicRuntime.fetch` from the Android app's source, with the same `album:1752214909:1:7`
row and byte progress callback. Both pass the full app's audio validation before a rename, then
play only their own private `files/save-check/{pilot,full}.m4a` files. The helper `2026.10.26` and
six Android libraries are shared; initializing the runtime for one arm can warm the other's local
setup. Each arm must start with an empty **audio** staging directory. It is a comparison of save
paths, not a timing benchmark or an end-to-end v90 UI/queue test. The old p11 method has no progress
callback; its reported bytes are a lower-bound observation of its staging files, not a percentage.

The workflow compares package bytes and runs deterministic tests on a separate fixture-only x86
APK. Pilot uses its archived local-file branch on synthetic staging, not a new p11 provider fetch.
Full uses a real loopback transfer and its production whole-file path. The test verifies isolation,
matching bytes and decoder/seek after a cold offline launch, rejects a corrupt file, and stops the
second arm on a simulated rate limit. The uploaded ARM64 APK is non-debuggable and contains no
fixture audio or test endpoints. Using it on your device is what tests live provider availability.

Build with Android SDK 35/build-tools 35.0.0 and JDK 17:

```sh
bash .github/ci/blackbox-save-check/build.sh /path/to/verified-p11.apk android/vendor/ytdlp /tmp/save-check
```

Each build has a temporary signing key. This diagnostic should not replace any installed app;
only clear or uninstall the diagnostic itself if you need a new pair of fresh test slots.
