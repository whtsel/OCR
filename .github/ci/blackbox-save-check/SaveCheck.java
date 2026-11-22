package com.blackboxstream.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

/** One fresh trial per arm, in a separate APK. No queue, fallback, or retry on launch. */
final class SaveCheck {
    static final String VIDEO = "51zjlMhdSTE";
    static final int SECONDS = 175;
    private static final long STOP_AFTER_MS = 275_000;
    private static final String PROCESS = UUID.randomUUID().toString();
    private static SaveCheck instance;

    enum Arm {
        PILOT("pilot", "Pilot"), FULL("full", "Full v90");
        final String key, label;
        Arm(String key, String label) { this.key = key; this.label = label; }
    }

    static final class Link {
        final long handle;
        final String label;
        final boolean validated;
        Link(long handle, String label, boolean validated) {
            this.handle = handle; this.label = label; this.validated = validated;
        }
        String caption() { return label + (handle == 0 || validated ? "" : " (Internet not confirmed)"); }
    }

    private static final class Job {
        final Arm arm;
        final Link link;
        final long started = SystemClock.elapsedRealtime();
        Thread thread;
        String stop = "", phase = "setup";
        long bytes, total;
        boolean changedLink;
        Job(Arm arm, Link link) { this.arm = arm; this.link = link; }
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final ConnectivityManager network;
    private final File home;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor();
    private final EnumMap<Arm, String> errors = new EnumMap<>(Arm.class);
    private Job active;

    static synchronized SaveCheck open(Context context) {
        if (instance == null) instance = new SaveCheck(context.getApplicationContext());
        return instance;
    }

    private SaveCheck(Context context) {
        app = context;
        home = new File(app.getFilesDir(), "save-check");
        prefs = app.getSharedPreferences("save-check", Context.MODE_PRIVATE);
        network = app.getSystemService(ConnectivityManager.class);
        for (Arm arm : Arm.values()) {
            if ("running".equals(state(arm))) {
                // An interrupted attempt is not another permission to contact the provider.
                prefs.edit().putString(arm.key + ".state", "failed")
                        .putString(arm.key + ".reason", "interrupted").commit();
            }
        }
    }

    Link link() {
        Network selected = network == null ? null : network.getActiveNetwork();
        NetworkCapabilities caps = selected == null ? null : network.getNetworkCapabilities(selected);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return new Link(0, "Offline", false);
        }
        String name = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Internet connection";
        return new Link(selected.getNetworkHandle(), name, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    }

    synchronized boolean running() { return active != null; }

    private String state(Arm arm) { return prefs.getString(arm.key + ".state", "idle"); }

    private boolean providerStopped() {
        for (Arm arm : Arm.values()) {
            String why = prefs.getString(arm.key + ".reason", "");
            if ("signin".equals(why) || "limited".equals(why) || "refused".equals(why) || "unavailable".equals(why)) return true;
        }
        return false;
    }

    synchronized String gate(Link link) {
        if (V90Api.expired()) return "This v90 test build has ended. Nothing was deleted.";
        if (active != null) return "One test is running. Keep this screen open.";
        if (providerStopped()) return "Comparison stopped after a provider refusal. Share the result; do not retry it here.";
        if (!"idle".equals(state(Arm.PILOT)) && !"idle".equals(state(Arm.FULL))) return "Both test slots used. No requests will be repeated.";
        if (link.handle == 0) return "Connect to the Internet to start a save. Saved files can be played offline.";
        long previous = prefs.getLong("network", 0);
        if (previous != 0 && previous != link.handle) return "The connection changed. This comparison cannot continue on a different connection.";
        return "Run both saves on this same connection before checking offline playback.";
    }

    synchronized boolean canStart(Arm arm, Link link) {
        long previous = prefs.getLong("network", 0);
        return !V90Api.expired() && active == null && "idle".equals(state(arm)) && !errors.containsKey(arm)
                && !providerStopped() && link.handle != 0 && (previous == 0 || previous == link.handle);
    }

    synchronized void begin(Arm arm) {
        Link now = link();
        if (!canStart(arm, now)) return;
        if (!prefs.edit().putLong("network", now.handle).putString(arm.key + ".link", now.caption())
                .putString(arm.key + ".state", "running").putString(arm.key + ".phase", "setup").commit()) {
            errors.put(arm, "Storage error; no request started.");
            return;
        }
        Job job = new Job(arm, now);
        active = job;
        worker.execute(() -> run(job));
    }

    synchronized void stop() {
        if (active != null) interrupt(active, "cancelled");
    }

    private void interrupt(Job job, String reason) {
        if (job.stop.isEmpty()) job.stop = reason;
        if (job.thread != null) job.thread.interrupt();
    }

    private File output(Arm arm) { return new File(home, arm.key + ".m4a"); }

    private File staging(Arm arm) {
        return arm == Arm.PILOT ? new File(app.getFilesDir(), "media-helper/audio") : new File(home, "full-stage");
    }

    private static void folder(File path) throws IOException {
        if (!path.isDirectory() && !path.mkdirs()) throw new OfflineMusicLibrary.Refusal("storage");
    }

    private static void empty(File path) throws IOException {
        folder(path);
        String[] names = path.list();
        if (names == null || names.length != 0) throw new OfflineMusicLibrary.Refusal("not-fresh");
    }

    private synchronized void phase(Job job, String stage, long bytes, long total) {
        if (active != job) return;
        job.phase = stage;
        job.bytes = Math.max(job.bytes, bytes);
        if (total > 0) job.total = total;
        if (job.total < job.bytes) job.total = 0;
    }

    private synchronized void tick(Job job, File stage) {
        if (active != job) return;
        if (link().handle != job.link.handle) job.changedLink = true;
        if (job.arm == Arm.PILOT && ("source".equals(job.phase) || "transfer".equals(job.phase))) {
            // The unmodified p11 method has no progress callback. Report only bytes observed on disk.
            File[] files = stage.listFiles((dir, name) -> name.startsWith(VIDEO + ".") && !name.endsWith(".ytdl"));
            if (files != null) for (File file : files) if (file.isFile() && file.length() > 0) {
                phase(job, "transfer", file.length(), 0);
            }
        }
        if (SystemClock.elapsedRealtime() - job.started >= STOP_AFTER_MS) interrupt(job, "timeout");
    }

    private synchronized void beforeRequest(Job job) throws IOException {
        if (!job.stop.isEmpty()) throw new OfflineMusicLibrary.Refusal(job.stop);
        if (link().handle != job.link.handle) throw new OfflineMusicLibrary.Refusal("network-changed");
        if (V90Api.expired()) throw new OfflineMusicLibrary.Refusal("expired");
        phase(job, "source", 0, 0);
    }

    private void run(Job job) {
        File stage = staging(job.arm);
        File published = output(job.arm);
        String reason = "helper";
        boolean ownsStage = false;
        long readyBytes = 0;
        synchronized (this) { job.thread = Thread.currentThread(); }
        ScheduledFuture<?> monitor = watch.scheduleWithFixedDelay(() -> tick(job, stage), 250, 250, TimeUnit.MILLISECONDS);
        try {
            folder(home);
            if (published.exists()) throw new OfflineMusicLibrary.Refusal("not-fresh");
            empty(stage);
            ownsStage = true;
            File expected = new File(stage, job.arm == Arm.PILOT ? VIDEO + ".m4a" : "audio.m4a");
            File result = job.arm == Arm.PILOT ? pilot(job, expected) : full(job, stage);
            if (result == null || !expected.getCanonicalFile().equals(result.getCanonicalFile())) {
                throw new OfflineMusicLibrary.Refusal("incomplete");
            }
            phase(job, "verify", result.length(), 0);
            OfflineMusicLibrary.verify(result, SECONDS);
            synchronized (this) {
                if (!job.stop.isEmpty()) throw new OfflineMusicLibrary.Refusal(job.stop);
                if (!result.renameTo(published)) throw new OfflineMusicLibrary.Refusal("storage");
                readyBytes = published.length();
                reason = "";
            }
        } catch (Exception | LinkageError failure) {
            reason = classify(failure);
        } finally {
            monitor.cancel(false);
            // Only clean the private staging directory that this attempt proved empty before writing.
            File[] leftovers = ownsStage ? stage.listFiles() : null;
            if (leftovers != null) for (File file : leftovers) file.delete();
            finish(job, reason, readyBytes);
        }
    }

    private File pilot(Job job, File expected) throws Exception {
        Class<?> type = Class.forName("com.blackbox.stream.MainActivity$BundledHelper");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        Object helper = constructor.newInstance(app);
        method(type, "start").invoke(helper);
        if (!Boolean.TRUE.equals(method(type, "ready").invoke(helper)) || !BundledMusicRuntime.matches(pinnedScript())) {
            throw new OfflineMusicLibrary.Refusal("setup");
        }
        beforeRequest(job);
        // This code is compiled out of the ARM64 app. The x86 sibling checks the p11 local-file branch only.
        if (SaveCheckBuild.FIXTURES) {
            File fixture = new File(app.getFilesDir(), "save-check-fixtures/pilot.m4a");
            if (!fixture.isFile() || fixture.length() > PreparedMusic.MAX_AUDIO)
                throw new OfflineMusicLibrary.Refusal("fixture");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(expected)) {
                Files.copy(fixture.toPath(), out);
            }
        }
        return (File) method(type, "audio", String.class).invoke(helper, VIDEO);
    }

    private static Method method(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    private File pinnedScript() { return new File(app.getNoBackupFilesDir(), "youtubedl-android/yt-dlp/yt-dlp"); }

    private File full(Job job, File stage) throws Exception {
        PreparedMusic.Track track = new PreparedMusic.Track(new JSONObject().put("ref", "album:1752214909:1:7")
                .put("title", "Espresso").put("artist", "Sabrina Carpenter").put("album", "Short n' Sweet")
                .put("seconds", SECONDS).put("source", ""));
        if (!VIDEO.equals(WholeFileMusicRuntime.pinned(track))) throw new OfflineMusicLibrary.Refusal("metadata");
        if (SaveCheckBuild.FIXTURES) {
            File flag = new File(app.getFilesDir(), "offline-music/fixture-only");
            folder(flag.getParentFile());
            if (!flag.isFile() && !flag.createNewFile()) throw new OfflineMusicLibrary.Refusal("fixture");
        }
        beforeRequest(job);
        return new WholeFileMusicRuntime(app).fetch(track, stage, (part, bytes, total) -> {
            if ("downloading".equals(part)) phase(job, "transfer", bytes, total);
            else if ("finishing".equals(part)) phase(job, "finish", bytes, total);
        });
    }

    private static String classify(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                ? failure.getCause() : failure;
        String text = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("video unavailable") || text.contains("private video") || text.contains("has been removed")) return "unavailable";
        if (text.contains("could not start on this device") || text.contains("could not launch the bundled")) return "setup";
        return OfflineMusicLibrary.classify(cause);
    }

    private synchronized void finish(Job job, String reason, long readyBytes) {
        if (link().handle != job.link.handle) job.changedLink = true;
        // A refusal always wins over a late manual stop. Otherwise preserve the user's stop/deadline reason.
        if (!job.stop.isEmpty() && !reason.isEmpty() && !"signin".equals(reason) && !"limited".equals(reason)
                && !"refused".equals(reason) && !"unavailable".equals(reason)) reason = job.stop;
        String prefix = job.arm.key + ".";
        if (!prefs.edit().putString(prefix + "state", reason.isEmpty() ? "saved" : "failed")
                .putString(prefix + "phase", job.phase).putString(prefix + "reason", reason)
                .putString(prefix + "process", PROCESS).putLong(prefix + "ms", SystemClock.elapsedRealtime() - job.started)
                .putLong(prefix + "bytes", reason.isEmpty() ? readyBytes : job.bytes)
                .putBoolean(prefix + "linkChanged", job.changedLink).commit()) {
            errors.put(job.arm, "Result storage failed. Do not repeat this request.");
        }
        active = null;
        // Don't leave an interrupt set on a pooled thread.
        Thread.interrupted();
    }

    synchronized File saved(Arm arm) {
        if (!"saved".equals(state(arm))) return null;
        File file = output(arm);
        return file.isFile() && file.length() >= 32 && file.length() == prefs.getLong(arm.key + ".bytes", 0) ? file : null;
    }

    synchronized boolean cold(Arm arm) {
        return saved(arm) != null && !PROCESS.equals(prefs.getString(arm.key + ".process", PROCESS));
    }

    synchronized boolean markOfflinePass(Arm arm) {
        return cold(arm) && link().handle == 0 && prefs.edit().putBoolean(arm.key + ".offline", true).commit();
    }

    synchronized String result(Arm arm) {
        if (errors.containsKey(arm)) return errors.get(arm);
        String prefix = arm.key + ".";
        String state = state(arm);
        if ("idle".equals(state)) return "Not tried · no saved test audio";
        if (active != null && active.arm == arm) {
            return (active.stop.isEmpty() ? stage(active.phase) : "Stopping safely") + " · "
                    + age(SystemClock.elapsedRealtime() - active.started)
                    + size(active.bytes, active.total, arm) + "\n" + active.link.caption();
        }
        String summary;
        if ("saved".equals(state)) {
            summary = saved(arm) == null ? "Saved file missing" : "Saved · audio verified";
            if (prefs.getBoolean(prefix + "offline", false) && saved(arm) != null) summary += "\nCold offline seek passed";
        } else if ("running".equals(state)) summary = "Previous attempt stopped before a result was recorded";
        else summary = reasonLabel(prefs.getString(prefix + "reason", "helper")) + "\nLast stage: " + stage(prefs.getString(prefix + "phase", "setup"));
        long ms = prefs.getLong(prefix + "ms", 0);
        long bytes = prefs.getLong(prefix + "bytes", 0);
        return summary + "\n" + prefs.getString(prefix + "link", "Connection not recorded")
                + (ms > 0 ? " · " + age(ms) : "")
                + ("saved".equals(state) && bytes > 0 ? String.format(Locale.ROOT, " · %,d bytes", bytes) : size(bytes, 0, arm))
                + (prefs.getBoolean(prefix + "linkChanged", false) ? "\nConnection changed during this attempt." : "");
    }

    synchronized String report() {
        return "BlackBox Music Save Check " + SaveCheckBuild.VERSION + (SaveCheckBuild.FIXTURES ? " (synthetic only)" : " (phone)")
                + "\nEspresso · " + VIDEO + " · 175 s · helper 2026.10.26\n\nPilot: " + result(Arm.PILOT)
                + "\n\nFull v90: " + result(Arm.FULL);
    }

    private static String stage(String phase) {
        switch (phase) {
            case "source": return "Opening source (no transfer reported yet)";
            case "transfer": return "Transferring audio";
            case "finish": return "Finishing audio file";
            case "verify": return "Verifying complete audio";
            default: return "Checking test storage and helper";
        }
    }

    private static String age(long ms) { return String.format(Locale.ROOT, "%.1f s", ms / 1000.0); }

    private static String size(long bytes, long total, Arm arm) {
        if (bytes <= 0) return "";
        String count = String.format(Locale.ROOT, " · %,d", bytes);
        if (total > 0) return count + String.format(Locale.ROOT, " / %,d bytes", total);
        return count + (arm == Arm.PILOT ? " file bytes observed" : " bytes reported");
    }

    private static String reasonLabel(String reason) {
        switch (reason) {
            case "signin": return "Stopped · provider requires sign-in";
            case "limited": return "Stopped · provider rate limit";
            case "refused": case "unavailable": return "Stopped · provider did not allow this recording";
            case "network": return "Connection interrupted";
            case "network-changed": return "Connection changed before request";
            case "timeout": return "Stopped · test time limit";
            case "incomplete": return "Rejected · recording did not pass file verification";
            case "storage": return "Could not write test storage";
            case "not-fresh": return "Test storage was not empty; nothing fetched";
            case "setup": return "Pinned helper could not start";
            case "cancelled": return "Stopped by you";
            case "interrupted": return "App stopped before saving a result";
            case "expired": return "This v90 test build has ended";
            case "fixture": return "Missing synthetic test input";
            case "metadata": case "exact": return "Recording identity could not be verified";
            default: return "Helper could not finish this recording";
        }
    }
}
