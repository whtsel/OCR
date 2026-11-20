# BlackBox Helper Search (Android diagnostic)

`BlackBox-Helper-Search-ARM64.apk` installs separately as **BlackBox Helper Search**
(`com.blackbox.stream.helpersearch`). It does not use the v90 catalogue or change any
installed BlackBox app. The test shell uses the archived p11 Android helper and the exact
`android/vendor/ytdlp` zipapp from the bundled 2026.10.26 pin. The zipapp was checked
byte-for-byte against the helper backup in Drive; it's not an APK by itself. The test
screen reads the archived wrapper's malformed duration field without altering the helper.
If Android rejects an update from an earlier test-signed build, uninstall only the old
**BlackBox Helper Search** and then install this APK.

1. Open the tester and wait for **Helper ready · no account session**.
2. Enter a new song/artist query and tap **Search**. The p11 helper runs `ytsearch8` on
   YouTube. No titles are preloaded and search alone never prepares audio.
3. Tap **Play** next to one result. The helper opens only that YouTube video ID and
   returns a private local audio file. The screen displays the source ID, the search
   and preparation outcomes, and, on success, the audio clock. Verify it advances and
   use **Seek past halfway**. Pick an upload you have permission to prepare.

An empty search result is not proof of a provider error or audio playback. If the
selected file cannot be prepared/decoded, the tester reports that stage and never
switches recordings. Search is a live request; replaying a prepared selection can
reuse the helper's private copy. No sign-in, external player or alternate source is used.

```sh
node --test .github/ci/blackbox-phone/helper-phone.test.cjs
gh workflow run blackbox-phone-test.yml --ref "$(git branch --show-current)" -f verify_live_search=true
```

The workflow validates the ARM64 package and boots the x86_64 counterpart on Android.
Controlled UI tests and startup checks are separate from the live check, which requires
an actual typed YouTube search, a selected audio result, and advancement after a seek.
The phone-test APK is retained after the startup checks even if a live request from
GitHub's emulator fails. No audio is published in the artifact.
