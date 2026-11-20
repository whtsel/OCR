package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real-device/emulator smoke, separate from the JVM tests and the distributed APK. */
public final class BlackBoxSmoke extends Instrumentation {
    private PlaybackService engine;
    private int checks;
    private boolean publicMediaVerified, originalMusicVerified, unpinnedMusicVerified;
    private Bundle fixtures;
    private final ServiceConnection binding = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder local) { engine = ((PlaybackService.LocalBinder) local).get(); }
        @Override public void onServiceDisconnected(ComponentName name) { engine = null; }
    };

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        fixtures = args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (fixtures != null && "yes".equals(fixtures.getString("helper_fixture"))) verifyHelperFixtures();
            else if (fixtures != null && "yes".equals(fixtures.getString("music_only"))) verifyMusicCandidate("yes".equals(fixtures.getString("unpinned_only")));
            else if (fixtures != null && "yes".equals(fixtures.getString("public_only"))) verifyCandidate();
            else if (fixtures != null && "yes".equals(fixtures.getString("public_preview"))) verifyPublicWithPlayer(getTargetContext());
            else verify();
            result.putString("stream", "\nPASS BlackBox Android smoke (" + checks + " checks)\n"
                    + (publicMediaVerified ? "PASS Android imported media: Sintel and The Dull Sword matched public files and both played with networking off\n" : "")
                    + (originalMusicVerified ? "PASS Original v90 Music: five cold full recordings advanced and sought in the original player, with offline replay after reopening\n" : "")
                    + (unpinnedMusicVerified ? "PASS Original v90 Music: unpinned card discovered a full recording, sought, and replayed offline after reopening\n" : ""));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable bad) {
            result.putString("stream", "\nFAIL BlackBox Android smoke: " + android.util.Log.getStackTraceString(bad) + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verify() throws Exception {
        Context app = getTargetContext();
        LibraryStore db = LibraryStore.get(app);
        MediaEntry fixture = new MediaEntry("BlackBox smoke only", MediaEntry.Kind.MOVIE,
                "content://" + app.getPackageName() + ".fixture/dawn.mp4");
        MediaEntry audioFixture = new MediaEntry("BlackBox audio smoke only", MediaEntry.Kind.MUSIC,
                "content://" + app.getPackageName() + ".fixture/orbit.m4a");
        File fixtureDir = new File(app.getCacheDir(), "test-media");
        if (!fixtureDir.isDirectory() && !fixtureDir.mkdir()) throw new java.io.IOException("Cannot stage fixtures");
        copyFixture("dawn.mp4", new File(fixtureDir, "dawn.mp4"));
        copyFixture("orbit.m4a", new File(fixtureDir, "orbit.m4a"));
        SmokeMediaProvider.available = true;
        db.save(fixture, false); db.save(audioFixture, false);
        Activity launcher = null;
        Activity sample = null;
        Activity offline = null;
        boolean bound = false;
        try {
            launcher = launch(MainActivity.class, null);
            Activity visible = launcher;
            bound = ui(() -> app.bindService(new Intent(app, PlaybackService.class), binding, Context.BIND_AUTO_CREATE));
            assertPass("Device Library has no preloaded cinema shelf or video sample", ui(() -> find(visible.getWindow().getDecorView(), "Open cinema") == null
                    && find(visible.getWindow().getDecorView(), "▶  Play sample") == null));
            verifyTmdbShell(app);
            screenFromHome(visible, "Torrents", TorrentActivity.class, "Open a torrent");
            screenFromHome(visible, "Discover", DiscoverActivity.class, "Discover");
            screenFromHome(visible, "Local Share", LanShareActivity.class, "Connect securely");
            screenFromHome(visible, "Community rooms  ›", CommunityActivity.class, "Join room");
            sample = launch(PlayerActivity.class, fixture.id);
            try {
                await("sample decodes H.264 and playback advances", 22000, () -> ui(() -> engine != null && engine.ready()
                        && engine.videoWidth() == 640 && engine.renderedVideo() && engine.duration() >= 13500 && engine.playing() && engine.position() > 500));
            } catch (AssertionError stalled) {
                throw new AssertionError(stalled.getMessage() + "; " + ui(() -> engine == null ? "unbound" :
                        "error=" + engine.failure() + ", width=" + engine.videoWidth() + ", firstFrame=" + engine.renderedVideo()
                                + ", playing=" + engine.playing() + ", position=" + engine.position()), stalled);
            }
            ui(() -> { engine.pause(); engine.seek(6500); return null; });
            await("native seek completes", 6000, () -> ui(() -> !engine.playing() && !engine.seeking()
                    && Math.abs(engine.position() - 6500) < 1000));
            assertPass("watch position is persisted", db.get(fixture.id).positionMs >= 5500);
            Activity sampleScreen = sample;
            ui(() -> { sampleScreen.finish(); return null; });
            sample = null;

            Downloads.deleteCopy(app, fixture);
            ui(() -> { SmokeMediaProvider.available = true; Downloads.enqueue(app, fixture); return null; });
            await("JobScheduler imports and validates a phone media copy", 60000,
                    () -> db.isOffline(fixture) && db.get(fixture.id).transfer == -1);
            assertPass("offline item persisted as saved", db.get(fixture.id).saved);
            try (InputStream original = getContext().getAssets().open("fixtures/dawn.mp4");
                 InputStream copy = new FileInputStream(db.offline(fixture))) {
                assertPass("offline file is an exact copy", Arrays.equals(sha(original), sha(copy)));
            }
            SmokeMediaProvider.available = false;
            offline = launch(PlayerActivity.class, fixture.id);
            await("offline playback works when the source document is unavailable", 16000, () -> ui(() -> engine.entry() != null
                    && fixture.id.equals(engine.entry().id) && engine.ready() && engine.videoWidth() == 640 && engine.position() > 500));
            Activity offlineScreen = offline;
            ui(() -> { offlineScreen.finish(); return null; });
            offline = null;

            verifyLanOnAndroid(app, db);
            if (fixtures != null && fixtures.containsKey("fixture_magnet")) {
                verifyTorrentOnAndroid(app, db, visible);
                verifyServicesOnAndroid();
            }
            if (fixtures != null && fixtures.containsKey("public_media")) verifyPublicMedia(app, db);
            SmokeMediaProvider.available = true;
            Activity audio = launch(PlayerActivity.class, audioFixture.id);
            try {
                await("audio plays through foreground media session", 16000, () -> ui(() -> engine.ready() && engine.playing()
                        && !engine.video() && engine.inBackgroundSession() && engine.position() > 500));
                ui(() -> { audio.finish(); return null; });
                await("audio continues after closing the player screen", 5000, () -> ui(() -> engine.inBackgroundSession() && engine.playing()));
            } finally { ui(() -> { audio.finish(); return null; }); }
        } finally {
            if (offline != null) { Activity a = offline; ui(() -> { a.finish(); return null; }); }
            if (sample != null) { Activity a = sample; ui(() -> { a.finish(); return null; }); }
            if (launcher != null) { Activity a = launcher; ui(() -> { a.finish(); return null; }); }
            if (bound) ui(() -> { if (engine != null) engine.close(); app.unbindService(binding); return null; });
            SmokeMediaProvider.available = true;
            Downloads.deleteCopy(app, fixture);
            db.getWritableDatabase().delete("media", "id=? OR id=?", new String[] {fixture.id, audioFixture.id});
            PreparedMusic.deleteScratch(fixtureDir);
        }
    }

    private static final String V90_READY = "window.bbxAndroidOpen && document.querySelector('#engineDot.ok') && document.querySelector('#heroSettings')";
    private void verifyTmdbShell(Context app) throws Exception {
        V90Api api = new V90Api(app);
        try {
            assertPass("Keyless TMDB routes require a credential, not a mock search result",
                    api.call("GET", "/api/tmdb/search/movie?query=Sintel", "").status == 412
                            && api.call("GET", "/api/anime/top", "").status == 412);
            assertPass("Old sample movie ids and direct demo actions are not playable",
                    api.call("GET", "/api/tmdb/movie/open_sintel", "").status == 404
                            && api.call("GET", "/api/mobile/open-films", "").status != 200
                            && api.call("GET", "/api/mobile/open-anime", "").status != 200
                            && api.call("POST", "/api/mobile/playable", "{\"key\":\"film:sintel\"}").status != 200);
        } finally { api.close(); }
        Activity desktop = launch(V90Activity.class, null);
        try {
            WebView web = ui(() -> findWeb(desktop.getWindow().getDecorView()));
            checkKeylessHome(web);
            js(web, "document.querySelector('#searchInput').value='Sintel'");
            tapWeb(web, "#searchBtn");
            await("Streaming search asks for TMDB and shows no sample card", 8000, () -> jsTrue(web,
                    "!document.querySelector('#viewBrowse').hidden && !document.querySelector('#browseGrid .card') && document.querySelector('#browseGrid').textContent.includes('TMDB key')"));
            tapWeb(web, "[data-nav=anime]");
            await("Anime uses the same key requirement and never injects an archive shelf", 9000, () -> jsTrue(web,
                    "state.browse.mode==='anime' && !document.querySelector('#androidAnimeArchive,#browseGrid .card')"
                            + " && document.querySelector('#browseGrid').textContent.includes('TMDB key')"));
        } finally { closeScreen(desktop); }
    }

    private void checkKeylessHome(WebView web) throws Exception {
        await("Original v90 boot shows TMDB setup, not demo movies", 18000, () -> jsTrue(web,
                V90_READY + " && document.title.includes('BlackBox Stream v90') && window.DOMPurify?.isSupported && !document.querySelector('#rows .card')"));
    }

    /** Tests the real helper loader, isolated queue worker, and actual v90 WebView using only built-in fixture media. */
    private void verifyHelperFixtures() throws Exception {
        Context app = getTargetContext();
        assertPass("Helper fixtures run only on the disposable preview package", app.getPackageName().equals("com.blackboxstream.mobile.music"));
        Class<?> runtime = Class.forName("com.blackboxstream.mobile.BundledMusicRuntime");
        Object instance = runtime.getDeclaredConstructor(Context.class).newInstance(app);
        java.lang.reflect.Method run = runtime.getDeclaredMethod("run", PreparedMusic.Control.class, File.class, String[].class);
        run.setAccessible(true);
        File root = app.getNoBackupFilesDir();
        File scratch = new File(root, "helper-smoke");
        File cache = PreparedMusic.cacheDirectory(app);
        assertPass("Prepare a fresh test directory", scratch.mkdir());
        Activity desktop = null;
        try {
            String installed = (String) run.invoke(instance, new PreparedMusic.Control(), scratch, new String[] {"--version"});
            assertPass("Pinned Android Python runtime executes yt-dlp 2026.10.26", PreparedMusic.VERSION.equals(installed.trim()));
            File video = new File(scratch, "fixture.mp4");
            copyFixture("dawn.mp4", video);
            String phases = (String) run.invoke(instance, new PreparedMusic.Control(), scratch,
                    new String[] {"--enable-file-urls", "--no-simulate", "--quiet", "--progress", "--newline",
                            "--progress-template", "download:BBX_MEDIA:%(progress.status)s",
                            "--print", "before_dl:BBX_DOWNLOAD_START %(ext)s", "-x", "--audio-format", "m4a",
                            "-o", new File(scratch, "audio.%(ext)s").getPath(), video.toURI().toString()});
            assertPass("The real extractor marks format selection and transfer end before conversion",
                    phases.indexOf("BBX_DOWNLOAD_START ") >= 0 && phases.indexOf("BBX_MEDIA:finished") > phases.indexOf("BBX_DOWNLOAD_START "));
            verifyMusicBudget();
            File audio = new File(scratch, "audio.m4a");
            PreparedMusic.verifyAudio(audio, 14);
            assertPass("Bundled FFmpeg extracts complete audio on Android", audio.isFile());
            try {
                PreparedMusic.verifyAudio(audio, 175);
                throw new AssertionError("Short fixture accepted as a 175-second recording");
            } catch (java.io.IOException expected) { checks++; }
            verifyPreparationQueue(scratch, audio);
            verifyMobileLookahead(scratch, audio);
            verifyProviderCooldown(scratch);
            verifySourceHints(app, instance, runtime);

            // Seed only the disposable test package. These files prove local playback, NOT live recording availability.
            assertPass("Preview cache starts empty", cache.mkdir());
            seedPreparedFixture(cache, spec("pick:3579685431", "The Fate of Ophelia", "Taylor Swift", "The Life of a Showgirl", 226, ""));
            JSONObject lockedRecording = spec("pick:62724015", "Locked out of Heaven", "Bruno Mars", "Unorthodox Jukebox", 233, "");
            seedPreparedFixture(cache, lockedRecording);
            String savedPlay = "#musicDownloadedList [data-musicid='" + new PreparedMusic.Track(lockedRecording).key + "'] [data-libplay]";
            desktop = launch(V90Activity.class, null);
            Activity shown = desktop;
            WebView web = ui(() -> findWeb(shown.getWindow().getDecorView()));
            checkKeylessHome(web);
            tapWeb(web, "#navMusic");
            String ophelia = "#music-shelf-trending-now [title='Play The Fate of Ophelia']";
            await("Original Music has fourteen genres and its original downloads, not a preparation shelf", 10000,
                    () -> jsTrue(web, "!document.querySelector('#viewMusic').hidden && !!document.querySelector(" + JSONObject.quote(ophelia) + ")"
                            + " && document.querySelectorAll('#musicGenreChips button').length===14 && document.querySelector('#musicDownloadedList')"
                            + " && !document.querySelector('#androidMusicHelper,#androidAudioLibrary,#androidPreparedMusic')"));
            // Hold the actual bridge reply long past a transient user gesture, while keeping the
            // production transport, spinner and audio decoder. No second tap is permitted.
            js(web, "window.planLog=[];window.holdNextPlan=true;window.releasePlan=null;window.delayFixtureWait=false;window.releaseFile=null;"
                    + "(()=>{const fetchLive=window.fetch;window.fetch=(url,options)=>{const result=fetchLive(url,options);"
                    + "if (typeof url==='string' && url.startsWith('/api/mobile/music-helper/await?') && window.delayFixtureWait) {"
                    + "return result.then(reply=>new Promise(resolve=>{window.releaseFile=()=>resolve(reply)}));}"
                    + "if (url!=='/api/mobile/music-helper/plan') return result;"
                    + "const plan=JSON.parse(options.body);planLog.push(plan);"
                    + "if (plan.selected && window.holdNextPlan) {window.holdNextPlan=false;return result.then(reply=>new Promise(resolve=>{"
                    + "window.releasePlan=async()=>{if(!window.delayFixtureWait){resolve(reply);return}"
                    + "const data=await reply.json();data.tracks[data.selected].state='working';"
                    + "resolve(new Response(JSON.stringify(data),{status:reply.status}));};}));}return result}})()");
            shell("cmd connectivity airplane-mode enable");
            shell("svc wifi disable"); shell("svc data disable");
            try {
                android.net.ConnectivityManager network = app.getSystemService(android.net.ConnectivityManager.class);
                await("Test device is offline", 14000, () -> network.getActiveNetwork() == null);
                String locked = "#music-shelf-trending-now [title='Play Locked out of Heaven']";
                String missing = "#music-shelf-trending-now [title='Play NUEVAYoL']";
                js(web, "window.musicMessageBeforePlay=document.querySelector('#musicMsg').textContent");
                tapWeb(web, ophelia);
                await("The original Play control shows only its loading ring while audio is pending", 7000,
                        () -> jsTrue(web, spinnerOnly(ophelia) + " && window.releasePlan && document.querySelector('#bgAudioBar').hidden"));
                SystemClock.sleep(6200);
                assertPass("The wait does not open a video, panel, or progress message", jsTrue(web, spinnerOnly(ophelia)
                        + " && document.querySelector('#bgAudioBar').hidden && document.querySelector('#bgAudio').paused"));
                assertPass("One plan preserves the next two queue slots and does not contact a later entry", jsTrue(web,
                        "planLog.length===1 && !planLog[0].playing && !planLog[0].mobileLookahead && planLog[0].selected.title==='The Fate of Ophelia' && planLog[0].upcoming.map(x=>x?.title).join('|')==='Locked out of Heaven|NUEVAYoL'"));
                js(web, "musicQueue.list[musicQueue.idx]={...musicQueue.list[musicQueue.idx]};renderMusicQueue();"
                        + "window.delayFixtureWait=true;window.releasePlan();window.releasePlan=null");
                await("The original Play is waiting for the selected full file", 7000, () -> jsTrue(web, "!!window.releaseFile"));
                SystemClock.sleep(46000); // Exceed the old 45 s total guard; no live provider call.
                assertPass("A slow first recording can still finish without a retry after the former cutoff",
                        jsTrue(web, spinnerOnly(ophelia) + " && document.querySelector('#bgAudioBar').hidden && planLog.length===1"));
                js(web, "window.delayFixtureWait=false;window.releaseFile();window.releaseFile=null");
                awaitPreparedFixture(web, "The Fate of Ophelia", 1); // Same exact row, new JS object.
                assertPass("One tap survives a queue rebuild and starts lookahead only after playing", jsTrue(web,
                        "planLog.length===2 && planLog[1].playing && !planLog[1].retry && planLog[1].selected.title===planLog[0].selected.title"
                                + " && JSON.stringify(planLog[1].upcoming)===JSON.stringify(planLog[0].upcoming)"));
                assertPass("The spinner clears and original artwork, player and queue controls remain visible", jsTrue(web,
                        "!document.querySelector('.music-ui-loading-v52') && document.querySelector('#bgArt').getAttribute('src')?.startsWith('/api/cover?')"
                                + " && document.querySelector('#bgQueueToggle').offsetWidth>0 && document.querySelector('#bgSeek').offsetWidth>0"));
                assertPass("The original queue is still in order and includes previously unlisted v90 tracks", jsTrue(web,
                        "musicQueue.list[musicQueue.idx+1].track==='Locked out of Heaven' && musicQueue.list[musicQueue.idx+2].track==='NUEVAYoL'"));
                js(web, "document.querySelector('#bbxMusicMobileLookahead').click()");
                assertPass("The queue's mobile opt-in updates the same active plan, not the track order", jsTrue(web,
                        "planLog.at(-1)?.playing && planLog.at(-1)?.mobileLookahead"
                                + " && localStorage.getItem('bbxAndroidMusicMobileLookahead')==='1'"
                                + " && planLog.at(-1).upcoming[0].title==='Locked out of Heaven'"));
                js(web, "document.querySelector('#bbxMusicMobileLookahead').click()");
                js(web, "(()=>{const seek=document.querySelector('#bgSeek');seek.value=66;seek.dispatchEvent(new Event('input'))})()");
                awaitPreparedFixture(web, "The Fate of Ophelia", 16.3);
                tapWeb(web, "#bgPlayBtn");
                await("Pause is available in the visible original player", 6000, () -> jsTrue(web, "document.querySelector('#bgAudio').paused"));
                tapWeb(web, "#bgPlayBtn");
                awaitPreparedFixture(web, "The Fate of Ophelia", 17);
                js(web, "document.querySelector('#bgAudio').currentTime = document.querySelector('#bgAudio').duration - 0.25");
                awaitPreparedFixture(web, "Locked out of Heaven", 1);
                assertPass("The automatic handoff uses the saved next recording without retrying the provider", jsTrue(web,
                        "planLog.some(p=>p.selected?.title==='Locked out of Heaven' && !p.playing && !p.retry)"));

                js(web, "window.holdNextPlan=true");
                tapWeb(web, ophelia);
                await("A second pending request holds its original spinner", 7000,
                        () -> jsTrue(web, spinnerOnly(ophelia) + " && window.releasePlan"));
                tapWeb(web, locked);
                awaitPreparedFixture(web, "Locked out of Heaven", 1);
                js(web, "window.releasePlan(); window.releasePlan=null");
                SystemClock.sleep(700);
                assertPass("Late reply cannot revive the cancelled track or its spinner", jsTrue(web,
                        "document.querySelector('#bgTitle').textContent==='Locked out of Heaven' && !document.querySelector('.music-ui-loading-v52')"));
                tapWeb(web, "#bgClose");
                await("Closing the original player clears the resume session without deleting Downloads", 7000,
                        () -> jsTrue(web, "document.querySelector('#bgAudioBar').hidden && !localStorage.getItem(MUSIC_SESSION_KEY) && musicDownloads.length===2"));
                tapWeb(web, missing);
                await("An unavailable offline song stops cleanly with no substitute or source dialog", 9000,
                        () -> jsTrue(web, "musicQueue.list[musicQueue.idx]?.track==='NUEVAYoL' && document.querySelector('#bgAudioBar').hidden"
                                + " && !document.querySelector('#bgAudio').src && !document.querySelector('.music-ui-loading-v52,dialog[open]')"
                                + " && document.querySelector('#toast.on')?.textContent.includes('Connect to the internet')"));
                closeScreen(desktop);
                desktop = launch(V90Activity.class, null);
                Activity reopened = desktop;
                WebView offline = ui(() -> findWeb(reopened.getWindow().getDecorView()));
                checkKeylessHome(offline);
                tapWeb(offline, "#navMusic");
                await("Both recordings are in the original offline downloads after reopening", 10000,
                        () -> jsTrue(offline, "musicDownloads.filter(t=>t.offline).length===2 && document.querySelector(" + JSONObject.quote(savedPlay) + ")"));
                tapWeb(offline, savedPlay);
                awaitPreparedFixture(offline, "Locked out of Heaven", 1);
                // The unplayed album track must not lose its album/disc/track identity in a saved playlist.
                assertPass("The original playlist preserves exact unplayed album rows across reload of its stored data", jsTrue(offline,
                        "(()=>{const list=bbxPickV78._create('Smoke catalogue playlist');"
                                + "const track=globalThis.BBX_MUSIC_CATALOG_V64.albums.find(a=>a[0]==='1752214909');"
                                + "const album=musicAlbumTracksV63({id:track[0],title:track[1],artist:track[2],rawTracks:track[6]});"
                                + "bbxPickV78._add(list.id,album[6]);loadMusicCollections();"
                                + "const id=collectionById(list.id).ids[0];const [saved]=youtubeQueue([{id}], 'audio');"
                                + "return saved.track==='Espresso' && saved.catalogTrackId===album[6].catalogTrackId && saved.duration===175})()"));
            } finally {
                shell("cmd connectivity airplane-mode disable"); shell("svc data enable"); shell("svc wifi enable");
            }
        } finally {
            closeScreen(desktop);
            PreparedMusic.deleteScratch(cache);
            PreparedMusic.deleteScratch(scratch);
        }
    }

    private static String spinnerOnly(String selector) {
        return "(()=>{const b=document.querySelector(" + JSONObject.quote(selector) + ");return b?.classList.contains('music-ui-loading-v52')"
                + " && b.getAttribute('aria-busy')==='true' && getComputedStyle(b,'::after').animationName!=='none' && !b.dataset.loadingLabel"
                + " && document.querySelector('#musicSourcePopup').hidden && !document.querySelector('dialog[open],#androidMusicSource,#androidMusicHelper')"
                + " && document.querySelector('#musicMsg').textContent===window.musicMessageBeforePlay})()";
    }

    private void awaitPreparedFixture(WebView web, String title, double from) throws Exception {
        awaitAudio(web, title, 24, 1, from, 14000);
    }

    private void awaitAudio(WebView web, String title, int seconds, int tolerance, double from, int timeout) throws Exception {
        String playable = "(()=>{const a=document.querySelector('#bgAudio'), b=document.querySelector('#bgAudioBar');return a.src.startsWith('blob:https://blackbox.invalid/')"
                + " && !b.hidden && getComputedStyle(b).display!=='none' && document.querySelector('#bgTitle').textContent==="
                + JSONObject.quote(title) + " && Math.abs(a.duration-" + seconds + ")<=" + tolerance
                + " && !a.paused && !a.muted && a.volume>0 && a.readyState>=2 && !a.seeking && a.currentTime>" + from + "})()";
        try {
            await(title + " starts or seeks in the original audio player without another tap", timeout, () -> jsTrue(web, playable));
        } catch (AssertionError error) {
            throw new AssertionError(error.getMessage() + ": " + js(web,
                    "JSON.stringify({title:document.querySelector('#bgTitle').textContent,blob:document.querySelector('#bgAudio').src.startsWith('blob:'),"
                            + "error:document.querySelector('#bgAudio').error?.code,duration:document.querySelector('#bgAudio').duration,time:document.querySelector('#bgAudio').currentTime,"
                            + "busy:document.querySelector('.music-ui-loading-v52')?.outerHTML.slice(0,150),paused:document.querySelector('#bgAudio').paused})"), error);
        }
    }

    private void verifyPreparationQueue(File scratch, File audio) throws Exception {
        PreparedMusic.Track a = new PreparedMusic.Track(spec("test:A", "A", "Fixture", "Album", 14, ""));
        PreparedMusic.Track b = new PreparedMusic.Track(spec("test:B", "B", "Fixture", "Album", 14, ""));
        PreparedMusic.Track c = new PreparedMusic.Track(spec("test:C", "C", "Fixture", "Album", 14, ""));
        PreparedMusic.Track d = new PreparedMusic.Track(spec("test:D", "D", "Fixture", "Album", 14, "ddddddddddd"));
        PreparedMusic.Track e = new PreparedMusic.Track(spec("test:E", "E", "Fixture", "Album", 14, ""));
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<PreparedMusic.Link> link =
                new java.util.concurrent.atomic.AtomicReference<>(PreparedMusic.Link.METERED);
        PreparedMusic.Runtime fixture = (track, work, control) -> {
            calls.add(track.title);
            if (track.key.equals(b.key)) while (!control.cancelled) Thread.sleep(10);
            File local = new File(work, "audio.m4a");
            try (InputStream in = new FileInputStream(audio); FileOutputStream out = new FileOutputStream(local)) {
                byte[] bytes = new byte[8192];
                for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
            }
            return new PreparedMusic.Result(local, track.key.equals(d.key) ? "ddddddddddd" : "aaaaaaaaaaa");
        };
        File dir = new File(scratch, "queue");
        try (PreparedMusic queue = new PreparedMusic(dir, fixture, link::get)) {
            assertPass("Bundled engine is ready without opt-in and browsing alone does no work", queue.state().getBoolean("available") && calls.isEmpty());
            JSONArray next = new JSONArray().put(b.json()).put(c.json());
            queue.plan(1, a.json(), next, true, false, false);
            await("Any catalogue row can prepare, without a fixed ID table", 7000, () -> queue.read(a.key) != null);
            assertPass("Metered prefetch waits for an unmetered connection", calls.equals(Arrays.asList("A"))
                    && "queued".equals(queue.state().getJSONObject("tracks").getJSONObject(b.key).getString("state")));
            link.set(PreparedMusic.Link.UNMETERED);
            queue.networkChanged();
            assertPass("Even on Wi-Fi, nothing is prepared ahead until the current player starts", calls.equals(Arrays.asList("A"))
                    && "queued".equals(queue.state().getJSONObject("tracks").getJSONObject(b.key).getString("state")));
            queue.plan(2, a.json(), next, false, true, false);
            await("First actual upcoming entry starts", 7000, () -> calls.contains("B"));
            queue.downloadAlbum(new JSONArray().put(e.json()).put(c.json()));
            assertPass("An explicit album request does not displace the current two-slot plan", calls.equals(Arrays.asList("A", "B")));
            queue.plan(3, b.json(), new JSONArray().put(c.json()), true, false, false); // Promote rather than restart B.
            queue.plan(5, d.json(), new JSONArray().put(c.json()).put(a.json()), true, false, false); // Cancel B when no longer wanted.
            queue.plan(4, a.json(), new JSONArray().put(b.json()), true, true, false); // Delayed stale bridge message.
            await("Foreground jumps ahead of lookahead and album work", 9000, () -> queue.read(d.key) != null);
            assertPass("Album work does not fill the gap before the new track starts", calls.equals(Arrays.asList("A", "B", "D")));
            queue.plan(6, d.json(), new JSONArray().put(c.json()).put(a.json()), false, true, false);
            await("The actual next slots run before the explicit album", 9000, () -> queue.read(c.key) != null && queue.read(e.key) != null);
            assertPass("Cancelled output never commits; kept files deduplicate across plans and album",
                    queue.read(b.key) == null && calls.equals(Arrays.asList("A", "B", "D", "C", "E")));
            try {
                queue.plan(7, a.json(), new JSONArray().put(b.json()).put(c.json()).put(d.json()), true, false, false);
                throw new AssertionError("Three upcoming items accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }
        try (PreparedMusic reopened = new PreparedMusic(dir, fixture, () -> PreparedMusic.Link.OFFLINE)) {
            reopened.plan(1, a.json(), new JSONArray(), true, false, false);
            assertPass("Completed catalogue recording is reused across instances when offline", reopened.read(a.key) != null && calls.size() == 5);
            PreparedMusic.Track otherRelease = new PreparedMusic.Track(spec("test:A", "A", "Fixture", "Another album", 14, ""));
            assertPass("Another release of the same title cannot read the cached recording", reopened.read(otherRelease.key) == null);
            reopened.forget(a.key);
            assertPass("Clearing the cache leaves no ready file", reopened.read(a.key) == null && !new File(dir, a.key).exists());
        }
    }

    private void verifyMusicBudget() throws Exception {
        MusicPreparationBudget tracker = new MusicPreparationBudget(1000);
        assertPass("Lookup can run past the progressive tester cutoff, but cannot run forever",
                tracker.timeout(24_000) == null && tracker.timeout(81_000).startsWith("Finding the exact recording"));
        assertPass("A resolved format gets its own transfer budget, separate from lookup",
                tracker.marker("BBX_DOWNLOAD_START m4a", 79_000) == MusicPreparationBudget.Phase.TRANSFER
                        && tracker.timeout(100_000) == null);
        tracker.sawBytes(2048, 110_000);
        tracker.sawBytes(2048, 149_000);
        tracker.marker("BBX_MEDIA:downloading", 149_000);
        assertPass("Only new file bytes extend the idle guard, not duplicate progress text",
                tracker.timeout(149_999) == null && tracker.timeout(150_000).contains("stopped receiving data"));
        tracker.sawBytes(4096, 223_000);
        assertPass("Even a growing transfer has a hard end", tracker.timeout(224_000).startsWith("Downloading the full audio"));
        assertPass("Conversion has a separate hard end and the UI is not the first deadline",
                tracker.marker("BBX_MEDIA:finished", 223_000) == MusicPreparationBudget.Phase.FINALIZE
                        && tracker.timeout(247_999) == null && tracker.timeout(248_000).startsWith("Finishing")
                        && PreparedMusic.SELECTION_WAIT_MS > MusicPreparationBudget.LOOKUP_MS
                                + MusicPreparationBudget.TRANSFER_MS + MusicPreparationBudget.FINALIZE_MS);
    }

    private void verifyMobileLookahead(File scratch, File fixture) throws Exception {
        PreparedMusic.Track a = new PreparedMusic.Track(spec("mobile:A", "A", "Fixture", "Album", 14, ""));
        PreparedMusic.Track b = new PreparedMusic.Track(spec("mobile:B", "B", "Fixture", "Album", 14, ""));
        PreparedMusic.Track c = new PreparedMusic.Track(spec("mobile:C", "C", "Fixture", "Album", 14, ""));
        PreparedMusic.Track longTrack = new PreparedMusic.Track(spec("mobile:long", "Long mix", "Fixture", "Album", 420, ""));
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<PreparedMusic.Link> link =
                new java.util.concurrent.atomic.AtomicReference<>(PreparedMusic.Link.METERED);
        java.util.concurrent.atomic.AtomicReference<PreparedMusic.Control> underway = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch completeNext = new java.util.concurrent.CountDownLatch(1);
        try (PreparedMusic queue = new PreparedMusic(new File(scratch, "mobile"), (track, work, control) -> {
            calls.add(track.title);
            if (track.key.equals(b.key)) {
                underway.set(control);
                completeNext.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            File audio = new File(work, "audio.m4a");
            try (InputStream in = new FileInputStream(fixture); FileOutputStream out = new FileOutputStream(audio)) {
                byte[] bytes = new byte[8192];
                for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
            }
            return new PreparedMusic.Result(audio, "aaaaaaaaaaa");
        }, link::get)) {
            JSONArray window = new JSONArray().put(b.json()).put(c.json());
            queue.plan(1, a.json(), window, true, false, true);
            await("The current recording prepares on mobile data", 7000, () -> queue.read(a.key) != null);
            queue.plan(2, a.json(), window, false, true, false);
            assertPass("Only explicit consent opens metered lookahead, not just a Play tap", calls.equals(Arrays.asList("A")));
            queue.plan(3, a.json(), new JSONArray().put(JSONObject.NULL).put(c.json()), false, true, true);
            queue.plan(4, a.json(), new JSONArray().put(a.json()).put(c.json()), false, true, true);
            queue.plan(5, a.json(), new JSONArray().put(longTrack.json()).put(c.json()), false, true, true);
            assertPass("An absent, duplicate or long mobile next slot does not fetch a later song", calls.equals(Arrays.asList("A")));
            queue.plan(6, a.json(), window, false, true, true);
            await("Metered opt-in prepares the exact next slot", 7000, () -> underway.get() != null);
            assertPass("Second lookahead slot does not start on mobile", calls.equals(Arrays.asList("A", "B"))
                    && "queued".equals(queue.state().getJSONObject("tracks").getJSONObject(c.key).getString("state")));
            queue.plan(7, b.json(), new JSONArray().put(c.json()), false, false, true);
            assertPass("Auto-next promotes the in-flight file without cancelling or restarting it", !underway.get().cancelled);
            completeNext.countDown();
            await("The promoted file commits completely under the same selection key", 7000, () -> queue.read(b.key) != null);
            link.set(PreparedMusic.Link.RESTRICTED);
            queue.plan(8, b.json(), new JSONArray().put(c.json()), false, true, true);
            queue.networkChanged();
            assertPass("Android Data Saver still blocks metered preparation after opt-in", calls.equals(Arrays.asList("A", "B")));
            link.set(PreparedMusic.Link.METERED);
            queue.networkChanged();
            await("The queue then prepares its new next track, once", 7000, () -> queue.read(c.key) != null);
            assertPass("Mobile preparation follows the actual handoff and never duplicates it", calls.equals(Arrays.asList("A", "B", "C")));
        } finally { completeNext.countDown(); }
    }

    private void verifyProviderCooldown(File scratch) throws Exception {
        JSONObject a = spec("limit:A", "A", "Fixture", "Album", 14, "");
        JSONObject b = spec("limit:B", "B", "Fixture", "Album", 14, "");
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (PreparedMusic limited = new PreparedMusic(new File(scratch, "limited"), (track, work, control) -> {
            attempts.incrementAndGet();
            throw new PreparedMusic.ProviderRateLimited();
        }, () -> PreparedMusic.Link.UNMETERED)) {
            JSONObject selected = limited.plan(1, a, new JSONArray().put(b), true, false, false);
            String key = selected.getString("selected");
            JSONObject result = limited.awaitSelection(key).getJSONObject("tracks").getJSONObject(key);
            assertPass("A provider refusal wakes the selection waiter with a clear failure", "failed".equals(result.getString("state"))
                    && result.getString("message").contains("rate-limiting"));
            String next = limited.plan(2, b, new JSONArray(), true, false, false).getString("selected");
            // The first waiter can return while the worker is still draining. Observe the terminal
            // state, not the immediate plan reply, in both worker schedules.
            JSONObject blocked = limited.awaitSelection(next).getJSONObject("tracks").getJSONObject(next);
            assertPass("Cooldown makes the next unprepared selection fail without another provider call",
                    "failed".equals(blocked.getString("state")) && attempts.get() == 1);
        }
    }

    private void verifySourceHints(Context app, Object runtime, Class<?> type) throws Exception {
        PreparedMusic.Track first = new PreparedMusic.Track(spec("test:remembered", "A", "Artist", "Edition A", 150, ""));
        PreparedMusic.Track another = new PreparedMusic.Track(spec("test:remembered", "A", "Artist", "Edition B", 150, ""));
        PreparedMusic.Track pinned = new PreparedMusic.Track(spec("test:remembered", "A", "Artist", "Edition A", 150, "aaaaaaaaaaa"));
        java.lang.reflect.Method remembered = type.getDeclaredMethod("remembered", String.class);
        remembered.setAccessible(true);
        ((PreparedMusic.Runtime) runtime).rememberSource(first, "bbbbbbbbbbb");
        ((PreparedMusic.Runtime) runtime).rememberSource(pinned, "aaaaaaaaaaa");
        Object reopened = type.getDeclaredConstructor(Context.class).newInstance(app);
        assertPass("Provider hint persists across runtime instances without changing the catalogue ID or edition",
                "bbbbbbbbbbb".equals(remembered.invoke(reopened, first.key)) && first.source.isEmpty()
                        && "".equals(remembered.invoke(reopened, another.key)) && "".equals(remembered.invoke(reopened, pinned.key)));
    }

    private static JSONObject spec(String ref, String title, String artist, String album, int seconds, String source) throws Exception {
        return new JSONObject().put("ref", ref).put("title", title).put("artist", artist).put("album", album)
                .put("seconds", seconds).put("source", source);
    }

    private void seedPreparedFixture(File cache, JSONObject recording) throws Exception {
        File entry = new File(cache, new PreparedMusic.Track(recording).key);
        if (!entry.mkdir()) throw new java.io.IOException("Could not seed prepared fixture");
        try (FileOutputStream out = new FileOutputStream(new File(entry, "recording.json"))) {
            out.write(new JSONObject().put("recording", recording).put("providerId", "aaaaaaaaaaa").toString().getBytes(StandardCharsets.UTF_8));
        }
        copyFixture("orbit.m4a", new File(entry, "audio.m4a"));
    }

    private void copyFixture(String resource, File target) throws Exception {
        try (InputStream in = getContext().getAssets().open("fixtures/" + resource); FileOutputStream out = new FileOutputStream(target)) {
            byte[] bytes = new byte[8192];
            for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
        }
    }

    private void verifyMusicCandidate(boolean unpinnedOnly) throws Exception {
        Context app = getTargetContext();
        assertPass("Live tests use a fresh Music candidate, not an installed user library",
                app.getPackageName().equals("com.blackboxstream.mobile.music") && !PreparedMusic.cacheDirectory(app).exists());
        LiveMusic results = new LiveMusic();
        Activity desktop = launch(V90Activity.class, null);
        try {
            WebView web = ui(() -> findWeb(desktop.getWindow().getDecorView()));
            await("The v90 music shell is ready on a phone viewport", 18000,
                    () -> jsTrue(web, V90_READY + " && innerWidth <= 400"));
            verifyOriginalMusic(web, results, unpinnedOnly);
        } finally { closeScreen(desktop); }
        if (!results.played.isEmpty()) verifyLiveOffline(app, results.played);
        int required = unpinnedOnly ? 1 : 5;
        assertPass(required + " cold catalogue recordings required: " + results.played.size() + " played. " + results.failures,
                results.played.size() == required && results.failures.isEmpty());
        unpinnedMusicVerified = unpinnedOnly;
        originalMusicVerified = !unpinnedOnly;
    }

    /** Run the same public media acceptance on the non-debuggable standalone app. */
    private void verifyCandidate() throws Exception {
        Context app = getTargetContext();
        assertPass("Standalone media acceptance does not depend on debug fixtures",
                (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0);
        verifyPublicWithPlayer(app);
    }

    private void verifyPublicWithPlayer(Context app) throws Exception {
        boolean bound = ui(() -> app.bindService(new Intent(app, PlaybackService.class), binding, Context.BIND_AUTO_CREATE));
        assertPass("Standalone native playback service binds", bound);
        try {
            await("Standalone native playback service is ready", 8000, () -> ui(() -> engine != null));
            verifyPublicMedia(app, LibraryStore.get(app));
        } finally { ui(() -> { if (engine != null) engine.close(); app.unbindService(binding); return null; }); }
    }

    private void verifyPublicMedia(Context app, LibraryStore db) throws Exception {
        JSONObject records = new JSONObject(new String(fixtureBytes("public_media"), StandardCharsets.UTF_8));
        Activity desktop = launch(V90Activity.class, null);
        WebView web = ui(() -> findWeb(desktop.getWindow().getDecorView()));
        java.util.List<MediaEntry> saved = new java.util.ArrayList<>();
        V90Api api = new V90Api(app);
        try {
            checkKeylessHome(web);
            // Test authorized HTTPS imports explicitly. These are not entries in the user catalogue.
            saved.add(verifyDirect(api, db, records, "film:sintel", "Sintel", MediaEntry.Kind.MOVIE, 720000L, 150000L));
            saved.add(verifyDirect(api, db, records, "anime:dull-sword", "The Dull Sword", MediaEntry.Kind.ANIME, 250000L, 60000L));
            tapWeb(web, "[data-nav=library]");
            await("Library shows every complete download in the original interface", 10000, () -> {
                for (MediaEntry entry : saved) if (!jsTrue(web, "document.querySelector(" + JSONObject.quote(offlineRow(entry)) + ")")) return false;
                return true;
            });
            try {
                shell("cmd connectivity airplane-mode enable");
                shell("svc wifi disable");
                shell("svc data disable");
                android.net.ConnectivityManager network = app.getSystemService(android.net.ConnectivityManager.class);
                await("The emulator has no active network before offline playback", 12000, () -> network.getActiveNetwork() == null);
                for (MediaEntry entry : saved) {
                    ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
                    Activity screen = null;
                    try {
                        tapWeb(web, offlineRow(entry) + " .android-library-play");
                        screen = waitForMonitorWithTimeout(monitor, 14000);
                        assertPass(entry.title + " reopens from Library in BlackBox with airplane mode on", screen != null);
                        awaitMedia(entry.title + " offline", entry, 60000L, true, db);
                    } finally { closeScreen(screen); removeMonitor(monitor); }
                }
            } finally {
                shell("cmd connectivity airplane-mode disable");
                shell("svc data enable");
                shell("svc wifi enable");
            }
            publicMediaVerified = true;
        } catch (AssertionError problem) {
            String view = js(web, "JSON.stringify({view:window.__BBX_STATE__?.view,title:document.querySelector('#detailBody h1')?.textContent,"
                    + "music:document.getElementById('musicMsg')?.textContent,status:Array.from(document.querySelectorAll('.android-action-status')).map(e=>e.textContent).filter(Boolean).slice(-5)})");
            throw new AssertionError(problem.getMessage() + "; page=" + view, problem);
        } finally {
            api.close();
            ui(() -> { if (engine != null) engine.close(); desktop.finish(); return null; });
            for (MediaEntry e : saved) {
                Downloads.deleteCopy(app, e); db.getWritableDatabase().delete("media", "id=?", new String[] {e.id});
            }
        }
    }

    private static final class LiveMusic {
        final List<String> failures = new java.util.ArrayList<>();
        final List<LiveTrack> played = new java.util.ArrayList<>();
    }

    private static final class LiveTrack {
        final String title, key;
        final int seconds;
        LiveTrack(String title, String key, int seconds) { this.title = title; this.key = key; this.seconds = seconds; }
    }

    private void verifyOriginalMusic(WebView web, LiveMusic results, boolean unpinnedOnly) throws Exception {
        // Observe the actual Android bridge. This shim cannot supply source IDs or audio.
        js(web, "window.liveMusicResult={}; (()=>{const live=window.fetch;window.fetch=async (url,options)=>{"
                + "const plan = url==='/api/mobile/music-helper/plan' && JSON.parse(options.body);"
                + "const response=await live(url,options);if (response.ok && typeof url==='string' && url.startsWith('/api/mobile/music-helper')) {"
                + "const result=await response.clone().json();const key=result.selected;const row=result.tracks?.[key];"
                + "if(row){const t=row.recording.title,old=liveMusicResult[t];"
                + "liveMusicResult[t]={key,state:row.state,message:row.message,source:row.recording.source,"
                + "initialState:plan?.selected && plan.retry ? row.state : old?.initialState};}} return response;};})()");
        js(web, "window.liveMusicToast='';const musicToast=document.querySelector('#toast');"
                + "new MutationObserver(()=>{if(musicToast.classList.contains('on'))window.liveMusicToast=musicToast.textContent})"
                + ".observe(musicToast,{attributes:true,childList:true,subtree:true,characterData:true});");
        tapWeb(web, "#navMusic");
        await("Original Music shelves contain 14 genres and the original Trending Now card", 10000,
                () -> jsTrue(web, "document.querySelectorAll('#musicGenreChips button').length===14 "
                        + "&& document.querySelector('#music-shelf-trending-now [title=\"Play The Fate of Ophelia\"]')"));
        tryLiveTrack(web, results, "The Fate of Ophelia", "#music-shelf-trending-now [title='Play The Fate of Ophelia']", 226, true);
        if (unpinnedOnly) return;
        // If the first unpinned attempt was refused, respect the cooldown before testing the
        // independent pinned route once. Never retrigger the refused recording during this run.
        boolean waitForProvider = jsTrue(web, "liveMusicResult['The Fate of Ophelia']?.message?.includes('rate-limiting')");
        long nextAttempt = SystemClock.elapsedRealtime() + (waitForProvider ? 61_000 : 0);
        js(web, "document.querySelector('#musicQuery').value='Sabrina Carpenter'");
        tapWeb(web, "#musicGo");
        String album = "[data-album-open-v63='1752214909']";
        await("Original artist search shows Short n' Sweet without replacing its artwork or artist", 12000,
                () -> jsTrue(web, "document.querySelector('.music-artist-card-v63')?.textContent.includes('Sabrina Carpenter') && "
                        + "document.querySelector(" + JSONObject.quote(album) + ")?.closest('.music-album-card-v63')?.querySelector('img')?.getAttribute('src')?.startsWith('/api/cover?')"));
        tapWeb(web, album);
        await("The original album opens in order with Espresso at track 7", 10000,
                () -> jsTrue(web, "Array.from(document.querySelectorAll('.music-album-tracklist-v63 strong'))[6]?.textContent === 'Espresso'"));
        tapWeb(web, "[data-album-queue-v63]");
        await("Queue retains the chosen album and its track order", 7000,
                () -> jsTrue(web, "!document.querySelector('#bgQueuePanel').hidden && "
                        + "document.querySelectorAll('#bgQueueList .bg-queue-title-v49')[6]?.textContent === 'Espresso'"));
        tapWeb(web, "#bgQueueClose");
        if (waitForProvider) SystemClock.sleep(Math.max(0, nextAttempt - SystemClock.elapsedRealtime()));
        tryLiveTrack(web, results, "Espresso", ".music-album-tracklist-v63 .music-catalog-track-v63:nth-child(7) [data-track-play-v63]", 175, false);
        tryLiveTrack(web, results, "Taste", ".music-album-tracklist-v63 .music-catalog-track-v63:first-child [data-track-play-v63]", 157, true);
        tryLiveTrack(web, results, "Sharpest Tool", ".music-album-tracklist-v63 .music-catalog-track-v63:nth-child(4) [data-track-play-v63]", 218, true);
        tapWeb(web, "#musicClear");
        tapWeb(web, "#musicGenreChips [title='Open 30 Ghana Essentials picks']");
        await("Ghana Essentials shows its original 30 picks", 8000,
                () -> jsTrue(web, "!document.querySelector('#viewMusicGenre').hidden && document.querySelectorAll('[data-genre-catalog] .music-genre-card-v50').length===30"));
        tryLiveTrack(web, results, "Kwaku the Traveller", "#musicGenrePage [title='Play Kwaku the Traveller']", 185, false);
    }

    private void tryLiveTrack(WebView web, LiveMusic results, String title, String control, int seconds,
                              boolean discoveryRequired) throws Exception {
        String row = "liveMusicResult[" + JSONObject.quote(title) + "]";
        long tapped = SystemClock.elapsedRealtime();
        try {
            js(web, "window.liveMusicToast=''");
            tapWeb(web, control);
            await(title + " reaches the live helper on one tap", 7000, () -> jsTrue(web, row + "?.initialState!=null"));
            assertPass(title + " began with no prepared audio and chose the expected lookup path",
                    jsTrue(web, row + ".initialState!=='ready' && " + row + ".source" + (discoveryRequired ? "===''" : "!==''")));
            await(title + " receives a private full recording", (int) PreparedMusic.SELECTION_WAIT_MS + 5000, () -> {
                if (jsTrue(web, row + "?.state==='failed'")) {
                    String reason = jsText(web, row + ".message");
                    await(title + " shows a failure reason and its Play ring clears", 4500,
                            () -> jsTrue(web, "document.querySelector('#toast.on')?.textContent===" + row + ".message"
                                    + " && !document.querySelector('.music-ui-loading-v52') && document.querySelector('#bgAudioBar').hidden"));
                    throw new AssertionError(title + ": " + reason);
                }
                if (jsTrue(web, row + "?.state==='ready'")) return true;
                if (jsTrue(web, "!!window.liveMusicToast && !document.querySelector('.music-ui-loading-v52') && document.querySelector('#bgAudioBar').hidden")) {
                    throw new AssertionError(title + ": " + jsText(web, "window.liveMusicToast"));
                }
                return false;
            });
            long ready = SystemClock.elapsedRealtime();
            awaitAudio(web, title, seconds, 6, 1, 13000);
            long advancing = SystemClock.elapsedRealtime() - tapped;
            assertPass(title + " advances within the bounded preparation and local player time", advancing <= PreparedMusic.SELECTION_WAIT_MS + 15000);
            js(web, "(()=>{const seek=document.querySelector('#bgSeek');seek.value=66;seek.dispatchEvent(new Event('input'))})()");
            awaitAudio(web, title, seconds, 6, seconds * 0.63, 10000);
            String key = jsText(web, row + ".key");
            assertPass(title + " is saved under its exact cache key", key.matches("[a-f0-9]{64}"));
            tapWeb(web, "#bgClose");
            await("Original player closes without deleting " + title, 7000,
                    () -> jsTrue(web, "document.querySelector('#bgAudioBar').hidden && !localStorage.getItem(MUSIC_SESSION_KEY)"));
            results.played.add(new LiveTrack(title, key, seconds));
            android.util.Log.i("BlackBoxSmoke", "v90 provider " + title + ": ready in " + (ready - tapped) + "ms, advancing in " + advancing + "ms; full seek passed");
        } catch (AssertionError blocked) {
            String message = blocked.getMessage() + " (" + (SystemClock.elapsedRealtime() - tapped) + "ms)";
            results.failures.add(message);
            android.util.Log.i("BlackBoxSmoke", "v90 provider " + message);
        }
    }

    private void verifyLiveOffline(Context app, List<LiveTrack> played) throws Exception {
        Activity desktop = null;
        try {
            shell("cmd connectivity airplane-mode enable");
            shell("svc wifi disable"); shell("svc data disable");
            android.net.ConnectivityManager network = app.getSystemService(android.net.ConnectivityManager.class);
            await("All network interfaces are disconnected before replay", 14000, () -> network.getActiveNetwork() == null);
            desktop = launch(V90Activity.class, null);
            Activity reopened = desktop;
            WebView offline = ui(() -> findWeb(reopened.getWindow().getDecorView()));
            await("The original shell reopens offline", 18000, () -> jsTrue(offline, V90_READY));
            tapWeb(offline, "#navMusic");
            for (LiveTrack track : played) {
                String control = "#musicDownloadedList [data-musicid='" + track.key + "'] [data-libplay]";
                await(track.title + " has its exact Downloads row", 10000,
                        () -> jsTrue(offline, "document.querySelector(" + JSONObject.quote(control) + ")"));
                tapWeb(offline, control);
                awaitAudio(offline, track.title, track.seconds, 6, 1, 7500);
                android.util.Log.i("BlackBoxSmoke", "v90 provider " + track.title + ": exact Downloads replay passed offline");
            }
        } finally {
            closeScreen(desktop);
            shell("cmd connectivity airplane-mode disable"); shell("svc data enable"); shell("svc wifi enable");
        }
    }

    private MediaEntry verifyDirect(V90Api api, LibraryStore db, JSONObject records, String key, String title,
                                    MediaEntry.Kind kind, long duration, long seek) throws Exception {
        JSONObject ref = records.getJSONObject(key);
        JSONObject request = new JSONObject().put("url", ref.getString("url")).put("title", title).put("kind", kind.name());
        V90Api.Reply opened = api.call("POST", "/api/mobile/direct", request.toString());
        MediaEntry entry = db.get(opened.value.optString("id"));
        assertPass(title + " imports the authorized exact source and media type", entry != null && entry.remote() && entry.kind == kind);
        Activity screen = launch(PlayerActivity.class, entry.id);
        try {
            awaitMedia(title + " online", entry, duration, false, db);
            ui(() -> { engine.seek(seek); return null; });
            await(title + " seeks in the full online file", 16000,
                    () -> ui(() -> engine.playing() && !engine.seeking() && engine.position() >= seek - 1100));
        } finally { closeScreen(screen); }
        V90Api.Reply download = api.call("POST", "/api/mobile/direct", request.put("download", true).toString());
        assertPass(title + " download request is accepted", download.status == 200 || download.status == 202);
        await(title + " download finishes and commits in Android", 110000, () -> {
            MediaEntry active = db.get(entry.id);
            if (active.transferError != null) throw new AssertionError(title + " download failed: " + active.transferError);
            return db.isOffline(entry) && active.transfer < 0;
        });
        File path = db.offline(entry);
        try (InputStream stream = new FileInputStream(path)) {
            assertPass(title + " offline file matches the independently fetched public bytes", path.length() == ref.getLong("bytes")
                    && TorrentMeta.hex(sha(stream)).equals(ref.getString("sha256")));
        }
        return entry;
    }

    private void awaitMedia(String label, MediaEntry entry, long minDuration, boolean offline, LibraryStore db) throws Exception {
        await(label + " decodes and advances in the native Android player", 41000,
                () -> ui(() -> {
                    if (engine == null || engine.entry() == null || !entry.id.equals(engine.entry().id)) return false;
                    if (engine.failure() != null) throw new AssertionError(label + ": " + engine.failure());
                    return engine.ready() && engine.duration() >= minDuration && engine.playing() && engine.position() > 1000
                            && offline == db.isOffline(entry) && (entry.music() ? !engine.video() && engine.hasAudio() && engine.inBackgroundSession()
                            : engine.videoWidth() > 0 && engine.renderedVideo());
                }));
    }

    private static String offlineRow(MediaEntry item) { return "#libraryList [data-media-id='" + item.id + "'][data-offline='true']"; }

    private void closeScreen(Activity screen) throws Exception {
        ui(() -> { if (screen != null) screen.finish(); if (engine != null) engine.close(); return null; });
        waitForIdleSync();
    }

    private void tapWeb(WebView web, String selector) throws Exception { tapWebAt(web, selector, -1, -1); }

    private void tapWebAt(WebView web, String selector, int left, int top) throws Exception {
        String node = "document.querySelector(" + JSONObject.quote(selector) + ")";
        String locate = "(()=>{const e=" + node + ";if(!e)return [state.view,'missing'];const r=e.getBoundingClientRect();"
                + "const x=r.left+" + (left < 0 ? "r.width/2" : left) + ",y=r.top+" + (top < 0 ? "r.height/2" : top)
                + ";const target=document.elementFromPoint(x,y);return [x,y,innerWidth,!!target&&e.contains(target),state.view,r.width,r.height,target?.tagName,target?.id,target?.className]})()";
        JSONArray hit = new JSONArray();
        // Let scroll/layout and the WebView's frame after navigation settle, but inject exactly one tap.
        for (int i = 0; i < 12; i++) {
            js(web, "(()=>{const e=" + node + ";e?.scrollIntoView({block:'center',inline:'center',behavior:'instant'});return !!e})()");
            hit = new JSONArray(js(web, locate));
            if (hit.optBoolean(3)) break;
            SystemClock.sleep(120);
        }
        assertPass("Touchable WebView control: " + selector + (hit.optBoolean(3) ? "" : "; hit=" + hit), hit.optBoolean(3));
        int[] window = ui(() -> { int[] where = new int[3]; web.getLocationOnScreen(where); where[2] = web.getWidth(); return where; });
        float scale = (float) (window[2] / hit.getDouble(2));
        long when = SystemClock.uptimeMillis();
        float x = window[0] + scale * (float) hit.getDouble(0), y = window[1] + scale * (float) hit.getDouble(1);
        for (int type : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent motion = MotionEvent.obtain(when, SystemClock.uptimeMillis(), type, x, y, 0);
            motion.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { getUiAutomation().injectInputEvent(motion, true); } finally { motion.recycle(); }
        }
    }

    private void shell(String command) throws Exception {
        try (InputStream stdout = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            byte[] drain = new byte[512]; while (stdout.read(drain) != -1) { }
        }
    }

    private boolean jsTrue(WebView web, String script) throws Exception {
        return "true".equals(js(web, "Boolean(" + script + ")"));
    }

    private String jsText(WebView web, String script) throws Exception {
        // Android evaluateJavascript returns JSON, including the quotes around JS strings.
        return new JSONArray("[" + js(web, script) + "]").getString(0);
    }

    private String js(WebView web, String script) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        ui(() -> { web.evaluateJavascript(script, result::complete); return null; });
        return result.get(8, TimeUnit.SECONDS);
    }

    private static WebView findWeb(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWeb(group.getChildAt(i)); if (found != null) return found;
            }
        }
        return null;
    }

    private void screenFromHome(Activity home, String button, Class<? extends Activity> type, String text) throws Exception {
        ActivityMonitor monitor = addMonitor(type.getName(), null, false);
        assertPass("Home exposes " + button, ui(() -> {
            TextView view = find(home.getWindow().getDecorView(), button);
            return view != null && view.performClick();
        }));
        Activity screen = waitForMonitorWithTimeout(monitor, 10000);
        try { assertPass(button + " renders real controls", screen != null && ui(() -> find(screen.getWindow().getDecorView(), text) != null)); }
        finally { if (screen != null) ui(() -> { screen.finish(); return null; }); }
        waitForIdleSync();
    }

    private void verifyLanOnAndroid(Context app, LibraryStore db) throws Exception {
        File bytes = File.createTempFile("lan-smoke", ".mp4", app.getCacheDir());
        try {
            try (InputStream original = getContext().getAssets().open("fixtures/dawn.mp4"); FileOutputStream temp = new FileOutputStream(bytes)) {
                byte[] block = new byte[8192];
                for (int n; (n = original.read(block)) != -1; ) temp.write(block, 0, n);
            }
            LanShare.Item selection = LanShare.Item.file("Android LAN test", MediaEntry.Kind.MOVIE, bytes);
            try (LanShare.Server host = new LanShare.Server(java.net.InetAddress.getByName("127.0.0.1"), 8,
                    java.util.Collections.singletonList(selection), 45000, true)) {
                LanShare.Invite invite = LanShare.parse(host.invite.encode(), true);
                MediaEntry entry;
                try (LanShare.Peer client = new LanShare.Peer(invite)) {
                    entry = LanSessions.set(invite, client.items(), null).get(0);
                    db.save(entry, false);
                }
                Activity screen = launch(PlayerActivity.class, entry.id);
                try {
                    await("Android MediaDataSource actually plays the pinned TLS LAN stream", 17000,
                            () -> ui(() -> engine.entry() != null && entry.id.equals(engine.entry().id) && engine.ready()
                                    && engine.videoWidth() == 640 && engine.renderedVideo() && engine.playing() && engine.position() > 500));
                } finally {
                    ui(() -> { screen.finish(); engine.close(); return null; });
                    db.getWritableDatabase().delete("media", "id=?", new String[] {entry.id});
                }
                assertPass("Closing the LAN player tears down TLS off the UI thread", ui(() -> engine.entry() == null));
            }
        } finally { bytes.delete(); }
    }

    private byte[] fixtureBytes(String argument) {
        return Base64.decode(fixtures.getString(argument), Base64.DEFAULT);
    }

    private void verifyImportedMagnet(LibraryStore db, Activity home, String magnet, TorrentMeta.Item item) throws Exception {
        String label = "Imported torrent smoke title";
        MediaSources.ImportResult catalogue = MediaSources.parsePlaylist("#EXTM3U\n#EXTINF:-1," + label + "\n" + magnet + "\n", null);
        MediaEntry imported = catalogue.entries.get(0);
        assertPass("M3U with a magnet is added to the Android Library", db.importEntries(catalogue.entries) == 1);
        Activity picker = null;
        ActivityMonitor monitor = addMonitor(TorrentActivity.class.getName(), null, false);
        try {
            assertPass("Library search opens the imported title's magnet, not an HTTP player", ui(() -> {
                View root = home.getWindow().getDecorView();
                TextView nav = find(root, "Library");
                if (nav == null || !nav.performClick()) return false;
                View search = findDescription(root, "Search library");
                if (!(search instanceof EditText)) return false;
                ((EditText) search).setText("torrent smoke");
                View title = find(root, label);
                for (int i = 0; title != null && i < 5; i++) {
                    if (title.isClickable()) return title.performClick();
                    title = title.getParent() instanceof View ? (View) title.getParent() : null;
                }
                return false;
            }));
            picker = waitForMonitorWithTimeout(monitor, 10000);
            assertPass("The resolved title opens the native source picker", picker != null);
            Activity screen = picker;
            await("The picker exposes the exact verified movie file", 20000, () -> ui(() ->
                    find(screen.getWindow().getDecorView(), item.name + "  ·  " + Ui.bytes(item.length)) != null));
        } finally {
            Activity screen = picker;
            ui(() -> { if (screen != null) screen.finish(); return null; });
            db.getWritableDatabase().delete("media", "id=?", new String[] {imported.id});
            waitForIdleSync();
        }
    }

    private void verifyTorrentOnAndroid(Context app, LibraryStore db, Activity home) throws Exception {
        TorrentMeta descriptor = TorrentMeta.read(fixtureBytes("fixture_torrent"));
        assertPass("Android reads the independently generated .torrent file", descriptor.items.size() == 1
                && descriptor.items.get(0).playable());
        String magnet = new String(fixtureBytes("fixture_magnet"), StandardCharsets.US_ASCII);
        TorrentMeta resolved = new TorrentNetwork(() -> false).resolve(TorrentMeta.magnet(magnet));
        assertPass("Android retrieves exact BEP 9 metadata through a magnet", Arrays.equals(descriptor.info, resolved.info));
        verifyImportedMagnet(db, home, magnet, resolved.items.get(0));

        TorrentMeta.Item selected = resolved.items.get(0);
        String hex = TorrentMeta.hex(resolved.hash);
        MediaEntry entry = new MediaEntry(selected.name, selected.kind(), "torrent:" + hex + ":" + selected.index);
        Downloads.deleteCopy(app, entry);
        Activity desktop = launch(V90Activity.class, null);
        Activity screen = null;
        try {
            WebView web = ui(() -> findWeb(desktop.getWindow().getDecorView()));
            await("v90 settings is ready for a magnet", 14000, () -> jsTrue(web, "!!window.bbxAndroidOpen && document.querySelector('#engineDot.ok')"));
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            assertPass("Settings submits the exact independently seeded magnet", jsTrue(web,
                    "(document.querySelector('#settingsBtn').click(),document.querySelector('#uri').value=" + JSONObject.quote(magnet)
                            + ",document.querySelector('#addBtn').click(),true)"));
            screen = waitForMonitorWithTimeout(monitor, 27000);
            assertPass("The v90 magnet lookup starts the native player with its selected file", screen != null);
            await("v90 plays verified TCP peer pieces before the offline copy is complete", 55000, () -> ui(() -> engine.entry() != null
                    && entry.id.equals(engine.entry().id) && engine.ready() && engine.videoWidth() == 640
                    && engine.renderedVideo() && engine.playing() && engine.position() > 500 && !db.isOffline(entry)));
            await("Verified torrent file is committed to Offline", 45000,
                    () -> db.isOffline(entry) && db.get(entry.id).transfer == -1);
            try (InputStream original = getContext().getAssets().open("fixtures/dawn.mp4");
                 InputStream copy = new FileInputStream(db.offline(entry))) {
                assertPass("Torrent's offline copy matches the independent seeder", Arrays.equals(sha(original), sha(copy)));
            }
            Activity playable = screen;
            ui(() -> { playable.finish(); engine.close(); return null; });
            assertPass("The original v90 Library can inspect the transferred file", jsTrue(web,
                    "(document.querySelector('[data-nav=library]').click(),true)"));
            await("v90 Library shows the exact finished offline copy", 10000, () -> jsTrue(web,
                    "!document.querySelector('#viewLibrary').hidden && document.querySelector('#libraryList').textContent.includes('Offline copy')"));
        } finally {
            Activity player = screen;
            ui(() -> { if (player != null) player.finish(); engine.close(); desktop.finish(); return null; });
            Downloads.deleteCopy(app, entry);
            db.getWritableDatabase().delete("media", "id=?", new String[] {entry.id});
            TorrentJobs.descriptor(app, TorrentMeta.hex(resolved.hash)).delete();
        }
    }

    private void verifyServicesOnAndroid() throws Exception {
        String relay = fixtures.getString("fixture_relay");
        // Trust only the ephemeral runner certificate in this instrumentation process.
        // The distributed app uses Android's normal trusted HTTPS certificates.
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(fixtureBytes("fixture_cert"))));
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, managers.getTrustManagers(), null);
        SSLSocketFactory previous = HttpsURLConnection.getDefaultSSLSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        try {
            List<Catalogue.Card> movies = Catalogue.browse(URI.create(relay + "/3"), Catalogue.Filter.MOVIE,
                    Catalogue.Shelf.TRENDING, "A + B", "aabbccddeeff1100aabbccddeeff1100", 1);
            assertPass("Android parses catalogue metadata from HTTPS", movies.size() == 1
                    && "A + B".equals(movies.get(0).title) && movies.get(0).entry() == null);
            JSONObject japanese = Catalogue.anime(URI.create(relay + "/3"), "header64.payload64.signature64", false, 2);
            assertPass("Android Anime uses only TMDB title metadata, not a sample provider", japanese.getJSONArray("items").length() == 1
                    && japanese.getJSONArray("items").getJSONObject(0).getInt("id") == 102);
            List<Catalogue.Card> music = Catalogue.music(URI.create(relay + "/ws/2"), Catalogue.Music.TRACKS, "Low Orbit");
            assertPass("Android parses MusicBrainz-shaped search results", music.size() == 1 && music.get(0).musicBrainz());

            CommunityRoom sender = CommunityRoom.create(relay), receiver = CommunityRoom.parse(sender.code());
            CommunityRoom.Event recommendation = CommunityRoom.Event.make("Android test", "Try this clip", "https://media.example/dawn.mp4");
            sender.post(recommendation);
            CommunityRoom.Feed received = receiver.fetch(0);
            assertPass("Android sends and decrypts a recommendation through the actual Community relay",
                    received.events.size() == 1 && recommendation.text.equals(received.events.get(0).text)
                            && recommendation.link.equals(received.events.get(0).link));
        } finally { HttpsURLConnection.setDefaultSSLSocketFactory(previous); }
    }

    private Activity launch(Class<? extends Activity> type, String id) {
        Intent intent = new Intent(getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (id != null) intent.putExtra(PlaybackService.EXTRA_ID, id);
        Activity a = startActivitySync(intent);
        waitForIdleSync();
        return a;
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static byte[] sha(InputStream bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[8192];
        for (int n; (n = bytes.read(chunk)) != -1;) digest.update(chunk, 0, n);
        return digest.digest();
    }

    private <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        runOnMainSync(task);
        return task.get();
    }

    private void await(String label, long timeout, Callable<Boolean> condition) throws Exception {
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            if (condition.call()) { assertPass(label, true); return; }
            SystemClock.sleep(100);
        }
        assertPass(label, false);
    }

    private void assertPass(String name, boolean pass) {
        if (!pass) throw new AssertionError(name);
        checks++;
        android.util.Log.i("BlackBoxSmoke", "PASS " + name);
    }
}
