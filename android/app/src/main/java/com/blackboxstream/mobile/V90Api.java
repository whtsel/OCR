package com.blackboxstream.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Implements the desktop catalogue, title/source and manifest routes against Android storage and jobs. */
final class V90Api {
    static final long EXPIRES_AT = 1806537600000L; // Matches the original v90 release policy (UTC).
    private final Context app;
    private final LibraryStore db;
    private final SharedPreferences prefs;
    private final V90Online online;
    private final PreparedMusic music;
    private final ExecutorService resolver = Executors.newFixedThreadPool(2);
    private final Map<String, Resolve> jobs = new LinkedHashMap<>();
    private volatile String key;
    private volatile boolean closed;

    static final class Reply {
        final int status;
        final JSONObject value;
        Reply(int status, JSONObject value) { this.status = status; this.value = value; }
    }

    private static final class Resolve {
        final String id = UUID.randomUUID().toString();
        volatile TorrentMeta meta;
        volatile String error;
        volatile boolean cancelled;
        final MediaEntry.Kind kind;
        Resolve(MediaEntry.Kind kind) { this.kind = kind; }
        boolean working() { return !cancelled && meta == null && error == null; }
    }

    V90Api(Context context) {
        this(context, PreparedMusic.builtIn(context.getApplicationContext()));
    }

    V90Api(Context context, PreparedMusic music) {
        app = context.getApplicationContext(); db = LibraryStore.get(app);
        prefs = app.getSharedPreferences("catalogue", Context.MODE_PRIVATE);
        key = prefs.getString("key", ""); online = new V90Online();
        this.music = music;
    }

    void close() {
        closed = true;
        synchronized (this) { for (Resolve job : jobs.values()) job.cancelled = true; jobs.clear(); }
        resolver.shutdownNow();
        music.close();
    }

    File preparedAudio(String id) { return closed || expired() ? null : music.read(id); }

    static boolean expired() { return System.currentTimeMillis() >= EXPIRES_AT; }
    private static Reply ok(JSONObject value) { return new Reply(200, value); }
    static Reply fail(int status, String reason) {
        try { return new Reply(status, new JSONObject().put("error", reason).put("why", reason).put("message", reason)); }
        catch (JSONException impossible) { return new Reply(500, new JSONObject()); }
    }

    Reply call(String method, String target, String body) {
        if (closed) return fail(503, "BlackBox is closing.");
        if (expired()) return fail(410, "This v90 build has ended. Install a newer version of BlackBox Stream.");
        if (target == null || target.length() > 5000 || !target.startsWith("/") || target.startsWith("//")) return fail(400, "Invalid local request.");
        if (body == null || body.length() > 128 * 1024) return fail(400, "Invalid request body.");
        if (!"GET".equals(method) && !"POST".equals(method)) return fail(405, "Unsupported request method.");
        boolean get = method.equals("GET");
        try {
            Uri uri = Uri.parse(target);
            String path = uri.getEncodedPath();
            if (path == null || !path.matches("/[A-Za-z0-9_/-]{1,160}")) return fail(400, "Invalid v90 route.");
            if (get && (path.equals("/health") || path.equals("/api/health"))) return ok(new JSONObject().put("ok", true).put("platform", "android"));
            if (get && path.equals("/api/tmdb/status")) return ok(new JSONObject().put("configured", !key.isEmpty()));
            if (!get && path.equals("/api/tmdb/credential")) {
                JSONObject data = new JSONObject(body);
                String userKey = Catalogue.credential(data.optString("credential"));
                Catalogue.validate(userKey);
                boolean remember = data.optBoolean("remember");
                if (remember) prefs.edit().putString("key", userKey).apply(); else prefs.edit().remove("key").apply();
                key = userKey;
                return ok(new JSONObject().put("remembered", remember));
            }
            if (!get && path.equals("/api/tmdb/forget")) {
                key = ""; prefs.edit().remove("key").apply(); return ok(new JSONObject().put("ok", true));
            }
            if (get && path.startsWith("/api/tmdb/")) return catalogue(uri, path.substring(9));
            if (get && path.matches("/api/anime/(top|airing|search)")) return tmdbAnime(uri, path.substring(11));
            if (get && path.matches("/api/sources/(find|deep)")) return search(uri, path.endsWith("/deep"));
            if (!get && path.equals("/api/availability")) return ok(new JSONObject()); // Metadata does not imply a playable file.
            if (!get && path.equals("/api/coverage")) return ok(new JSONObject()); // No per-episode availability is inferred.
            if (!get && (path.equals("/api/content-resolve") || path.equals("/api/resolve"))) {
                JSONObject data = new JSONObject(body);
                return resolve(data.optString("uri"), mediaKind(data.optString("kind"), null));
            }
            if (path.startsWith("/api/content-job/") || path.startsWith("/api/job/")) return status(path, get);
            if (!get && path.matches("/api/torrent/[a-f0-9]{40}/select/[0-9]{1,4}")) {
                String[] parts = path.split("/");
                JSONObject selection = new JSONObject(body);
                return select(parts[3], Integer.parseInt(parts[5]), selection.optString("job"), selection.optString("title"));
            }
            if (get && path.equals("/api/mobile/library")) return library();
            if (get && path.equals("/api/mobile/music-helper")) return ok(music.state());
            if (get && path.equals("/api/mobile/music-helper/await")) return ok(music.awaitSelection(param(uri, "key", "")));
            if (!get && path.equals("/api/mobile/music-helper/warm")) {
                music.warm();
                return ok(new JSONObject());
            }
            if (!get && path.equals("/api/mobile/music-helper/forget")) {
                music.forget(new JSONObject(body).getString("key"));
                return ok(music.state());
            }
            if (!get && path.equals("/api/mobile/music-helper/album")) {
                return ok(music.downloadAlbum(new JSONObject(body).getJSONArray("tracks")));
            }
            if (!get && path.equals("/api/mobile/music-helper/plan")) {
                JSONObject data = new JSONObject(body);
                return ok(music.plan(data.getInt("sequence"), data.isNull("selected") ? null : data.getJSONObject("selected"),
                        data.getJSONArray("upcoming"), data.optBoolean("retry"),
                        data.optBoolean("playing"), data.optBoolean("mobileLookahead")));
            }
            if (get && path.equals("/api/mobile/music")) {
                JSONArray tracks = new JSONArray();
                String query = V90Online.searchText(param(uri, "q", ""));
                if (!query.isEmpty()) for (Catalogue.Card track : Catalogue.music(Catalogue.Music.TRACKS, query)) {
                    tracks.put(new JSONObject().put("title", track.title).put("track", track.title)
                            .put("artist", track.detail.split(" · ", 2)[0]).put("listing", track.listing())
                            .put("source", "MusicBrainz metadata"));
                }
                return ok(new JSONObject().put("results", tracks));
            }
            if (!get && path.equals("/api/mobile/direct")) {
                JSONObject data = new JSONObject(body); String url = MediaSources.https(data.optString("url"));
                if (MediaSources.playlist(url) || MediaSources.torrentDescriptor(url)) return fail(400, "Use a direct file link, or import the playlist or torrent in Settings.");
                String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
                if (host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com"))
                    return fail(415, "A YouTube page is not an audio or video file link. Use a direct HTTPS file or import from your phone.");
                MediaEntry.Kind kind = mediaKind(data.optString("kind"), MediaSources.infer(null, url));
                return prepareMedia(new MediaEntry(data.optString("title", MediaSources.titleFor(url)), kind, url), data.optBoolean("download"));
            }
            if (!get && path.matches("/api/mobile/transfer/[a-f0-9]{64}/(cancel|download)")) {
                MediaEntry entry = db.get(path.split("/")[4]);
                if (entry == null) return fail(404, "This file is no longer in your Library.");
                if (path.endsWith("/download")) return prepareMedia(entry, true);
                if (entry.transfer < 0) return fail(409, "This download is no longer queued.");
                Downloads.cancel(app, entry); return ok(new JSONObject().put("ok", true));
            }
            if (get && path.equals("/api/ytdlp/status")) return ok(new JSONObject().put("installed", false).put("supported", false)
                    .put("cookies", false).put("message", "The Windows music/trailer helper isn't installed on Android."));
            if (get && path.equals("/api/community/status")) return ok(new JSONObject().put("available", false).put("android_rooms", true));
            return fail(501, "This Windows endpoint isn't available in the Android port.");
        } catch (IllegalArgumentException | JSONException invalid) {
            return fail(400, invalid instanceof IllegalArgumentException && invalid.getMessage() != null ? invalid.getMessage() : "Invalid v90 request data.");
        } catch (IOException failed) {
            return fail(502, safeFailure(failed, "Couldn't complete the request. Check the connection and try again."));
        } catch (RuntimeException | StackOverflowError failure) { return fail(500, "Android couldn't complete this operation."); }
    }

    private Reply prepareMedia(MediaEntry entry, boolean download) throws IOException, JSONException {
        if (download && !Downloads.canKeep(entry)) return fail(415, "This stream cannot be downloaded. Choose a direct media file.");
        MediaEntry existing = db.get(entry.id);
        if (existing == null || existing.kind != entry.kind) db.save(entry, true);
        if (download) {
            if (existing != null) Downloads.failIfMissing(app, existing);
            try { ensureQueued(entry); }
            catch (IllegalStateException busy) { return fail(409, safeFailure(busy, "The download can't be queued yet.")); }
        }
        MediaEntry current = db.get(entry.id);
        return new Reply(download && current != null && current.transfer >= 0 ? 202 : 200, new JSONObject().put("id", entry.id)
                .put("offline", db.isOffline(entry)).put("queued", current != null && current.transfer >= 0));
    }

    private static MediaEntry.Kind mediaKind(String name, MediaEntry.Kind fallback) {
        try { return MediaEntry.Kind.valueOf(name); } catch (IllegalArgumentException invalid) { return fallback; }
    }

    private Reply catalogue(Uri uri, String route) throws JSONException, IOException {
        if (!route.matches("/(?:configuration|(?:discover|trending|search|genre|movie|tv|person|find)(?:/[a-zA-Z0-9_]+){1,5})")) {
            return fail(400, "Unsupported catalogue route.");
        }
        if (route.startsWith("/movie/open_")) return fail(404, "Not a TMDB title.");
        String credential = credential();
        if (credential.isEmpty()) return fail(412, "Add your TMDB API key in Settings to browse the catalogue.");
        StringBuilder request = new StringBuilder(route);
        for (String name : uri.getQueryParameterNames()) {
            if (!name.matches("[a-zA-Z0-9_.-]{1,60}") || name.equalsIgnoreCase("api_key") || name.equals("include_adult")) continue;
            String value = param(uri, name, "");
            if (value.length() > 550) continue;
            request.append(request.indexOf("?") < 0 ? '?' : '&').append(name).append('=').append(V90Online.encode(value));
        }
        if (route.startsWith("/search/") || route.startsWith("/discover/")) request.append(request.indexOf("?") < 0 ? '?' : '&').append("include_adult=false");
        return ok(new JSONObject(Catalogue.fetch(URI.create(Catalogue.TMDB), request.toString(), credential)));
    }

    private String credential() {
        // An unsaved key lives only for this activity. It is never forwarded to source indexes.
        return key.isEmpty() ? prefs.getString("key", "") : key;
    }

    private Reply tmdbAnime(Uri uri, String mode) throws IOException, JSONException {
        // General search already includes TMDB series. Do not append duplicate results from another index.
        if ("search".equals(mode)) return ok(new JSONObject().put("items", new JSONArray()));
        String credential = credential();
        if (credential.isEmpty()) return fail(412, "Add your TMDB API key in Settings to browse anime.");
        int page = Math.max(1, Math.min(100, number(uri, "page", 1)));
        return ok(Catalogue.anime(URI.create(Catalogue.TMDB), credential, "airing".equals(mode), page));
    }

    private Reply search(Uri uri, boolean deep) throws JSONException {
        String title = V90Online.searchText(param(uri, "title", "")), year = param(uri, "year", ""), type = param(uri, "type", "movie");
        if (title.length() < 2 || !type.matches("movie|tv|anime|anime-film")) return fail(400, "Choose a title to search.");
        int season = number(uri, "season", 1), episode = number(uri, "episode", 0);
        String alternative = V90Online.searchText(param(uri, "alt", ""));
        Map<String, JSONObject> distinct = new LinkedHashMap<>();
        JSONArray providers = new JSONArray();
        for (MediaEntry local : db.saved()) {
            if (!local.title.equalsIgnoreCase(title) || !local.unresolvedTorrent()) continue;
            if (local.source.startsWith("magnet:?")) {
                try { distinct.putIfAbsent(TorrentMeta.hex(TorrentMeta.magnet(local.source).hash), V90Online.source(local.title, local.source, 0, 0, "Your Library")); }
                catch (IOException ignored) { }
            } else distinct.put(local.id, V90Online.source(local.title, local.source, 0, 0, "Your Library"));
        }
        String index = deep ? "Torrents-CSV" : "Knaben", indexStatus = "skipped";
        String note = "Source counts are reported by the index. BlackBox checks the actual manifest and pieces before playing.";
        if (distinct.isEmpty() || deep) {
            try {
                JSONArray found = online.sources(deep, title, year, type, season, episode);
                indexStatus = "ok";
                if (found.length() == 0 && alternative.length() > 1 && !alternative.equalsIgnoreCase(title))
                    found = online.sources(deep, alternative, year, type, season, episode);
                if (found.length() > 0) providers.put(index);
                for (int i = 0; i < found.length(); i++) {
                    JSONObject item = found.getJSONObject(i);
                    distinct.putIfAbsent(TorrentMeta.hex(TorrentMeta.magnet(item.getString("magnet")).hash), item);
                }
                if (distinct.isEmpty()) note = index + " returned no exact v1 results for this title and episode/year. Try an authorized magnet or .torrent.";
            } catch (IOException | JSONException failed) {
                indexStatus = "unavailable";
                note = index + " is unavailable right now. " + safeFailure(failed, "Try again or open a known source.");
            }
        } else note = "Matching sources from your Library.";
        JSONArray sources = new JSONArray();
        for (JSONObject source : distinct.values()) sources.put(source);
        return ok(new JSONObject().put("sources", sources).put("note", note).put("providers", providers).put("index", index).put("indexStatus", indexStatus));
    }

    private synchronized Reply resolve(String source, MediaEntry.Kind kind) throws JSONException {
        String link = source.trim();
        if (link.length() > 12000 || !(link.regionMatches(true, 0, "magnet:?", 0, 8) || MediaSources.torrentDescriptor(link)))
            return fail(400, "Use a v1 magnet, an HTTPS .torrent URL, or choose a .torrent document in the Android picker.");
        if (jobs.size() >= 12) {
            java.util.Iterator<Resolve> old = jobs.values().iterator();
            while (old.hasNext() && jobs.size() >= 12) if (!old.next().working()) old.remove();
            if (jobs.size() >= 12) return fail(429, "Too many torrent lookups. Cancel one and try again.");
        }
        Resolve job = new Resolve(kind); jobs.put(job.id, job);
        resolver.execute(() -> {
            try {
                if (job.cancelled || closed) return;
                TorrentMeta meta = loadManifest(link, () -> job.cancelled || closed);
                boolean playable = false;
                for (TorrentMeta.Item f : meta.items) playable |= f.playable();
                if (!playable) throw new IOException("There is no Android-playable audio or video file under 4 GiB in this torrent.");
                synchronized (V90Api.this) {
                    if (job.cancelled || closed) return;
                    job.meta = meta;
                }
            } catch (IOException | RuntimeException failed) { job.error = safeFailure(failed, "Couldn't load and verify the torrent manifest."); }
        });
        return new Reply(202, new JSONObject().put("job", job.id));
    }

    private TorrentMeta loadManifest(String link, TorrentNetwork.Stop stopped) throws IOException {
        if (MediaSources.torrentDescriptor(link)) return TorrentMeta.read(TorrentWebSeed.descriptor(link));
        TorrentMeta.Magnet magnet = TorrentMeta.magnet(link);
        String hash = TorrentMeta.hex(magnet.hash);
        synchronized (this) {
            for (Resolve job : jobs.values()) {
                TorrentMeta cached = job.meta;
                if (!job.cancelled && cached != null && TorrentMeta.hex(cached.hash).equals(hash)) return cached.withMagnet(magnet);
            }
        }
        File cache = TorrentJobs.descriptor(app, hash);
        if (cache.isFile()) {
            try { return TorrentMeta.read(TorrentMeta.slurp(new FileInputStream(cache), TorrentMeta.MAX_META)).withMagnet(magnet); }
            catch (IOException invalid) { /* Fetch verified metadata again. */ }
        }
        return new TorrentNetwork(stopped).resolve(magnet);
    }

    private synchronized Reply status(String path, boolean get) throws JSONException {
        String[] parts = path.split("/");
        Resolve job = parts.length < 4 ? null : jobs.get(parts[3]);
        if (job == null) return fail(404, "Torrent lookup has ended. Open the source again.");
        if (!get && parts.length == 5 && parts[4].equals("cancel")) { job.cancelled = true; job.meta = null; }
        else if (!get || parts.length != 4) return fail(400, "Invalid lookup status request.");
        if (job.cancelled) return ok(new JSONObject().put("state", "cancelled"));
        if (job.error != null) return ok(new JSONObject().put("state", "error").put("error", job.error));
        if (job.meta == null) return ok(new JSONObject().put("state", "working").put("pct", 10).put("msg", "Resolving verified torrent metadata"));
        JSONArray files = new JSONArray();
        for (TorrentMeta.Item f : job.meta.items) if (f.playable() && files.length() < 1024)
            files.put(new JSONObject().put("idx", f.index).put("name", f.name).put("len", f.length));
        JSONObject data = new JSONObject().put("id", TorrentMeta.hex(job.meta.hash)).put("info_hash", TorrentMeta.hex(job.meta.hash))
                .put("name", job.meta.name).put("files", files).put("job", job.id);
        return ok(new JSONObject().put("state", "done").put("pct", 100).put("result", data));
    }

    private synchronized Reply select(String hash, int index, String jobId, String title) throws JSONException, IOException {
        Resolve job = jobs.get(jobId);
        TorrentMeta meta = job == null || job.cancelled ? null : job.meta;
        if (meta == null || !TorrentMeta.hex(meta.hash).equals(hash)) return fail(409, "The selected file doesn't match this verified source check. Open the release again.");
        if (index < 0 || index >= meta.items.size() || !meta.items.get(index).playable()) return fail(415, "This file is not an Android media selection.");
        TorrentMeta.Item file = meta.items.get(index);
        Cinema.Film film = Cinema.fromTorrent("torrent:" + hash + ":" + index);
        String name = film != null ? film.title : title.trim().isEmpty() || meta.items.size() > 1 ? file.name : MediaEntry.cleanTitle(title);
        MediaEntry entry = TorrentJobs.save(app, meta, file, name);
        if (!entry.music() && (job.kind == MediaEntry.Kind.ANIME || job.kind == MediaEntry.Kind.SERIES)) {
            entry = new MediaEntry(entry.title, job.kind, entry.source);
            db.save(entry, true);
        }
        ensureQueued(entry);
        return new Reply(202, new JSONObject().put("id", entry.id).put("manifest_verified", true));
    }

    synchronized void ensureQueued(MediaEntry entry) throws IOException {
        MediaEntry latest = db.get(entry.id);
        if (!db.isOffline(entry) && (latest == null || latest.transfer < 0)) Downloads.enqueue(app, entry);
    }

    private Reply library() throws JSONException {
        Map<String, MediaEntry> entries = new LinkedHashMap<>();
        for (MediaEntry e : db.transfers()) entries.put(e.id, e);
        for (MediaEntry e : db.saved()) entries.putIfAbsent(e.id, e);
        for (MediaEntry e : db.recentlyPlayed()) entries.putIfAbsent(e.id, e);
        JSONArray result = new JSONArray();
        for (MediaEntry e : entries.values()) {
            boolean offline = db.isOffline(e);
            long progress = e.transfer >= 0 ? Downloads.received(db, e) : 0;
            result.put(new JSONObject().put("id", e.id).put("title", e.title).put("kind", e.kind.label)
                    .put("label", e.sourceLabel()).put("kindId", e.kind.name()).put("offline", offline).put("queued", e.transfer >= 0)
                    .put("downloadable", Downloads.canKeep(e))
                    .put("error", e.transferError == null ? "" : e.transferError).put("received", progress)
                    .put("total", e.totalBytes).put("position", e.positionMs));
            if (result.length() >= 400) break;
        }
        return ok(new JSONObject().put("items", result));
    }

    private static int number(Uri uri, String key, int fallback) {
        try { return Integer.parseInt(param(uri, key, "")); } catch (NumberFormatException bad) { return fallback; }
    }
    static String param(Uri uri, String name, String fallback) {
        String value = uri.getQueryParameter(name); return value == null ? fallback : value;
    }
    static String safeFailure(Throwable e, String fallback) {
        // Provider I/O exceptions can contain credential-bearing URLs. Only our own short messages are shown.
        String m = e.getMessage();
        if (m == null || m.length() > 240 || m.contains("://") || m.contains("api_key") || m.contains("Bearer")) return fallback;
        return m;
    }
}
