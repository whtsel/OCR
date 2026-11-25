package com.blackboxstream.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Owns saved-track playback independently of the v90 screen. Never downloads or auto-starts music. */
public final class OfflineMusicService extends Service {
    private static final String CHANNEL = "offline_music_playback";
    private static final String VALUE = "value";
    private static final int NOTIFICATION = 5;
    private static final long PAUSE_TIMEOUT = 5 * 60_000L;
    // The bridge reads a volatile snapshot; player commands and the service stay on the main thread.
    private static volatile OfflineMusicPlayer current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idle = this::endSession;
    private final BroadcastReceiver headphones = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { transport("pause", ""); }
    };
    private OfflineMusicLibrary library;
    private OfflineMusicPlayer player;
    private NotificationManager notifications;
    private MediaSession session;
    private PendingIntent openApp;
    private boolean foreground;

    static boolean isOfflineBuild(Context context) {
        String pkg = context.getPackageName();
        return "com.blackboxstream.mobile.offline.desktop".equals(pkg)
                || "com.blackboxstream.mobile.offline.retry".equals(pkg);
    }

    static String snapshot() {
        OfflineMusicPlayer p = current;
        return p == null ? "{}" : p.snapshot();
    }

    private static boolean opensTrack(String action) { return "play".equals(action) || "playlist".equals(action); }

    // Only the resumed activity may request a new session. Notification buttons control an existing one.
    static boolean fromScreen(Context context, String action, String value) {
        boolean open = opensTrack(action);
        if (!open && (current == null || !("toggle".equals(action) || "stop".equals(action) || "next".equals(action)
                || "previous".equals(action) || "seek".equals(action)))) return true;
        Intent request = new Intent(context, OfflineMusicService.class).setAction(action).putExtra(VALUE, value);
        try {
            if (open && Build.VERSION.SDK_INT >= 26) context.startForegroundService(request);
            else context.startService(request);
            return true;
        } catch (IllegalStateException | SecurityException denied) { return false; }
    }

    @Override public void onCreate() {
        super.onCreate();
        library = OfflineMusicLibrary.open(this);
        notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Saved music playback", NotificationManager.IMPORTANCE_LOW));
        openApp = PendingIntent.getActivity(this, 5, new Intent(this, V90Activity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession(this, "BlackBoxOfflineMusic");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(openApp);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { transport("resume", ""); }
            @Override public void onPause() { transport("pause", ""); }
            @Override public void onStop() { transport("stop", ""); }
            @Override public void onSkipToNext() { transport("next", ""); }
            @Override public void onSkipToPrevious() { transport("previous", ""); }
            @Override public void onSeekTo(long where) { transport("seek", Long.toString(where)); }
        }, main);
        IntentFilter noisy = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(headphones, noisy, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(headphones, noisy);
        player = new OfflineMusicPlayer(this, library, this::changed);
        current = player;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent request, int flags, int startId) {
        if (request == null || !isOfflineBuild(this)) { endSession(); return START_NOT_STICKY; }
        String action = request.getAction();
        if (opensTrack(action) && !foreground) {
            try {
                // Promote before opening the decoder or requesting focus (required on Android 15).
                Notification notice = notification(null);
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NOTIFICATION, notice);
                foreground = true;
            } catch (IllegalStateException | SecurityException denied) {
                Log.w("BlackBoxOfflineMusic", "Android did not allow the playback service to start");
                endSession();
                return START_NOT_STICKY;
            }
        }
        String value = request.getStringExtra(VALUE);
        if (action != null) transport(action, value == null ? "" : value);
        if (!foreground || player.key().isEmpty()) endSession();
        return START_NOT_STICKY; // No playback after a force-stop or process kill until the user presses Play.
    }

    private void transport(String action, String value) {
        if (!foreground) return;
        if (V90Api.expired()) { endSession(); return; }
        player.command(action, value);
    }

    private PendingIntent button(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, OfflineMusicService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification(PreparedMusic.Track track) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setContentTitle(track == null ? "Opening saved music…" : track.title)
                .setContentText(track == null ? getString(R.string.app_name) : track.artist)
                .setContentIntent(openApp).setSmallIcon(R.drawable.ic_play).setOnlyAlertOnce(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(player.preparing() || player.playing());
        Notification.MediaStyle style = new Notification.MediaStyle().setMediaSession(session.getSessionToken());
        if (track != null) {
            boolean playing = player.playing() || player.preparing();
            builder.addAction(new Notification.Action.Builder(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                    playing ? "Pause" : "Play", button("toggle", 1)).build());
            style.setShowActionsInCompactView(0);
        }
        return builder.addAction(new Notification.Action.Builder(R.drawable.ic_stop, "Stop", button("stop", 2)).build())
                .setStyle(style).build();
    }

    private void changed() {
        if (!foreground) return;
        if (player.key().isEmpty()) { endSession(); return; }
        PreparedMusic.Track track = library.recording(player.key());
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration());
        if (track != null) metadata.putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist).putString(MediaMetadata.METADATA_KEY_ALBUM, track.album);
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
        if (player.previous()) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (player.next()) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        if (player.duration() > 0) actions |= PlaybackState.ACTION_SEEK_TO;
        boolean playing = player.playing(), opening = player.preparing();
        session.setMetadata(metadata.build());
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(
                playing ? PlaybackState.STATE_PLAYING : opening ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED,
                player.position(), playing ? 1f : 0f, SystemClock.elapsedRealtime()).build());
        session.setActive(true);
        notifications.notify(NOTIFICATION, notification(track));
        main.removeCallbacks(idle);
        if (!playing && !opening) main.postDelayed(idle, PAUSE_TIMEOUT);
    }

    private void endSession() {
        main.removeCallbacks(idle);
        if (foreground) {
            foreground = false;
            player.command("stop", "");
            session.setActive(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(idle);
        current = null;
        player.close();
        session.release();
        unregisterReceiver(headphones);
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
