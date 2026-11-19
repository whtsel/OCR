package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
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
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Real-device/emulator smoke, separate from the JVM tests and the distributed APK. */
public final class BlackBoxSmoke extends Instrumentation {
    private PlaybackService engine;
    private int checks;
    private boolean liveVerified;
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
            verify();
            result.putString("stream", "\nPASS BlackBox Android smoke (" + checks + " checks)\n"
                    + (liveVerified ? "PASS Live catalogue: full Sintel rendered and sought to 2:30 while still downloading verified torrent pieces\n" : ""));
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
            if (fixtures != null && "yes".equals(fixtures.getString("live_cinema"))) verifyLiveFilm(app, db, visible);
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
            await("sample decodes H.264 and playback advances", 16000, () -> ui(() -> engine != null && engine.ready()
                    && engine.videoWidth() == 640 && engine.renderedVideo() && engine.duration() >= 13500 && engine.playing() && engine.position() > 500));
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

    private void verifyLiveFilm(Context app, LibraryStore db, Activity home) throws Exception {
        Cinema.Film film = Cinema.FILMS.get(0);
        MediaEntry entry = film.torrentEntry();
        Downloads.deleteCopy(app, entry);
        Activity screen = null;
        ActivityMonitor monitor = addMonitor(PlayerActivity.class.getName(), null, false);
        try {
            assertPass("One tap chooses the actual featured film torrent", ui(() -> {
                TextView watch = find(home.getWindow().getDecorView(), "▶  Watch now");
                return watch != null && watch.performClick();
            }));
            screen = waitForMonitorWithTimeout(monitor, 10000);
            assertPass("The featured source opens the Android player", screen != null);
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
            throw new AssertionError(problem.getMessage() + "; player=" + ui(() -> engine == null ? "unbound" : engine.failure())
                    + "; transfer=" + (row == null ? "not queued" : row.transferError + " / " + Downloads.received(db, row) + " verified bytes"), problem);
        } finally {
            Activity player = screen;
            ui(() -> { if (player != null) player.finish(); if (engine != null) engine.close(); return null; });
            Downloads.deleteCopy(app, entry);
            db.getWritableDatabase().delete("media", "id=?", new String[] {entry.id});
            TorrentJobs.descriptor(app, film.hash).delete();
            waitForIdleSync();
        }
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

        MediaEntry entry = TorrentJobs.save(app, resolved, resolved.items.get(0));
        Downloads.deleteCopy(app, entry);
        Activity screen = null;
        try {
            ui(() -> { Downloads.enqueue(app, entry); return null; });
            screen = launch(PlayerActivity.class, entry.id);
            await("Android plays verified TCP peer pieces before the offline copy is complete", 55000, () -> ui(() -> engine.entry() != null
                    && entry.id.equals(engine.entry().id) && engine.ready() && engine.videoWidth() == 640
                    && engine.renderedVideo() && engine.playing() && engine.position() > 500 && !db.isOffline(entry)));
            await("Verified torrent file is committed to Offline", 45000,
                    () -> db.isOffline(entry) && db.get(entry.id).transfer == -1);
            try (InputStream original = app.getResources().openRawResource(R.raw.dawn);
                 InputStream copy = new FileInputStream(db.offline(entry))) {
                assertPass("Torrent's offline copy matches the independent seeder", Arrays.equals(sha(original), sha(copy)));
            }
        } finally {
            Activity player = screen;
            ui(() -> { if (player != null) player.finish(); engine.close(); return null; });
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
