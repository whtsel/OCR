package com.blackboxstream.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable, explicit download queue. The downloader has no connection to the player. */
final class OfflineMusicLibrary {
    static final int MAX_RECORDS = 1000;
    static final int MAX_BATCH = 120; // The largest packaged v90 album has 100 rows.
    private static final int MAX_PLAYLISTS = 100;
    private static final int PARALLEL_DOWNLOADS = 5;
    private static final int PARALLEL_LOOKUPS = 2;
    private static final int MAX_AUTO_RETRIES = 3;
    private static final long FIRST_RETRY_DELAY_MS = 2_000;
    static final String TAG = "BlackBoxOfflineMusic";
    private static OfflineMusicLibrary instance;

    // Loading the standalone v90 build does not load any helper dependencies.
    interface Downloader {
        File fetch(PreparedMusic.Track track, File staging, String excludedSource, Progress progress) throws Exception;
        default boolean needsLookup(PreparedMusic.Track track) { return true; }
        default void published(PreparedMusic.Track track, File staging) { }
    }
    interface Progress {
        void report(String phase, long received, long total);
        default OfflineDownloadReport.Attempt downloadTest() { return null; }
    }

    static synchronized OfflineMusicLibrary open(Context context) {
        if (instance == null) instance = new OfflineMusicLibrary(context.getApplicationContext());
        return instance;
    }

    static final class Refusal extends IOException {
        final String code;
        Refusal(String code) { this(code, code); }
        Refusal(String code, String message) { super(message); this.code = code; }
    }

    // A selected watch source failed to open, not an explicit access or transfer failure.
    static final class SourceUnavailable extends IOException {
        final String id, code;
        SourceUnavailable(String id, boolean removed) {
            super(removed ? "The selected source was removed." : "The selected source is unavailable.");
            this.id = id;
            code = removed ? "source-gone" : "source-unavailable";
        }
    }

    private static final class Entry {
        final JSONObject info;
        final PreparedMusic.Track track;
        final String batch, label, failure, excludedSource;
        final boolean complete;
        final int autoRetries;
        final long retryAt;

        Entry(JSONObject row, String batch, String label, String failure, boolean complete, int autoRetries,
                String excludedSource) throws JSONException {
            info = clean(row);
            JSONObject spec = new JSONObject(info.toString());
            // A missing edition/duration is a visible blocked queue item, not permission to look up a substitute.
            spec.put("source", "");
            track = new PreparedMusic.Track(spec, false);
            this.batch = batch; this.label = label; this.failure = failure; this.complete = complete;
            this.excludedSource = PreparedMusic.videoId(excludedSource) ? excludedSource : "";
            this.autoRetries = Math.max(0, Math.min(MAX_AUTO_RETRIES, autoRetries));
            retryAt = 0; // A short, process-local backoff; the attempt budget survives reopening the app.
        }

        private Entry(Entry prev, String reason, boolean complete, int autoRetries, long retryAt, String excludedSource) {
            info = prev.info; track = prev.track; batch = prev.batch; label = prev.label;
            failure = reason; this.complete = complete; this.excludedSource = excludedSource;
            this.autoRetries = autoRetries; this.retryAt = retryAt;
        }

        private static String text(JSONObject row, String name, int limit) throws JSONException {
            String text = row.optString(name).trim();
            if (text.length() > limit || text.chars().anyMatch(c -> c < 32 || c == 127)) throw new JSONException("Invalid recording details");
            return text;
        }

        private static JSONObject clean(JSONObject row) throws JSONException {
            String ref = text(row, "ref", 630);
            if (ref.isEmpty()) throw new JSONException("Missing recording identity");
            return new JSONObject().put("ref", ref).put("title", text(row, "title", 240)).put("artist", text(row, "artist", 240))
                    .put("album", text(row, "album", 240)).put("albumArtist", text(row, "albumArtist", 240))
                    .put("albumId", text(row, "albumId", 100)).put("art", text(row, "art", 400))
                    .put("seconds", Math.max(0, Math.min(PreparedMusic.MAX_SECONDS, row.optInt("seconds"))))
                    .put("disc", Math.max(1, Math.min(100, row.optInt("disc", 1))))
                    .put("number", Math.max(0, Math.min(10000, row.optInt("number"))))
                    .put("total", Math.max(0, Math.min(MAX_BATCH, row.optInt("total"))));
        }

        boolean identified() { return track.seconds > 0 && !track.album.isEmpty(); }
        Entry result(String reason, boolean saved) {
            // Failed rows can say whether a fresh search already ran; a manual Retry starts over.
            return new Entry(this, reason, saved, 0, 0, reason.isEmpty() || saved ? "" : excludedSource);
        }
        Entry refresh(String source) { return new Entry(this, "", false, autoRetries, 0, source); }
        Entry retryAt(long when) { return new Entry(this, "", false, autoRetries + 1, when, excludedSource); }
        JSONObject encode() throws JSONException {
            JSONObject row = new JSONObject(info.toString()).put("batch", batch).put("label", label)
                    .put("failure", failure).put("complete", complete);
            if (autoRetries > 0) row.put("autoRetries", autoRetries);
            if (!excludedSource.isEmpty()) row.put("excludedSource", excludedSource);
            return row;
        }
    }

    private final File saved, stage;
    private final SharedPreferences prefs;
    private final ConnectivityManager network;
    private final Downloader downloader;
    private final OfflineDownloadReport downloadTest;
    private static final class Job {
        String phase = "preparing";
        long received, total;
        boolean resolving;
        Job(boolean resolving) { this.resolving = resolving; }
    }

    private final ExecutorService workers = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS);
    private final ScheduledExecutorService wakeups = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private JSONArray playlists = new JSONArray();
    private final Map<String, Job> active = new LinkedHashMap<>();
    private boolean paused;
    private long cooldownUntil;
    private String lastSnapshot;
    private int lastLink = -1;

    private OfflineMusicLibrary(Context app) {
        File root = new File(app.getFilesDir(), "offline-music");
        saved = new File(root, "saved"); stage = new File(root, "staging");
        prefs = app.getSharedPreferences("v90-offline-music", Context.MODE_PRIVATE);
        downloadTest = OfflineMusicService.isDownloadTest(app) ? new OfflineDownloadReport(app) : null;
        paused = prefs.getBoolean("paused", false);
        try { playlists = new JSONArray(prefs.getString("playlists", "[]")); }
        catch (JSONException invalid) { Log.w(TAG, "Unreadable Music playlists"); }
        try {
            JSONArray rows = new JSONArray(prefs.getString("entries", "[]"));
            for (int i = 0; i < Math.min(rows.length(), MAX_RECORDS); i++) {
                try {
                    JSONObject row = rows.getJSONObject(i);
                    Entry entry = new Entry(row, row.getString("batch"), row.getString("label"),
                            row.getString("failure"), row.optBoolean("complete"), row.optInt("autoRetries"),
                            row.optString("excludedSource"));
                    if (ready(entry) != null) entry = entry.result("", true); // A kill may land between rename and prefs write.
                    else if (entry.complete) entry = entry.result("missing", false);
                    entries.put(entry.track.key, entry);
                } catch (JSONException | IllegalArgumentException skipped) { Log.w(TAG, "Unreadable Music queue row"); }
            }
        } catch (JSONException invalid) { Log.w(TAG, "Unreadable Music queue"); }
        Downloader runtime = null;
        try {
            runtime = (Downloader) Class.forName("com.blackboxstream.mobile.WholeFileMusicRuntime")
                    .getDeclaredConstructor(Context.class).newInstance(app);
        } catch (ReflectiveOperationException | LinkageError absent) { Log.w(TAG, "Offline Music helper unavailable"); }
        downloader = runtime;
        network = app.getSystemService(ConnectivityManager.class);
        if (network != null) network.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network link) { kick(); }
            @Override public void onCapabilitiesChanged(Network link, NetworkCapabilities caps) { kick(); }
        });
        kick();
    }

    private File file(Entry entry) { return new File(saved, entry.track.key + ".m4a"); }

    private static boolean hasOutput(File audio) {
        if (audio == null || !audio.isFile()) return false;
        long bytes = audio.length();
        return bytes >= 32 && bytes <= PreparedMusic.MAX_AUDIO;
    }

    private File ready(Entry entry) {
        File audio = file(entry);
        return hasOutput(audio) ? audio : null;
    }

    synchronized File saved(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : ready(entry);
    }

    synchronized PreparedMusic.Track recording(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.track;
    }

    private int link() {
        Network current = network == null ? null : network.getActiveNetwork();
        NetworkCapabilities caps = current == null ? null : network.getNetworkCapabilities(current);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return 0;
        // Metering is diagnostic only. Any available Internet transport can start the queue.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ? 2 : 1;
    }

    private void persist() throws JSONException, IOException {
        JSONArray rows = new JSONArray();
        for (Entry entry : entries.values()) rows.put(entry.encode());
        if (!prefs.edit().putString("entries", rows.toString()).putString("playlists", playlists.toString())
                .putBoolean("paused", paused).commit())
            throw new IOException("Unable to record your downloads");
    }

    synchronized String command(String action, String value) {
        if (action == null || value == null || value.length() > 150000) return error("Invalid download request.");
        Map<String, Entry> before = new LinkedHashMap<>(entries);
        JSONArray beforePlaylists = playlists;
        boolean wasPaused = paused;
        try {
            switch (action) {
                case "enqueue": {
                    JSONObject request = new JSONObject(value);
                    JSONArray tracks = request.getJSONArray("tracks");
                    if (tracks.length() == 0 || tracks.length() > MAX_BATCH) return error("Queue up to 120 songs at a time.");
                    String batch = UUID.randomUUID().toString(), label = Entry.text(request, "label", 240);
                    for (int i = 0; i < tracks.length(); i++) {
                        Entry entry = new Entry(tracks.getJSONObject(i), batch, label, "", false, 0, "");
                        if (!entry.identified()) entry = entry.result("metadata", false);
                        if (!entries.containsKey(entry.track.key) && entries.size() >= MAX_RECORDS) {
                            entries.clear(); entries.putAll(before);
                            return error("Your Music library is full. Remove a saved song or clear a queue item first.");
                        }
                        entries.putIfAbsent(entry.track.key, entry);
                    }
                    break;
                }
                case "pause": paused = true; break;
                case "resume": paused = false; break;
                case "retry": {
                    Entry entry = entries.get(value);
                    if (entry != null && !active.containsKey(value) && retryable(entry.failure)) entries.put(value, entry.result("", false));
                    break;
                }
                case "remove": {
                    Entry entry = entries.get(value);
                    if (entry != null && !active.containsKey(value) && ready(entry) == null) entries.remove(value);
                    break;
                }
                case "playlist-add": {
                    if (playlists.length() >= MAX_PLAYLISTS) return error("Remove a playlist before making another.");
                    JSONObject request = new JSONObject(value);
                    String name = Entry.text(request, "name", 60);
                    JSONArray keys = request.getJSONArray("keys");
                    if (name.isEmpty() || keys.length() < 1 || keys.length() > MAX_BATCH)
                        return error("Name the playlist and select between 1 and 120 saved songs.");
                    java.util.Set<String> unique = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < keys.length(); i++) {
                        String key = keys.getString(i);
                        if (saved(key) != null) unique.add(key);
                    }
                    if (unique.isEmpty()) return error("These songs are not saved on this device.");
                    JSONObject list = new JSONObject().put("id", UUID.randomUUID().toString())
                            .put("name", name).put("keys", new JSONArray(unique));
                    playlists = new JSONArray(playlists.toString()).put(list);
                    break;
                }
                case "playlist-remove": {
                    JSONArray keep = new JSONArray();
                    for (int i = 0; i < playlists.length(); i++) {
                        JSONObject list = playlists.optJSONObject(i);
                        if (list != null && !value.equals(list.optString("id"))) keep.put(list);
                    }
                    playlists = keep;
                    break;
                }
                case "delete": {
                    Entry entry = entries.get(value);
                    if (entry != null && !active.containsKey(value) && ready(entry) != null) {
                        // Commit the intent first; a failed metadata write must not delete the file.
                        entries.remove(value);
                        persist();
                        if (!file(entry).delete()) throw new IOException("Could not delete the saved file");
                    }
                    break;
                }
                default: return error("Unknown download action.");
            }
            if (!action.equals("delete")) persist();
            lastSnapshot = null;
            kick();
            return snapshot();
        } catch (IOException | JSONException | IllegalArgumentException invalid) {
            entries.clear(); entries.putAll(before); playlists = beforePlaylists; paused = wasPaused; lastSnapshot = null;
            return error("The download list could not be updated. Check your storage and try again.");
        }
    }

    synchronized String snapshot() {
        int link = link();
        if (lastSnapshot != null && lastLink == link) return lastSnapshot;
        try {
            JSONArray rows = new JSONArray();
            for (Entry entry : entries.values()) {
                String status, reason = entry.failure;
                if (ready(entry) != null) status = "saved";
                else if (active.containsKey(entry.track.key)) status = "working";
                else if (entry.complete) { status = "failed"; reason = "missing"; }
                else if (!entry.failure.isEmpty()) status = "failed";
                else if (paused) status = "paused";
                else if (link == 0) status = "offline";
                else if (cooldownUntil > SystemClock.elapsedRealtime()) status = "cooldown";
                else if (entry.autoRetries > 0) status = "retrying";
                else status = "queued";
                JSONObject row = new JSONObject(entry.info.toString()).put("key", entry.track.key).put("state", status)
                        .put("batch", entry.batch).put("label", entry.label).put("reason", reason)
                        .put("retryable", retryable(reason));
                if (entry.autoRetries > 0) row.put("retry", entry.autoRetries).put("retryLimit", MAX_AUTO_RETRIES);
                if ("failed".equals(status) && !entry.excludedSource.isEmpty()) row.put("freshSearch", true);
                rows.put(row);
            }
            lastLink = link;
            JSONObject state = new JSONObject().put("tracks", rows).put("paused", paused)
                    .put("connected", link > 0).put("metered", link == 1).put("playlists", playlists).put("available", downloader != null);
            if (downloadTest != null) state.put("downloadTest", true).put("reportReady", downloadTest.ready());
            return lastSnapshot = state.toString();
        } catch (JSONException invalid) { return error("Your Music library could not be read."); }
    }

    private static String error(String message) { return "{\"error\":" + JSONObject.quote(message) + "}"; }

    private static boolean retryable(String reason) {
        return !"signin".equals(reason) && !"refused".equals(reason) && !"metadata".equals(reason);
    }

    synchronized String downloadTestReport() { return downloadTest == null ? "" : downloadTest.text(); }

    /** Separate from the durable list so byte ticks never rebuild or persist the queue. */
    synchronized String progress() {
        try {
            JSONObject result = new JSONObject();
            for (Map.Entry<String, Job> item : active.entrySet()) {
                Job job = item.getValue();
                result.put(item.getKey(), new JSONObject().put("phase", job.phase)
                        .put("received", job.received).put("total", job.total));
            }
            return result.toString();
        } catch (JSONException invalid) { return "{}"; }
    }

    private synchronized void report(String key, Job job, String phase, long received, long total) {
        if (active.get(key) != job) return; // Ignore a late parser callback from a finished job.
        job.phase = phase;
        job.received = Math.max(job.received, received);
        if (total > 0) job.total = total;
        if (job.received > job.total) job.total = 0; // No trustworthy denominator yet.
        if (job.resolving && ("downloading".equals(phase) || "finishing".equals(phase))) {
            // The same helper proceeds into transfer. Allow the next lookup without restarting it.
            job.resolving = false;
            kick();
        }
    }

    private synchronized void kick() {
        long now = SystemClock.elapsedRealtime();
        if (paused || active.size() >= PARALLEL_DOWNLOADS || downloader == null || V90Api.expired()
                || cooldownUntil > now || link() == 0) return;
        int lookups = 0;
        for (Job job : active.values()) if (job.resolving) lookups++;
        for (Entry entry : entries.values()) {
            if (active.size() >= PARALLEL_DOWNLOADS) break;
            if (entry.complete || !entry.failure.isEmpty() || entry.retryAt > now
                    || active.containsKey(entry.track.key) || ready(entry) != null) continue;
            File work = new File(stage, entry.track.key);
            boolean lookup = !entry.excludedSource.isEmpty() || downloader.needsLookup(entry.track);
            if (lookup && lookups >= PARALLEL_LOOKUPS) continue;
            if (lookup) lookups++;
            Job job = new Job(lookup);
            active.put(entry.track.key, job);
            lastSnapshot = null;
            workers.execute(() -> download(entry, job, work));
        }
    }

    private static boolean directory(File folder) {
        return folder.isDirectory() || folder.mkdirs() || folder.isDirectory(); // Other workers may create the parent.
    }

    private void download(Entry entry, Job job, File work) {
        String failure = "", unavailable = "", key = entry.track.key;
        boolean deferred = false, attempted = false;
        OfflineDownloadReport.Attempt test = null;
        long publishedBytes = 0;
        try {
            int connection;
            synchronized (this) {
                connection = paused ? 0 : link();
                deferred = connection == 0 || V90Api.expired() || cooldownUntil > SystemClock.elapsedRealtime();
            }
            if (!deferred) {
                attempted = true;
                if (downloadTest != null) test = new OfflineDownloadReport.Attempt(entry.track, entry.autoRetries, connection);
                if (!directory(work) || !directory(saved)) throw new Refusal("storage");
                Progress progress = (phase, received, total) -> report(key, job, phase, received, total);
                if (test != null) {
                    OfflineDownloadReport.Attempt trace = test;
                    progress = new Progress() {
                        @Override public OfflineDownloadReport.Attempt downloadTest() { return trace; }
                        @Override public void report(String phase, long received, long total) {
                            OfflineMusicLibrary.this.report(key, job, phase, received, total);
                        }
                    };
                }
                File result = downloader.fetch(entry.track, work, entry.excludedSource, progress);
                if (test != null) test.publication();
                // Like desktop, a successful helper run commits its completed output. No media probe.
                if (!hasOutput(result) || !work.getCanonicalFile().equals(result.getCanonicalFile().getParentFile()))
                    throw new Refusal("incomplete");
                File destination = file(entry);
                if (!result.renameTo(destination)) throw new Refusal("storage");
                if (test != null) publishedBytes = destination.length();
                try { downloader.published(entry.track, work); }
                catch (RuntimeException ignored) { /* Optional source hints cannot undo a completed save. */ }
            }
        } catch (Exception | LinkageError stopped) {
            failure = classify(stopped);
            if (stopped instanceof SourceUnavailable && entry.excludedSource.isEmpty())
                unavailable = ((SourceUnavailable) stopped).id;
            // A lost transport can defer the attempt; a refusal or file failure cannot.
            deferred = link() == 0 && waitForConnection(failure);
        } finally {
            if (attempted) {
                File[] files = work.listFiles();
                if (files != null) for (File f : files) f.delete();
                work.delete();
            }
        }
        synchronized (this) {
            String next = deferred ? "wait for connection" : "none";
            if (!deferred) {
                if (!unavailable.isEmpty()) {
                    // Give the fresh search a normal lookup slot. Do not spend a transfer retry,
                    // and keep the failed source excluded if a subsequent transfer is interrupted.
                    entries.put(key, entry.refresh(unavailable));
                    next = "fresh search";
                } else if (entry.autoRetries < MAX_AUTO_RETRIES && retryAutomatically(failure)) {
                    // Release both queue slots during backoff. The normal scheduler checks pause,
                    // network and cooldown again before starting any new helper process.
                    long delay = FIRST_RETRY_DELAY_MS << entry.autoRetries;
                    entries.put(key, entry.retryAt(SystemClock.elapsedRealtime() + delay));
                    wakeups.schedule(this::kick, delay, TimeUnit.MILLISECONDS);
                    next = "automatic retry";
                } else {
                    entries.put(key, entry.result(failure, failure.isEmpty()));
                }
                if ("limited".equals(failure)) {
                    cooldownUntil = SystemClock.elapsedRealtime() + 60000;
                    next = "provider cooldown";
                    wakeups.schedule(() -> { synchronized (this) { lastSnapshot = null; kick(); } }, 60, TimeUnit.SECONDS);
                }
                try { persist(); } catch (IOException | JSONException ignored) { Log.w(TAG, "Music job result could not be recorded"); }
            }
            if (test != null) {
                try { downloadTest.record(test, failure.isEmpty() ? "saved" : failure, next, job.received, publishedBytes); }
                catch (RuntimeException ignored) { /* Optional local reporting never changes a download. */ }
            }
            active.remove(key);
            lastSnapshot = null;
            kick();
        }
    }

    private static boolean retryAutomatically(String reason) {
        // Never retry an access refusal, rate limit, catalogue mismatch, or local setup/storage error.
        return waitForConnection(reason) || "finalize-timeout".equals(reason) || "finalize".equals(reason)
                || "incomplete".equals(reason) || "helper".equals(reason);
    }

    private static boolean waitForConnection(String reason) {
        switch (reason) {
            case "network": case "lookup-io": case "transfer-io": case "lookup-timeout":
            case "transfer-timeout": case "transfer-idle": return true;
            default: return false;
        }
    }

    static String classify(Throwable error) {
        if (error instanceof SourceUnavailable) return ((SourceUnavailable) error).code;
        if (error instanceof Refusal) return ((Refusal) error).code;
        if (error instanceof PreparedMusic.ProviderRateLimited) return "limited";
        String problem = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (problem.contains("429") || problem.contains("too many requests")) return "limited";
        if (problem.contains("sign in") || problem.contains("sign-in") || problem.contains("not a bot") || problem.contains("login_required")) return "signin";
        if (problem.contains("no exact full recording")) return "exact";
        if (problem.contains("http error 403") || problem.contains("forbidden") || problem.contains("video unavailable") || problem.contains("did not allow")) return "refused";
        if (problem.contains("space left") || problem.contains("storage")) return "storage";
        if (problem.contains("timed out") || problem.contains("connection") || problem.contains("took too long") || problem.contains("resolve host")
                || problem.contains("no address") || problem.contains("stopped receiving data") || error instanceof InterruptedException) return "network";
        return "helper";
    }

    // Extra checks for the isolated Save Check diagnostic only. Regular Downloads do not call this.
    static void verify(File file, int seconds) throws Refusal {
        long bytes = file.length();
        if (bytes < 32 || bytes > PreparedMusic.MAX_AUDIO) throw new Refusal("incomplete");
        try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
            boolean moov = false, mdat = false;
            long position = 0;
            for (int boxes = 0; position < bytes && boxes < 1024; boxes++) {
                data.seek(position);
                long size = data.readInt() & 0xffffffffL;
                int type = data.readInt(), header = 8;
                if (size == 1) { size = data.readLong(); header = 16; }
                if (size < header || size > bytes - position) throw new IOException("Incomplete MP4 box");
                if (size > header && type == 0x6d6f6f76) moov = true;
                if (size > header && type == 0x6d646174) mdat = true;
                position += size;
            }
            if (position != bytes || !moov || !mdat) throw new IOException("Incomplete MP4 structure");
        } catch (IOException invalid) { throw new Refusal("incomplete"); }
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            long duration = Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (!"yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    || "yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    || Math.abs(duration - seconds * 1000L) > 6000) throw new Refusal("incomplete");
        } catch (RuntimeException unreadable) { throw new Refusal("incomplete"); }
        finally { try { reader.release(); } catch (Exception ignored) { } }
    }
}
