package com.blackboxstream.mobile;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Main-thread player. Its only input is a descriptor for a file already in the collection. */
final class OfflineMusicPlayer {
    private final OfflineMusicLibrary library;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private final AudioManager.OnAudioFocusChangeListener listener = change -> {
        if (change < 0) main.post(this::pause);
    };
    private final AudioFocusRequest focus;
    private boolean hasFocus, ready, foreground, closed;
    private final List<String> playlist = new ArrayList<>();
    private int index;
    private MediaPlayer media;
    private String key = "", problem = "";
    private volatile String snapshot = "{}";
    private final Runnable tick = this::publish;

    OfflineMusicPlayer(Context context, OfflineMusicLibrary library) {
        this.library = library;
        audio = context.getSystemService(AudioManager.class);
        focus = Build.VERSION.SDK_INT >= 26 ? new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener, main)
                .setWillPauseWhenDucked(true).build() : null;
        publish();
    }

    String snapshot() { return snapshot; }

    void command(String action, String value) {
        if (closed || !foreground) return;
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
                    playlist.clear();
                    for (int i = 0; i < keys.length(); i++) {
                        String item = keys.getString(i);
                        if (library.saved(item) != null && !playlist.contains(item)) playlist.add(item);
                    }
                    if (!playlist.isEmpty()) load(0);
                } catch (JSONException ignored) {}
                break;
            case "stop":
                releaseMedia(); playlist.clear(); index = 0; key = ""; problem = ""; publish();
                break;
            case "toggle":
                if (ready && media != null && media.isPlaying()) pause();
                else if (ready) start();
                else if (!key.isEmpty() && media == null) load(index);
                break;
            case "previous": if (index > 0) load(index - 1); break;
            case "next": if (index + 1 < playlist.size()) load(index + 1); break;
            case "seek":
                if (!ready) return;
                try {
                    int where = Math.max(0, Math.min(Integer.parseInt(value), media.getDuration() - 1));
                    if (Build.VERSION.SDK_INT >= 26) media.seekTo(where, MediaPlayer.SEEK_CLOSEST);
                    else media.seekTo(where);
                } catch (NumberFormatException ignored) {}
                break;
        }
    }

    private void load(int at) {
        releaseMedia();
        index = at;
        key = playlist.get(at);
        problem = "";
        File file = library.saved(key);
        if (file == null) { error("This file is no longer saved on this device."); return; }
        MediaPlayer next = new MediaPlayer();
        media = next;
        try (FileInputStream source = new FileInputStream(file)) {
            next.setAudioAttributes(attributes);
            next.setDataSource(source.getFD());
            next.setOnPreparedListener(prepared -> {
                if (media != prepared) return;
                ready = true;
                if (foreground) start(); else publish();
            });
            next.setOnSeekCompleteListener(seeked -> { if (media == seeked) publish(); });
            next.setOnCompletionListener(finished -> {
                if (media != finished) return;
                if (foreground && index + 1 < playlist.size()) load(index + 1);
                else { abandonFocus(); publish(); }
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (media == failed) error("This saved file could not be played.");
                return true;
            });
            next.prepareAsync();
        } catch (IOException | RuntimeException unreadable) {
            error("This saved file could not be opened.");
        }
        publish();
    }

    @SuppressWarnings("deprecation")
    private void start() {
        if (!ready || !foreground) return;
        if (audio != null && !hasFocus) {
            hasFocus = (Build.VERSION.SDK_INT >= 26 ? audio.requestAudioFocus(focus)
                    : audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN))
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) { problem = "Audio is in use. Try Play again."; publish(); return; }
        }
        problem = "";
        try { media.start(); publish(); }
        catch (IllegalStateException unavailable) { error("This saved file could not be played."); }
    }

    private void pause() {
        if (ready && media.isPlaying()) media.pause();
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
        if (media != null) media.release();
        media = null;
        ready = false;
        abandonFocus();
    }

    private void error(String message) {
        releaseMedia();
        problem = message;
        publish();
    }

    private void publish() {
        main.removeCallbacks(tick);
        if (closed) return;
        boolean playing = ready && media.isPlaying();
        try {
            snapshot = new JSONObject().put("key", key).put("playing", playing).put("ready", ready)
                    .put("previous", index > 0).put("next", index + 1 < playlist.size())
                    .put("position", ready ? media.getCurrentPosition() : 0)
                    .put("duration", ready ? media.getDuration() : 0).put("problem", problem).toString();
        } catch (JSONException impossible) { snapshot = "{}"; }
        if (playing) main.postDelayed(tick, 300);
    }

    void foreground(boolean present) {
        foreground = present;
        if (!present) pause();
    }

    void close() { closed = true; main.removeCallbacks(tick); releaseMedia(); }
}
