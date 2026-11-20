package com.blackboxstream.mobile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Catalogue {
    static final String TMDB = "https://api.themoviedb.org/3";
    private static final int MAX_REPLY = 1024 * 1024;
    enum Filter {
        MOVIE("Movies"), TV("Series"), ANIME("Anime"), AUDIO("Music"), WATCHLIST("Saved titles");
        final String label;
        Filter(String label) { this.label = label; }
        boolean tmdb() { return this == MOVIE || this == TV || this == ANIME; }
        String endpoint() { return this == MOVIE ? "movie" : "tv"; }
    }
    enum Shelf { TRENDING, TOP_RATED, UPCOMING }
    enum Music {
        TRACKS("recording"), ALBUMS("release-group"), ARTISTS("artist");
        final String path;
        Music(String path) { this.path = path; }
    }

    static final class Card {
        final String id, title, detail, year, credit;
        final MediaEntry.Kind kind;
        final String source, type;
        Card(String id, String title, String detail, String year, String credit, MediaEntry.Kind kind, String source, String type) {
            this.id = id; this.title = MediaEntry.cleanTitle(title); this.detail = detail; this.year = year;
            this.credit = credit; this.kind = kind; this.source = source; this.type = type;
        }

        MediaEntry entry() { return source == null ? null : new MediaEntry(title, kind, source); }
        boolean musicBrainz() { return type != null && type.startsWith("music:"); }
        String listing() {
            if (type == null) return null;
            String key = id.substring(id.indexOf(':') + 1);
            return musicBrainz() ? "https://musicbrainz.org/" + type.substring(6) + "/" + key
                    : "https://www.themoviedb.org/" + type + "/" + key;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("title", title).put("detail", detail).put("year", year)
                    .put("credit", credit).put("kind", kind.name()).put("source", source == null ? JSONObject.NULL : source)
                    .put("type", type == null ? JSONObject.NULL : type);
        }
        static Card saved(JSONObject o) throws JSONException {
            return new Card(o.getString("id"), o.getString("title"), o.optString("detail"), o.optString("year"),
                    o.optString("credit"), MediaEntry.Kind.valueOf(o.getString("kind")),
                    o.isNull("source") ? null : o.optString("source"), o.isNull("type") ? null : o.optString("type"));
        }
    }

    static String credential(String value) {
        String input = value == null ? "" : value.trim();
        if (input.matches("[0-9a-fA-F]{32}") || input.matches("[A-Za-z0-9_-]{6,256}\\.[A-Za-z0-9_-]{6,1600}\\.[A-Za-z0-9_-]{6,400}")) return input;
        throw new IllegalArgumentException("Enter a TMDB v3 API key (32 hex characters) or API Read Access Token.");
    }

    static void validate(String key) throws IOException { fetch(URI.create(TMDB), "/configuration", credential(key)); }

    static List<Card> browse(Filter filter, Shelf shelf, String query, String key, int page) throws IOException {
        return browse(URI.create(TMDB), filter, shelf, query, key, page);
    }

    static List<Card> browse(URI base, Filter filter, Shelf shelf, String query, String key, int page) throws IOException {
        if (!filter.tmdb() || page < 1 || page > 100) throw new IllegalArgumentException("Invalid catalogue page");
        String text = query.trim();
        if (text.length() > 130) throw new IllegalArgumentException("Search with 130 characters or fewer.");
        String type = filter.endpoint(), route;
        if (!text.isEmpty()) route = "/search/" + type + "?include_adult=false&query=" + URLEncoder.encode(text, "UTF-8");
        else if (filter == Filter.ANIME) route = "/discover/tv?include_adult=false&with_genres=16&with_original_language=ja&sort_by="
                + (shelf == Shelf.TOP_RATED ? "vote_average.desc&vote_count.gte=50" : "popularity.desc")
                + (shelf == Shelf.UPCOMING ? "&air_date.gte=" + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date()) : "");
        else if (shelf == Shelf.TRENDING) route = "/trending/" + type + "/week?";
        else if (shelf == Shelf.TOP_RATED) route = "/" + type + "/top_rated?";
        else route = "/" + type + (filter == Filter.MOVIE ? "/upcoming?" : "/on_the_air?");
        return parse(fetch(base, route + "&language=en-US&page=" + page, credential(key)), filter);
    }

    /** TMDB series in the v90 Anime shelf response shape. Never append a playable or local fixture. */
    static JSONObject anime(URI base, String key, boolean airing, int page) throws IOException, JSONException {
        String route = "/discover/tv?include_adult=false&with_genres=16&with_original_language=ja&page=" + page;
        if (airing) {
            long today = System.currentTimeMillis(), day = 86_400_000L;
            java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
            date.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            route += "&air_date.gte=" + date.format(new java.util.Date(today - 60 * day))
                    + "&air_date.lte=" + date.format(new java.util.Date(today + 30 * day)) + "&sort_by=popularity.desc";
        } else route += "&sort_by=vote_average.desc&vote_count.gte=50";
        JSONObject reply = new JSONObject(fetch(base, route, credential(key)));
        JSONArray results = reply.getJSONArray("results"), items = new JSONArray();
        for (int i = 0; i < results.length(); i++) {
            JSONObject row = results.optJSONObject(i);
            if (row != null && row.optLong("id", 0) > 0 && !row.optBoolean("adult")
                    && "ja".equals(row.optString("original_language"))) {
                JSONArray genres = row.optJSONArray("genre_ids");
                if (genres != null) for (int j = 0; j < genres.length(); j++) if (genres.optInt(j) == 16) { items.put(row); break; }
            }
        }
        return new JSONObject().put("items", items).put("pagination",
                new JSONObject().put("lastPage", Math.max(1, Math.min(500, reply.optInt("total_pages", 1)))));
    }

    static List<Card> parse(String content, Filter filter) throws IOException {
        try {
            JSONArray rows = new JSONObject(content).getJSONArray("results");
            List<Card> cards = new ArrayList<>();
            for (int i = 0; i < rows.length() && i < 60; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || row.optBoolean("adult") || row.optLong("id", 0) <= 0) continue;
                String type = filter.endpoint();
                if (filter == Filter.ANIME) {
                    JSONArray genres = row.optJSONArray("genre_ids"); boolean animation = false;
                    if (genres != null) for (int g = 0; g < genres.length(); g++) if (genres.optInt(g) == 16) animation = true;
                    if (!animation || !"ja".equals(row.optString("original_language"))) continue;
                }
                boolean movie = filter == Filter.MOVIE;
                String title = row.optString(movie ? "title" : "name").trim();
                if (title.isEmpty()) continue;
                String aired = row.optString(movie ? "release_date" : "first_air_date");
                String overview = row.optString("overview");
                if (overview.length() > 1000) overview = overview.substring(0, 1000) + "…";
                cards.add(new Card(type + ":" + row.optLong("id"), title, overview,
                        aired.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? aired.substring(0, 4) : "",
                        "TMDB · metadata only", movie ? MediaEntry.Kind.MOVIE : filter == Filter.ANIME ? MediaEntry.Kind.ANIME : MediaEntry.Kind.SERIES, null, type));
            }
            return cards;
        } catch (JSONException | StackOverflowError invalid) { throw new IOException("The catalogue sent an invalid response."); }
    }

    static List<Card> music(Music scope, String text) throws IOException {
        return music(URI.create("https://musicbrainz.org/ws/2"), scope, text);
    }

    static List<Card> music(URI base, Music scope, String text) throws IOException {
        String value = text.trim();
        if (value.isEmpty() || value.length() > 130) throw new IllegalArgumentException("Enter a track, album or artist name (up to 130 characters).");
        String field = scope == Music.TRACKS ? "recording" : scope == Music.ARTISTS ? "artist" : "releasegroup";
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        String query = URLEncoder.encode(field + ":\"" + escaped + "\"", "UTF-8");
        HttpsURLConnection request = (HttpsURLConnection) URI.create(base.toASCIIString() + "/" + scope.path + "/?fmt=json&limit=20&query=" + query).toURL().openConnection();
        request.setConnectTimeout(8000); request.setReadTimeout(9000); request.setInstanceFollowRedirects(false);
        request.setRequestProperty("Accept", "application/json");
        request.setRequestProperty("User-Agent", "BlackBoxStream/1.1 (https://github.com/whtsel/OCR)");
        try {
            int status = request.getResponseCode();
            if (status == 429 || status == 503) throw new IOException("MusicBrainz is busy. Wait a moment and try again.");
            if (status != 200) throw new IOException("MusicBrainz returned HTTP " + status + ".");
            return parseMusic(new String(TorrentMeta.slurp(request.getInputStream(), MAX_REPLY), StandardCharsets.UTF_8), scope);
        } finally { request.disconnect(); }
    }

    static List<Card> parseMusic(String data, Music scope) throws IOException {
        try {
            JSONArray rows = new JSONObject(data).getJSONArray(scope.path + "s");
            List<Card> cards = new ArrayList<>();
            for (int i = 0; i < rows.length() && i < 25; i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id"), title = item.optString(scope == Music.ARTISTS ? "name" : "title");
                if (!id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") || title.trim().isEmpty()) continue;
                String description = item.optString("disambiguation", "");
                JSONArray credits = item.optJSONArray("artist-credit");
                if (credits != null && credits.length() > 0) {
                    JSONObject credit = credits.optJSONObject(0);
                    if (credit != null) {
                        String artist = credit.optString("name", "");
                        if (artist.isEmpty() && credit.optJSONObject("artist") != null) artist = credit.optJSONObject("artist").optString("name", "");
                        if (!artist.isEmpty()) description = artist + (description.isEmpty() ? "" : " · " + description);
                    }
                }
                String date = item.optString("first-release-date");
                cards.add(new Card("music:" + id, title, description.length() > 400 ? description.substring(0, 400) : description,
                        date.matches("[0-9]{4}(?:-[0-9]{2}(?:-[0-9]{2})?)?") ? date.substring(0, 4) : "",
                        "MusicBrainz · metadata only", MediaEntry.Kind.MUSIC, null, "music:" + scope.path));
            }
            return cards;
        } catch (JSONException | StackOverflowError invalid) { throw new IOException("MusicBrainz sent an invalid catalogue response."); }
    }

    static String fetch(URI base, String path, String key) throws IOException {
        boolean apiKey = key.length() == 32 && key.matches("[0-9a-fA-F]{32}");
        String url = base.toASCIIString() + path + (apiKey ? (path.contains("?") ? "&" : "?") + "api_key=" + key : "");
        HttpsURLConnection request = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        request.setConnectTimeout(8000); request.setReadTimeout(9000); request.setInstanceFollowRedirects(false);
        request.setRequestProperty("Accept", "application/json");
        if (!apiKey) request.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int status = request.getResponseCode();
            if (status == 401 || status == 403) throw new IOException("TMDB did not accept this credential. Check your API key or Read Access Token.");
            if (status == 429) throw new IOException("Catalogue rate limit reached. Wait a moment and retry.");
            if (status != 200) throw new IOException("Catalogue returned HTTP " + status + ".");
            if (request.getContentLengthLong() > MAX_REPLY) throw new IOException("Catalogue response is too large.");
            return new String(TorrentMeta.slurp(request.getInputStream(), MAX_REPLY), StandardCharsets.UTF_8);
        } finally { request.disconnect(); }
    }
}
