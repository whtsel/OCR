package com.blackboxstream.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One private, bounded worker for a selected catalogue recording and its next two queue slots. */
final class PreparedMusic implements AutoCloseable {
    static final String VERSION = "2026.10.26";
    static final long MAX_AUDIO = 24L * 1024 * 1024;
    static final int PREPARE_TIMEOUT_SECONDS = 155;
    static final int MAX_SECONDS = 1800;
    private static final long CACHE_BYTES = 256L * 1024 * 1024;
    private static final int CACHE_TRACKS = 64;
    private static final String OPT_IN = "exact_music_helper";
    private static final String CACHE = "prepared-music-" + VERSION;

    static final class Track {
        final String key, ref, title, artist, album, source;
        final int seconds;

        Track(JSONObject fields) throws JSONException {
            ref = field(fields, "ref", 640, true);
            title = field(fields, "title", 240, true);
            artist = field(fields, "artist", 240, true);
            album = field(fields, "album", 240, false);
            source = field(fields, "source", 11, false);
            seconds = fields.getInt("seconds");
            if (!source.isEmpty() && !videoId(source) || seconds < 0 || seconds > MAX_SECONDS
                    || source.isEmpty() && (album.isEmpty() || seconds == 0))
                throw new IllegalArgumentException("Link the exact full recording first.");
            // Full recording identity, not a title-only match. Explicitly changing a link changes its cache key.
            try {
                String spec = ref + "\u001f" + title + "\u001f" + artist + "\u001f" + album + "\u001f" + seconds + "\u001f" + source;
                key = TorrentMeta.hex(MessageDigest.getInstance("SHA-256").digest(spec.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("ref", ref).put("title", title).put("artist", artist)
                    .put("album", album).put("seconds", seconds).put("source", source);
        }

        private static String field(JSONObject fields, String name, int max, boolean required) throws JSONException {
            String s = fields.getString(name).trim();
            if (s.length() > max || required && s.isEmpty() || s.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IllegalArgumentException("Invalid recording details.");
            return s;
        }
    }

    static boolean videoId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{11}"); }

    static final class Result {
        final File audio;
        final String source;
        Result(File audio, String source) { this.audio = audio; this.source = source; }
    }

    interface Runtime { Result fetch(Track track, File scratch, Control control) throws Exception; }
    interface Network { boolean allowed(boolean background); }

    static final class Control {
        volatile boolean cancelled;
        volatile String phase;
        private Process process;
        Control() { this("preparing"); }
        Control(String phase) { this.phase = phase; }
        synchronized void attach(Process p) { if (cancelled) p.destroy(); else process = p; }
        synchronized void detach() { process = null; }
        synchronized void cancel() { cancelled = true; if (process != null) process.destroy(); }
    }

    private final File directory;
    private final SharedPreferences preferences;
    private final Runtime runtime;
    private final Network network;
    private final Map<String, Track> desired = new LinkedHashMap<>(), cached = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean dispatching, closed, loaded;
    private int epoch;
    private String requested = "", running;
    private Control active;

    static PreparedMusic builtIn(Context app) {
        Runtime runtime = null;
        try {
            Class<?> type = Class.forName("com.blackboxstream.mobile.BundledMusicRuntime");
            runtime = (Runtime) type.getDeclaredConstructor(Context.class).newInstance(app);
        } catch (ReflectiveOperationException | LinkageError absent) { /* The dependency-free baseline build. */ }
        ConnectivityManager manager = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = background -> manager != null && manager.getActiveNetwork() != null
                && (!background || !manager.isActiveNetworkMetered());
        return new PreparedMusic(cacheDirectory(app), app.getSharedPreferences("music-preparation", Context.MODE_PRIVATE), runtime, network);
    }

    static File cacheDirectory(Context app) { return new File(app.getNoBackupFilesDir(), CACHE); }

    PreparedMusic(File directory, SharedPreferences preferences, Runtime runtime, Network network) {
        this.directory = directory;
        this.preferences = preferences;
        this.runtime = runtime;
        this.network = network;
    }

    private boolean enabled() { return !closed && runtime != null && preferences.getBoolean(OPT_IN, false); }

    synchronized void options(boolean enabled, boolean clear) throws IOException {
        if (closed || runtime == null) return;
        preferences.edit().putBoolean(OPT_IN, enabled && !clear).apply();
        if (!enabled || clear) clearPlan();
        if (clear) {
            loadCache();
            // Delete complete v90 entries, plus any files from the limited earlier helper preview.
            File[] items = directory.listFiles((parent, name) -> name.matches("[a-f0-9]{64}|[A-Za-z0-9_-]{11}\\.m4a"));
            if (items != null) for (File item : items) deleteScratch(item);
            cached.clear(); loaded = false;
        }
    }

    synchronized JSONObject plan(int sequence, JSONObject selected, JSONArray upcoming, boolean retry) throws JSONException {
        if (!enabled() || sequence <= epoch) return state();
        if (upcoming.length() > 2) throw new IllegalArgumentException("Only the next two queue entries may be prepared.");
        Map<String, Track> next = new LinkedHashMap<>();
        String key = "";
        if (selected != null) {
            Track first = new Track(selected);
            key = first.key;
            next.put(key, first);
            for (int i = 0; i < upcoming.length(); i++) if (!upcoming.isNull(i)) {
                Track track = new Track(upcoming.getJSONObject(i));
                next.putIfAbsent(track.key, track);
            }
        }
        loadCache();
        epoch = sequence;
        if (retry) failures.remove(key);
        failures.keySet().retainAll(next.keySet());
        if (active != null && (!next.containsKey(running) || !requested.equals(key)
                && !key.equals(running) && readyFile(key) == null)) active.cancel();
        requested = key;
        desired.clear(); desired.putAll(next);
        return state();
    }

    synchronized JSONObject state() throws JSONException {
        JSONObject rows = new JSONObject();
        boolean on = enabled();
        if (runtime != null) loadCache();
        if (on) {
            dispatch();
            Map<String, Track> entries = new LinkedHashMap<>(cached);
            entries.putAll(desired);
            for (Track track : entries.values()) {
                String key = track.key;
                boolean prepared = readyFile(key) != null;
                String state = prepared ? "ready" : key.equals(running) && !active.cancelled ? active.phase
                        : failures.containsKey(key) ? "failed" : desired.containsKey(key) ? "queued" : "idle";
                rows.put(key, new JSONObject().put("state", state).put("recording", track.json())
                        .put("message", "failed".equals(state) ? failures.get(key) : ""));
            }
        }
        return new JSONObject().put("available", runtime != null).put("enabled", on).put("stored", !cached.isEmpty())
                .put("selected", requested).put("version", VERSION).put("unmetered", on && network.allowed(true)).put("tracks", rows);
    }

    synchronized File read(String key) {
        if (!enabled()) return null;
        loadCache();
        File audio = readyFile(key);
        if (audio != null) audio.setLastModified(System.currentTimeMillis());
        return audio;
    }

    private File entry(String key) { return new File(directory, key); }
    private File file(String key) { return new File(entry(key), "audio.m4a"); }
    private File readyFile(String key) {
        if (!cached.containsKey(key)) return null;
        File audio = file(key);
        return audio.isFile() && audio.length() > 0 && audio.length() <= MAX_AUDIO ? audio : null;
    }

    private void loadCache() {
        if (loaded) return;
        loaded = true;
        File[] entries = directory.listFiles((parent, name) -> name.matches("[a-f0-9]{64}"));
        if (entries == null) return;
        // The same order on reopening as cache creation. The input files live in this app's private storage.
        Arrays.sort(entries, Comparator.comparingLong(File::lastModified));
        for (File entry : entries) {
            try (FileInputStream in = new FileInputStream(new File(entry, "recording.json"))) {
                JSONObject meta = new JSONObject(new String(TorrentMeta.slurp(in, 8192), StandardCharsets.UTF_8));
                Track track = new Track(meta.getJSONObject("recording"));
                if (track.key.equals(entry.getName()) && videoId(meta.optString("providerId"))
                        && (track.source.isEmpty() || track.source.equals(meta.optString("providerId")))) {
                    cached.put(track.key, track);
                    if (readyFile(track.key) == null) cached.remove(track.key);
                }
            } catch (IOException | JSONException | IllegalArgumentException damaged) { /* Never serve an invalid cache entry. */ }
        }
    }

    private Track nextJob() {
        if (!enabled() || failures.containsKey(requested)) return null;
        for (Track track : desired.values()) {
            if (readyFile(track.key) != null || failures.containsKey(track.key)) continue;
            boolean background = !track.key.equals(requested);
            if (network.allowed(background)) return track;
            if (!background) {
                failures.put(track.key, "Not prepared yet. Connect to the internet to prepare this recording.");
                return null;
            }
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
            final Track track;
            final Control control;
            synchronized (this) {
                track = nextJob();
                if (track == null) { dispatching = false; return; }
                running = track.key;
                active = control = new Control(track.source.isEmpty() ? "matching" : "preparing");
            }
            File scratch = null;
            String failure = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to open prepared music storage.");
                if (directory.getUsableSpace() < 3 * MAX_AUDIO) throw new IOException("Not enough space to prepare this recording.");
                scratch = new File(directory, "work-" + java.util.UUID.randomUUID());
                if (!scratch.mkdir()) throw new IOException("Unable to stage the recording.");
                Result out = runtime.fetch(track, scratch, control);
                if (control.cancelled) continue;
                if (out == null || !videoId(out.source) || !track.source.isEmpty() && !track.source.equals(out.source))
                    throw new IOException("No verified full recording was prepared.");
                verifyAudio(out.audio, track.seconds);
                // Publish the audio plus its exact recording identity together, never a partial download.
                File complete = new File(scratch, "complete");
                if (!complete.mkdir() || !out.audio.renameTo(new File(complete, "audio.m4a")))
                    throw new IOException("Unable to stage the complete recording.");
                try (FileOutputStream stream = new FileOutputStream(new File(complete, "recording.json"))) {
                    stream.write(new JSONObject().put("recording", track.json()).put("providerId", out.source).toString().getBytes(StandardCharsets.UTF_8));
                }
                synchronized (this) {
                    if (!control.cancelled && enabled() && desired.containsKey(track.key)) {
                        if (readyFile(track.key) != null) continue;
                        deleteScratch(entry(track.key));
                        if (!complete.renameTo(entry(track.key))) throw new IOException("Unable to store the prepared recording.");
                        cached.put(track.key, track);
                        trimCache();
                    }
                }
            } catch (Exception | LinkageError error) {
                failure = error instanceof IOException ? safeReason(error.getMessage()) : "The bundled music helper could not start.";
            } finally {
                deleteScratch(scratch);
                synchronized (this) {
                    if (failure != null && !control.cancelled && desired.containsKey(track.key)) failures.put(track.key, failure);
                    running = null;
                    active = null;
                }
            }
        }
    }

    private void trimCache() {
        long bytes = 0;
        for (String key : cached.keySet()) bytes += file(key).length();
        while (cached.size() > CACHE_TRACKS || bytes > CACHE_BYTES) {
            String oldest = cached.keySet().stream().filter(key -> !desired.containsKey(key))
                    .min(Comparator.comparingLong(key -> file(key).lastModified())).orElse(null);
            if (oldest == null) return;
            long size = file(oldest).length();
            deleteScratch(entry(oldest));
            if (file(oldest).exists()) return;
            cached.remove(oldest);
            bytes -= size;
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
            long millis = time == null ? 0 : Long.parseLong(time);
            if (millis < 1000 || millis > (MAX_SECONDS + 6) * 1000L || seconds > 0 && Math.abs(millis - seconds * 1000L) > 6000)
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
