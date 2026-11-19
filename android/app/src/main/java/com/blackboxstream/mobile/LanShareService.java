package com.blackboxstream.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An explicit, fifteen-minute share that remains visible in Android's system controls. */
public final class LanShareService extends Service {
    static final String EXTRA_FILES = "files";
    static final String STOP = "com.blackboxstream.mobile.STOP_LAN";
    static final long TTL = 15 * 60 * 1000L;
    private static final String CHANNEL = "local-share";
    private static volatile LanShare.Server active;
    static volatile boolean starting;
    static volatile String notice;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private File scratch;
    private Runnable timeout;

    static String invitation() {
        LanShare.Server service = active;
        return service == null || service.expired() ? null : service.invite.encode();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        String[] ids = intent.getStringArrayExtra(EXTRA_FILES);
        int run = ++generation;
        starting = true; notice = null;
        retireShare();
        if (timeout != null) main.removeCallbacks(timeout);
        try { startForeground(16, notification()); }
        catch (IllegalStateException | SecurityException unavailable) { starting = false; notice = "Android couldn't start sharing. Open this screen and try again."; stopSelf(); return START_NOT_STICKY; }
        worker.execute(() -> {
            File temp = null;
            LanShare.Server server = null;
            try {
                WifiRoute wifi = WifiRoute.find(this, null);
                List<LanShare.Item> chosen = new ArrayList<>();
                LibraryStore db = LibraryStore.get(this);
                temp = new File(getCacheDir(), "share-" + java.util.UUID.randomUUID());
                if (!temp.mkdir()) throw new IOException("Could not prepare the share.");
                if (ids != null && ids.length <= LanShare.MAX_ITEMS) for (String id : ids) {
                    MediaEntry e = db.get(id);
                    if (e == null) continue;
                    File file = db.offline(e);
                    if (e.bundled()) {
                        file = new File(temp, e.id);
                        try (InputStream in = getResources().openRawResource(e.music() ? R.raw.orbit : R.raw.dawn);
                             FileOutputStream out = new FileOutputStream(file)) {
                            byte[] bytes = new byte[32768];
                            for (int n; (n = in.read(bytes)) != -1; ) out.write(bytes, 0, n);
                        }
                    }
                    if (file.isFile() && file.length() > 0) chosen.add(LanShare.Item.file(e.title, e.kind, file));
                }
                if (chosen.isEmpty()) throw new IOException("Select a completed offline copy or a built-in sample.");
                if (run != generation) { remove(temp); return; }
                server = new LanShare.Server(wifi.address, wifi.prefix, chosen, TTL, false);
                final LanShare.Server opened = server;
                final File dir = temp;
                main.post(() -> {
                    if (run != generation) { retire(opened, dir); return; }
                    active = opened; scratch = dir; starting = false;
                    timeout = () -> { if (run == generation) stopSelf(); };
                    main.postDelayed(timeout, TTL);
                });
            } catch (IOException | RuntimeException failure) {
                if (server != null) server.close(); remove(temp);
                main.post(() -> { if (run == generation) { starting = false; notice = failure.getMessage(); stopSelf(); } });
            }
        });
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Local Share", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        PendingIntent show = PendingIntent.getActivity(this, 16, new Intent(this, LanShareActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 17, new Intent(this, LanShareService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return builder.setContentTitle("BlackBox Local Share").setContentText("Selected media · local network only")
                .setContentIntent(show).setSmallIcon(R.drawable.ic_play).setOngoing(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(R.drawable.ic_stop, "Stop sharing", stop).build()).build();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) { stopSelf(startId); }
    @Override public void onDestroy() {
        generation++; starting = false; retireShare();
        if (timeout != null) main.removeCallbacks(timeout);
        // Stale startup callbacks still run to retire any socket already opened by the worker.
        worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    private void retireShare() {
        LanShare.Server server = active; active = null;
        File dir = scratch; scratch = null;
        retire(server, dir);
    }
    private static void retire(LanShare.Server server, File dir) {
        if (server == null && dir == null) return;
        if (server != null) server.revoke();
        IoCleanup.run(() -> { try { if (server != null) server.close(); } finally { remove(dir); } });
    }
    private static void remove(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles(); if (files != null) for (File f : files) f.delete(); dir.delete();
    }
}
