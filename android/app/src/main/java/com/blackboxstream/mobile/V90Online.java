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
    static final String ANILIST = "https://graphql.anilist.co";
    private static final int LIMIT = 2 * 1024 * 1024;
    private static final String PUBLIC_TRACKERS = "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
            + "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce";

    interface Requester { String get(String endpoint, JSONObject post) throws IOException; }
    private final Requester http;
    private volatile long retryJikanAt;
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

    static String words(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static boolean releaseMatches(String title, String year, String type, int season, int episode, String release) {
        if (release == null || release.length() > 360 || release.matches("(?i).*(?:\\.(?:exe|msi|apk|zip|rar|iso|bat|scr|cmd|js)|\\bpassword\\b).*")) return false;
        boolean anime = "anime".equals(type);
        String label = anime || "anime-film".equals(type) ? release.replaceFirst("^\\s*\\[[^\\]\\r\\n]{1,64}\\]\\s*", "") : release;
        String actual = words(label), target = words(title);
        if (target.isEmpty() || !(actual.equals(target) || actual.startsWith(target + " "))) return false;
        if ("tv".equals(type) || anime) {
            if (episode <= 0) return true;
            if (season < 0 || season > 999 || episode > 9999) return false;
            String series = "(?:^| )s0*" + season + "[ ]?e0*" + episode + "(?:$|[^0-9])";
            String alt = "(?:^| )0*" + season + "x0*" + episode + "(?:$|[^0-9])";
            if (actual.matches(".*" + series + ".*") || actual.matches(".*" + alt + ".*")) return true;
            // Anime release groups also label single episodes as "Title - 03" or "Title E03".
            String rest = actual.substring(target.length()).trim();
            return anime && season == 1 && rest.matches("(?:e(?:p(?:isode)?)? ?)?0*" + episode + "(?:v[0-9]+)?(?:$| .*)");
        }
        return !year.matches("[0-9]{4}") || (" " + actual.substring(target.length()).trim() + " ").contains(" " + year + " ");
    }

    JSONArray sources(boolean deep, String title, String year, String type, int season, int episode) throws IOException, JSONException {
        String clean = searchText(title), y = year.matches("[0-9]{4}") ? year : "";
        if (clean.length() < 2) return new JSONArray();
        String tail = "tv".equals(type) && season >= 0 && episode > 0 ? String.format(Locale.ROOT, " S%02dE%02d", season, episode)
                : ("movie".equals(type) || "anime-film".equals(type)) && !y.isEmpty() ? " " + y : "";
        String key = deep + ":" + type + ":" + season + ":" + episode + ":" + clean.toLowerCase(Locale.ROOT) + tail;
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
            String name = text(row, deep ? "name" : "title"), category = text(row, "category").toLowerCase(Locale.ROOT);
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
        // Keep the v90 shape even if Jikan cannot be reached on this network.
        boolean search = "search".equals(mode), airing = "airing".equals(mode);
        String title = searchText(q);
        int limit = search ? 12 : 24, number = Math.max(1, Math.min(100, page));
        if (search && title.isEmpty()) return animePage(new JSONArray(), 1, false, "");
        if (retryJikanAt == 0 || System.nanoTime() - retryJikanAt >= 0) {
            String route = search ? "/anime?sfw=true&q=" + encode(title) : "/top/anime?sfw=true" + (airing ? "&filter=airing" : "");
            try {
                return parseAnime(http.get(JIKAN + route + "&limit=" + limit + "&page=" + number, null));
            } catch (IOException | JSONException unreachable) { retryJikanAt = System.nanoTime() + 120_000_000_000L; }
        }
        String query = "query Catalogue($page:Int!,$limit:Int!" + (search ? ",$title:String!" : "") + "){"
                + "Page(page:$page,perPage:$limit){pageInfo{lastPage hasNextPage}media(type:ANIME,isAdult:false,"
                + (search ? "search:$title,sort:[SEARCH_MATCH]" : airing ? "status:RELEASING,sort:[POPULARITY_DESC]" : "sort:[SCORE_DESC]")
                + "){id idMal title{english romaji native} format episodes startDate{year} popularity averageScore genres status "
                + "description(asHtml:false) coverImage{large} bannerImage trailer{id site}}}}";
        JSONObject variables = new JSONObject().put("page", number).put("limit", limit);
        if (search) variables.put("title", title);
        try {
            return parseAniList(http.get(ANILIST, new JSONObject().put("query", query).put("variables", variables)));
        } catch (IOException | JSONException unreachable) {
            throw new IOException("Jikan and AniList did not return an anime catalogue. You can still look up an exact source by title.", unreachable);
        }
    }

    static JSONObject parseAniList(String json) throws JSONException {
        JSONObject data = new JSONObject(json).getJSONObject("data").getJSONObject("Page");
        JSONArray media = data.getJSONArray("media"), items = new JSONArray();
        for (int i = 0; i < Math.min(media.length(), 30); i++) {
            JSONObject row = media.optJSONObject(i);
            if (row == null || row.optLong("id") <= 0) continue;
            JSONObject titles = row.optJSONObject("title"), date = row.optJSONObject("startDate");
            JSONObject cover = row.optJSONObject("coverImage"), trailer = row.optJSONObject("trailer");
            if (titles == null) continue;
            String en = text(titles, "english"), romaji = text(titles, "romaji");
            if (en.isEmpty() && romaji.isEmpty()) continue;
            long mal = row.optLong("idMal", 0);
            String video = trailer != null && "youtube".equalsIgnoreCase(text(trailer, "site")) ? text(trailer, "id") : "";
            items.put(new JSONObject().put("id", mal > 0 ? Long.toString(mal) : "anilist" + row.optLong("id"))
                    .put("title", en.isEmpty() ? romaji : en).put("romaji", romaji).put("native", text(titles, "native"))
                    .put("year", date == null ? 0 : date.optInt("year")).put("episodes", row.optInt("episodes"))
                    .put("score", row.optInt("averageScore")).put("popularity", row.optInt("popularity"))
                    .put("format", text(row, "format")).put("status", text(row, "status"))
                    .put("cover", cover == null ? "" : text(cover, "large")).put("banner", text(row, "bannerImage"))
                    .put("overview", text(row, "description")).put("genres", row.optJSONArray("genres") == null ? new JSONArray() : row.getJSONArray("genres"))
                    .put("trailer", video.matches("[A-Za-z0-9_-]{11}") ? video : ""));
        }
        JSONObject pages = data.optJSONObject("pageInfo");
        return animePage(items, pages == null ? 1 : pages.optInt("lastPage", 1), pages != null && pages.optBoolean("hasNextPage"), "AniList");
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
            JSONObject aired = row.optJSONObject("aired"), youtube = row.optJSONObject("trailer");
            String type = text(row, "type"), status = text(row, "status"), start = aired == null ? "" : text(aired, "from");
            int year = row.optInt("year", 0);
            if (year == 0 && start.matches("[0-9]{4}-.*")) year = Integer.parseInt(start.substring(0, 4));
            String primary = text(row, "title"), english = text(row, "title_english"), cover = jpg == null ? "" : text(jpg, "large_image_url");
            if (cover.isEmpty() && jpg != null) cover = text(jpg, "image_url");
            if (primary.isEmpty() && english.isEmpty()) continue;
            String trailer = youtube == null ? "" : text(youtube, "youtube_id");
            items.put(new JSONObject().put("id", row.optLong("mal_id")).put("title", english.isEmpty() ? primary : english)
                    .put("romaji", primary).put("native", text(row, "title_japanese"))
                    .put("episodes", row.optInt("episodes")).put("year", year)
                    .put("score", Math.round(10 * row.optDouble("score", 0))).put("popularity", row.optInt("members"))
                    .put("cover", cover).put("overview", text(row, "synopsis")).put("genres", genres)
                    .put("format", type.toUpperCase(Locale.ROOT)).put("status", status.equals("Not yet aired") ? "NOT_YET_RELEASED" : "RELEASED")
                    .put("trailer", trailer.matches("[A-Za-z0-9_-]{11}") ? trailer : ""));
        }
        int pages = paging == null ? 1 : Math.max(1, paging.optInt("last_visible_page", 1));
        boolean more = paging != null && paging.optBoolean("has_next_page");
        return animePage(items, pages, more, "Jikan");
    }

    private static JSONObject animePage(JSONArray items, int pages, boolean more, String provider) throws JSONException {
        return new JSONObject().put("items", items).put("provider", provider)
                .put("pagination", new JSONObject().put("lastPage", Math.max(1, pages)).put("hasNextPage", more));
    }

    private static String text(JSONObject row, String name) { return row.isNull(name) ? "" : row.optString(name, ""); }

    static String request(String address, JSONObject post) throws IOException {
        HttpsURLConnection req = (HttpsURLConnection) URI.create(address).toURL().openConnection();
        req.setConnectTimeout(5500); req.setReadTimeout(8000); req.setInstanceFollowRedirects(false);
        req.setRequestProperty("Accept", "application/json"); req.setRequestProperty("User-Agent", MediaSources.USER_AGENT);
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
