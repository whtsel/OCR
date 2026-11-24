package com.blackboxstream.mobile;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;

/** The tested upstream Android 0.18.1 runtime and the pinned zipapp; no web bridge or account state. */
final class BundledMusicRuntime implements PreparedMusic.Runtime {
    static final String PIN = "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3";
    private static final int SOURCE_HINT_LIMIT = 128;
    private static final Object SOURCE_HINT_LOCK = new Object();
    private final Context app;
    private final File root, nativeDir, script;
    private final AtomicFile sourceHints;
    private boolean ready;
    private String companion;

    BundledMusicRuntime(Context app) {
        this.app = app;
        root = new File(app.getNoBackupFilesDir(), "youtubedl-android");
        nativeDir = new File(app.getApplicationInfo().nativeLibraryDir);
        script = new File(root, "yt-dlp/yt-dlp");
        sourceHints = new AtomicFile(new File(root, "recording-sources-v1.json"));
    }

    @Override public void warm() throws Exception { start(); }

    private synchronized void start() throws Exception {
        if (ready) return;
        Log.i("BlackBoxMusic", "Initializing packaged v90 helper");
        YoutubeDL.getInstance().init(app);
        FFmpeg.getInstance().init(app);
        // Update only from our own APK; init() alone leaves a previous installation's zipapp in place.
        if (!matches(script)) {
            File tmp = new File(script.getParentFile(), "verified-ytdlp.tmp");
            try {
                try (InputStream in = app.getResources().openRawResource(R.raw.ytdlp);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
                    out.getFD().sync();
                }
                if (!matches(tmp) || !tmp.renameTo(script)) throw new IOException("Music helper checksum verification failed.");
            } finally { tmp.delete(); }
        }
        try (InputStream in = app.getResources().openRawResource(R.raw.catalogue_music)) {
            // One small packaged Python program; keep the same bound in the diagnostic assembler.
            companion = new String(TorrentMeta.slurp(in, 24 * 1024), StandardCharsets.UTF_8);
        }
        ready = true;
        Log.i("BlackBoxMusic", "Packaged v90 helper ready; pin verified");
    }

    static boolean matches(File file) throws Exception {
        if (!file.isFile() || file.length() != 3073072) return false;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1;) hash.update(buffer, 0, n);
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : hash.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
        return PIN.contentEquals(actual);
    }

    private static final String BYTE_PROGRESS = "download:BBX_MEDIA:%(progress.status)s\n"
            + "BBX_BYTES:%(progress.status)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s";

    @Override public PreparedMusic.Result fetch(PreparedMusic.Track track, File scratch, PreparedMusic.Control control) throws Exception {
        return fetch(track, scratch, control, track.source, null, false, null);
    }

    /** Resolve like v90 desktop, then save one complete file without progressive playback. */
    File saveOffline(PreparedMusic.Track track, File scratch, String pinned, String fixture,
            OfflineMusicLibrary.Progress progress) throws Exception {
        PreparedMusic.Result result = fetch(track, scratch, new PreparedMusic.Control(), pinned, fixture, true, progress);
        return result == null ? null : result.audio;
    }

    private PreparedMusic.Result fetch(PreparedMusic.Track track, File scratch, PreparedMusic.Control control,
            String directId, String fixture, boolean offline, OfflineMusicLibrary.Progress progress) throws Exception {
        if (control.cancelled) return null;
        // Download the accepted recording from the same v90 extractor session. Prefer AAC
        // when offered, no speculative second transfer, and no retry of a provider refusal.
        ArrayList<String> options = new ArrayList<>(Arrays.asList("--no-playlist", "--socket-timeout", "20",
                "--retries", "0", "--extractor-retries", "0", "--fragment-retries", "0", "--abort-on-unavailable-fragments",
                "--no-simulate", "--quiet", "--progress", "--newline", "--progress-delta", progress == null ? "1" : "0.5",
                "--progress-template", progress == null ? ProgressiveMusicAudio.PROGRESS_TEMPLATE : BYTE_PROGRESS,
                "--print", "before_dl:BBX_DOWNLOAD_START %(ext)s",
                "--max-filesize", "24M", "-f", "bestaudio[ext=m4a]/bestaudio",
                "-x", "--audio-format", "m4a", "--audio-quality", "0", "-o", new File(scratch, "audio.%(ext)s").getAbsolutePath()));
        // A fresh resolution may pick a different transfer format; never append to old audio bytes.
        if (offline) options.add("--no-continue");
        File audio = new File(scratch, "audio.m4a");
        boolean pcFixture = fixture != null && directId.isEmpty() && hasPcFixture(scratch);
        if (fixture != null && !pcFixture) {
            // Only the test APK may supply a native-generated local tone path; never a bridge URL.
            options.add("http://127.0.0.1:19090/" + fixture + ".m4a");
            execute(control, scratch, false, progress, options.toArray(new String[0]));
            return new PreparedMusic.Result(audio, "");
        }
        if (!directId.isEmpty()) {
            if (!offline) {
                int min = Math.max(1, track.seconds - 6);
                int max = track.seconds == 0 ? PreparedMusic.MAX_SECONDS : track.seconds + 6;
                options.addAll(Arrays.asList("--match-filters", "id = " + directId + " & !is_live & !was_live & duration >= " + min + " & duration <= " + max));
            }
            options.add("https://www.youtube.com/watch?v=" + directId);
            execute(control, scratch, false, progress, options.toArray(new String[0]));
            return new PreparedMusic.Result(audio, directId);
        }
        new File(scratch, "source-id").delete();
        String artist = offline ? WholeFileMusicRuntime.lookupArtist(track) : track.artist;
        String policy = pcFixture ? "pc-fixture" : offline ? "pc" : "exact";
        options.addAll(0, Arrays.asList(track.title, artist, track.album, Integer.toString(track.seconds),
                pcFixture ? "" : remembered(track.key), policy));
        execute(control, scratch, true, progress, options.toArray(new String[0]));
        if (control.cancelled) return null;
        return new PreparedMusic.Result(audio, sourceId(scratch));
    }

    static boolean hasPcFixture(File scratch) { return new File(scratch, "pc-fixture.json").isFile(); }

    static String sourceId(File scratch) throws IOException {
        try (FileInputStream in = new FileInputStream(new File(scratch, "source-id"))) {
            return new String(TorrentMeta.slurp(in, 16), StandardCharsets.US_ASCII);
        }
    }

    private static void maybeOfferStream(String line, boolean discover, File scratch, PreparedMusic.Control control) {
        if (!line.startsWith("BBX_PREFIX:downloading:") || !control.wantsStream()) return;
        ProgressiveMusicAudio.Candidate candidate = ProgressiveMusicAudio.candidate(line);
        if (candidate == null) return;
        try {
            // Catalogue entries cannot publish bytes until the exact matcher accepted this same ID.
            if (!discover || candidate.id.equals(sourceId(scratch)))
                control.offerStream(new File(scratch, "audio.m4a.part"), candidate.length, candidate.id);
        } catch (IOException ignored) { /* A missing marker never changes the complete-file path. */ }
    }

    private Map<String, String> readHints() {
        Map<String, String> hints = new LinkedHashMap<>();
        try (FileInputStream in = sourceHints.openRead()) {
            JSONArray rows = new JSONArray(new String(TorrentMeta.slurp(in, 16 * 1024), StandardCharsets.UTF_8));
            for (int i = Math.max(0, rows.length() - SOURCE_HINT_LIMIT); i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null) continue;
                String key = row.optString(0), id = row.optString(1);
                if (key.matches("[a-f0-9]{64}") && PreparedMusic.videoId(id)) hints.put(key, id);
            }
        } catch (IOException | JSONException unavailable) { /* Hints are optional and safely rebuildable. */ }
        return hints;
    }

    private String remembered(String key) {
        synchronized (SOURCE_HINT_LOCK) { return readHints().getOrDefault(key, ""); }
    }

    @Override public void rememberSource(PreparedMusic.Track track, String providerId) {
        if (!track.source.isEmpty() || !PreparedMusic.videoId(providerId)) return;
        synchronized (SOURCE_HINT_LOCK) {
            Map<String, String> hints = readHints();
            hints.remove(track.key);
            hints.put(track.key, providerId);
            while (hints.size() > SOURCE_HINT_LIMIT) hints.remove(hints.keySet().iterator().next());
            JSONArray rows = new JSONArray();
            hints.forEach((key, id) -> rows.put(new JSONArray().put(key).put(id)));
            FileOutputStream out = null;
            try {
                out = sourceHints.startWrite();
                out.write(rows.toString().getBytes(StandardCharsets.UTF_8));
                sourceHints.finishWrite(out);
            } catch (IOException failed) { sourceHints.failWrite(out); }
        }
    }

    // Parse only byte counts emitted by the CLI; an absent/estimated total stays indeterminate.
    private static void reportProgress(String line, OfflineMusicLibrary.Progress progress) {
        if (progress == null) return;
        if ("BBX_CATALOGUE_QUERY".equals(line) || "BBX_CATALOGUE_ALTERNATE".equals(line)) {
            progress.report("searching", 0, 0);
        } else if ("BBX_PC_OPEN".equals(line)) {
            progress.report("opening", 0, 0);
        } else if (line.startsWith("BBX_DOWNLOAD_START ")) {
            progress.report("downloading", 0, 0);
        } else if (line.startsWith("BBX_BYTES:") && line.length() <= 80) {
            String[] fields = line.split(":", -1);
            if (fields.length != 4) return;
            if ("downloading".equals(fields[1]) || "finished".equals(fields[1]))
                progress.report("finished".equals(fields[1]) ? "finishing" : "downloading", bytes(fields[2]), bytes(fields[3]));
        }
    }

    private static long bytes(String text) {
        if (!text.matches("[0-9]{1,12}")) return 0;
        return Long.parseLong(text);
    }

    private String execute(PreparedMusic.Control control, File scratch, boolean discover,
            OfflineMusicLibrary.Progress progress, String... args) throws Exception {
        long initAt = SystemClock.elapsedRealtime();
        try { start(); }
        catch (Exception | LinkageError failed) {
            throw new OfflineMusicLibrary.Refusal("setup", "The bundled music helper could not start on this device.");
        }
        if (control.cancelled) return "";
        if (SystemClock.elapsedRealtime() - initAt >= MusicPreparationBudget.LOOKUP_MS)
            throw new OfflineMusicLibrary.Refusal("setup-timeout", "The helper took too long to start.");
        File python = new File(root, "packages/python/usr"), ffmpeg = new File(root, "packages/ffmpeg/usr");
        ArrayList<String> command = new ArrayList<>();
        command.add(new File(nativeDir, "libpython.so").getAbsolutePath());
        if (discover) command.addAll(Arrays.asList("-c", companion));
        command.add(script.getAbsolutePath());
        int positional = discover ? 6 : 0;
        command.addAll(Arrays.asList(args).subList(0, positional));
        command.addAll(Arrays.asList("--ignore-config", "--no-update", "--no-cache-dir", "--no-plugin-dirs",
                "--no-remote-components", "--js-runtimes", "quickjs:" + new File(nativeDir, "libqjs.so").getAbsolutePath(),
                "--ffmpeg-location", new File(nativeDir, "libffmpeg.so").getAbsolutePath()));
        command.addAll(Arrays.asList(args).subList(positional, args.length));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).directory(scratch);
        Map<String, String> env = builder.environment();
        env.put("LD_LIBRARY_PATH", python + "/lib:" + ffmpeg + "/lib");
        env.put("PYTHONHOME", python.getAbsolutePath());
        env.put("SSL_CERT_FILE", python + "/etc/tls/cert.pem");
        env.put("HOME", scratch.getAbsolutePath());
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONUNBUFFERED", "1");
        env.put("TMPDIR", scratch.getAbsolutePath());
        env.put("PATH", "/system/bin:" + nativeDir.getAbsolutePath());
        boolean probe = (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && new File(app.getFilesDir(), "offline-music/provider-probe").isFile();
        env.remove("BBX_PUBLIC_PROBE");
        if (probe) env.put("BBX_PUBLIC_PROBE", "1");
        control.stage(discover ? "helper ready, search" : "helper ready, resolve");
        Process child;
        try { child = builder.start(); } catch (IOException denied) {
            throw new OfflineMusicLibrary.Refusal("setup", "Android could not launch the bundled music runtime.");
        }
        // Local initialization and waiting for the helper lock do not spend an Offline lookup's
        // provider deadline. Keep the existing absolute selection guard for the streaming preview.
        MusicPreparationBudget budget = new MusicPreparationBudget(progress == null ? initAt : SystemClock.elapsedRealtime());
        control.attach(child);
        StringBuffer captured = new StringBuffer();
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = lines.readLine()) != null;) {
                    MusicPreparationBudget.Phase phase = budget.marker(line, SystemClock.elapsedRealtime());
                    if (phase == MusicPreparationBudget.Phase.TRANSFER) control.stage("resolved, transfer audio");
                    else if (phase == MusicPreparationBudget.Phase.FINALIZE) control.stage("downloaded, finalize audio");
                    else if ("BBX_CATALOGUE_QUERY".equals(line)) control.stage("search catalogue");
                    else if ("BBX_CATALOGUE_ALTERNATE".equals(line)) control.stage("search alternate wording");
                    else if ("BBX_PC_OPEN".equals(line)) control.stage("open selected source");
                    else if ("BBX_CATALOGUE_VERIFY".equals(line)) control.stage("verify source metadata");
                    if (line.startsWith("BBX_PUBLIC_PROBE:")) {
                        if (probe && line.length() <= 1000) Log.i("BlackBoxMusic", line);
                        continue; // Diagnostics must never influence the failure classification.
                    }
                    if (progress == null) maybeOfferStream(line, discover, scratch, control);
                    else reportProgress(line, progress);
                    // Capture bounded output for diagnostics; never impose a line reading limit on the child.
                    int keep = Math.min(line.length(), 65536 - captured.length());
                    if (keep > 0) captured.append(line, 0, keep).append('\n');
                }
            } catch (IOException stopped) { /* Normal on cancellation. */ }
        }, "music-helper-output");
        reader.setDaemon(true);
        reader.start();
        try {
            for (;;) {
                try { child.exitValue(); break; }
                catch (IllegalThreadStateException stillRunning) { Thread.sleep(200); }
                if (control.cancelled) return "";
                long bytes = 0;
                File[] files = scratch.listFiles();
                if (files != null) for (File f : files) if (f.isFile() && f.getName().startsWith("audio.")) {
                    long length = f.length();
                    if (length > PreparedMusic.MAX_AUDIO)
                        throw new OfflineMusicLibrary.Refusal("size", "This recording is above the preparation size limit.");
                    bytes += length;
                }
                long now = SystemClock.elapsedRealtime();
                budget.sawBytes(bytes, now);
                MusicPreparationBudget.Limit timeout = budget.timeout(now);
                if (timeout != null) throw new OfflineMusicLibrary.Refusal(timeout.code, timeout.message);
            }
            reader.join(2000);
            if (control.cancelled) return "";
            String output = captured.toString();
            if (child.exitValue() != 0) throw providerFailure(output, budget.currentPhase());
            return output;
        } finally {
            child.destroy();
            control.detach();
        }
    }

    static IOException providerFailure(String output, MusicPreparationBudget.Phase phase) {
        // Use only failure lines and fixed companion markers, not progress or upload titles.
        StringBuilder problems = new StringBuilder();
        for (String line : output.split("\n")) {
            if (line.startsWith("ERROR:") || line.startsWith("BBX_PROVIDER_") || line.equals("BBX_NO_EXACT_RECORDING")
                    || line.equals("BBX_NO_PC_MATCH"))
                problems.append(line).append('\n');
        }
        String message = problems.toString().toLowerCase(Locale.ROOT);
        if (message.contains("bbx_provider_limited") || message.contains("http error 429") || message.contains("too many requests"))
            return new PreparedMusic.ProviderRateLimited();
        if (message.contains("bbx_provider_signin") || message.contains("http error 401") || message.contains("sign in")
                || message.contains("sign-in") || message.contains("not a bot") || message.contains("login_required")
                || message.contains("login required"))
            return new OfflineMusicLibrary.Refusal("signin", "The provider requires sign-in. No account session was used.");
        if (message.contains("bbx_provider_refused") || message.contains("http error 403") || message.contains("video unavailable")
                || message.contains("private video") || message.contains("video is private") || message.contains("music premium")
                || message.contains("premium members") || message.contains("members-only") || message.contains("members only")
                || message.contains("subscriber-only") || message.contains("not available in your country")
                || message.contains("not available in your region"))
            return new OfflineMusicLibrary.Refusal("refused", "The provider did not allow this recording.");
        if (message.contains("bbx_no_pc_match"))
            return new OfflineMusicLibrary.Refusal("no-source", "No matching source was returned by the provider.");
        if (message.contains("bbx_no_exact_recording"))
            return new OfflineMusicLibrary.Refusal("exact", "No exact full recording could be verified. Nothing else was played.");
        if (message.contains("no space left"))
            return new OfflineMusicLibrary.Refusal("storage", "There is not enough storage to save this recording.");
        if (phase == MusicPreparationBudget.Phase.FINALIZE)
            return new OfflineMusicLibrary.Refusal("finalize", "The helper could not finish the audio file.");
        if (message.contains("timed out") || message.contains("failed to resolve") || message.contains("no address")
                || message.contains("connection reset") || message.contains("remote end closed") || message.contains("incomplete read")
                || message.contains("content too short")) {
            if (phase == MusicPreparationBudget.Phase.TRANSFER)
                return new OfflineMusicLibrary.Refusal("transfer-io", "The audio transfer was interrupted. Nothing played.");
            return new OfflineMusicLibrary.Refusal("lookup-io", "The provider lookup was interrupted. Nothing played.");
        }
        return new OfflineMusicLibrary.Refusal("helper", "The helper could not prepare this exact recording.");
    }
}
