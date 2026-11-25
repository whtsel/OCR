package com.blackboxstream.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;

/** Local report for the separate Download Test APK. Nothing from raw helper output is exported. */
final class OfflineDownloadReport {
    private static final int LIMIT = 20;
    private static final Pattern HTTP = Pattern.compile("\\bHTTP (?:Error |status(?: code)?[: ]+)([45]\\d\\d)\\b", Pattern.CASE_INSENSITIVE);
    private static final String[][] SIGNALS = {
        {"sign-in requested", "bbx_provider_signin", "sign in", "sign-in", "login required", "login_required"},
        {"bot or captcha challenge", "not a bot", "captcha"},
        {"provider rate limit", "bbx_provider_limited", "too many requests", "rate-limited", "rate limit"},
        {"provider refused", "bbx_provider_refused", "forbidden", "not authorized"},
        {"private source", "private video", "video is private"},
        {"subscription required", "music premium", "premium members", "members-only", "members only", "subscriber-only"},
        {"region restricted", "available in your country", "available in your region", "geo-restricted"},
        {"age restricted", "age-restricted", "confirm your age"},
        {"copyright restriction", "copyright"},
        {"client not allowed", "not available on this app", "not available on this device"},
        {"source removed", "has been removed", "has been deleted", "video does not exist"},
        {"source unavailable", "video unavailable", "content isn't available", "video is not available"},
        {"no matching result", "bbx_no_pc_match", "bbx_no_exact_recording"},
        {"incomplete source description", "bbx_pc_incomplete_source"},
        {"no usable formats", "requested format is not available", "no video formats", "only images are available"},
        {"helper challenge solver", "challenge solving failed", "nsig extraction failed", "no supported javascript runtime"},
        {"provider token warning", "po token", "sabr streaming"},
        {"DNS failure", "failed to resolve", "name or service not known", "no address"},
        {"TLS failure", "certificate verify failed", "ssl:", "ssl error", "tls error"},
        {"timeout", "timed out", "timeout"},
        {"interrupted body", "incomplete read", "content too short", "connection reset", "remote end closed"},
        {"storage full", "no space left"},
        {"media conversion error", "postprocessing:", "ffmpeg exited", "ffprobe and ffmpeg not found"},
    };

    private final SharedPreferences prefs;
    private final ArrayDeque<String> attempts = new ArrayDeque<>();

    OfflineDownloadReport(Context app) {
        prefs = app.getSharedPreferences("offline-download-test", Context.MODE_PRIVATE);
        try {
            JSONArray values = new JSONArray(prefs.getString("attempts", "[]"));
            for (int i = Math.max(0, values.length() - LIMIT); i < values.length(); i++) {
                String row = values.optString(i);
                if (!row.isEmpty() && row.length() <= 2000) attempts.add(row);
            }
        } catch (JSONException invalid) { /* A damaged report must not affect Downloads. */ }
    }

    boolean ready() { return !attempts.isEmpty(); }

    void record(Attempt attempt, String outcome, String next, long received, long published) {
        while (attempts.size() >= LIMIT) attempts.removeFirst();
        attempts.add(attempt.describe(outcome, next, received, published));
        prefs.edit().putString("attempts", new JSONArray(attempts).toString()).apply();
    }

    String text() {
        if (!ready()) return "";
        return "BlackBox Stream v90 Download Test (Offline 1.5.9 path)\nAndroid API " + Build.VERSION.SDK_INT
                + " | bundled helper " + PreparedMusic.VERSION + " | no provider account\n\n"
                + TextUtils.join("\n\n", attempts);
    }

    static final class Attempt {
        private final PreparedMusic.Track track;
        private final String link;
        private final int retries;
        private final long started = SystemClock.elapsedRealtime();
        private String route = "not selected", source = "", stage = "prepare storage", helperStage = "not started";
        private int exit = -1;
        private final Set<String> errors = new LinkedHashSet<>(), warnings = new LinkedHashSet<>();

        Attempt(PreparedMusic.Track track, int retries, int network) {
            this.track = track;
            this.retries = retries;
            link = network == 1 ? "metered" : network == 2 ? "unmetered" : "offline";
        }

        void choose(String route, String id) {
            this.route = route;
            if (PreparedMusic.videoId(id)) source = id;
            stage = "start helper";
        }

        void helperOutput(String output, MusicPreparationBudget.Phase phase, int exit) {
            this.exit = exit;
            String lookup = source.isEmpty() ? "artist/title search" : "open source";
            for (String line : output.split("\n")) {
                if (line.startsWith("BBX_PC_OPEN:")) {
                    String id = line.substring("BBX_PC_OPEN:".length());
                    if (PreparedMusic.videoId(id)) { source = id; lookup = "open source"; }
                } else if (line.equals("BBX_CATALOGUE_VERIFY")) lookup = "open source";
                else if (line.equals("BBX_CATALOGUE_QUERY") || line.equals("BBX_CATALOGUE_ALTERNATE")
                        || line.equals("BBX_PC_REFRESH")) lookup = "artist/title search";
                if (line.startsWith("ERROR:") || line.startsWith("BBX_PROVIDER_") || line.startsWith("BBX_NO_")
                        || line.equals("BBX_PC_INCOMPLETE_SOURCE")) observe(line, errors, true);
                else if (line.startsWith("WARNING:")) observe(line, warnings, false);
            }
            helperStage = phase == MusicPreparationBudget.Phase.LOOKUP ? lookup
                    : phase == MusicPreparationBudget.Phase.TRANSFER ? "audio transfer" : "finish audio";
            stage = helperStage;
        }

        void publication() { stage = "publish file"; }

        private String describe(String outcome, String next, long received, long published) {
            StringBuilder text = new StringBuilder().append(label(track.title)).append(" — ").append(label(track.artist))
                    .append(" | ").append(label(track.album)).append(" (").append(track.seconds).append(" s)\n")
                    .append("  Route: ").append(route).append(" | Source: ").append(source.isEmpty() ? "not recorded" : source)
                    .append(" | Link: ").append(link).append('\n')
                    .append("  Stage: ").append(stage).append(" | Result: ").append(outcome)
                    .append(" | Helper: ").append(helperStage);
            if (exit >= 0) text.append(" (exit ").append(exit).append(')');
            text.append(" | ").append(SystemClock.elapsedRealtime() - started).append(" ms\n")
                    .append("  Received: ").append(received).append(" B | Published: ").append(published).append(" B")
                    .append(" | Automatic retry: ").append(retries).append(" | Next: ").append(next);
            if (!errors.isEmpty()) text.append("\n  Errors: ").append(TextUtils.join("; ", errors));
            if (!warnings.isEmpty()) text.append("\n  Warnings: ").append(TextUtils.join("; ", warnings));
            return text.toString();
        }
    }

    private static String label(String text) {
        String safe = text.replaceAll("(?i)(?:https?://|www\\.)\\S+", "[link]");
        return safe.length() > 100 ? safe.substring(0, 100) + "…" : safe;
    }

    private static void observe(String line, Set<String> signals, boolean error) {
        String lower = line.toLowerCase(Locale.ROOT);
        boolean recognized = false;
        Matcher status = HTTP.matcher(line);
        while (status.find() && signals.size() < 12) {
            signals.add("HTTP " + status.group(1));
            recognized = true;
        }
        for (String[] row : SIGNALS) {
            for (int i = 1; i < row.length; i++) {
                if (lower.contains(row[i])) {
                    if (signals.size() < 12) signals.add(row[0]);
                    recognized = true;
                    break;
                }
            }
        }
        if (error && !recognized && signals.size() < 12) signals.add("unclassified helper error (text withheld)");
    }
}
