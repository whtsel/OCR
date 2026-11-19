package com.blackboxstream.mobile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The v90 metadata and source providers. These requests never carry the phone library or a TMDB key. */
final class V90Online {
    static final String KNABEN = "https://api.knaben.org/v1";
    static final String CSV = "https://torrents-csv.com/service/search";
    static final String JIKAN = "https://api.jikan.moe/v4";
    private static final int LIMIT = 2 * 1024 * 1024;
    private static final String PUBLIC_TRACKERS = "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
            + "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce";

    interface Requester { String get(String endpoint, JSONObject post) throws IOException; }
    private final Requester http;
    private final Map<String, Cached> cache = new LinkedHashMap<String, Cached>(48, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> e) { return size() > 48; }
    };

    private static final class Cached {
        final JSONArray entries;
        final long until = System.nanoTime() + 120_000_000_000L;
        Cached(JSONArray entries) { this.entries = entries; }
    }

    V90Online() { this(V90Online::request); }
    V90Online(Requester http) { this.http = http; }

    static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    static String searchText(String s) {
        String clean = s == null ? "" : s.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        return clean.substring(0, Math.min(130, clean.length()));
    }

    private static String words(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static boolean releaseMatches(String title, String year, String type, int season, int episode, String release) {
        if (release == null || release.length() > 360 || release.matches("(?i).*(?:\\.(?:exe|msi|apk|zip|rar|iso|bat|scr|cmd|js)|\\bpassword\\b).*")) return false;
        String actual = words(release), target = words(title);
        if (target.isEmpty() || !(actual.equals(target) || actual.startsWith(target + " "))) return false;
        if ("tv".equals(type)) {
            if (episode <= 0) return true;
            if (season < 0 || season > 999 || episode > 9999) return false;
            String series = "(?:^| )s0*" + season + "[ ]?e0*" + episode + "(?:$|[^0-9])";
            String alt = "(?:^| )0*" + season + "x0*" + episode + "(?:$|[^0-9])";
            return actual.matches(".*" + series + ".*") || actual.matches(".*" + alt + ".*");
        }
        return !year.matches("[0-9]{4}") || (" " + actual.substring(target.length()).trim() + " ").contains(" " + year + " ");
    }

    JSONArray sources(boolean deep, String title, String year, String type, int season, int episode) throws IOException, JSONException {
        String clean = searchText(title), y = year.matches("[0-9]{4}") ? year : "";
        if (clean.length() < 2) return new JSONArray();
        String tail = "tv".equals(type) && season >= 0 && episode > 0 ? String.format(Locale.ROOT, " S%02dE%02d", season, episode)
                : !"tv".equals(type) && !y.isEmpty() ? " " + y : "";
        String key = deep + ":" + clean.toLowerCase(Locale.ROOT) + tail;
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && hit.until - System.nanoTime() > 0) return hit.entries;
        }
        String text;
        if (deep) text = http.get(CSV + "?q=" + encode(clean + tail) + "&size=40", null);
        else text = http.get(KNABEN, new JSONObject().put("search_type", "100%").put("search_field", "title")
                .put("query", clean + tail).put("size", 40).put("order_by", "seeders").put("order_direction", "desc")
                .put("hide_unsafe", true).put("hide_xxx", true));
        JSONArray items = parseSources(text, deep, clean, y, type, season, episode);
        synchronized (cache) { cache.put(key, new Cached(items)); }
        return items;
    }

    static JSONArray parseSources(String text, boolean deep, String title, String year, String type, int season, int episode) throws JSONException {
        JSONArray rows = new JSONObject(text).getJSONArray(deep ? "torrents" : "hits");
        Map<String, JSONObject> exact = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(150, rows.length()); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String name = row.optString(deep ? "name" : "title"), category = row.optString("category").toLowerCase(Locale.ROOT);
            if (!releaseMatches(title, year, type, season, episode, name) || category.matches(".*(?:adult|xxx|software|games|porn).*")) continue;
            String hash = row.optString(deep ? "infohash" : "hash").toLowerCase(Locale.ROOT);
            String candidate = row.optString("magnetUrl");
            if (candidate.regionMatches(true, 0, "magnet:?", 0, 8)) {
                try {
                    String reported = TorrentMeta.hex(TorrentMeta.magnet(candidate).hash);
                    if (hash.matches("[a-f0-9]{40}") && !hash.equals(reported)) continue;
                    hash = reported;
                } catch (IOException bad) { continue; }
            }
            if (!hash.matches("[a-f0-9]{40}")) continue;
            // Search indexes aren't trusted to direct the phone to private peers, signed URLs or metadata hosts.
            // Keep the identity only. Known public trackers and the standard DHT locate the exact swarm.
            long bytes = row.optLong(deep ? "size_bytes" : "bytes", 0);
            long seeds = Math.max(0, row.optLong("seeders", 0));
            if (bytes < 0 || bytes > TorrentMeta.MAX_SWARM_BYTES) continue;
            JSONObject current = exact.get(hash);
            if (current != null && current.optLong("seeders") >= seeds) continue;
            exact.put(hash, source(name, "magnet:?xt=urn:btih:" + hash + PUBLIC_TRACKERS, bytes, seeds,
                    deep ? "Torrents-CSV" : "Knaben"));
        }
        List<JSONObject> bySeeds = new ArrayList<>(exact.values());
        bySeeds.sort(Comparator.comparingLong((JSONObject item) -> item.optLong("seeders")).reversed());
        JSONArray result = new JSONArray();
        for (JSONObject s : bySeeds) if (result.length() < 40) result.put(s);
        return result;
    }

    static JSONObject source(String title, String magnet, long bytes, long seeds, String provider) throws JSONException {
        return new JSONObject().put("title", MediaEntry.cleanTitle(title)).put("magnet", magnet)
                .put("sizeBytes", bytes).put("seeders", seeds).put("provider", provider);
    }

    JSONObject anime(String mode, String q, int page) throws IOException, JSONException {
        // v90 talks to Jikan and expects a flat list of anime title/format/cover records.
        if (mode.equals("search") && searchText(q).isEmpty()) return new JSONObject().put("items", new JSONArray());
        String route = mode.equals("search") ? "/anime?sfw=true&q=" + encode(searchText(q))
                : "/top/anime?sfw=true" + (mode.equals("airing") ? "&filter=airing" : "");
        return parseAnime(http.get(JIKAN + route + "&limit=24&page=" + Math.max(1, Math.min(100, page)), null));
    }

    static JSONObject parseAnime(String text) throws JSONException {
        JSONObject root = new JSONObject(text), paging = root.optJSONObject("pagination");
        JSONArray data = root.getJSONArray("data"), items = new JSONArray();
        for (int i = 0; i < Math.min(data.length(), 30); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null || row.optLong("mal_id") <= 0) continue;
            JSONObject images = row.optJSONObject("images"), jpg = images == null ? null : images.optJSONObject("jpg");
            JSONArray genres = new JSONArray(), names = row.optJSONArray("genres");
            if (names != null) for (int n = 0; n < Math.min(20, names.length()); n++) {
                JSONObject g = names.optJSONObject(n); if (g != null) genres.put(g.optString("name"));
            }
            String type = row.optString("type", "TV"), status = row.optString("status");
            items.put(new JSONObject().put("id", row.optLong("mal_id")).put("title", row.optString("title"))
                    .put("romaji", row.optString("title_english")).put("native", row.optString("title_japanese"))
                    .put("episodes", row.optInt("episodes")).put("year", row.optInt("year"))
                    .put("score", Math.round(10 * row.optDouble("score", 0))).put("popularity", row.optInt("members"))
                    .put("cover", jpg == null ? "" : jpg.optString("large_image_url", jpg.optString("image_url")))
                    .put("overview", row.optString("synopsis")).put("genres", genres)
                    .put("format", type.toUpperCase(Locale.ROOT)).put("status", status.equals("Not yet aired") ? "NOT_YET_RELEASED" : "RELEASED"));
        }
        int pages = paging == null ? 1 : paging.optInt("last_visible_page", 1);
        return new JSONObject().put("items", items).put("total_pages", pages).put("has_more", paging != null && paging.optBoolean("has_next_page"));
    }

    private static String request(String address, JSONObject post) throws IOException {
        HttpsURLConnection req = (HttpsURLConnection) URI.create(address).toURL().openConnection();
        req.setConnectTimeout(5500); req.setReadTimeout(8000); req.setInstanceFollowRedirects(false);
        req.setRequestProperty("Accept", "application/json"); req.setRequestProperty("User-Agent", "BlackBoxStream-Android/1.3");
        try {
            if (post != null) {
                byte[] bytes = post.toString().getBytes(StandardCharsets.UTF_8);
                req.setRequestMethod("POST"); req.setDoOutput(true); req.setFixedLengthStreamingMode(bytes.length);
                req.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = req.getOutputStream()) { out.write(bytes); }
            }
            int code = req.getResponseCode();
            if (code != 200) throw new IOException("Provider returned HTTP " + code + ".");
            if (req.getContentLengthLong() > LIMIT) throw new IOException("Provider response is too large.");
            return new String(TorrentMeta.slurp(req.getInputStream(), LIMIT), StandardCharsets.UTF_8);
        } finally { req.disconnect(); }
    }
}
