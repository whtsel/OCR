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
    private boolean liveVerified;
    private String providerStatus = "";
    private boolean publicMediaVerified, originalMusicVerified;
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
            else if (fixtures != null && "yes".equals(fixtures.getString("music_only"))) verifyMusicCandidate();
            else if (fixtures != null && "yes".equals(fixtures.getString("public_only"))) verifyCandidate();
            else verify();
            result.putString("stream", "\nPASS BlackBox Android smoke (" + checks + " checks)\n"
                    + (liveVerified ? "PASS v90 Android: original search and source picker opened full Sintel, rendered and sought to 2:30 before the transfer finished\n" : "")
                    + (publicMediaVerified ? "PASS Android in-app playback and complete downloads: Sintel and The Dull Sword (bytes matched public files; both played with networking off)\n" : "")
                    + (originalMusicVerified ? "PASS Original v90 Music: Espresso from Short n’ Sweet and Kwaku the Traveller from Ghana Essentials played the exact official recordings in BlackBox\n" : "")
                    + providerStatus);
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
        Activity launcher = null;
        Activity sample = null;
        Activity offline = null;
        boolean bound = false;
        try {
            launcher = launch(MainActivity.class, null);
            Activity visible = launcher;
            bound = ui(() -> app.bindService(new Intent(app, PlaybackService.class), binding, Context.BIND_AUTO_CREATE));
            assertPass("Cold launch opens a populated film shelf", ui(() -> find(visible.getWindow().getDecorView(), "Open cinema") != null
                    && find(visible.getWindow().getDecorView(), "▶  Watch now") != null
                    && find(visible.getWindow().getDecorView(), "Tears of Steel") != null));
            for (Cinema.Film film : Cinema.FILMS) {
                TorrentMeta meta = film.metadata(app);
                assertPass(film.title + " has an exact playable torrent and HTTP source", meta.items.get(film.index).playable()
                        && film.directEntry(app).remote() && Arrays.equals(meta.hash, TorrentMeta.magnet(film.magnet()).hash));
            }
            screenFromHome(visible, "Sources", FilmActivity.class, "▶  Watch torrent");
            verifyV90OnAndroid(app, db, fixtures != null && "yes".equals(fixtures.getString("live_cinema")));
            screenFromHome(visible, "Torrents", TorrentActivity.class, "Open a torrent");
            screenFromHome(visible, "Discover", DiscoverActivity.class, "Play on-device sample  ›");
            screenFromHome(visible, "Local Share", LanShareActivity.class, "Connect securely");
            screenFromHome(visible, "Community rooms  ›", CommunityActivity.class, "Join room");
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            assertPass("Home sample control", ui(() -> {
                TextView sampleButton = find(visible.getWindow().getDecorView(), "▶  Play sample");
                return sampleButton != null && sampleButton.performClick();
            }));
            sample = waitForMonitorWithTimeout(monitor, 10000);
            assertPass("Tap opens native PlayerActivity", sample != null);
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
            assertPass("watch position is persisted", db.get(LibraryStore.SAMPLE_VIDEO.id).positionMs >= 5500);
            Activity sampleScreen = sample;
            ui(() -> { sampleScreen.finish(); return null; });
            sample = null;

            Downloads.deleteCopy(app, fixture);
            ui(() -> { SmokeMediaProvider.available = true; Downloads.enqueue(app, fixture); return null; });
            await("JobScheduler imports and validates a phone media copy", 60000,
                    () -> db.isOffline(fixture) && db.get(fixture.id).transfer == -1);
            assertPass("offline item persisted as saved", db.get(fixture.id).saved);
            try (InputStream original = app.getResources().openRawResource(R.raw.dawn);
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
            if (liveVerified) verifyLiveProviders(app);
            if (fixtures != null && fixtures.containsKey("public_media")) verifyPublicMedia(app, db);
            Activity audio = launch(PlayerActivity.class, LibraryStore.SAMPLE_AUDIO.id);
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
            if (db.get(fixture.id) != null) db.getWritableDatabase().delete("media", "id=?", new String[] {fixture.id});
        }
    }

    private void verifyV90OnAndroid(Context app, LibraryStore db, boolean live) throws Exception {
        Cinema.Film film = Cinema.FILMS.get(0);
        MediaEntry entry = film.torrentEntry();
        if (live) Downloads.deleteCopy(app, entry);
        V90Api local = new V90Api(app);
        try {
            V90Api.Reply films = local.call("GET", "/api/mobile/open-films", "");
            assertPass("The v90 API returns the real open-film cards: " + films.status + (films.status != 200 ? " / " + films.value.optString("error") : ""),
                    films.status == 200 && films.value.getJSONArray("results").length() == 4);
            V90Api.Reply title = local.call("GET", "/api/tmdb/movie/open_sintel", "");
            assertPass("v90 keyless detail correctly maps to the selected full film", title.status == 200 && "Sintel".equals(title.value.optString("title")));
            String lookup = local.call("POST", "/api/content-resolve", new JSONObject().put("uri", film.magnet()).toString()).value.getString("job");
            String status = "/api/content-job/" + lookup;
            await("v90 manifest lookup binds the original info hash to the requested job", 7000, () -> {
                JSONObject value = local.call("GET", status, "").value;
                JSONObject data = value.optJSONObject("result");
                return data != null && lookup.equals(data.optString("job")) && film.hash.equals(data.optString("id"));
            });
            String selection = new JSONObject().put("job", lookup).toString();
            assertPass("Picking a different info hash cannot start another torrent", local.call("POST",
                    "/api/torrent/" + Cinema.FILMS.get(1).hash + "/select/1", selection).status == 409);
            local.call("POST", status + "/cancel", "{}");
            assertPass("Cancelling a v90 source cannot select or queue its file", local.call("POST",
                    "/api/torrent/" + film.hash + "/select/5", selection).status == 409);
        } finally { local.close(); }
        Activity desktop = launch(V90Activity.class, null);
        Activity screen = null;
        WebView web = ui(() -> findWeb(desktop.getWindow().getDecorView()));
        try {
            assertPass("The Android v90 launcher hosts the bundled Windows interface", web != null);
            await("Android loads the shipped DOMPurify sanitizer as a local asset", 12000,
                    () -> jsTrue(web, "window.DOMPurify?.isSupported === true"));
            await("Original v90 boot and Open films row complete on Android WebView", 18000, () -> jsTrue(web,
                    "document.title.includes('BlackBox Stream v90') && document.querySelector('#rows .card') && document.querySelector('#engineDot.ok') && !!window.bbxExpiryV90"));
            assertPass("Original v90 search accepts a full title", jsTrue(web,
                    "(document.querySelector('#searchInput').value='Sintel',document.querySelector('#searchBtn').click(),true)"));
            await("Original browse cards show the exact title", 12000, () -> jsTrue(web,
                    "!document.querySelector('#viewBrowse').hidden && Array.from(document.querySelectorAll('#browseGrid .card')).some(c=>c.textContent.includes('Sintel'))"));
            assertPass("A v90 result opens its original Detail view", jsTrue(web,
                    "(Array.from(document.querySelectorAll('#browseGrid .card')).find(c=>c.textContent.includes('Sintel')).click(),true)"));
            await("Native adapter supplies the exact source to the original v90 source rows", 12000, () -> jsTrue(web,
                    "!document.querySelector('#viewDetail').hidden && !!Array.from(document.querySelectorAll('#sourcesBox .src')).find(c=>c.title.includes('Blender open film'))"));
            assertPass("Finding sources does not queue a movie transfer", !live || !db.isOffline(entry) && (db.get(entry.id) == null || db.get(entry.id).transfer < 0));
            if (!live) return;
            ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
            assertPass("Playing the v90 source starts the exact manifest lookup", jsTrue(web,
                    "(Array.from(document.querySelectorAll('#sourcesBox .src')).find(c=>c.title.includes('Blender open film')).click(),true)"));
            screen = waitForMonitorWithTimeout(monitor, 18000);
            assertPass("The original v90 chooser opens a native Android player", screen != null);
            await("Sintel renders from the public torrent web seed before download completes", 65000,
                    () -> ui(() -> engine != null && engine.entry() != null && entry.id.equals(engine.entry().id)
                            && engine.ready() && engine.duration() > 720000 && engine.videoWidth() > 640
                            && engine.renderedVideo() && engine.playing() && engine.position() > 750 && !db.isOffline(entry)));
            ui(() -> { engine.seek(150000); return null; });
            await("Sintel seeks to a later part of the full film, still before completion", 22000,
                    () -> ui(() -> engine.playing() && !engine.seeking() && engine.position() >= 149000 && !db.isOffline(entry)));
            liveVerified = true;
        } catch (AssertionError problem) {
            MediaEntry row = db.get(entry.id);
            String page = web == null ? "no WebView" : js(web, "JSON.stringify({url:location.href, title:document.title, adapter:typeof window.bbxAndroidOpen, view:window.__BBX_STATE__?.view, rows:document.querySelector('#rows')?.innerText.slice(0,200), tmdb:window.__BBX_STATE__?.tmdb, hero:document.querySelector('#hero')?.innerText.slice(0,150), engine:document.querySelector('#engineDot')?.className, sources:document.querySelector('#sourcesBox')?.innerText.slice(0,160)})");
            throw new AssertionError(problem.getMessage() + "; page=" + page + "; player=" + ui(() -> engine == null ? "unbound" : engine.failure())
                    + "; transfer=" + (row == null ? "not queued" : row.transferError + " / " + Downloads.received(db, row) + " verified bytes"), problem);
        } finally {
            Activity player = screen;
            ui(() -> { if (player != null) player.finish(); if (engine != null) engine.close(); desktop.finish(); return null; });
            if (live) {
                Downloads.deleteCopy(app, entry);
                db.getWritableDatabase().delete("media", "id=?", new String[] {entry.id});
                TorrentJobs.descriptor(app, film.hash).delete();
            }
            waitForIdleSync();
        }
    }

    /** Tests the real helper loader, isolated queue worker, and actual v90 WebView using only built-in fixture media. */
    private void verifyHelperFixtures() throws Exception {
        Context app = getTargetContext();
        assertPass("Helper fixtures run only on the disposable preview package", app.getPackageName().endsWith(".helper"));
        Class<?> runtime = Class.forName("com.blackboxstream.mobile.BundledMusicRuntime");
        Object instance = runtime.getDeclaredConstructor(Context.class).newInstance(app);
        java.lang.reflect.Method run = runtime.getDeclaredMethod("run", PreparedMusic.Control.class, File.class, String[].class);
        run.setAccessible(true);
        File root = app.getNoBackupFilesDir();
        File scratch = new File(root, "helper-smoke");
        File cache = new File(root, "prepared-music-" + PreparedMusic.VERSION);
        android.content.SharedPreferences prefs = app.getSharedPreferences("music-preparation", Context.MODE_PRIVATE);
        assertPass("Prepare a fresh test directory", scratch.mkdir());
        Activity desktop = null;
        try {
            String installed = (String) run.invoke(instance, new PreparedMusic.Control(), scratch, new String[] {"--version"});
            assertPass("Pinned Android Python runtime executes yt-dlp 2026.10.26", PreparedMusic.VERSION.equals(installed.trim()));
            File video = new File(scratch, "fixture.mp4");
            copyRaw(app, R.raw.dawn, video);
            run.invoke(instance, new PreparedMusic.Control(), scratch, new String[] {"--enable-file-urls", "-x", "--audio-format", "m4a",
                    "-o", new File(scratch, "audio.%(ext)s").getPath(), video.toURI().toString()});
            File audio = new File(scratch, "audio.m4a");
            PreparedMusic.verifyAudio(audio, 14);
            assertPass("Bundled FFmpeg extracts complete audio on Android", audio.isFile());
            try {
                PreparedMusic.verifyAudio(audio, 175);
                throw new AssertionError("Short fixture accepted as a 175-second recording");
            } catch (java.io.IOException expected) { checks++; }
            verifyPreparationQueue(app, scratch, audio);

            // Seed only the disposable test package. These files prove local playback, NOT live recording availability.
            assertPass("Preview cache starts empty", cache.mkdir());
            copyRaw(app, R.raw.orbit, new File(cache, "51zjlMhdSTE.m4a"));
            copyRaw(app, R.raw.orbit, new File(cache, "Hb6I7GoTvzI.m4a"));
            desktop = launch(V90Activity.class, null);
            Activity shown = desktop;
            WebView web = ui(() -> findWeb(shown.getWindow().getDecorView()));
            await("v90 preview opens the original library and genres", 18000,
                    () -> jsTrue(web, "window.bbxAndroidOpen && document.querySelector('#rows .card')"));
            tapWeb(web, "#navMusic");
            await("The Music opt-in is present, with the original fourteen genres", 8000,
                    () -> jsTrue(web, "document.querySelector('#androidMusicHelper')?.hidden===false && document.querySelectorAll('#musicGenreChips button').length===14"));
            tapWeb(web, "#androidMusicHelperToggle");
            tapWeb(web, "#androidMusicHelperConsent .primary");
            await("Two opted-in cached fixtures are ready", 8000, () -> jsTrue(web, "document.querySelectorAll('#androidPreparedMusic button').length===2"));
            shell("cmd connectivity airplane-mode enable");
            shell("svc wifi disable"); shell("svc data disable");
            try {
                android.net.ConnectivityManager network = app.getSystemService(android.net.ConnectivityManager.class);
                await("Test device is offline", 14000, () -> network.getActiveNetwork() == null);
                tapWeb(web, "#music-shelf-trending-now [title='Play Espresso']");
                awaitPreparedFixture(web, "Espresso", 1);
                js(web, "(()=>{const seek=document.querySelector('#bgSeek');seek.value=66;seek.dispatchEvent(new Event('input'))})()");
                awaitPreparedFixture(web, "Espresso", 16.3);
                tapWeb(web, "#bgPlayBtn");
                await("Pause is available in the visible original player", 6000, () -> jsTrue(web, "document.querySelector('#bgAudio').paused"));
                tapWeb(web, "#bgPlayBtn");
                awaitPreparedFixture(web, "Espresso", 17);
                // Queue the two canonical rows without any online search, and let playback completion advance.
                js(web, "queueSet([musicQueue.list[musicQueue.idx],{title:'Kwaku the Traveller',artist:'Black Sherif',album:'The Villain I Never Was',duration:185}],0,'Fixture only')");
                js(web, "document.querySelector('#bgAudio').currentTime = document.querySelector('#bgAudio').duration - 0.25");
                awaitPreparedFixture(web, "Kwaku the Traveller", 1);
                closeScreen(desktop);
                desktop = launch(V90Activity.class, null);
                Activity reopened = desktop;
                WebView offline = ui(() -> findWeb(reopened.getWindow().getDecorView()));
                await("Preview relaunches with networking still off", 18000,
                        () -> jsTrue(offline, "window.bbxAndroidOpen && document.querySelector('#rows .card')"));
                tapWeb(offline, "#navMusic");
                await("Kwaku fixture is still available from the committed cache", 10000,
                        () -> jsTrue(offline, "document.querySelectorAll('#androidPreparedMusic button').length===2"));
                tapWeb(offline, "#androidPreparedMusic button:last-child");
                awaitPreparedFixture(offline, "Kwaku the Traveller", 1);
            } finally {
                shell("cmd connectivity airplane-mode disable"); shell("svc data enable"); shell("svc wifi enable");
            }
        } finally {
            closeScreen(desktop);
            prefs.edit().clear().commit();
            PreparedMusic.deleteScratch(cache);
            PreparedMusic.deleteScratch(scratch);
        }
    }

    private void awaitPreparedFixture(WebView web, String title, double from) throws Exception {
        String playable = "(()=>{const a=document.querySelector('#bgAudio'), b=document.querySelector('#bgAudioBar');return a?.src.startsWith('blob:https://blackbox.invalid/') && !b.hidden && getComputedStyle(b).display!=='none' && document.querySelector('#bgTitle').textContent==="
                + JSONObject.quote(title) + " && a.duration > 23 && a.duration < 25 && !a.paused && a.currentTime > " + from + "})()";
        try {
            await(title + " fixture loads into the v90 audio player", 12000,
                    () -> jsTrue(web, "document.querySelector('#bgAudio').readyState>=2 && document.querySelector('#bgTitle').textContent===" + JSONObject.quote(title)));
            if (jsTrue(web, "document.querySelector('#bgAudio').paused")) tapWeb(web, "#bgPlayBtn");
            await(title + " fixture decodes and advances from " + from, 10000, () -> jsTrue(web, playable));
        } catch (AssertionError error) {
            throw new AssertionError(error.getMessage() + ": " + js(web, "JSON.stringify({blob:document.querySelector('#bgAudio').src.startsWith('blob:'),error:document.querySelector('#bgAudio').error?.code,duration:document.querySelector('#bgAudio').duration,time:document.querySelector('#bgAudio').currentTime,status:document.querySelector('#musicMsg').textContent})"),error);
        }
    }

    private void verifyPreparationQueue(Context app, File scratch, File audio) throws Exception {
        String a = "aaaaaaaaaaa", b = "bbbbbbbbbbb", c = "ccccccccccc", d = "ddddddddddd";
        PreparedMusic.Track[] tracks = {new PreparedMusic.Track(a, "A", "Fixture", 14), new PreparedMusic.Track(b, "B", "Fixture", 14),
                new PreparedMusic.Track(c, "C", "Fixture", 14), new PreparedMusic.Track(d, "D", "Fixture", 14)};
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean unmetered = new java.util.concurrent.atomic.AtomicBoolean(false);
        PreparedMusic.Runtime fixture = (track, work, control) -> {
            calls.add(track.id);
            if (track.id.equals(b)) while (!control.cancelled) Thread.sleep(10);
            File local = new File(work, "audio.m4a");
            try (InputStream in = new FileInputStream(audio); FileOutputStream out = new FileOutputStream(local)) {
                byte[] bytes = new byte[8192];
                for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
            }
            return local;
        };
        File dir = new File(scratch, "queue");
        android.content.SharedPreferences prefs = app.getSharedPreferences("helper-fixture-unit", Context.MODE_PRIVATE);
        try (PreparedMusic queue = new PreparedMusic(dir, prefs, fixture, background -> !background || unmetered.get(), tracks)) {
            queue.plan(1, a, new JSONArray().put(b).put(c), true);
            assertPass("Opt-out prevents queueing and access", calls.isEmpty() && queue.read(a) == null);
            queue.options(true, false);
            queue.plan(1, a, new JSONArray().put(b).put(c), true);
            await("Selected audio prepares on a metered connection", 7000, () -> queue.read(a) != null);
            assertPass("Metered prefetch waits for an unmetered connection", calls.equals(Arrays.asList(a))
                    && "queued".equals(queue.state().getJSONObject("tracks").getJSONObject(b).getString("state")));
            unmetered.set(true);
            queue.state();
            await("First actual upcoming entry starts", 7000, () -> calls.contains(b));
            assertPass("Only one job runs at once", calls.equals(Arrays.asList(a,b)));
            queue.plan(2, b, new JSONArray().put(c), true); // Promote rather than restart B.
            queue.plan(4, d, new JSONArray().put(c).put(a), true); // Cancel B when it's no longer wanted.
            queue.plan(3, a, new JSONArray().put(b), true); // Delayed stale bridge message.
            await("New foreground jumps ahead; still-relevant second entry prepares", 9000, () -> queue.read(d) != null && queue.read(c) != null);
            assertPass("Cancelled output never commits; kept files deduplicate", queue.read(b) == null && calls.equals(Arrays.asList(a,b,d,c)));
            try {
                queue.plan(5, a, new JSONArray().put(b).put(c).put(d), true);
                throw new AssertionError("Three upcoming items accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }
        try (PreparedMusic reopened = new PreparedMusic(dir, prefs, fixture, background -> false, tracks)) {
            reopened.plan(1, a, new JSONArray(), true);
            assertPass("Completed file is reused across instances when offline", reopened.read(a) != null && calls.size() == 4);
            reopened.options(false, true);
            assertPass("Clearing the cache leaves no ready file", reopened.read(a) == null && !new File(dir, a + ".m4a").exists());
        } finally { prefs.edit().clear().commit(); }
    }

    private static void copyRaw(Context app, int resource, File target) throws Exception {
        try (InputStream in = app.getResources().openRawResource(resource); FileOutputStream out = new FileOutputStream(target)) {
            byte[] bytes = new byte[8192];
            for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
        }
    }

    private void verifyMusicCandidate() throws Exception {
        Activity desktop = launch(V90Activity.class, null);
        try {
            WebView web = ui(() -> findWeb(desktop.getWindow().getDecorView()));
            await("The v90 music shell is ready on a phone viewport", 18000,
                    () -> jsTrue(web, "window.bbxAndroidOpen && document.querySelector('#rows .card') && innerWidth <= 400"));
            verifyOriginalMusic(web);
            originalMusicVerified = true;
        } finally { closeScreen(desktop); }
    }

    /** Run the same public media acceptance on the non-debuggable standalone app. */
    private void verifyCandidate() throws Exception {
        Context app = getTargetContext();
        assertPass("Standalone media acceptance does not depend on debug fixtures",
                (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0);
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
        final String station = "#androidDetailMedia", play = " .android-media-play", download = " .android-media-download";
        try {
            await("The v90 touch UI is ready on a phone viewport", 18000,
                    () -> jsTrue(web, "document.querySelector('#rows .card') && innerWidth <= 400"));
            // Use the real pointer, including the original navigation and detail actions.
            js(web, "document.querySelector('#searchInput').value='Sintel'");
            tapWeb(web, "#searchBtn");
            await("Search results render a touchable Sintel card", 17000, () -> jsTrue(web,
                    "Array.from(document.querySelectorAll('#browseGrid .card')).some(c=>c.textContent.includes('Sintel'))"));
            js(web, "Array.from(document.querySelectorAll('#browseGrid .card')).find(c=>c.textContent.includes('Sintel')).id='smokeSintel'");
            tapWeb(web, "#smokeSintel");
            saved.add(verifyDirect(web, db, records, "film:sintel", "Sintel", station, "#detailPlay", station + download,
                    MediaEntry.Kind.MOVIE, 720000L, 150000L));

            tapWeb(web, "[data-nav=anime]");
            String archive = "#androidAnimeArchive [data-playable-key='anime:dull-sword']";
            await("Anime tab shows a real in-app playable archive title", 12000,
                    () -> jsTrue(web, "document.querySelector(" + JSONObject.quote(archive) + ")"));
            tapWeb(web, archive);
            saved.add(verifyDirect(web, db, records, "anime:dull-sword", "The Dull Sword", station, station + play, station + download,
                    MediaEntry.Kind.ANIME, 250000L, 60000L));

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
            if ("yes".equals(fixtures.getString("public_only"))) {
                verifyOriginalMusic(web);
                originalMusicVerified = true;
            }
        } catch (AssertionError problem) {
            String view = js(web, "JSON.stringify({view:window.__BBX_STATE__?.view,title:document.querySelector('#detailBody h1')?.textContent,"
                    + "music:document.getElementById('musicMsg')?.textContent,status:Array.from(document.querySelectorAll('.android-action-status')).map(e=>e.textContent).filter(Boolean).slice(-5)})");
            throw new AssertionError(problem.getMessage() + "; page=" + view, problem);
        } finally {
            ui(() -> { if (engine != null) engine.close(); desktop.finish(); return null; });
            for (MediaEntry e : saved) {
                Downloads.deleteCopy(app, e); db.getWritableDatabase().delete("media", "id=?", new String[] {e.id});
            }
        }
    }

    private void verifyOriginalMusic(WebView web) throws Exception {
        tapWeb(web, "#navMusic");
        await("Original Music shelves contain 14 genres and the original Trending Now card", 10000,
                () -> jsTrue(web, "document.querySelectorAll('#musicGenreChips button').length===14 "
                        + "&& document.querySelector('#music-shelf-trending-now [title=\"Play Espresso\"]')"));
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
        verifyOfficialTrack(web, "Espresso", ".music-album-tracklist-v63 .music-catalog-track-v63:nth-child(7) [data-track-play-v63]", "sc:1799087761", 175);
        tapWeb(web, "#musicClear");
        tapWeb(web, "#musicGenreChips [title='Open 30 Ghana Essentials picks']");
        await("Ghana Essentials shows its original 30 picks", 8000,
                () -> jsTrue(web, "!document.querySelector('#viewMusicGenre').hidden && document.querySelectorAll('[data-genre-catalog] .music-genre-card-v50').length===30"));
        verifyOfficialTrack(web, "Kwaku the Traveller", "#musicGenrePage [title='Play Kwaku the Traveller']", "sc:1229321974", 185);
        tapWeb(web, "#musicGenrePage [title='Play Adonai (Remix) [feat. Castro]']");
        await("An unlinked recording offers its own sources without silently playing a substitute", 8000,
                () -> jsTrue(web, "document.querySelector('#androidMusicSource')?.open && "
                        + "document.querySelector('#androidTrackTitle')?.textContent.includes('Adonai')"));
    }

    private void verifyOfficialTrack(WebView web, String title, String control, String id, int seconds) throws Exception {
        ActivityMonitor monitor = addMonitor(MusicVideoActivity.class.getName(), null, false);
        Activity screen = null;
        WebView player = null;
        try {
            tapWeb(web, control);
            screen = waitForMonitorWithTimeout(monitor, 12000);
            assertPass(title + " opens the official player inside BlackBox", screen != null && id.equals(screen.getIntent().getStringExtra(MusicVideoActivity.EXTRA_VIDEO)));
            Activity activity = screen;
            player = ui(() -> findWeb(activity.getWindow().getDecorView()));
            assertPass("The separate provider player has no catalogue bridge", jsTrue(player, "typeof BlackBoxAndroid === 'undefined'"));
            WebView embed = player;
            if (id.startsWith("sc:")) {
                await(title + " SoundCloud controls finish loading", 24000, () -> officialState(embed).optBoolean("ready"));
                // Normal player fallback when the WebView/provider doesn't allow autoplay.
                SystemClock.sleep(2800);
                JSONObject state = officialState(embed);
                if (state.optInt("error") == 0 && state.optDouble("seconds", 0) < .2) tapWebAt(embed, "#recording", 36, 36);
            }
            await(title + " plays the exact full online recording (not a preview)", 65000, () -> {
                JSONObject state = officialState(embed);
                if (state.optInt("error") != 0) throw new AssertionError(title + " provider error: " + state);
                return state.optInt("state", -2) == 1 && state.optDouble("seconds", 0) >= 1
                        && Math.abs(state.optDouble("duration", 0) - seconds) <= 6
                        && (id.startsWith("sc:") ? id.substring(3).equals(state.optString("id"))
                        : state.optString("url").contains("v=" + id));
            });
            int seek = seconds * 2 / 3;
            js(player, "bbxSeek(" + seek + ")");
            await(title + " advances from a seek beyond the preview window", 22000, () -> {
                JSONObject state = officialState(embed);
                return state.optInt("state", -2) == 1 && state.optDouble("seconds", 0) > seek + 1;
            });
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "; official player=" + (player == null ? "not opened" : officialState(player)), failure);
        } finally { closeScreen(screen); removeMonitor(monitor); }
    }

    private JSONObject officialState(WebView web) throws Exception {
        String value = js(web, "JSON.stringify(window.bbxPlayerState?.() || {url:location.href,api:typeof YT})");
        return new JSONObject((String) new org.json.JSONTokener(value).nextValue());
    }

    private MediaEntry verifyDirect(WebView web, LibraryStore db, JSONObject records, String key, String title,
                                    String card, String play, String download, MediaEntry.Kind kind,
                                    long duration, long seek) throws Exception {
        await(title + " has both Play and Download on screen", 22000, () -> jsTrue(web,
                "document.querySelector(" + JSONObject.quote(card) + ")?.dataset.playableKey === " + JSONObject.quote(key)
                + " && document.querySelector(" + JSONObject.quote(play) + ") && document.querySelector(" + JSONObject.quote(download) + ")"));
        ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
        Activity screen = null;
        MediaEntry entry;
        try {
            tapWeb(web, play);
            screen = waitForMonitorWithTimeout(monitor, 17000);
            assertPass(title + " opens the internal player (not another app)", screen != null);
            entry = db.get(screen.getIntent().getStringExtra(PlaybackService.EXTRA_ID));
            assertPass(title + " resolves the right direct source and media type", entry != null && entry.remote() && entry.kind == kind && entry.title.contains(title));
            awaitMedia(title + " online", entry, duration, false, db);
            ui(() -> { engine.seek(seek); return null; });
            await(title + " seeks in the full online file", 16000,
                    () -> ui(() -> engine.playing() && !engine.seeking() && engine.position() >= seek - 1100));
        } finally { closeScreen(screen); removeMonitor(monitor); }
        tapWeb(web, download);
        await(title + " download finishes and commits in Android", 110000, () -> {
            MediaEntry active = db.get(entry.id);
            if (active.transferError != null) throw new AssertionError(title + " download failed: " + active.transferError);
            return db.isOffline(entry) && active.transfer < 0;
        });
        JSONObject ref = records.getJSONObject(key);
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
        js(web, "(()=>{const e=" + node + ";e?.scrollIntoView({block:'center',inline:'center',behavior:'instant'});return !!e})()");
        JSONArray hit = new JSONArray(js(web, "(()=>{const e=" + node + ";if(!e)return [];const r=e.getBoundingClientRect();"
                + "const x=r.left+" + (left < 0 ? "r.width/2" : left) + ",y=r.top+" + (top < 0 ? "r.height/2" : top)
                + ";return [x,y,innerWidth,!!document.elementFromPoint(x,y)&&e.contains(document.elementFromPoint(x,y))]})()"));
        assertPass("Touchable WebView control: " + selector, hit.length() == 4 && hit.getBoolean(3));
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
            try (InputStream original = app.getResources().openRawResource(R.raw.dawn); FileOutputStream temp = new FileOutputStream(bytes)) {
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
            try (InputStream original = app.getResources().openRawResource(R.raw.dawn);
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

    private void verifyLiveProviders(Context app) throws Exception {
        V90Api api = new V90Api(app);
        java.util.concurrent.ExecutorService probes = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.Map<String, java.util.concurrent.Future<String>> responses = new java.util.LinkedHashMap<>();
            for (String name : Arrays.asList("Knaben", "Torrents-CSV", "Anime catalogue", "Incompetech")) {
                responses.put(name, probes.submit(() -> {
                    String route = name.equals("Anime catalogue") ? "/api/anime/search?q=Astro%20Boy&page=1"
                            : name.equals("Incompetech") ? "/api/mobile/music?q=Sauropod%20Spotting"
                            : "/api/sources/" + (name.equals("Knaben") ? "find" : "deep") + "?type=movie&title=Elephants%20Dream&year=2006";
                    V90Api.Reply reply = api.call("GET", route, "");
                    if (reply.status != 200) return "unavailable (" + reply.status + ": " + reply.value.optString("message") + ")";
                    if ("unavailable".equals(reply.value.optString("indexStatus"))) return reply.value.optString("note");
                    org.json.JSONArray entries = reply.value.optJSONArray(name.equals("Anime catalogue") ? "items" : name.equals("Incompetech") ? "results" : "sources");
                    String provider = reply.value.optString("provider");
                    return (provider.isEmpty() ? "" : provider + " ") + "answered with " + (entries == null ? 0 : entries.length()) + " results";
                }));
            }
            StringBuilder report = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.concurrent.Future<String>> result : responses.entrySet()) {
                String status;
                try { status = result.getValue().get(18, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) { status = "did not complete"; }
                report.append("v90 provider ").append(result.getKey()).append(": ").append(status).append('\n');
            }
            providerStatus = report.toString();
        } finally { api.close(); probes.shutdownNow(); }
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
