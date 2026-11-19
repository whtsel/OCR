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
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Real-device/emulator smoke, separate from the JVM tests and the distributed APK. */
public final class BlackBoxSmoke extends Instrumentation {
    private PlaybackService engine;
    private int checks;
    private final ServiceConnection binding = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder local) { engine = ((PlaybackService.LocalBinder) local).get(); }
        @Override public void onServiceDisconnected(ComponentName name) { engine = null; }
    };

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verify();
            result.putString("stream", "\nPASS BlackBox Android smoke (" + checks + " checks)\n");
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
            bound = ui(() -> app.bindService(new Intent(app, PlaybackService.class), binding, Context.BIND_AUTO_CREATE));
            await("sample decodes H.264 and playback advances", 16000, () -> ui(() -> engine != null && engine.ready()
                    && engine.videoWidth() == 640 && engine.duration() >= 13500 && engine.playing() && engine.position() > 500));
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
                                    && engine.videoWidth() == 640 && engine.playing() && engine.position() > 500));
                } finally {
                    ui(() -> { screen.finish(); engine.close(); return null; });
                    db.getWritableDatabase().delete("media", "id=?", new String[] {entry.id});
                }
            }
        } finally { bytes.delete(); }
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
