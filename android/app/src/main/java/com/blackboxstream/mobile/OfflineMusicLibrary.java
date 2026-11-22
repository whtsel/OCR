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
    static final String TAG = "BlackBoxOfflineMusic";
    private static OfflineMusicLibrary instance;

    // Loading the standalone v90 build does not load any helper dependencies.
    interface Downloader { File fetch(PreparedMusic.Track track, File staging) throws Exception; }

    static synchronized OfflineMusicLibrary open(Context context) {
        if (instance == null) instance = new OfflineMusicLibrary(context.getApplicationContext());
        return instance;
    }

    static final class Refusal extends IOException {
        final String code;
        Refusal(String code) { super(code); this.code = code; }
    }

    private static final class Entry {
        final JSONObject info;
        final PreparedMusic.Track track;
        final String batch, label, failure;
        final boolean mobile, complete;

        Entry(JSONObject row, String batch, String label, String failure, boolean mobile, boolean complete) throws JSONException {
            info = clean(row);
            JSONObject spec = new JSONObject(info.toString());
            // A missing edition/duration is a visible blocked queue item, not permission to look up a substitute.
            spec.put("source", "");
            track = new PreparedMusic.Track(spec, false);
            this.batch = batch; this.label = label; this.failure = failure; this.mobile = mobile; this.complete = complete;
        }

        private Entry(Entry prev, String reason, boolean mobile, boolean complete) {
            info = prev.info; track = prev.track; batch = prev.batch; label = prev.label;
            failure = reason; this.mobile = mobile; this.complete = complete;
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
        Entry result(String reason, boolean saved) { return new Entry(this, reason, mobile, saved); }
        Entry allowMobile() { return new Entry(this, failure, true, complete); }
        JSONObject encode() throws JSONException {
            return new JSONObject(info.toString()).put("batch", batch).put("label", label)
                    .put("failure", failure).put("mobile", mobile).put("complete", complete);
        }
    }

    private final File saved, stage;
    private final SharedPreferences prefs;
    private final ConnectivityManager network;
    private final Downloader downloader;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private JSONArray playlists = new JSONArray();
    private String active = "";
    private boolean paused;
    private long cooldownUntil;
    private String lastSnapshot;
    private int lastLink = -1;

    private OfflineMusicLibrary(Context app) {
        File root = new File(app.getFilesDir(), "offline-music");
        saved = new File(root, "saved"); stage = new File(root, "staging");
        prefs = app.getSharedPreferences("v90-offline-music", Context.MODE_PRIVATE);
        paused = prefs.getBoolean("paused", false);
        try { playlists = new JSONArray(prefs.getString("playlists", "[]")); }
        catch (JSONException invalid) { Log.w(TAG, "Unreadable Music playlists"); }
        try {
            JSONArray rows = new JSONArray(prefs.getString("entries", "[]"));
            for (int i = 0; i < Math.min(rows.length(), MAX_RECORDS); i++) {
                try {
                    JSONObject row = rows.getJSONObject(i);
                    Entry entry = new Entry(row, row.getString("batch"), row.getString("label"),
                            row.getString("failure"), row.optBoolean("mobile"), row.optBoolean("complete"));
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

    private File ready(Entry entry) {
        File audio = file(entry);
        return audio.isFile() && audio.length() >= 32 && audio.length() <= PreparedMusic.MAX_AUDIO ? audio : null;
    }

    synchronized File saved(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : ready(entry);
    }

    private int link() {
        Network current = network == null ? null : network.getActiveNetwork();
        NetworkCapabilities caps = current == null ? null : network.getNetworkCapabilities(current);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return 0;
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return 3;
        return network.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED ? 2 : 1;
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
                        Entry entry = new Entry(tracks.getJSONObject(i), batch, label, "", false, false);
                        if (!entry.identified()) entry = entry.result("exact", false);
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
                case "mobile": entries.replaceAll((key, entry) -> entry.batch.equals(value) ? entry.allowMobile() : entry); break;
                case "retry": {
                    Entry entry = entries.get(value);
                    if (entry != null && !value.equals(active) && retryable(entry.failure)) entries.put(value, entry.result("", false));
                    break;
                }
                case "remove": {
                    Entry entry = entries.get(value);
                    if (entry != null && !value.equals(active) && ready(entry) == null) entries.remove(value);
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
                    if (entry != null && !value.equals(active) && ready(entry) != null) {
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
                else if (entry.track.key.equals(active)) status = "working";
                else if (entry.complete) { status = "failed"; reason = "missing"; }
                else if (!entry.failure.isEmpty()) status = "failed";
                else if (paused) status = "paused";
                else if (link == 0) status = "offline";
                else if (link < 3 && (!entry.mobile || link == 1)) status = link == 1 ? "restricted" : "wifi";
                else if (cooldownUntil > SystemClock.elapsedRealtime()) status = "cooldown";
                else status = "queued";
                rows.put(new JSONObject(entry.info.toString()).put("key", entry.track.key).put("state", status)
                        .put("batch", entry.batch).put("label", entry.label).put("reason", reason)
                        .put("retryable", retryable(reason)));
            }
            lastLink = link;
            return lastSnapshot = new JSONObject().put("tracks", rows).put("paused", paused)
                    .put("playlists", playlists).put("available", downloader != null).toString();
        } catch (JSONException invalid) { return error("Your Music library could not be read."); }
    }

    private static String error(String message) { return "{\"error\":" + JSONObject.quote(message) + "}"; }

    private static boolean retryable(String reason) {
        return !"signin".equals(reason) && !"refused".equals(reason) && !"exact".equals(reason);
    }

    private synchronized void kick() {
        if (paused || !active.isEmpty() || downloader == null || V90Api.expired()
                || cooldownUntil > SystemClock.elapsedRealtime()) return;
        int link = link();
        if (link < 2) return;
        for (Entry entry : entries.values()) {
            if (entry.complete || !entry.failure.isEmpty() || ready(entry) != null || link != 3 && !entry.mobile) continue;
            active = entry.track.key;
            lastSnapshot = null;
            worker.execute(() -> download(entry));
            return;
        }
    }

    private void download(Entry entry) {
        String failure = "";
        boolean deferred = false;
        File work = new File(stage, entry.track.key);
        try {
            synchronized (this) {
                int link = link();
                Entry current = entries.get(entry.track.key);
                deferred = paused || link < 2 || link == 2 && !current.mobile || V90Api.expired();
            }
            if (!deferred) {
                if ((!work.isDirectory() && !work.mkdirs()) || (!saved.isDirectory() && !saved.mkdirs())) throw new Refusal("storage");
                File result = downloader.fetch(entry.track, work);
                if (result == null || !work.getCanonicalFile().equals(result.getCanonicalFile().getParentFile())) throw new Refusal("incomplete");
                verify(result, entry.track.seconds);
                if (!result.renameTo(file(entry))) throw new Refusal("storage");
            }
        } catch (Exception | LinkageError stopped) {
            failure = classify(stopped);
        } finally {
            if (!deferred) {
                File[] files = work.listFiles();
                if (files != null) for (File f : files) f.delete();
                work.delete();
            }
        }
        synchronized (this) {
            if (!deferred) {
                entries.put(entry.track.key, entries.get(entry.track.key).result(failure, failure.isEmpty()));
                if ("limited".equals(failure)) {
                    cooldownUntil = SystemClock.elapsedRealtime() + 60000;
                    worker.schedule(() -> { synchronized (this) { lastSnapshot = null; kick(); } }, 60, TimeUnit.SECONDS);
                }
                try { persist(); } catch (IOException | JSONException ignored) { Log.w(TAG, "Music job result could not be recorded"); }
            }
            active = "";
            lastSnapshot = null;
            kick();
        }
    }

    static String classify(Throwable error) {
        if (error instanceof Refusal) return ((Refusal) error).code;
        if (error instanceof PreparedMusic.ProviderRateLimited) return "limited";
        String problem = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (problem.contains("429") || problem.contains("too many requests")) return "limited";
        if (problem.contains("sign in") || problem.contains("sign-in") || problem.contains("not a bot") || problem.contains("login_required")) return "signin";
        if (problem.contains("no exact full recording")) return "exact";
        if (problem.contains("http error 403") || problem.contains("forbidden") || problem.contains("video unavailable") || problem.contains("did not allow")) return "refused";
        if (problem.contains("space left") || problem.contains("storage")) return "storage";
        if (problem.contains("timed out") || problem.contains("connection") || problem.contains("took too long") || problem.contains("resolve host")
                || problem.contains("no address") || error instanceof InterruptedException) return "network";
        return "helper";
    }

    private static void verify(File file, int seconds) throws Refusal {
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
