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
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDataSource;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single native playback session. Video is visible-only; audio can outlive the activity. */
public final class PlaybackService extends Service {
    static final String EXTRA_ID = "id";
    private static final String TOGGLE = "com.blackboxstream.mobile.TOGGLE";
    private static final String CLOSE = "com.blackboxstream.mobile.CLOSE";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION = 4;
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    interface Listener { void playerChanged(); }
    final class LocalBinder extends Binder { PlaybackService get() { return PlaybackService.this; } }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService documents = Executors.newFixedThreadPool(2);
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final Runnable idleAudio = this::close;
    private final AudioManager.OnAudioFocusChangeListener focusCallback = code -> main.post(() -> focusChanged(code));
    private final BroadcastReceiver headphones = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); }
    };
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest request;
    private LibraryStore store;
    private MediaPlayer player;
    private ExternalSource externalSource;
    private Object visibleOwner;
    private SurfaceHolder display;
    private MediaEntry entry;
    private String failure;
    private boolean preparing, ready, video, buffering, ended, playing, foreground, resumeOnFocus, destroyed, startWhenPrepared, rendered;
    private long queuedPosition, lastPersist;
    private int generation;

    private final Runnable prepareDeadline = () -> {
        if (preparing) fail(entry.torrent() ? "Torrent playback timed out waiting for verified pieces. Check Library or try another source."
                : "This source did not open in time. Check the connection, retry, or download the file first.");
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            if (SystemClock.elapsedRealtime() - lastPersist > 5000) persist(false);
            emit();
            main.postDelayed(this, 350);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = LibraryStore.get(this);
        audio = getSystemService(AudioManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(ATTRIBUTES)
                    .setOnAudioFocusChangeListener(focusCallback, main).build();
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Media playback", NotificationManager.IMPORTANCE_LOW));
        }
        IntentFilter noisy = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(headphones, noisy, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(headphones, noisy);
        session = new MediaSession(this, "BlackBoxPlayer");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { close(); }
            @Override public void onSeekTo(long where) { seek(where); }
            @Override public void onFastForward() { seek(position() + 10000); }
            @Override public void onRewind() { seek(position() - 10000); }
        }, main);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CLOSE.equals(intent.getAction())) close();
        else if (intent != null && TOGGLE.equals(intent.getAction())) toggle();
        if (!foreground) stopSelf(startId);
        return START_NOT_STICKY;
    }

    void observe(Listener listener) { listeners.add(listener); }
    void unobserve(Listener listener) { listeners.remove(listener); }
    private void emit() { for (Listener listener : new ArrayList<>(listeners)) listener.playerChanged(); }
    MediaEntry entry() { return entry; }
    boolean ready() { return ready; }
    boolean playing() { return playing; }
    boolean video() { return video; }
    boolean renderedVideo() { return rendered; }
    boolean hasAudio() {
        if (!ready || player == null) return false;
        try {
            for (MediaPlayer.TrackInfo track : player.getTrackInfo()) if (track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) return true;
        } catch (IllegalStateException ignored) { }
        return false;
    }
    boolean buffering() { return buffering; }
    boolean inBackgroundSession() { return foreground; }
    boolean seeking() { return queuedPosition >= 0 && ready; }
    boolean preparing() { return preparing; }
    boolean ended() { return ended; }
    String failure() { return failure; }

    void show(Object owner) { visibleOwner = owner; }

    void hide(Object owner) {
        if (visibleOwner != owner) return;
        visibleOwner = null;
        setDisplay(null);
        if (!ready || video || !foreground) pause();
    }

    void display(Object owner, SurfaceHolder surface) {
        if (visibleOwner == owner) setDisplay(surface);
    }

    private void setDisplay(SurfaceHolder surface) {
        display = surface;
        if (player != null) {
            try { player.setDisplay(surface); }
            catch (IllegalArgumentException | IllegalStateException ignored) { }
        }
    }

    void open(MediaEntry next, boolean force, boolean autoplay) {
        if (!force && entry != null && entry.id.equals(next.id)) {
            startWhenPrepared = autoplay;
            if (autoplay) play();
            return;
        }
        persist(false);
        dropPlayer();
        leaveForeground();
        entry = next;
        failure = null;
        video = !next.music();
        queuedPosition = next.positionMs;
        startWhenPrepared = autoplay;
        preparing = true;
        main.postDelayed(prepareDeadline, next.torrent() ? 65000 : 45000);
        final int ticket = generation;
        // A document provider may be backed by cloud storage. Don't call it from the UI thread.
        if ((next.torrent() || next.lan()) && !store.isOffline(next)) {
            documents.execute(() -> {
                MediaBytes bytes = null;
                try { bytes = next.torrent() ? TorrentJobs.open(next) : LanSessions.open(next); }
                catch (IOException | RuntimeException ignored) { }
                final MediaBytes prepared = bytes;
                main.post(() -> {
                    if (destroyed || ticket != generation) { closeSource(prepared); return; }
                    if (prepared == null) {
                        if (store.isOffline(next)) { open(next, true, startWhenPrepared); return; }
                        fail(next.torrent() ? "Waiting for torrent data. Check Offline for progress or a seeder error, then tap Retry."
                                : "Local Share isn't connected. Reopen its invitation or play a saved copy.");
                        return;
                    }
                    ExternalSource source = new ExternalSource(prepared);
                    externalSource = source;
                    prepare(p -> p.setDataSource(source));
                });
            });
        } else if (next.fromPhone() && !store.isOffline(next)) {
            documents.execute(() -> {
                AssetFileDescriptor fd = null;
                try { fd = getContentResolver().openAssetFileDescriptor(Uri.parse(next.source), "r"); }
                catch (IOException | RuntimeException ignored) { }
                final AssetFileDescriptor asset = fd;
                main.post(() -> {
                    try (AssetFileDescriptor input = asset) {
                        if (destroyed || ticket != generation) return;
                        if (input == null) fail("Android couldn't open the file. Pick it again from your phone.");
                        else prepare(p -> p.setDataSource(input));
                    } catch (IOException ignored) { }
                });
            });
        } else {
            prepare(p -> {
                if (store.isOffline(next)) {
                    try (FileInputStream file = new FileInputStream(store.offline(next))) { p.setDataSource(file.getFD()); }
                } else if (next.bundled()) {
                    int resource = "demo:orbit".equals(next.source) ? R.raw.orbit : R.raw.dawn;
                    try (AssetFileDescriptor fd = getResources().openRawResourceFd(resource)) { p.setDataSource(fd); }
                } else p.setDataSource(this, Uri.parse(next.source), java.util.Collections.singletonMap("User-Agent", MediaSources.USER_AGENT));
            });
        }
        emit();
    }

    private interface DataSource { void set(MediaPlayer p) throws IOException; }

    private void prepare(DataSource source) {
        try {
            MediaPlayer candidate = new MediaPlayer();
            player = candidate;
            candidate.setAudioAttributes(ATTRIBUTES);
            if (display != null) setDisplay(display);
            candidate.setOnPreparedListener(this::prepared);
            candidate.setOnCompletionListener(p -> {
                if (p != player) return;
                playing = false;
                ended = true;
                buffering = false;
                queuedPosition = 0;
                resumeOnFocus = false;
                abandonFocus();
                main.removeCallbacks(tick);
                persist(true);
                queueIdle();
                updateSession();
                emit();
            });
            candidate.setOnSeekCompleteListener(p -> {
                if (p == player) { queuedPosition = -1; persist(false); updateSession(); emit(); }
            });
            candidate.setOnVideoSizeChangedListener((p, width, height) -> {
                if (p == player && width > 0 && height > 0) {
                    video = true;
                    leaveForeground();
                    if (visibleOwner == null) pause();
                    emit();
                }
            });
            candidate.setOnInfoListener((p, what, extra) -> {
                if (p != player) return false;
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { rendered = true; emit(); }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    buffering = what == MediaPlayer.MEDIA_INFO_BUFFERING_START;
                    updateSession();
                    emit();
                }
                return false;
            });
            candidate.setOnErrorListener((p, what, extra) -> {
                if (p == player) fail(errorText(extra));
                return true;
            });
            source.set(candidate);
            candidate.prepareAsync();
        } catch (IOException | RuntimeException err) {
            fail(entry.remote() ? "Couldn't open this link. Check the connection and use a direct media URL."
                    : "Android couldn't open this file. Try a supported audio or video format.");
        }
    }

    private void prepared(MediaPlayer p) {
        if (p != player) return;
        preparing = false;
        main.removeCallbacks(prepareDeadline);
        ready = true;
        try {
            video = p.getVideoWidth() > 0;
            for (MediaPlayer.TrackInfo t : p.getTrackInfo()) {
                if (t.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) video = true;
            }
        } catch (IllegalStateException ignored) { }
        long resume = queuedPosition;
        queuedPosition = -1;
        if (resume > 0 && duration() > resume + 600) seek(resume);
        if (visibleOwner != null && startWhenPrepared) play();
        else { updateSession(); emit(); }
    }

    private static String errorText(int extra) {
        if (extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            return "Media couldn't be read. Check the file or link and your connection.";
        }
        if (extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_MALFORMED) {
            return "This format or codec isn't supported on this phone. Try H.264 MP4 or AAC/MP3.";
        }
        return "This media couldn't play. Try a different file or direct HTTPS link.";
    }

    private void fail(String message) {
        persist(false);
        dropPlayer();
        leaveForeground();
        failure = message;
        updateSession();
        emit();
    }

    long duration() {
        try { return ready && player != null ? Math.max(0, player.getDuration()) : 0; }
        catch (IllegalStateException ignored) { return 0; }
    }

    long position() {
        if (!ready || player == null) return Math.max(0, queuedPosition);
        if (ended) return duration();
        if (queuedPosition >= 0) return queuedPosition;
        try { return Math.max(0, player.getCurrentPosition()); }
        catch (IllegalStateException ignored) { return 0; }
    }

    int videoWidth() { return ready && player != null ? player.getVideoWidth() : 0; }
    int videoHeight() { return ready && player != null ? player.getVideoHeight() : 0; }

    void seek(long where) {
        long total = duration();
        if (!ready || player == null || total <= 0) return;
        long to = Math.max(0, Math.min(where, total));
        try {
            if (Build.VERSION.SDK_INT >= 26) player.seekTo(to, MediaPlayer.SEEK_CLOSEST);
            else player.seekTo((int) to);
            ended = false;
            queuedPosition = to;
            updateSession();
            emit();
        } catch (IllegalStateException ignored) { }
    }

    void toggle() { if (playing) pause(); else play(); }

    void play() {
        if (!ready || player == null || playing || (visibleOwner == null && (video || !foreground))) return;
        if (ended) seek(0);
        try {
            int gain = Build.VERSION.SDK_INT >= 26 ? audio.requestAudioFocus(request)
                    : audio.requestAudioFocus(focusCallback, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (gain != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { emit(); return; }
            main.removeCallbacks(idleAudio);
            player.setVolume(1, 1);
            player.start();
            playing = true;
            ended = false;
            resumeOnFocus = false;
            if (!video && !foreground) startAudioForeground();
            persist(false);
            updateSession();
            main.removeCallbacks(tick);
            main.post(tick);
        } catch (IllegalStateException err) { fail("The player couldn't start. Try again."); }
    }

    void pause() { pauseInternal(false); }

    private void pauseInternal(boolean transientFocus) {
        if (!transientFocus) resumeOnFocus = false;
        if (!playing || player == null) return;
        try { player.pause(); } catch (IllegalStateException ignored) { }
        playing = false;
        main.removeCallbacks(tick);
        if (!transientFocus) abandonFocus();
        persist(false);
        queueIdle();
        updateSession();
        emit();
    }

    private void focusChanged(int code) {
        if (player == null) return;
        if (code == AudioManager.AUDIOFOCUS_LOSS) pause();
        else if (code == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocus = playing;
            pauseInternal(true);
        } else if (code == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && playing) {
            player.setVolume(.18f, .18f);
        } else if (code == AudioManager.AUDIOFOCUS_GAIN) {
            player.setVolume(1, 1);
            if (resumeOnFocus) { resumeOnFocus = false; play(); }
        }
    }

    private void abandonFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(request);
        else audio.abandonAudioFocus(focusCallback);
    }

    private void persist(boolean complete) {
        if (entry == null || !ready) return;
        store.played(entry, complete || ended ? 0 : position());
        lastPersist = SystemClock.elapsedRealtime();
    }

    private void startAudioForeground() {
        try {
            session.setActive(true);
            // The visible player initiates this; no boot receiver starts playback.
            startService(new Intent(this, PlaybackService.class));
            startForeground(NOTIFICATION, notification());
            foreground = true;
        } catch (SecurityException | IllegalStateException notAllowed) {
            stopSelf();
            // Audio can still play while the activity is visible.
        }
    }

    private void queueIdle() {
        main.removeCallbacks(idleAudio);
        if (foreground) main.postDelayed(idleAudio, 5 * 60 * 1000L);
    }

    private PendingIntent button(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent playerIntent() {
        return PendingIntent.getActivity(this, 3, new Intent(this, PlayerActivity.class)
                .putExtra(EXTRA_ID, entry.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification.Action play = new Notification.Action.Builder(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                playing ? "Pause" : "Play", button(TOGGLE, 1)).build();
        return builder.setContentTitle(entry.title).setContentText(getString(R.string.app_name))
                .setContentIntent(playerIntent()).setSmallIcon(R.drawable.ic_play).setOnlyAlertOnce(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(playing)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0))
                .addAction(play).addAction(new Notification.Action.Builder(R.drawable.ic_stop, "Stop", button(CLOSE, 2)).build()).build();
    }

    private void updateSession() {
        boolean active = entry != null && ready && !video;
        session.setActive(active);
        if (!active) return;
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, entry.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
        int state = buffering ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
        if (duration() > 0) actions |= PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, position(), playing ? 1 : 0, SystemClock.elapsedRealtime()).build());
        session.setSessionActivity(playerIntent());
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }

    private void leaveForeground() {
        main.removeCallbacks(idleAudio);
        if (foreground) {
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        session.setActive(false);
    }

    private static void closeSource(MediaBytes bytes) {
        if (bytes != null) IoCleanup.run(() -> {
            try { bytes.close(); } catch (IOException ignored) { }
        });
    }

    private static final class ExternalSource extends MediaDataSource {
        private final MediaBytes bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        ExternalSource(MediaBytes bytes) { this.bytes = bytes; }
        @Override public int readAt(long offset, byte[] buffer, int start, int size) throws IOException {
            if (closed.get()) throw new IOException("Playback source closed.");
            return bytes.read(offset, buffer, start, size);
        }
        @Override public long getSize() { return bytes.size(); }
        // Android may also invoke this from release(). TLS close can write to the socket.
        @Override public void close() { if (closed.compareAndSet(false, true)) closeSource(bytes); }
    }

    private void dropPlayer() {
        generation++;
        main.removeCallbacks(prepareDeadline);
        if (externalSource != null) { externalSource.close(); externalSource = null; }
        main.removeCallbacks(tick);
        if (player != null) { player.release(); player = null; }
        abandonFocus();
        playing = ready = preparing = buffering = ended = resumeOnFocus = rendered = false;
        queuedPosition = -1;
    }

    void close() {
        persist(false);
        dropPlayer();
        leaveForeground();
        entry = null;
        failure = null;
        emit();
    }

    @Override public void onDestroy() {
        destroyed = true;
        close();
        session.release();
        unregisterReceiver(headphones);
        documents.shutdownNow();
        listeners.clear();
        super.onDestroy();
    }
}
