package com.blackboxstream.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local, allowlisted observations. Never retain raw output, media URLs, or network identities. */
final class OfflineDownloadReport {
    static final String FORMAT_MARKER = "BBX_TEST_FORMAT:";
    private static final int LIMIT = 20, ROW_LIMIT = 3200, SIGNAL_LIMIT = 10;
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
        {"media request failed", "unable to download video data"},
        {"manifest request failed", "failed to download manifest", "failed to download m3u8", "failed to download mpd"},
        {"fragment unavailable", "not found, unable to continue", "failed to download fragment"},
        {"signature solver failed", "signature solving failed", "signature extraction failed"},
        {"n-challenge solver failed", "n challenge solving failed", "nsig extraction failed"},
        {"JavaScript runtime unavailable", "no supported javascript runtime"},
        {"challenge component unavailable", "remote component", "challenge solver script"},
        {"provider token warning", "po token"},
        {"SABR streaming warning", "sabr streaming"},
        {"DNS failure", "failed to resolve", "name or service not known", "no address"},
        {"TLS failure", "certificate verify failed", "ssl:", "ssl error", "tls error"},
        {"timeout", "timed out", "timeout"},
        {"interrupted body", "incomplete read", "content too short", "connection reset", "remote end closed"},
        {"empty response", "did not get any data blocks", "the downloaded file is empty"},
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
                if (!row.isEmpty() && row.length() <= ROW_LIMIT) attempts.add(row);
            }
        } catch (JSONException invalid) { /* A damaged report must not affect Downloads. */ }
    }

    boolean ready() { return !attempts.isEmpty(); }

    void record(Attempt attempt, String outcome, String next, long received, long published, String linkAtEnd) {
        while (attempts.size() >= LIMIT) attempts.removeFirst();
        String row = attempt.describe(outcome, next, received, published, linkAtEnd);
        attempts.add(row.length() > ROW_LIMIT ? row.substring(0, ROW_LIMIT - 1) + "…" : row);
        prefs.edit().putString("attempts", new JSONArray(attempts).toString()).apply();
    }

    String text() {
        if (!ready()) return "";
        return "BlackBox Stream v90 Download Test 4 (Offline 1.5.11 path)\nAndroid API " + Build.VERSION.SDK_INT
                + " | bundled helper " + PreparedMusic.VERSION + " | no provider account\n"
                + "Timeline: milliseconds from attempt start. Queue counts include this song.\n\n"
                + TextUtils.join("\n\n", attempts);
    }

    static String link(NetworkCapabilities caps, int connection) {
        if (caps == null || connection == 0) return "offline";
        String transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet" : "other transport";
        return transport + (connection == 1 ? ", metered" : ", unmetered")
                + (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? ", validated" : ", not validated");
    }

    // The helper reader and the library update one attempt from different threads. These methods
    // never call back into the library or the helper, and the queue never reads a diagnostic value.
    static final class Attempt {
        private final PreparedMusic.Track track;
        private final String link;
        private final String excluded;
        private final int retries, jobsAtStart;
        private final long started = SystemClock.elapsedRealtime();
        private String route = "not selected", source = "", stage = "prepare storage", helperStage = "not started";
        private int exit = -1, jobsAtTransfer, peakJobs;
        private boolean helperDone, bytesSeen;
        private String format = "";
        private final Map<String, Long> timeline = new LinkedHashMap<>();
        private final Map<String, String> errors = new LinkedHashMap<>(), warnings = new LinkedHashMap<>();

        Attempt(PreparedMusic.Track track, int retries, String link, String excluded, int jobs) {
            this.track = track;
            this.retries = retries;
            this.link = link;
            this.excluded = PreparedMusic.videoId(excluded) ? excluded : "";
            jobsAtStart = peakJobs = jobs;
        }

        synchronized void choose(String route, String id) {
            this.route = route;
            if (PreparedMusic.videoId(id)) source = id;
            stage = "start helper";
        }

        synchronized void overlap(int jobs) { peakJobs = Math.max(peakJobs, jobs); }

        synchronized void progress(String phase, long received, int jobs) {
            if (helperDone) return;
            if ("downloading".equals(phase) && jobsAtTransfer == 0) jobsAtTransfer = jobs;
            if (received > 0 && !bytesSeen) {
                bytesSeen = true;
                mark("first byte progress", SystemClock.elapsedRealtime());
            }
        }

        synchronized void helperStarted(boolean direct, long now) {
            stage = helperStage = direct ? "open source" : "start resolver";
            mark("helper launched", now);
        }

        synchronized void helperLine(String line, long now) {
            if (helperDone) return;
            if (line.startsWith(FORMAT_MARKER)) {
                if (line.length() <= 512) format = describeFormat(line.substring(FORMAT_MARKER.length()));
                return;
            }
            if (line.startsWith("BBX_PC_OPEN:")) {
                String id = line.substring("BBX_PC_OPEN:".length());
                if (PreparedMusic.videoId(id)) {
                    source = id;
                    step("open source", now);
                }
            } else if (line.equals("BBX_CATALOGUE_VERIFY")) step("open source", now);
            else if (line.equals("BBX_CATALOGUE_QUERY") || line.equals("BBX_CATALOGUE_ALTERNATE")
                    || line.equals("BBX_PC_REFRESH")) step("artist/title search", now);
            else if (line.startsWith("BBX_DOWNLOAD_START ") || line.equals("BBX_MEDIA:downloading"))
                step("audio transfer", now);
            else if (line.equals("BBX_MEDIA:finished")) step("finish audio", now);
            else if (line.startsWith("ERROR:") || line.startsWith("BBX_PROVIDER_") || line.startsWith("BBX_NO_")
                    || line.equals("BBX_PC_INCOMPLETE_SOURCE")) observe(line, errors, true, now);
            else if (line.startsWith("WARNING:")) observe(line, warnings, false, now);
        }

        synchronized void helperEnded(MusicPreparationBudget.Phase phase, int exit, long now) {
            this.exit = exit;
            helperDone = true;
            if (phase != MusicPreparationBudget.Phase.LOOKUP)
                helperStage = phase == MusicPreparationBudget.Phase.TRANSFER ? "audio transfer" : "finish audio";
            stage = helperStage;
            mark(exit < 0 ? "helper stopped (no exit observed)" : "helper ended", now);
        }

        synchronized void publication() {
            stage = "publish file";
            mark(stage, SystemClock.elapsedRealtime());
        }

        private void step(String name, long now) {
            stage = helperStage = name;
            mark(name, now);
        }

        private long elapsed(long now) { return Math.max(0, now - started); }
        private void mark(String name, long now) { timeline.putIfAbsent(name, elapsed(now)); }

        private void observe(String line, Map<String, String> signals, boolean error, long now) {
            String when = " (" + helperStage + " +" + elapsed(now) + " ms)";
            String lower = line.toLowerCase(Locale.ROOT);
            boolean recognized = false;
            Matcher status = HTTP.matcher(line);
            while (signals.size() < SIGNAL_LIMIT && status.find()) {
                signals.putIfAbsent("HTTP " + status.group(1), when);
                recognized = true;
            }
            for (String[] row : SIGNALS) {
                if (signals.size() >= SIGNAL_LIMIT) break;
                for (int i = 1; i < row.length; i++) {
                    if (!lower.contains(row[i])) continue;
                    signals.putIfAbsent(row[0], when);
                    recognized = true;
                    break;
                }
            }
            if (error && !recognized && signals.size() < SIGNAL_LIMIT)
                signals.putIfAbsent("unclassified helper error (text withheld)", when);
        }

        private synchronized String describe(String outcome, String next, long received, long published, String linkAtEnd) {
            StringBuilder text = new StringBuilder().append(label(track.title)).append(" — ").append(label(track.artist))
                    .append(" | ").append(label(track.album)).append(" (").append(track.seconds).append(" s)\n")
                    .append("  Route: ").append(route).append(" | Source: ").append(source.isEmpty() ? "not recorded" : source);
            if (!excluded.isEmpty()) text.append(" | Excluded: ").append(excluded);
            text.append(" | Link: ").append(link);
            if (!link.equals(linkAtEnd)) text.append(" → ").append(linkAtEnd).append(" at end");
            text.append("\n  Stage: ").append(stage).append(" | Result: ").append(outcome)
                    .append(" | Helper: ").append(helperStage);
            if (exit >= 0) text.append(" (exit ").append(exit).append(')');
            text.append(" | ").append(elapsed(SystemClock.elapsedRealtime())).append(" ms\n")
                    .append("  Queue jobs (start/transfer/peak): ").append(jobsAtStart).append('/')
                    .append(jobsAtTransfer > 0 ? Integer.toString(jobsAtTransfer) : "–").append('/').append(peakJobs)
                    .append(" | Automatic retry: ").append(retries).append(" | Next: ").append(next);
            if (!timeline.isEmpty()) {
                String separator = "\n  Timeline: ";
                for (Map.Entry<String, Long> point : timeline.entrySet()) {
                    text.append(separator).append(point.getKey()).append('=').append(point.getValue());
                    separator = "; ";
                }
            }
            if (!format.isEmpty()) text.append("\n  Media: ").append(format);
            text.append("\n  Received: ").append(received).append(" B reported | Published: ").append(published).append(" B");
            appendSignals(text, "Errors", errors);
            appendSignals(text, "Warnings", warnings);
            return text.toString();
        }
    }

    private static void appendSignals(StringBuilder to, String name, Map<String, String> signals) {
        String separator = "\n  " + name + ": ";
        for (Map.Entry<String, String> signal : signals.entrySet()) {
            to.append(separator).append(signal.getKey()).append(signal.getValue());
            separator = "; ";
        }
    }

    private static String label(String text) {
        String safe = text.replaceAll("(?i)(?:https?://|www\\.)\\S+", "[link]");
        return safe.length() > 100 ? safe.substring(0, 100) + "…" : safe;
    }

    // Only known enums, numeric format IDs and bounded numeric metadata can cross this boundary.
    // The JSON template excludes URLs and headers even before validation here.
    static String describeFormat(String json) {
        try {
            JSONObject meta = new JSONObject(json);
            String id = field(meta, "format_id", "[0-9]{1,5}(?:-(?:drc|sr|hls|dash|https|[0-9]{1,2})){0,2}");
            String ext = field(meta, "ext", "m4a|mp4|webm|mp3|ogg|opus|aac|flac|wav|ts|mkv|3gp");
            String codec = field(meta, "acodec", "mp4a(?:\\.[0-9]{1,2}){0,3}|aac|opus|vorbis|mp3|alac|flac|none");
            String protocol = field(meta, "protocol", "https?|http_dash_segments|m3u8(?:_native)?");
            StringBuilder text = new StringBuilder("format ").append(id).append(" | ").append(ext)
                    .append(" | ").append(codec).append(" | ").append(protocol);
            long duration = quantity(meta, "duration", 24L * 60 * 60);
            if (duration > 0) text.append(" | source duration ").append(duration).append(" s");
            long size = quantity(meta, "filesize", 1_000_000_000L);
            if (size > 0) text.append(" | advertised ").append(size).append(" B");
            else {
                long estimate = quantity(meta, "filesize_approx", 1_000_000_000L);
                if (estimate > 0) text.append(" | estimated ").append(estimate).append(" B");
            }
            return text.toString();
        } catch (JSONException invalid) { return ""; }
    }

    private static long quantity(JSONObject meta, String name, long limit) {
        Object value = meta.opt(name);
        if (!(value instanceof Number)) return 0;
        double number = ((Number) value).doubleValue();
        return number > 0 && number <= limit ? Math.round(number) : 0;
    }

    private static String field(JSONObject meta, String name, String allowed) {
        Object value = meta.opt(name);
        return value instanceof String && ((String) value).matches(allowed) ? (String) value : "unknown";
    }
}
