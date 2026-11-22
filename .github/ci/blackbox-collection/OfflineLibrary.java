package com.blackbox.stream.collectionpilot;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A durable queue around the unchanged, phone-tested p11 whole-file helper. */
final class OfflineLibrary {
    static final String TAG = "BlackBoxOfflineMusic";
    private static final long MAX_BYTES = 24 * 1024 * 1024L;
    private static OfflineLibrary instance;

    static synchronized OfflineLibrary open(Context context) throws IOException, JSONException {
        if (instance == null) instance = new OfflineLibrary(context.getApplicationContext());
        return instance;
    }

    private static final class Track {
        final JSONObject info;
        final String key, id;
        final int seconds;

        Track(JSONObject row) throws JSONException {
            info = row;
            key = row.getString("key");
            id = row.getString("id");
            seconds = row.getInt("seconds");
            if (!key.matches("[a-z]{3,18}") || !id.matches("[A-Za-z0-9_-]{11}") || seconds < 12 || seconds > 300) {
                throw new JSONException("Invalid pilot recording");
            }
        }
    }

    private static final class Job {
        final Track track;
        final String batch;
        boolean allowMobile;
        String failure = "";

        Job(Track track, String batch) { this.track = track; this.batch = batch; }
    }

    private final Context app;
    private final SharedPreferences preferences;
    private final ConnectivityManager network;
    private final File collection, staging;
    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private final Map<String, Job> queue = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final boolean fixtures;
    private boolean paused;
    private Job active;
    private ArchivedHelper helper;

    private OfflineLibrary(Context app) throws IOException, JSONException {
        this.app = app;
        collection = new File(app.getFilesDir(), "collection-audio");
        staging = new File(app.getFilesDir(), "media-helper/audio");
        JSONObject profile;
        try (InputStream input = app.getAssets().open("collection/catalogue.json")) {
            byte[] bytes = new byte[4096];
            int size = 0, n;
            while (size < bytes.length && (n = input.read(bytes, size, bytes.length - size)) != -1) size += n;
            if (size == bytes.length) throw new IOException("Pilot catalogue too large");
            profile = new JSONObject(new String(bytes, 0, size, StandardCharsets.UTF_8));
        }
        fixtures = profile.optBoolean("fixtures");
        JSONArray catalogue = profile.getJSONArray("tracks");
        if (catalogue.length() != 2) throw new JSONException("The pilot uses two recordings");
        for (int i = 0; i < catalogue.length(); i++) {
            Track track = new Track(catalogue.getJSONObject(i));
            tracks.put(track.key, track);
        }
        if (tracks.size() != 2) throw new JSONException("Duplicate pilot keys");
        preferences = app.getSharedPreferences("offline_music", Context.MODE_PRIVATE);
        paused = preferences.getBoolean("paused", false);
        JSONArray pending = new JSONArray(preferences.getString("jobs", "[]"));
        for (int i = 0; i < pending.length(); i++) {
            JSONObject row = pending.getJSONObject(i);
            Track track = tracks.get(row.optString("key"));
            String batch = row.optString("batch");
            if (track == null || !batch.matches("[a-f0-9]{16}") || saved(track) != null) continue;
            Job job = new Job(track, batch);
            job.failure = row.optString("failure");
            job.allowMobile = row.optBoolean("mobile");
            queue.put(track.key, job);
        }
        network = app.getSystemService(ConnectivityManager.class);
        if (network != null) network.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network link) { kick(); }
            @Override public void onCapabilitiesChanged(Network link, NetworkCapabilities caps) { kick(); }
        });
        kick();
    }

    private File file(Track track) { return new File(collection, track.id + ".m4a"); }

    private File saved(Track track) {
        File file = file(track);
        return file.isFile() && file.length() > 0 && file.length() <= MAX_BYTES ? file : null;
    }

    synchronized File saved(String key) {
        Track track = tracks.get(key);
        return track == null ? null : saved(track);
    }

    private int link() {
        Network selected = network == null ? null : network.getActiveNetwork();
        NetworkCapabilities caps = selected == null ? null : network.getNetworkCapabilities(selected);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return 0;
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ? 2 : 1;
    }

    private void persist() throws JSONException, IOException {
        JSONArray pending = new JSONArray();
        for (Job job : queue.values()) pending.put(new JSONObject().put("key", job.track.key)
                .put("batch", job.batch).put("mobile", job.allowMobile).put("failure", job.failure));
        if (!preferences.edit().putString("jobs", pending.toString()).putBoolean("paused", paused).commit()) {
            throw new IOException("Unable to record download queue");
        }
    }

    synchronized String command(String action, String value) {
        if (action == null || value == null || value.length() > 128) return snapshot();
        try {
            switch (action) {
                case "enqueue": {
                    JSONArray keys = new JSONArray(value);
                    if (keys.length() > tracks.size()) break;
                    String batch = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    for (int i = 0; i < keys.length(); i++) {
                        Track track = tracks.get(keys.optString(i));
                        if (track != null && saved(track) == null && !queue.containsKey(track.key)) {
                            queue.put(track.key, new Job(track, batch));
                        }
                    }
                    break;
                }
                case "pause": paused = true; break;
                case "resume": paused = false; break;
                case "mobile":
                    for (Job job : queue.values()) if (job.batch.equals(value)) job.allowMobile = true;
                    break;
                case "retry": {
                    Job job = queue.get(value);
                    if (job != null && job != active && DownloadFailure.retryable(job.failure)) job.failure = "";
                    break;
                }
                case "remove": {
                    Job job = queue.get(value);
                    if (job != null && job != active) queue.remove(value);
                    break;
                }
                default: return snapshot();
            }
            persist();
            kick();
            return snapshot();
        } catch (IOException | JSONException trouble) {
            Log.w(TAG, "Could not update the download list");
            return "{\"error\":\"The download list could not be saved. Check your storage.\"}";
        }
    }

    synchronized String snapshot() {
        try {
            int link = link();
            JSONArray library = new JSONArray(), pending = new JSONArray();
            for (Track track : tracks.values()) {
                JSONObject info = new JSONObject(track.info.toString());
                info.remove("id");
                library.put(info.put("saved", saved(track) != null));
            }
            for (Job job : queue.values()) {
                String state = job == active ? "working" : !job.failure.isEmpty() ? "failed" : paused ? "paused"
                        : link == 0 ? "offline" : link == 1 && !job.allowMobile ? "wifi" : "queued";
                pending.put(new JSONObject().put("key", job.track.key).put("batch", job.batch)
                        .put("state", state).put("reason", job.failure).put("mobile", job.allowMobile)
                        .put("retryable", DownloadFailure.retryable(job.failure)));
            }
            return new JSONObject().put("tracks", library).put("jobs", pending).put("paused", paused)
                    .put("fixtures", fixtures).toString();
        } catch (JSONException invalid) {
            return "{\"error\":\"Your collection could not be read.\"}";
        }
    }

    private synchronized void kick() {
        if (paused || active != null) return;
        int link = link();
        if (link == 0) return;
        for (Job job : queue.values()) {
            if (job.failure.isEmpty() && (link == 2 || job.allowMobile)) {
                active = job;
                worker.execute(() -> prepare(job));
                return;
            }
        }
    }

    private void prepare(Job job) {
        String failure = "";
        boolean deferred = false;
        try {
            Track track = job.track;
            if (saved(track) == null) {
                if (helper == null) helper = new ArchivedHelper(app);
                // Recheck after the potentially slow first init, before starting a provider request.
                synchronized (this) {
                    int link = link();
                    deferred = paused || link == 0 || (link == 1 && !job.allowMobile);
                }
                if (!deferred) {
                    File candidate = new File(staging, track.id + ".m4a");
                    purgeStagedExtras(track, candidate);
                    // The x86 smoke sibling uses cache hits, with no provider calls.
                    if (fixtures && !candidate.isFile()) throw new IncompleteAudio();
                    File audio = helper.audio(track.id);
                    if (audio == null || !audio.getCanonicalFile().equals(candidate.getCanonicalFile())) {
                        throw new IncompleteAudio();
                    }
                    try { verify(audio, track.seconds); }
                    catch (IncompleteAudio invalid) {
                        // Only discard this pilot's rejected staging output, never files in the saved collection.
                        audio.delete();
                        throw invalid;
                    }
                    if ((!collection.isDirectory() && !collection.mkdirs()) || !audio.renameTo(file(track))) {
                        throw new IOException("Unable to save to the collection");
                    }
                }
            }
        } catch (IncompleteAudio invalid) {
            failure = "incomplete";
        } catch (Exception | LinkageError error) {
            failure = DownloadFailure.classify(error);
        }
        synchronized (this) {
            if (!deferred) {
                if (failure.isEmpty()) queue.remove(job.track.key);
                else job.failure = failure;
            }
            active = null;
            try { persist(); } catch (IOException | JSONException ignored) { Log.w(TAG, "Queue state not written"); }
            if (fixtures) Log.i(TAG, "fixture " + job.track.key + ": " + (deferred ? "deferred" : failure.isEmpty() ? "saved" : failure));
            kick();
        }
    }

    private void purgeStagedExtras(Track track, File candidate) throws IOException {
        // The old cache lookup accepts any prefix match except .part and .ytdl. After a process kill,
        // ignore (and remove) other containers or unfinished conversions for only this pinned ID.
        File[] extras = staging.listFiles((dir, name) -> name.startsWith(track.id + ".")
                && !name.equals(candidate.getName()) && !name.endsWith(".part") && !name.endsWith(".ytdl"));
        if (extras != null) for (File extra : extras) {
            if (!extra.delete()) throw new IOException("Unable to clear staging storage");
        }
    }

    private static final class IncompleteAudio extends IOException {}

    private static void verify(File file, int seconds) throws IncompleteAudio {
        long length = file.length();
        if (length < 32 || length > MAX_BYTES) throw new IncompleteAudio();
        // A fast-start header alone isn't a full recording. Check that the top-level MP4 boxes fit.
        try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
            boolean moov = false, mdat = false;
            long start = 0;
            for (int boxes = 0; start < length && boxes < 1024; boxes++) {
                data.seek(start);
                long size = data.readInt() & 0xffffffffL;
                int type = data.readInt(), header = 8;
                if (size == 1) { size = data.readLong(); header = 16; }
                if (size < header || size > length - start) throw new IncompleteAudio();
                if (type == 0x6d6f6f76 && size > header) moov = true;
                if (type == 0x6d646174 && size > header) mdat = true;
                start += size;
            }
            if (start != length || !moov || !mdat) throw new IncompleteAudio();
        } catch (IOException error) { throw new IncompleteAudio(); }
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(file.getAbsolutePath());
            long duration = Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (!"yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    || "yes".equals(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                    || Math.abs(duration - seconds * 1000L) > 6000) throw new IncompleteAudio();
        } catch (RuntimeException error) { throw new IncompleteAudio(); }
        finally { try { reader.release(); } catch (Exception ignored) {} }
    }

    /** No replacement downloader or new extraction flags. The archived class is in classes.dex verbatim. */
    private static final class ArchivedHelper {
        final Object instance;
        final Method audio;

        ArchivedHelper(Context context) throws ReflectiveOperationException, IOException {
            Class<?> type = Class.forName("com.blackbox.stream.MainActivity$BundledHelper");
            Constructor<?> create = type.getDeclaredConstructor(Context.class);
            create.setAccessible(true);
            instance = create.newInstance(context);
            File zipapp = new File(context.getNoBackupFilesDir(), "youtubedl-android/yt-dlp/yt-dlp");
            // Recover an interrupted first extraction. The original library copies its resource only if absent.
            if (zipapp.exists() && !pinned(zipapp) && !zipapp.delete()) throw new IOException("Unable to repair helper storage");
            method(type, "start").invoke(instance);
            if (!Boolean.TRUE.equals(method(type, "ready").invoke(instance)) || !pinned(zipapp)) {
                throw new IOException("The shipped audio helper was not ready");
            }
            audio = method(type, "audio", String.class);
            Log.i(TAG, "p11 whole-file helper ready · verified 2026.10.26");
        }

        private static boolean pinned(File file) throws IOException {
            if (!file.isFile() || file.length() != 3073072L) return false;
            MessageDigest sha;
            try { sha = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
            try (InputStream input = new FileInputStream(file)) {
                byte[] chunk = new byte[32768];
                int read;
                while ((read = input.read(chunk)) != -1) sha.update(chunk, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3".contentEquals(hex);
        }

        File audio(String id) throws ReflectiveOperationException { return (File) audio.invoke(instance, id); }

        private static Method method(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
            Method method = type.getDeclaredMethod(name, params);
            method.setAccessible(true);
            return method;
        }
    }
}
