# BlackBox Helper Stream (Android diagnostic)

`BlackBox-Helper-Stream-ARM64.apk` installs alongside v90 and the older helper-only
search tester as **BlackBox Helper Stream** (`com.blackbox.stream.helperstream`).
The adapter uses the archived p11 native runtime and exact Drive-matched yt-dlp
2026.10.26 pin. It does not change either installed app, use an account, or load
tracks from the v90 catalogue.

1. Wait for **Helper ready · no account session**. Enter your own query and tap **Search**.
2. Tap **Play** next to one result you have permission to stream. Nothing is fetched
   merely by listing it. The lookup verifies the exact selected video and duration.
3. Look for **First audio in …**, measured from the Play tap (not from the search).
   Check that you hear it, the clock advances, and **Seek past halfway** still plays.
4. A partial buffer is not a download. After **Whole audio buffered for replay in
   this session**, select the same result again to test local replay (also works
   with networking off while the tester is still open). The tester keeps at most
   two session buffers, not a persistent Downloads shelf.

The helper tries an existing audio-only AAC or WebM/Opus HTTPS representation with
byte-range support. The private loopback shares verified ranges with the decoder as
bytes arrive and can fetch a range for a seek. It does not wait for a full download
or transcode, expose signed URLs to the screen, or switch uploads if the provider
refuses or has no compatible progressive source. In that case the older whole-file
tester is a separate comparison, not an automatic fallback. A cold start under five
seconds is a target, not a provider or mobile-network guarantee.

```sh
node --test .github/ci/blackbox-phone/helper-phone.test.cjs
python3 -B .github/ci/blackbox-phone/test_stream.py
gh workflow run blackbox-phone-test.yml --ref "$(git branch --show-current)" -f verify_live_search=true
```

Only the x86_64 emulator sibling includes the slow controlled fixture. CI requires real
Android playback before all its bytes arrive, under five seconds on that fixture,
continued audio after seeking, and a completed-buffer replay. The optional cold live
check is separate; the fixture-verified ARM64 test APK is retained even when GitHub's
emulator cannot play a live result. No audio is published in the phone APK.
