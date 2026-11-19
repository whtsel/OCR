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
        boolean working() { return !cancelled && meta == null && error == null; }
    }

    V90Api(Context context) {
        app = context.getApplicationContext(); db = LibraryStore.get(app);
        prefs = app.getSharedPreferences("catalogue", Context.MODE_PRIVATE);
        key = prefs.getString("key", ""); online = new V90Online();
    }

    void close() {
        closed = true;
        synchronized (this) { for (Resolve job : jobs.values()) job.cancelled = true; jobs.clear(); }
        resolver.shutdownNow();
    }

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
            if (get && path.equals("/api/mobile/open-films")) return ok(new JSONObject().put("results", films("", 1)));
            if (get && path.matches("/api/anime/(top|airing|search)")) {
                return ok(online.anime(path.substring(11), param(uri, "q", param(uri, "query", "")), number(uri, "page", 1)));
            }
            if (get && path.matches("/api/sources/(find|deep)")) return search(uri, path.endsWith("/deep"));
            if (!get && path.equals("/api/availability")) return availability(body);
            if (!get && path.equals("/api/coverage")) return ok(new JSONObject()); // No per-episode availability is inferred.
            if (!get && (path.equals("/api/content-resolve") || path.equals("/api/resolve"))) return resolve(new JSONObject(body).optString("uri"));
            if (path.startsWith("/api/content-job/") || path.startsWith("/api/job/")) return status(path, get);
            if (!get && path.matches("/api/torrent/[a-f0-9]{40}/select/[0-9]{1,4}")) {
                String[] parts = path.split("/");
                JSONObject selection = new JSONObject(body);
                return select(parts[3], Integer.parseInt(parts[5]), selection.optString("job"), selection.optString("title"));
            }
            if (get && path.equals("/api/mobile/library")) return library();
            if (get && path.equals("/api/mobile/music")) {
                JSONArray tracks = new JSONArray();
                String query = V90Online.searchText(param(uri, "q", ""));
                if (!query.isEmpty()) for (Catalogue.Card track : Catalogue.music(Catalogue.Music.TRACKS, query)) {
                    tracks.put(new JSONObject().put("title", track.title).put("track", track.title)
                            .put("artist", track.detail.split(" · ", 2)[0]).put("listing", track.listing()));
                }
                return ok(new JSONObject().put("results", tracks));
            }
            if (!get && path.equals("/api/mobile/direct")) {
                JSONObject data = new JSONObject(body); String url = MediaSources.https(data.optString("url"));
                if (MediaSources.playlist(url) || MediaSources.torrentDescriptor(url)) return fail(400, "Open this through the catalogue or torrent picker.");
                MediaEntry entry = new MediaEntry(data.optString("title", MediaSources.titleFor(url)), MediaSources.infer(null, url), url);
                if (db.get(entry.id) == null) db.save(entry, true);
                return ok(new JSONObject().put("id", entry.id));
            }
            if (!get && path.matches("/api/mobile/transfer/[a-f0-9]{64}/cancel")) {
                MediaEntry entry = db.get(path.split("/")[4]);
                if (entry == null || entry.transfer < 0) return fail(404, "This download isn't queued.");
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

    private Reply catalogue(Uri uri, String route) throws JSONException, IOException {
        if (!route.matches("/(?:configuration|(?:discover|trending|search|genre|movie|tv|person|find)(?:/[a-zA-Z0-9_]+){1,5})")) {
            return fail(400, "Unsupported catalogue route.");
        }
        String credential = prefs.getString("key", key);
        if (credential.isEmpty()) credential = key;
        String openId = route.startsWith("/movie/open_") ? route.substring(12).split("/")[0] : "";
        if (!openId.isEmpty()) {
            Cinema.Film film = Cinema.fromSlug(openId.replace('_', '-'));
            if (film == null) return fail(404, "Open film not found.");
            if (route.equals("/movie/open_" + openId)) return ok(card(film));
            return ok(new JSONObject().put("results", new JSONArray()));
        }
        if (credential.isEmpty()) {
            if (route.matches("/(?:movie/(?:popular|top_rated)|discover/movie|trending/(?:all|movie)/week|search/(?:multi|movie))")) {
                JSONArray films = films(param(uri, "query", ""), number(uri, "page", 1));
                return ok(new JSONObject().put("page", 1).put("total_pages", 1).put("total_results", films.length()).put("results", films));
            }
            if (route.startsWith("/search/") || route.startsWith("/discover/")) return ok(new JSONObject().put("results", new JSONArray()).put("total_pages", 1));
            return fail(412, "Add your TMDB API key in Settings for the full v90 catalogue.");
        }
        StringBuilder request = new StringBuilder(route);
        for (String name : uri.getQueryParameterNames()) {
            if (!name.matches("[a-zA-Z0-9_.-]{1,60}") || name.equalsIgnoreCase("api_key") || name.equals("include_adult")) continue;
            String value = param(uri, name, "");
            if (value.length() > 550) continue;
            request.append(request.indexOf("?") < 0 ? '?' : '&').append(name).append('=').append(V90Online.encode(value));
        }
        if (route.startsWith("/search/") || route.startsWith("/discover/")) request.append(request.indexOf("?") < 0 ? '?' : '&').append("include_adult=false");
        String raw = Catalogue.fetch(URI.create(Catalogue.TMDB), request.toString(), credential);
        return ok(new JSONObject(raw));
    }

    private static JSONObject card(Cinema.Film film) throws JSONException {
        String slug = film.slug.replace('-', '_'), art = "open/" + slug + ".jpg";
        return new JSONObject().put("id", "open_" + slug).put("title", film.title).put("original_title", film.title)
                .put("media_type", "movie").put("release_date", film.year + "-01-01").put("status", "Released")
                .put("poster_path", "/" + art).put("backdrop_path", "/" + art).put("overview", film.story).put("_openFilm", true)
                .put("runtime", Integer.parseInt(film.duration.split(" ")[0])).put("genre_ids", new JSONArray().put(16))
                .put("genres", new JSONArray().put(new JSONObject().put("name", film.genre)));
    }

    private static JSONArray films(String query, int page) throws JSONException {
        JSONArray rows = new JSONArray();
        if (page > 1) return rows;
        String clean = V90Online.searchText(query).toLowerCase(Locale.ROOT);
        for (Cinema.Film f : Cinema.FILMS) if (f.title.toLowerCase(Locale.ROOT).contains(clean)) rows.put(card(f));
        return rows;
    }

    private Reply availability(String body) throws JSONException {
        JSONObject known = new JSONObject(); JSONArray checks = new JSONArray(body);
        // Do not send the whole poster grid to torrent indexes. Only the opened title is searched.
        for (int i = 0; i < Math.min(100, checks.length()); i++) {
            JSONObject check = checks.optJSONObject(i);
            if (check == null || !"movie".equals(check.optString("type"))) continue;
            Cinema.Film film = Cinema.exactTitle(check.optString("title"));
            if (film != null && film.year.equals(check.optString("year"))) known.put(check.optString("key"), 1);
        }
        return ok(known);
    }

    private Reply search(Uri uri, boolean deep) throws JSONException {
        String title = V90Online.searchText(param(uri, "title", "")), year = param(uri, "year", ""), type = param(uri, "type", "movie");
        if (title.length() < 2 || !(type.equals("movie") || type.equals("tv") || type.equals("anime"))) return fail(400, "Choose a title to search.");
        int season = number(uri, "season", 1), episode = number(uri, "episode", 0);
        String alternative = V90Online.searchText(param(uri, "alt", ""));
        Map<String, JSONObject> distinct = new LinkedHashMap<>();
        JSONArray providers = new JSONArray();
        Cinema.Film film = "movie".equals(type) ? Cinema.exactTitle(title) : null;
        if (film != null && (film.year.equals(year) || year.isEmpty())) {
            try {
                TorrentMeta meta = film.metadata(app);
                distinct.put(film.hash, V90Online.source(film.title + " · Blender open film · Full " + film.fileName.substring(film.fileName.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT),
                        film.magnet(), meta.items.get(film.index).length, 0, "Official open film"));
                providers.put("Official open film");
            } catch (IOException ignored) { }
        }
        for (MediaEntry local : db.saved()) {
            if (!local.title.equalsIgnoreCase(title) || !local.source.startsWith("magnet:?")) continue;
            try { distinct.putIfAbsent(TorrentMeta.hex(TorrentMeta.magnet(local.source).hash), V90Online.source(local.title, local.source, 0, 0, "Your Library")); }
            catch (IOException ignored) { }
        }
        String index = deep ? "Torrents-CSV" : "Knaben", indexStatus = "skipped";
        String note = "Source counts are reported by the index. BlackBox checks the actual manifest and pieces before playing.";
        // Pinned open-film manifests can be shown immediately, even without an index response.
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
        } else note = film != null ? "The original Blender film is available from an exact, verified descriptor." : "Matching sources from your Library.";
        JSONArray sources = new JSONArray();
        for (JSONObject source : distinct.values()) sources.put(source);
        return ok(new JSONObject().put("sources", sources).put("note", note).put("providers", providers).put("index", index).put("indexStatus", indexStatus));
    }

    private synchronized Reply resolve(String source) throws JSONException {
        String link = source.trim();
        if (link.length() > 12000 || !(link.regionMatches(true, 0, "magnet:?", 0, 8) || MediaSources.torrentDescriptor(link)))
            return fail(400, "Use a v1 magnet, an HTTPS .torrent URL, or choose a .torrent document in the Android picker.");
        if (jobs.size() >= 12) {
            java.util.Iterator<Resolve> old = jobs.values().iterator();
            while (old.hasNext() && jobs.size() >= 12) if (!old.next().working()) old.remove();
            if (jobs.size() >= 12) return fail(429, "Too many torrent lookups. Cancel one and try again.");
        }
        Resolve job = new Resolve(); jobs.put(job.id, job);
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
        for (Cinema.Film film : Cinema.FILMS) if (film.hash.equals(hash)) return film.metadata(app).withMagnet(magnet);
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
