package com.blackboxstream.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, app-private preparations for the current exact recording and two queue positions. */
final class PreparedMusic implements AutoCloseable {
    static final String VERSION = "2026.10.26";
    static final long MAX_AUDIO = 24L * 1024 * 1024;
    static final int PREPARE_TIMEOUT_SECONDS = 155;
    private static final String OPT_IN = "exact_music_helper";
    private static final Track[] VERIFIED = {
        new Track("51zjlMhdSTE", "Espresso", "Sabrina Carpenter", 175),
        new Track("ZD6rXLXZOEI", "bad guy", "Billie Eilish", 194),
        new Track("fHI8X4OXluQ", "Blinding Lights", "The Weeknd", 200),
        new Track("UFsskuvj8GQ", "On the Low", "Burna Boy", 185),
        new Track("Hb6I7GoTvzI", "Kwaku the Traveller", "Black Sherif", 185),
    };

    static final class Track {
        final String id, title, artist;
        final int seconds;
        Track(String id, String title, String artist, int seconds) {
            if (!id.matches("[A-Za-z0-9_-]{11}") || seconds < 1 || seconds > 1800) throw new IllegalArgumentException("Invalid recording");
            this.id = id; this.title = title; this.artist = artist; this.seconds = seconds;
        }
    }

    interface Runtime {
        File fetch(Track track, File scratch, Control control) throws Exception;
    }
    interface Network { boolean allowed(boolean background); }

    static final class Control {
        volatile boolean cancelled;
        private Process process;
        synchronized void attach(Process p) {
            if (cancelled) p.destroy();
            else process = p;
        }
        synchronized void detach() { process = null; }
        synchronized void cancel() {
            cancelled = true;
            if (process != null) process.destroy();
        }
    }

    private final File directory;
    private final SharedPreferences preferences;
    private final Runtime runtime;
    private final Network network;
    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> desired = new ArrayList<>(3);
    private boolean dispatching, closed;
    private int epoch;
    private String requested = "", running;
    private Control active;

    static PreparedMusic builtIn(Context app) {
        Runtime runtime = null;
        try {
            Class<?> type = Class.forName("com.blackboxstream.mobile.BundledMusicRuntime");
            runtime = (Runtime) type.getDeclaredConstructor(Context.class).newInstance(app);
        } catch (ReflectiveOperationException | LinkageError absent) { /* Non-helper APK. */ }
        ConnectivityManager manager = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = background -> manager != null && manager.getActiveNetwork() != null
                && (!background || !manager.isActiveNetworkMetered());
        return new PreparedMusic(new File(app.getNoBackupFilesDir(), "prepared-music-" + VERSION),
                app.getSharedPreferences("music-preparation", Context.MODE_PRIVATE), runtime, network, VERIFIED);
    }

    PreparedMusic(File directory, SharedPreferences preferences, Runtime runtime, Network network, Track[] permitted) {
        this.directory = directory;
        this.preferences = preferences;
        this.runtime = runtime;
        this.network = network;
        for (Track track : permitted) tracks.put(track.id, track);
    }

    synchronized boolean enabled() { return !closed && runtime != null && preferences.getBoolean(OPT_IN, false); }

    synchronized void options(boolean enabled, boolean clear) {
        if (closed || runtime == null) return;
        preferences.edit().putBoolean(OPT_IN, enabled && !clear).apply();
        if (!enabled || clear) clearPlan();
        if (clear) for (Track track : tracks.values()) file(track.id).delete();
    }

    synchronized void plan(int sequence, String selected, JSONArray upcoming, boolean retry) throws JSONException {
        if (!enabled() || sequence <= epoch) return;
        if (!selected.isEmpty() && !tracks.containsKey(selected)) throw new IllegalArgumentException("No verified source for this track.");
        if (upcoming == null || upcoming.length() > 2) throw new IllegalArgumentException("Only the next two queue entries may be prepared.");
        List<String> next = new ArrayList<>(3);
        if (!selected.isEmpty()) {
            next.add(selected);
            for (int i = 0; i < upcoming.length(); i++) {
                String id = upcoming.getString(i);
                if (!id.isEmpty() && !tracks.containsKey(id)) throw new IllegalArgumentException("No verified source for a queued track.");
                if (!id.isEmpty() && !next.contains(id)) next.add(id);
            }
        }
        epoch = sequence;
        if (retry && !selected.isEmpty()) failures.remove(selected);
        // Removed targets cannot keep a queued failure alive into a different listening session.
        failures.keySet().retainAll(next);
        if (active != null && (!next.contains(running) || !requested.equals(selected)
                && !selected.equals(running) && readyFile(selected) == null)) active.cancel();
        requested = selected;
        desired.clear(); desired.addAll(next);
        dispatch();
    }

    synchronized JSONObject state() throws JSONException {
        dispatch(); // Resume still-relevant queued work when the connection becomes eligible.
        JSONObject rows = new JSONObject();
        boolean on = enabled();
        if (runtime != null) for (Track track : tracks.values()) {
            String id = track.id;
            boolean prepared = on && readyFile(id) != null;
            String state = prepared ? "ready" : on && id.equals(running) && !active.cancelled ? "preparing"
                    : on && failures.containsKey(id) ? "failed" : on && desired.contains(id) ? "queued" : "idle";
            rows.put(id, new JSONObject().put("state", state).put("title", track.title).put("artist", track.artist)
                    .put("seconds", track.seconds).put("message", "failed".equals(state) ? failures.get(id) : ""));
        }
        return new JSONObject().put("available", runtime != null).put("enabled", on)
                .put("version", VERSION).put("unmetered", on && network.allowed(true)).put("tracks", rows);
    }

    synchronized File read(String id) { return enabled() ? readyFile(id) : null; }
    private File file(String id) { return new File(directory, id + ".m4a"); }
    private File readyFile(String id) {
        if (!tracks.containsKey(id)) return null;
        File file = file(id);
        return file.isFile() && file.length() > 0 && file.length() <= MAX_AUDIO ? file : null;
    }

    private String nextJob() {
        if (!enabled()) return null;
        // Do not queue network fallback while offline, or fetch upcoming tracks on metered data.
        for (String id : desired) {
            if (readyFile(id) != null || failures.containsKey(id)) continue;
            boolean background = !id.equals(requested);
            if (!network.allowed(background)) {
                if (!background) failures.put(id, "Not prepared yet. Connect to the internet to prepare this recording.");
                continue;
            }
            return id;
        }
        return null;
    }

    private void dispatch() {
        if (!dispatching && nextJob() != null) {
            dispatching = true;
            worker.execute(this::drain);
        }
    }

    private void drain() {
        for (;;) {
            final String id;
            final Control control;
            synchronized (this) {
                id = nextJob();
                if (id == null) { dispatching = false; return; }
                running = id;
                active = control = new Control();
            }
            File scratch = null;
            String failure = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to open prepared music storage.");
                if (directory.getUsableSpace() < 3 * MAX_AUDIO) throw new IOException("Not enough space to prepare this recording.");
                // Staging is never opened by the player. Keep incomplete files out of the cache.
                scratch = new File(directory, "work-" + java.util.UUID.randomUUID());
                if (!scratch.mkdir()) throw new IOException("Unable to stage the recording.");
                File out = runtime.fetch(tracks.get(id), scratch, control);
                if (control.cancelled) continue;
                verifyAudio(out, tracks.get(id).seconds);
                synchronized (this) {
                    if (!control.cancelled && enabled() && desired.contains(id) && !out.renameTo(file(id)))
                        throw new IOException("Unable to store the prepared recording.");
                }
            } catch (Exception | LinkageError error) {
                // Only our own safe I/O reasons are exposed, never commands, URL tokens, or raw provider output.
                failure = error instanceof IOException ? safeReason(error.getMessage()) : "The bundled music helper could not start.";
            } finally {
                deleteScratch(scratch);
                synchronized (this) {
                    if (failure != null && !control.cancelled && desired.contains(id)) failures.put(id, failure);
                    running = null;
                    active = null;
                }
            }
        }
    }

    static void verifyAudio(File file, int seconds) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_AUDIO)
            throw new IOException("No complete audio file was prepared.");
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            if (!"yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    || "yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))
                throw new IOException("The helper did not return an audio-only file.");
            String time = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (time == null || Math.abs(Long.parseLong(time) - seconds * 1000L) > 6000)
                throw new IOException("The prepared file does not match the full recording length.");
        } catch (RuntimeException malformed) { throw new IOException("The phone could not read the prepared audio."); }
        finally { reader.release(); }
    }

    static void deleteScratch(File file) {
        if (file == null) return;
        File[] files = file.isDirectory() ? file.listFiles() : null;
        if (files != null) for (File child : files) deleteScratch(child);
        file.delete();
    }

    private static String safeReason(String text) {
        if (text != null && text.matches("[A-Za-z0-9 .,'-]{1,140}")) return text;
        return "Could not prepare this exact recording.";
    }

    private void clearPlan() {
        desired.clear(); failures.clear(); requested = "";
        if (active != null) active.cancel();
    }
    @Override public synchronized void close() {
        closed = true;
        clearPlan();
        worker.shutdownNow();
    }
}
