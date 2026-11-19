package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;

public final class PlayerActivity extends Activity implements PlaybackService.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LibraryStore store;
    private String mediaId;
    private MediaEntry entry;
    private PlaybackService engine;
    private boolean bound, dragging, landscape, resumeOnShow = true, waitingTorrent;
    private FrameLayout stage;
    private SurfaceView video;
    private SurfaceHolder surface;
    private View art;
    private TextView title, meta, stageLabel, hint, left, right, play, back, next, save, offline;
    private SeekBar seek;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (entry != null) {
                if (entry.transfer >= 0) Downloads.failIfMissing(PlayerActivity.this, entry);
                updateLibrary();
                if (waitingTorrent && engine != null) tryOpen();
            }
            main.postDelayed(this, 1300);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            engine = ((PlaybackService.LocalBinder) service).get();
            engine.show(PlayerActivity.this);
            engine.observe(PlayerActivity.this);
            if (surface != null) engine.display(PlayerActivity.this, surface);
            if (entry != null) tryOpen();
            playerChanged();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            engine = null;
            playerChanged();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = LibraryStore.get(this);
        mediaId = getIntent().getStringExtra(PlaybackService.EXTRA_ID);
        entry = store.get(mediaId);
        if (entry == null) { finish(); return; }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if (state != null) resumeOnShow = state.getBoolean("resumeOnShow", true);
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        build();
        updateLibrary();
        playerChanged();
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String id = intent.getStringExtra(PlaybackService.EXTRA_ID);
        if (!java.util.Objects.equals(mediaId, id)) recreate();
    }

    @Override protected void onStart() {
        super.onStart();
        if (entry == null) return;
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        main.post(poll);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("resumeOnShow", engine != null ? engine.playing() || engine.preparing() : resumeOnShow);
    }

    @Override protected void onStop() {
        main.removeCallbacks(poll);
        if (engine != null) {
            engine.unobserve(this);
            resumeOnShow = engine.playing() || engine.preparing();
            engine.hide(this);
            engine = null;
        }
        if (bound) { unbindService(connection); bound = false; }
        super.onStop();
    }

    private void build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        Ui.fitWindow(this, root);
        setContentView(root);
        LinearLayout top = Ui.row(this);
        Ui.pad(top, 16, 5);
        top.addView(Ui.action(this, "‹  Back", false, v -> finish()));
        Ui.space(top, 12);
        TextView on = Ui.kicker(this, "NOW PLAYING");
        top.addView(on);
        Ui.flex(top);
        TextView mode = Ui.action(this, landscape ? "Portrait" : "Landscape", false, v -> setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE));
        top.addView(mode);
        root.addView(top);

        LinearLayout content = Ui.column(this);
        Ui.pad(content, landscape ? 16 : 20, landscape ? 0 : 12);
        if (landscape) root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.addView(content);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        stage = new FrameLayout(this);
        Ui.card(stage, Color.BLACK, 16, Ui.BORDER);
        stage.setClipToOutline(true);
        content.addView(stage, landscape ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, Ui.dp(this, 228)));
        Cinema.Film film = Cinema.fromTorrent(entry.source);
        art = film == null ? new Ui.OrbitArt(this, entry.music()) : CinemaViews.art(this, film);
        stage.addView(art, new FrameLayout.LayoutParams(-1, -1));
        video = new SurfaceView(this);
        video.setVisibility(View.INVISIBLE);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stage.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        stageLabel = Ui.kicker(this, "OPENING MEDIA");
        Ui.pad(stageLabel, 10, 7);
        Ui.card(stageLabel, 0xdd0c1015, 8, Ui.BORDER);
        FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        label.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        stage.addView(stageLabel, label);
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) sizeVideo();
        });
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surface = holder;
                if (engine != null) engine.display(PlayerActivity.this, holder);
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
                if (engine != null) engine.display(PlayerActivity.this, null);
            }
        });

        if (!landscape) {
            Ui.gap(content, 23);
            title = Ui.text(this, entry.title, 32, Ui.INK, Ui.DISPLAY);
            content.addView(title);
            OpenAnime archive = OpenAnime.fromSource(entry.source);
            String credit = archive != null ? archive.credits : entry.source.startsWith(PlayableMusic.AUDIO) ? PlayableMusic.CREDIT : null;
            if (credit != null) content.addView(Ui.text(this, credit, 12, Ui.MUTED, null));
            Ui.gap(content, 7);
            meta = Ui.kicker(this, ""); content.addView(meta);
            Ui.gap(content, 22);
        }
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setContentDescription("Playback position");
        seek.setProgressTintList(ColorStateList.valueOf(Ui.MINT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.MINT));
        if (landscape) seek.setMinimumHeight(Ui.dp(this, 30));
        content.addView(seek, new LinearLayout.LayoutParams(-1, Ui.dp(this, landscape ? 36 : 46)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int position, boolean fromUser) {
                if (!fromUser || engine == null) return;
                long point = engine.duration() * position / s.getMax();
                left.setText(Ui.clock(point));
                if (!dragging) engine.seek(point); // keyboard / accessibility action
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (engine != null) engine.seek(engine.duration() * s.getProgress() / s.getMax());
            }
        });
        LinearLayout times = Ui.row(this);
        left = Ui.text(this, "0:00", 12, Ui.MUTED, Ui.MONO);
        right = Ui.text(this, "—:—", 12, Ui.MUTED, Ui.MONO);
        times.addView(left); Ui.flex(times); times.addView(right);
        content.addView(times);
        Ui.gap(content, landscape ? 4 : 12);
        LinearLayout controls = Ui.row(this);
        back = Ui.action(this, "−10 s", false, v -> { if (engine != null) engine.seek(engine.position() - 10000); });
        back.setContentDescription("Back ten seconds");
        next = Ui.action(this, "+10 s", false, v -> { if (engine != null) engine.seek(engine.position() + 10000); });
        next.setContentDescription("Forward ten seconds");
        play = Ui.action(this, "Play", true, v -> {
            if (engine == null) return;
            if (entry.torrent() && !store.isOffline(entry) && entry.transfer < 0 && !engine.ready()) {
                download(entry); tryOpen();
            } else if (engine.failure() != null || engine.entry() == null) engine.open(entry, true, true);
            else engine.toggle();
        });
        controls.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        Ui.space(controls, 8); controls.addView(play, new LinearLayout.LayoutParams(0, -2, 1.65f));
        Ui.space(controls, 8); controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(controls);
        Ui.gap(content, 8);
        hint = Ui.text(this, "", 13, Ui.MUTED, null);
        content.addView(hint);
        if (!landscape) {
            Ui.gap(content, 24);
            LinearLayout actions = Ui.row(this);
            save = Ui.action(this, "Save to Library", false, v -> {
                MediaEntry item = store.get(mediaId);
                if (item == null) return;
                if (item.saved && (store.isOffline(item) || item.transfer >= 0)) {
                    toast("Remove its offline copy or cancel the download first."); return;
                }
                store.save(item, !item.saved);
                updateLibrary();
            });
            offline = Ui.action(this, "Keep offline", false, v -> offlineActions());
            actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
            Ui.space(actions, 8); actions.addView(offline, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(actions);
            if (film != null) {
                Ui.gap(content, 9);
                content.addView(Ui.action(this, "Other sources for " + film.title, false, v -> FilmActivity.show(this, film)));
            }
            Ui.gap(content, 8);
        }
    }

    private void tryOpen() {
        if (entry.torrent() && entry.transfer >= 0 && !store.isOffline(entry) && !TorrentJobs.streaming(entry)) {
            waitingTorrent = true;
            if (engine.entry() != null && !mediaId.equals(engine.entry().id)) engine.close();
            return;
        }
        waitingTorrent = false;
        engine.open(entry, engine.failure() != null, resumeOnShow);
        playerChanged();
    }

    private void sizeVideo() {
        if (engine == null || !engine.video()) return;
        int w = stage.getWidth(), h = stage.getHeight();
        int vw = engine.videoWidth(), vh = engine.videoHeight();
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return;
        if ((long) w * vh > (long) h * vw) w = Math.max(1, h * vw / vh);
        else h = Math.max(1, w * vh / vw);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (p.width != w || p.height != h) { p.width = w; p.height = h; video.setLayoutParams(p); }
    }

    @Override public void playerChanged() {
        if (stage == null) return;
        boolean loaded = engine != null && engine.entry() != null && mediaId.equals(engine.entry().id);
        boolean ready = loaded && engine.ready();
        boolean playing = ready && engine.playing();
        boolean picture = ready && engine.video();
        boolean busy = waitingTorrent || !loaded && engine == null || (loaded && engine.preparing());
        String error = loaded ? engine.failure() : null;
        if (error != null && entry != null && entry.torrent() && entry.transferError != null) error = entry.transferError;
        long duration = ready ? engine.duration() : 0;
        long position = ready ? engine.position() : 0;
        video.setVisibility(picture ? View.VISIBLE : View.INVISIBLE);
        art.setVisibility(picture ? View.INVISIBLE : View.VISIBLE);
        stage.setKeepScreenOn(picture && playing);
        if (picture) sizeVideo();
        if (!dragging) {
            left.setText(Ui.clock(position));
            seek.setProgress(duration == 0 ? 0 : (int) Math.min(1000, position * 1000 / duration));
        }
        right.setText(duration > 0 ? Ui.clock(duration) : ready ? "NO SEEK" : "—:—");
        setEnabled(seek, ready && duration > 0);
        setEnabled(back, ready && duration > 0);
        setEnabled(next, ready && duration > 0);
        setEnabled(play, !busy);
        play.setText(error != null ? "Retry" : busy ? "Opening…" : playing ? "Pause" : ready && engine.ended() ? "Replay" : "Play");
        stageLabel.setVisibility(picture && !engine.buffering() ? View.GONE : View.VISIBLE);
        stageLabel.setText(error != null ? "CAN'T PLAY" : busy ? "OPENING MEDIA" : ready && engine.buffering() ? "BUFFERING"
                : ready && !picture ? "AUDIO" : "PLAYER");
        String text = error != null ? error : waitingTorrent ? "Waiting for verified torrent pieces…" : busy ? "Opening in the Android player…" : ready && engine.buffering() ? "Buffering…"
                : ready && duration <= 0 ? "This stream has no seekable timeline." : ready && !picture
                ? engine.inBackgroundSession() ? "Audio can continue while you use other apps."
                : "Audio will pause when you leave the player." : "";
        hint.setText(text);
        hint.setTextColor(error == null ? Ui.MUTED : Ui.RED);
        hint.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static void setEnabled(View view, boolean enabled) { view.setEnabled(enabled); view.setAlpha(enabled ? 1f : .48f); }

    private void updateLibrary() {
        MediaEntry fresh = store.get(mediaId);
        if (fresh == null) return;
        entry = fresh;
        if (title == null) return;
        title.setText(entry.title);
        boolean downloaded = store.isOffline(entry);
        meta.setText(entry.kind.badge + "  /  " + (entry.bundled() ? "ON-DEVICE SAMPLE" : downloaded ? "OFFLINE COPY"
                : MediaSources.hls(entry.source) ? "HLS STREAM" : entry.sourceLabel()));
        save.setText(entry.saved ? "✓ In Library" : "Save to Library");
        if (entry.bundled()) { offline.setText("Built in"); setEnabled(offline, false); }
        else if (downloaded) { offline.setText("✓ Offline copy"); setEnabled(offline, true); }
        else if (entry.transfer >= 0) {
            long size = Downloads.received(store, entry);
            offline.setText(entry.totalBytes > 0 ? "Downloading " + Math.min(99, size * 100 / entry.totalBytes) + "%"
                    : size > 0 ? Ui.bytes(size) + " saved…" : "Download queued");
            setEnabled(offline, true);
        } else {
            boolean allowed = Downloads.canKeep(entry);
            offline.setText(allowed ? entry.transferError == null ? "Keep offline" : "Download failed" : "Stream only");
            setEnabled(offline, allowed);
        }
    }

    private void offlineActions() {
        MediaEntry item = store.get(mediaId);
        if (item == null) return;
        if (store.isOffline(item)) {
            new AlertDialog.Builder(this).setTitle("Offline copy").setItems(new CharSequence[] {"Share copy", "Remove offline copy"}, (dialog, which) -> {
                if (which == 0) startActivity(OfflineMediaProvider.share(this, item));
                else new AlertDialog.Builder(this).setTitle("Remove offline copy?").setMessage("The Library entry stays saved.")
                        .setNegativeButton("Keep", null).setPositiveButton("Remove", (d, w) -> {
                            try { Downloads.deleteCopy(this, item); } catch (IOException err) { toast(err.getMessage()); }
                            updateLibrary();
                        }).show();
            }).show();
        } else if (item.transfer >= 0) {
            new AlertDialog.Builder(this).setTitle("Cancel download?").setMessage("This removes the unfinished copy only.")
                    .setNegativeButton("Keep downloading", null).setPositiveButton("Cancel download", (d, w) -> {
                        Downloads.cancel(this, item); updateLibrary();
                    }).show();
        } else if (item.transferError != null) {
            new AlertDialog.Builder(this).setTitle("Download failed").setMessage(item.transferError)
                    .setNegativeButton("Dismiss", null).setPositiveButton("Retry", (d, w) -> download(item)).show();
        } else download(item);
    }

    private void download(MediaEntry item) {
        try { Downloads.enqueue(this, item); toast("Queued in Offline"); }
        catch (IOException | IllegalStateException | IllegalArgumentException error) { toast(error.getMessage()); }
        updateLibrary();
    }

    private void toast(String message) { if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
