package com.blackboxstream.mobile;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One private, bounded worker for a selected catalogue recording and its next two queue slots. */
final class PreparedMusic implements AutoCloseable {
    static final String VERSION = "2026.10.26";
    static final long MAX_AUDIO = 24L * 1024 * 1024;
    // Absolute UI guard, with room for the native stage budgets and queue promotion.
    static final long SELECTION_WAIT_MS = 270_000;
    static final int MOBILE_LOOKAHEAD_SECONDS = 360;
    private static final long PROVIDER_COOLDOWN_MS = 60_000;
    private static final long WAIT_SLICE_MS = 4_000;
    private static final String RATE_LIMITED = "The audio provider is rate-limiting requests. Try again shortly.";
    static final int MAX_SECONDS = 1800;
    private static final long CACHE_BYTES = 256L * 1024 * 1024;
    private static final int CACHE_TRACKS = 64;
    private static final String CACHE = "prepared-music-" + VERSION;

    static final class Track {
        final String key, ref, title, artist, album, source;
        final int seconds;

        Track(JSONObject fields) throws JSONException { this(fields, true); }

        Track(JSONObject fields, boolean requireEdition) throws JSONException {
            ref = field(fields, "ref", 640, true);
            title = field(fields, "title", 240, true);
            artist = field(fields, "artist", 240, true);
            album = field(fields, "album", 240, false);
            source = field(fields, "source", 11, false);
            seconds = fields.getInt("seconds");
            if (!source.isEmpty() && !videoId(source) || seconds < 0 || seconds > MAX_SECONDS
                    || requireEdition && source.isEmpty() && (album.isEmpty() || seconds == 0))
                throw new IllegalArgumentException("The catalogue does not identify an exact full recording.");
            // Full recording identity, not a title-only match. The pinned source, when present, is part of that identity.
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

    interface Runtime {
        default void warm() throws Exception { }
        Result fetch(Track track, File scratch, Control control) throws Exception;
        default void rememberSource(Track track, String providerId) { }
    }
    static final class ProviderRateLimited extends IOException {
        ProviderRateLimited() { super(RATE_LIMITED); }
    }
    enum Link { OFFLINE, RESTRICTED, METERED, UNMETERED }
    interface Network { Link current(); }

    static final class Control {
        volatile boolean cancelled;
        private final long started = SystemClock.elapsedRealtime();
        private final PreparedMusic queue;
        private final Track track;
        private Process process;
        Control() { this(null, null); }
        Control(PreparedMusic queue, Track track) { this.queue = queue; this.track = track; }
        boolean wantsStream() { return queue != null && queue.wantsStream(this); }
        void offerStream(File part, long length, String providerId) {
            if (queue != null) queue.offerStream(this, part, length, providerId);
        }
        void stage(String stage) {
            // Internal stage names and elapsed times only. Never publish provider output or track metadata.
            Log.i("BlackBoxMusic", stage + " +" + (SystemClock.elapsedRealtime() - started) + "ms");
        }
        synchronized void attach(Process p) { if (cancelled) p.destroy(); else process = p; }
        synchronized void detach() { process = null; }
        synchronized void cancel() { cancelled = true; if (process != null) process.destroy(); }
    }

    private final File directory;
    private Runnable unregisterNetwork;
    private final Runtime runtime;
    private final Network network;
    private final Map<String, Track> desired = new LinkedHashMap<>(), cached = new LinkedHashMap<>();
    // Explicit album downloads are separate from the two automatic lookahead slots.
    private final Map<String, Track> album = new LinkedHashMap<>();
    private final Set<String> albumCompleted = new HashSet<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean dispatching, closed, loaded, warming, started, mobileLookahead;
    private long retryProviderAt;
    private int epoch;
    private String requested = "", firstUpcoming = "", running;
    private Control active;
    // At most one live reader source, even when the worker has moved on to the next queue file.
    private Control streamOwner;
    private ProgressiveMusicAudio stream;

    static PreparedMusic builtIn(Context app) {
        Runtime runtime = null;
        try {
            Class<?> type = Class.forName("com.blackboxstream.mobile.BundledMusicRuntime");
            runtime = (Runtime) type.getDeclaredConstructor(Context.class).newInstance(app);
        } catch (ReflectiveOperationException | LinkageError absent) { /* The dependency-free baseline build. */ }
        ConnectivityManager manager = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = () -> {
            if (manager == null || manager.getActiveNetwork() == null) return Link.OFFLINE;
            if (!manager.isActiveNetworkMetered()) return Link.UNMETERED;
            return manager.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
                    ? Link.METERED : Link.RESTRICTED;
        };
        PreparedMusic engine = new PreparedMusic(cacheDirectory(app), runtime, network);
        if (runtime != null && manager != null) {
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(android.net.Network net, NetworkCapabilities caps) { engine.networkChanged(); }
                @Override public void onLost(android.net.Network net) { engine.networkChanged(); }
                @Override public void onBlockedStatusChanged(android.net.Network net, boolean blocked) { engine.networkChanged(); }
            };
            manager.registerDefaultNetworkCallback(callback);
            engine.unregisterNetwork = () -> manager.unregisterNetworkCallback(callback);
        }
        return engine;
    }

    static File cacheDirectory(Context app) { return new File(app.getNoBackupFilesDir(), CACHE); }

    PreparedMusic(File directory, Runtime runtime, Network network) {
        this.directory = directory;
        this.runtime = runtime;
        this.network = network;
    }

    private boolean available() { return !closed && runtime != null; }

    // Only unpack/verify the bundled code; a Music view never starts a source lookup by itself.
    synchronized void warm() {
        if (!available() || warming) return;
        warming = true;
        worker.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                runtime.warm();
                Log.i("BlackBoxMusic", "helper warm +" + (SystemClock.elapsedRealtime() - started) + "ms");
            }
            catch (Exception | LinkageError problem) {
                Log.i("BlackBoxMusic", "helper warm failed +" + (SystemClock.elapsedRealtime() - started) + "ms");
                // A selected track can retry the local initialization and get a real failure result.
                synchronized (this) { warming = false; }
            }
        });
    }

    synchronized void networkChanged() {
        if (!available()) return;
        if (active != null && !requested.equals(running)) reconsiderActive();
        notifyAll();
        dispatch();
    }

    synchronized void forget(String key) {
        if (!available() || !key.matches("[a-f0-9]{64}")) return;
        loadCache();
        desired.remove(key);
        album.remove(key);
        albumCompleted.remove(key);
        failures.remove(key);
        if (active != null && key.equals(running)) active.cancel();
        if (key.equals(requested)) closeStream();
        deleteScratch(entry(key));
        cached.remove(key);
        notifyAll();
    }

    synchronized JSONObject plan(int sequence, JSONObject selected, JSONArray upcoming, boolean retry,
                                 boolean playing, boolean allowMobile) throws JSONException {
        if (!available() || sequence <= epoch) return state();
        if (upcoming.length() > 2) throw new IllegalArgumentException("Only the next two queue entries may be prepared.");
        Map<String, Track> next = new LinkedHashMap<>();
        String key = "", firstKey = "";
        if (selected != null) {
            Track first = new Track(selected);
            key = first.key;
            next.put(key, first);
            for (int i = 0; i < upcoming.length(); i++) if (!upcoming.isNull(i)) {
                Track track = new Track(upcoming.getJSONObject(i));
                if (i == 0 && !key.equals(track.key)) firstKey = track.key;
                next.putIfAbsent(track.key, track);
            }
        }
        loadCache();
        epoch = sequence;
        if (retry) failures.remove(key);
        failures.keySet().removeIf(id -> !next.containsKey(id) && !album.containsKey(id));
        if (!key.equals(requested)) closeStream();
        requested = key;
        firstUpcoming = firstKey;
        // The WebView can confirm playback while the current file is still being transferred.
        // Remember that confirmation; lookahead still waits for the first file to be committed.
        started = playing && !key.isEmpty() && (readyFile(key) != null || stream != null);
        mobileLookahead = allowMobile;
        desired.clear(); desired.putAll(next);
        reconsiderActive();
        notifyAll();
        return state();
    }

    private void reconsiderActive() {
        if (active == null || readyFile(running) != null) return;
        Track first = nextJob();
        if (first == null || !first.key.equals(running)) active.cancel();
    }

    /** Wake for a playable prefix or the final offline file. A fallback can wait for the latter only. */
    synchronized JSONObject awaitSelection(String key, boolean savedOnly) throws JSONException {
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid recording key.");
        long until = SystemClock.elapsedRealtime() + WAIT_SLICE_MS;
        while (available() && requested.equals(key) && desired.containsKey(key) && readyFile(key) == null
                && !failures.containsKey(key) && (savedOnly || stream == null)) {
            long waitMs = until - SystemClock.elapsedRealtime();
            if (waitMs <= 0) break;
            try { wait(waitMs); }
            catch (InterruptedException closing) { Thread.currentThread().interrupt(); break; }
        }
        return state();
    }

    private synchronized boolean wantsStream(Control control) {
        return available() && stream == null && active == control && !control.cancelled
                && requested.equals(control.track.key) && desired.containsKey(requested) && readyFile(requested) == null;
    }

    private void offerStream(Control control, File part, long length, String providerId) {
        if (!wantsStream(control) || !videoId(providerId)
                || !control.track.source.isEmpty() && !control.track.source.equals(providerId)) return;
        ProgressiveMusicAudio opened = ProgressiveMusicAudio.openPartial(part, length);
        if (opened == null) return;
        synchronized (this) {
            if (wantsStream(control)) {
                stream = opened;
                streamOwner = control;
                control.stage("stream ready");
                notifyAll();
            } else opened.close();
        }
    }

    synchronized ProgressiveMusicAudio.Read readStream(String key, String token, String range) {
        return available() && requested.equals(key) && stream != null && stream.token.equals(token)
                ? stream.read(range) : null;
    }

    private void closeStream() {
        if (stream != null) stream.close();
        stream = null;
        streamOwner = null;
    }

    synchronized JSONObject downloadAlbum(JSONArray tracks) throws JSONException {
        if (!available()) return state();
        if (tracks.length() > CACHE_TRACKS) throw new IllegalArgumentException("Album exceeds offline cache capacity.");
        Map<String, Track> next = new LinkedHashMap<>();
        for (int i = 0; i < tracks.length(); i++) {
            Track track = new Track(tracks.getJSONObject(i));
            next.putIfAbsent(track.key, track);
        }
        loadCache();
        if (active != null && !desired.containsKey(running) && !next.containsKey(running)) active.cancel();
        album.clear(); album.putAll(next);
        albumCompleted.clear();
        failures.keySet().removeIf(key -> !desired.containsKey(key) || album.containsKey(key) && !requested.equals(key));
        for (String key : album.keySet()) if (readyFile(key) != null) albumCompleted.add(key);
        return state();
    }

    synchronized JSONObject state() throws JSONException {
        JSONObject rows = new JSONObject();
        boolean on = available();
        if (runtime != null) loadCache();
        if (on) {
            dispatch();
            Map<String, Track> entries = new LinkedHashMap<>(cached);
            entries.putAll(album);
            entries.putAll(desired);
            for (Track track : entries.values()) {
                String key = track.key;
                boolean prepared = readyFile(key) != null;
                String state = prepared ? "ready" : key.equals(running) && !active.cancelled ? "working"
                        : failures.containsKey(key) || albumCompleted.contains(key) && !desired.containsKey(key) ? "failed" : "queued";
                JSONObject row = new JSONObject().put("state", state).put("recording", track.json())
                        .put("message", "failed".equals(state) ? failures.get(key) : "");
                if ("working".equals(state) && key.equals(requested) && stream != null) row.put("stream", stream.token);
                rows.put(key, row);
            }
        }
        return new JSONObject().put("available", on).put("selected", requested).put("sequence", epoch)
                .put("selectionWaitMs", SELECTION_WAIT_MS).put("tracks", rows);
    }

    synchronized File read(String key) {
        if (!available()) return null;
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
        if (!available()) return null;
        if (SystemClock.elapsedRealtime() < retryProviderAt) {
            if (desired.containsKey(requested) && readyFile(requested) == null) failures.put(requested, RATE_LIMITED);
            for (String key : album.keySet()) if (readyFile(key) == null) failures.putIfAbsent(key, RATE_LIMITED);
            return null;
        }
        Link link = network.current();
        Track selected = desired.get(requested);
        if (selected != null && readyFile(requested) == null && !failures.containsKey(requested)) {
            if (link != Link.OFFLINE) return selected;
            failures.put(requested, "Not prepared yet. Connect to the internet to prepare this recording.");
        }
        // A play tap alone does not start lookahead. The original player confirms that it started.
        // On metered networks require opt-in, and never fill a missing/duplicate first slot with a later track.
        if (started && selected != null && readyFile(requested) != null) for (Track track : desired.values()) {
            if (readyFile(track.key) != null || failures.containsKey(track.key)) continue;
            if (link == Link.UNMETERED || link == Link.METERED && mobileLookahead && track.key.equals(firstUpcoming)
                    && track.seconds > 0 && track.seconds <= MOBILE_LOOKAHEAD_SECONDS) return track;
        }
        if (link == Link.UNMETERED && (selected == null || started || failures.containsKey(requested))) for (Track track : album.values())
            if (!albumCompleted.contains(track.key) && !failures.containsKey(track.key) && readyFile(track.key) == null) return track;
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
                active = control = new Control(this, track);
            }
            File scratch = null;
            String failure = null;
            boolean rateLimited = false;
            try {
                control.stage(track.source.isEmpty() ? "prepare catalogue" : "prepare pinned");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to open prepared music storage.");
                if (directory.getUsableSpace() < 3 * MAX_AUDIO) throw new IOException("Not enough space to prepare this recording.");
                scratch = new File(directory, "work-" + java.util.UUID.randomUUID());
                if (!scratch.mkdir()) throw new IOException("Unable to stage the recording.");
                Result out = runtime.fetch(track, scratch, control);
                if (control.cancelled) continue;
                if (out == null || !videoId(out.source) || !track.source.isEmpty() && !track.source.equals(out.source))
                    throw new IOException("No verified full recording was prepared.");
                control.stage("validate audio");
                verifyAudio(out.audio, track.seconds);
                // Publish the audio plus its exact recording identity together, never a partial download.
                File complete = new File(scratch, "complete");
                if (!complete.mkdir() || !out.audio.renameTo(new File(complete, "audio.m4a")))
                    throw new IOException("Unable to stage the complete recording.");
                try (FileOutputStream stream = new FileOutputStream(new File(complete, "recording.json"))) {
                    stream.write(new JSONObject().put("recording", track.json()).put("providerId", out.source).toString().getBytes(StandardCharsets.UTF_8));
                }
                boolean published = false;
                synchronized (this) {
                    if (!control.cancelled && available() && wanted(track.key)) {
                        if (readyFile(track.key) != null) continue;
                        deleteScratch(entry(track.key));
                        if (!complete.renameTo(entry(track.key))) throw new IOException("Unable to store the prepared recording.");
                        cached.put(track.key, track);
                        if (album.containsKey(track.key)) albumCompleted.add(track.key);
                        trimCache();
                        published = true;
                        notifyAll();
                    }
                }
                // Hints never gate publication or change the queue/offline key. No media I/O under the monitor.
                if (published && track.source.isEmpty()) {
                    try { runtime.rememberSource(track, out.source); }
                    catch (RuntimeException ignored) { /* A failed hint write cannot fail a prepared recording. */ }
                }
            } catch (Exception | LinkageError error) {
                rateLimited = error instanceof ProviderRateLimited;
                failure = error instanceof IOException ? safeReason(error.getMessage()) : "The bundled music helper could not start.";
            } finally {
                deleteScratch(scratch);
                control.stage(control.cancelled ? "cancelled" : rateLimited ? "provider refused (429)" : failure != null ? "failed" : "committed");
                synchronized (this) {
                    // A finalization failure need not interrupt a fully buffered stream. A truncated
                    // or cancelled transfer must never give the player a successful EOF.
                    if (streamOwner == control && (control.cancelled || !stream.hasAllBytes())) closeStream();
                    if (failure != null && !control.cancelled && wanted(track.key)) {
                        failures.put(track.key, failure);
                        if (rateLimited) retryProviderAt = SystemClock.elapsedRealtime() + PROVIDER_COOLDOWN_MS;
                    }
                    running = null;
                    active = null;
                    notifyAll();
                }
            }
        }
    }

    private boolean wanted(String key) { return desired.containsKey(key) || album.containsKey(key); }

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
        desired.clear(); album.clear(); albumCompleted.clear(); failures.clear();
        requested = firstUpcoming = "";
        started = false;
        closeStream();
        if (active != null) active.cancel();
    }
    @Override public synchronized void close() {
        closed = true;
        clearPlan();
        notifyAll();
        worker.shutdownNow();
        if (unregisterNetwork != null) { unregisterNetwork.run(); unregisterNetwork = null; }
    }
}
