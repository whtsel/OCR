package com.blackboxstream.mobile;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Service-owned main-thread player. Its only input is a file already in the collection. */
final class OfflineMusicPlayer {
    private final Context app;
    private final OfflineMusicLibrary library;
    private final AudioManager audio;
    private final Runnable stateChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private final AudioManager.OnAudioFocusChangeListener listener = change -> {
        if (change < 0) main.post(this::pause);
    };
    private final AudioFocusRequest focus;
    private boolean hasFocus, ready, closed, playWhenReady;
    private final List<String> playlist = new ArrayList<>();
    private int index;
    private MediaPlayer media;
    private String key = "", problem = "";
    private volatile String snapshot = "{}";
    private final Runnable tick = () -> {
        if (V90Api.expired()) stop(); else updateSnapshot();
    };
    private final Runnable prepareDeadline = () -> {
        if (media != null && !ready) error("This saved file took too long to open.");
    };

    OfflineMusicPlayer(Context context, OfflineMusicLibrary library, Runnable stateChanged) {
        app = context.getApplicationContext();
        this.library = library;
        this.stateChanged = stateChanged;
        audio = app.getSystemService(AudioManager.class);
        focus = Build.VERSION.SDK_INT >= 26 ? new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener, main)
                .setWillPauseWhenDucked(true).build() : null;
        updateSnapshot();
    }

    String snapshot() { return snapshot; }
    String key() { return key; }
    boolean opening() { return media != null && !ready; }
    boolean preparing() { return opening() && playWhenReady; }
    boolean next() { return index + 1 < playlist.size(); }
    boolean previous() { return index > 0; }
    boolean playing() {
        try { return ready && media.isPlaying(); }
        catch (IllegalStateException interrupted) { return false; }
    }
    int position() {
        try { return ready ? media.getCurrentPosition() : 0; }
        catch (IllegalStateException interrupted) { return 0; }
    }
    int duration() {
        try { return ready ? media.getDuration() : 0; }
        catch (IllegalStateException interrupted) { return 0; }
    }

    void command(String action, String value) {
        if (closed) return;
        switch (action) {
            case "play":
                playlist.clear();
                playlist.add(value);
                load(0);
                break;
            case "playlist":
                try {
                    JSONArray keys = new JSONArray(value);
                    if (keys.length() < 1 || keys.length() > OfflineMusicLibrary.MAX_RECORDS) return;
                    List<String> selection = new ArrayList<>();
                    for (int i = 0; i < keys.length(); i++) {
                        String item = keys.getString(i);
                        if (library.saved(item) != null && !selection.contains(item)) selection.add(item);
                    }
                    if (!selection.isEmpty()) {
                        playlist.clear(); playlist.addAll(selection); load(0);
                    }
                } catch (JSONException ignored) { }
                break;
            case "stop": stop(); break;
            case "pause": pause(); break;
            case "resume": resume(); break;
            case "toggle": if (playing() || preparing()) pause(); else resume(); break;
            case "previous": if (previous()) load(index - 1); break;
            case "next": if (next()) load(index + 1); break;
            case "seek":
                if (!ready) return;
                try {
                    int where = (int) Math.max(0, Math.min(Long.parseLong(value), duration() - 1));
                    if (Build.VERSION.SDK_INT >= 26) media.seekTo(where, MediaPlayer.SEEK_CLOSEST);
                    else media.seekTo(where);
                } catch (IllegalArgumentException | IllegalStateException ignored) { }
                break;
        }
    }

    private void load(int at) {
        if (V90Api.expired()) { stop(); return; }
        releaseMedia();
        index = at;
        key = playlist.get(at);
        problem = "";
        File file = library.saved(key);
        if (file == null) { error("This file is no longer saved on this device."); return; }
        MediaPlayer next = new MediaPlayer();
        media = next;
        playWhenReady = true;
        try (FileInputStream source = new FileInputStream(file)) {
            next.setAudioAttributes(attributes);
            // Android holds this only while the decoder is active, not while paused or stopped.
            next.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
            next.setDataSource(source.getFD());
            next.setOnPreparedListener(prepared -> {
                if (media != prepared) return;
                main.removeCallbacks(prepareDeadline);
                ready = true;
                if (playWhenReady) start(); else publish();
            });
            next.setOnSeekCompleteListener(seeked -> { if (media == seeked) publish(); });
            next.setOnCompletionListener(finished -> {
                if (media != finished) return;
                if (playWhenReady && next()) load(index + 1);
                else { playWhenReady = false; abandonFocus(); publish(); }
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (media == failed) error("This saved file could not be played.");
                return true;
            });
            next.prepareAsync();
            main.postDelayed(prepareDeadline, 20_000);
        } catch (IOException | RuntimeException unreadable) {
            error("This saved file could not be opened.");
        }
        publish();
    }

    private void resume() {
        if (playing()) return;
        if (ready) start();
        else if (opening()) { playWhenReady = true; publish(); }
        else if (!key.isEmpty()) load(index);
    }

    @SuppressWarnings("deprecation")
    private void start() {
        if (!ready || closed) return;
        if (V90Api.expired()) { stop(); return; }
        if (audio != null && !hasFocus) {
            hasFocus = (Build.VERSION.SDK_INT >= 26 ? audio.requestAudioFocus(focus)
                    : audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN))
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) { playWhenReady = false; problem = "Audio is in use. Try Play again."; publish(); return; }
        }
        problem = "";
        playWhenReady = true;
        try { media.start(); publish(); }
        catch (IllegalStateException unavailable) { error("This saved file could not be played."); }
    }

    private void pause() {
        if (closed) return;
        playWhenReady = false;
        try { if (playing()) media.pause(); }
        catch (IllegalStateException interrupted) { }
        abandonFocus();
        publish();
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (!hasFocus || audio == null) return;
        hasFocus = false;
        if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(focus);
        else audio.abandonAudioFocus(listener);
    }

    private void releaseMedia() {
        main.removeCallbacks(prepareDeadline);
        if (media != null) media.release();
        media = null;
        ready = playWhenReady = false;
        abandonFocus();
    }

    private void stop() {
        releaseMedia(); playlist.clear(); index = 0; key = ""; problem = ""; publish();
    }

    private void error(String message) {
        releaseMedia();
        problem = message;
        publish();
    }

    private void publish() {
        updateSnapshot();
        if (!closed) stateChanged.run(); // System controls update on transitions, not every UI tick.
    }

    private void updateSnapshot() {
        main.removeCallbacks(tick);
        if (closed) return;
        boolean playing = playing();
        try {
            snapshot = new JSONObject().put("key", key).put("playing", playing).put("ready", ready)
                    .put("previous", previous()).put("next", next()).put("position", position())
                    .put("duration", duration()).put("problem", problem).toString();
        } catch (JSONException impossible) { snapshot = "{}"; }
        if (playing) main.postDelayed(tick, 300);
    }

    void close() { closed = true; main.removeCallbacks(tick); releaseMedia(); }
}
