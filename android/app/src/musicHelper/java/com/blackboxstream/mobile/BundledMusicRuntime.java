package com.blackboxstream.mobile;

import android.content.Context;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** The tested upstream Android 0.18.1 runtime and the pinned zipapp; no web bridge or account state. */
final class BundledMusicRuntime implements PreparedMusic.Runtime {
    static final String PIN = "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3";
    private final Context app;
    private final File root, nativeDir, script;
    private boolean ready;
    private String companion;

    BundledMusicRuntime(Context app) {
        this.app = app;
        root = new File(app.getNoBackupFilesDir(), "youtubedl-android");
        nativeDir = new File(app.getApplicationInfo().nativeLibraryDir);
        script = new File(root, "yt-dlp/yt-dlp");
    }

    private synchronized void start() throws Exception {
        if (ready) return;
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
            companion = new String(TorrentMeta.slurp(in, 16 * 1024), StandardCharsets.UTF_8);
        }
        ready = true;
    }

    private static boolean matches(File file) throws Exception {
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

    @Override public PreparedMusic.Result fetch(PreparedMusic.Track track, File scratch, PreparedMusic.Control control) throws Exception {
        if (control.cancelled) return null;
        ArrayList<String> options = new ArrayList<>(Arrays.asList("--no-playlist", "--no-progress", "--socket-timeout", "12",
                "--retries", "1", "--fragment-retries", "1", "--max-filesize", "24M", "-f", "bestaudio[ext=m4a]/bestaudio",
                "-x", "--audio-format", "m4a", "--audio-quality", "0", "-o", new File(scratch, "audio.%(ext)s").getAbsolutePath()));
        File audio = new File(scratch, "audio.m4a");
        if (!track.source.isEmpty()) {
            int min = Math.max(1, track.seconds - 6);
            int max = track.seconds == 0 ? PreparedMusic.MAX_SECONDS : track.seconds + 6;
            options.addAll(Arrays.asList("--match-filters", "id = " + track.source + " & !is_live & !was_live & duration >= " + min + " & duration <= " + max,
                    "https://www.youtube.com/watch?v=" + track.source));
            run(control, scratch, options.toArray(new String[0]));
            return new PreparedMusic.Result(audio, track.source);
        }
        // The companion is bundled code. Recording text is passed only as positional arguments.
        options.addAll(0, Arrays.asList(track.title, track.artist, track.album, Integer.toString(track.seconds)));
        execute(control, scratch, true, options.toArray(new String[0]));
        if (control.cancelled) return null;
        try (FileInputStream in = new FileInputStream(new File(scratch, "source-id"))) {
            return new PreparedMusic.Result(audio, new String(TorrentMeta.slurp(in, 16), StandardCharsets.US_ASCII));
        }
    }

    private String run(PreparedMusic.Control control, File scratch, String... args) throws Exception {
        return execute(control, scratch, false, args);
    }

    private String execute(PreparedMusic.Control control, File scratch, boolean discover, String... args) throws Exception {
        try { start(); }
        catch (Exception | LinkageError failed) { throw new IOException("The bundled music helper could not start on this device."); }
        if (control.cancelled) return "";
        File python = new File(root, "packages/python/usr"), ffmpeg = new File(root, "packages/ffmpeg/usr");
        ArrayList<String> command = new ArrayList<>();
        command.add(new File(nativeDir, "libpython.so").getAbsolutePath());
        if (discover) command.addAll(Arrays.asList("-c", companion));
        command.add(script.getAbsolutePath());
        int positional = discover ? 4 : 0;
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
        env.put("TMPDIR", scratch.getAbsolutePath());
        env.put("PATH", "/system/bin:" + nativeDir.getAbsolutePath());
        Process child;
        try { child = builder.start(); } catch (IOException denied) { throw new IOException("Android could not launch the bundled music runtime."); }
        control.attach(child);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = child.getInputStream()) {
                byte[] chunk = new byte[4096];
                for (int n; (n = in.read(chunk)) != -1;) {
                    int keep = Math.min(n, 65536 - captured.size());
                    if (keep > 0) captured.write(chunk, 0, keep);
                }
            } catch (IOException stopped) { /* Normal on cancellation. */ }
        }, "music-helper-output");
        reader.setDaemon(true);
        reader.start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(PreparedMusic.PREPARE_TIMEOUT_SECONDS);
            File match = discover ? new File(scratch, "source-id") : null;
            for (;;) {
                try { child.exitValue(); break; }
                catch (IllegalThreadStateException stillRunning) { Thread.sleep(200); }
                if (control.cancelled) return "";
                if (System.nanoTime() >= until) throw new IOException("The recording took too long to prepare.");
                if (match != null && match.isFile()) control.phase = "preparing";
                File[] files = scratch.listFiles();
                if (files != null) for (File f : files) if (f.length() > PreparedMusic.MAX_AUDIO)
                    throw new IOException("This recording is above the preparation size limit.");
            }
            reader.join(2000);
            if (control.cancelled) return "";
            String output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
            if (child.exitValue() != 0) throw new IOException(providerFailure(output));
            return output;
        } finally {
            child.destroy();
            control.detach();
        }
    }

    private static String providerFailure(String output) {
        String message = output.toLowerCase(Locale.ROOT);
        if (message.contains("sign in") || message.contains("not a bot")) return "The provider requires sign-in. No account session was used.";
        if (message.contains("http error 429")) return "The provider rate-limited this recording. Try again later.";
        if (message.contains("http error 403") || message.contains("unavailable")) return "The provider did not allow this recording.";
        if (message.contains("bbx_no_exact_recording")) return "No exact full recording could be verified. Link its exact source.";
        if (message.contains("timed out") || message.contains("resolve") || message.contains("no address")) return "Could not complete the connection to the provider.";
        return "The helper could not prepare this exact recording.";
    }
}
