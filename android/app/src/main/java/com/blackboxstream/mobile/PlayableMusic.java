package com.blackboxstream.mobile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Official Incompetech CC BY music feed. Only single filenames from this licensed index become media sources. */
final class PlayableMusic {
    static final String FEED = "https://incompetech.com/music/royalty-free/pieces.json";
    static final String AUDIO = "https://incompetech.com/music/royalty-free/mp3-royaltyfree/";
    static final String PAGE = "https://incompetech.com/music/royalty-free/index.html?isrc=";
    static final String ARTIST = "Kevin MacLeod";
    static final String LICENSE = "https://creativecommons.org/licenses/by/4.0/";
    static final String CREDIT = "Kevin MacLeod (incompetech.com) · Creative Commons Attribution 4.0";
    private static final int PAGE_SIZE = 24;
    private static final Map<Integer, String> GENRES;
    static {
        Map<Integer, String> names = new LinkedHashMap<>();
        String[] order = {"", "", "African", "Blues", "Classical", "Contemporary", "Disco", "Electronica", "Funk", "Holiday",
                "Horror", "Jazz", "Latin", "Modern", "Musical", "Polka", "Pop", "", "Reggae", "Rock", "Silent Film Score",
                "Ska", "Soundtrack", "Stings", "Unclassified", "World", "Urban"};
        for (int i = 0; i < order.length; i++) if (!order[i].isEmpty()) names.put(i, order[i]);
        GENRES = Collections.unmodifiableMap(names);
    }

    static final class Track {
        final String isrc, title, filename, keywords;
        final int genre, seconds;
        Track(String isrc, String title, String filename, int genre, int seconds, String keywords) {
            this.isrc = isrc; this.title = title; this.filename = filename; this.genre = genre; this.seconds = seconds;
            this.keywords = V90Online.words(title + " " + ARTIST + " " + genreName(genre) + " " + keywords);
        }
        MediaEntry entry() {
            return new MediaEntry(title + " — " + ARTIST, MediaEntry.Kind.MUSIC,
                    AUDIO + V90Online.encode(filename).replace("+", "%20"));
        }
        JSONObject item() throws JSONException {
            return new JSONObject().put("key", "music:" + isrc).put("title", title).put("artist", ARTIST)
                    .put("genre", genreName(genre)).put("seconds", seconds).put("credits", CREDIT)
                    .put("reference", PAGE + isrc).put("license", LICENSE).put("downloadable", true);
        }
    }

    private static final List<Track> FEATURED = Collections.unmodifiableList(Arrays.asList(
        new Track("USUAN2300007", "Cloud Dancer", "Cloud Dancer.mp3", 7, 220, "EDM synthwave"),
        new Track("USUAN2600004", "The Britons", "The Britons.mp3", 22, 307, "medieval lute flute"),
        new Track("USUAN1900058", "Maple Leaf Rag", "Maple Leaf Rag.mp3", 20, 179, "Scott Joplin piano ragtime"),
        new Track("USUAN2400001", "That Zen Moment", "That Zen Moment.mp3", 25, 602, "relaxing ambient"),
        new Track("USUAN2500001", "Cretaceous Dawn", "Cretaceous Dawn.mp3", 25, 245, "drums action"),
        new Track("USUAN2300011", "Mesmerizing Galaxy", "Mesmerizing Galaxy Loop.mp3", 7, 93, "synth game"),
        new Track("USUAN2300009", "Galactic Rap", "Galactic Rap.mp3", 7, 142, "synths rap"),
        new Track("USUAN2300008", "Equatorial Complex", "Equatorial Complex.mp3", 7, 240, "synthwave relaxing")
    ));

    private final V90Online.Requester http;
    private volatile List<Track> catalogue;
    private long nextFetch;
    private boolean fetching;
    PlayableMusic() { this(V90Online::request); }
    PlayableMusic(V90Online.Requester http) { this.http = http; }

    private static String genreName(int genre) { return GENRES.containsKey(genre) ? GENRES.get(genre) : "Instrumental"; }

    Track track(String isrc) {
        if (!isrc.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) return null;
        Track found = find(catalogue, isrc);
        if (found == null) found = find(FEATURED, isrc);
        return found;
    }
    private static Track find(List<Track> list, String id) {
        if (list != null) for (Track t : list) if (t.isrc.equals(id)) return t;
        return null;
    }

    JSONObject search(String query, int genre, int offset, boolean featured) throws JSONException {
        String cleaned = V90Online.words(V90Online.searchText(query));
        if (!featured) fetch();
        List<Track> live = catalogue;
        List<Track> source = featured || live == null ? FEATURED : live;
        List<Track> matched = new ArrayList<>();
        for (Track t : source) {
            if (genre != 0 && t.genre != genre) continue;
            if (!query.trim().isEmpty() && cleaned.isEmpty()) continue;
            boolean match = true;
            for (String term : cleaned.split(" ")) if (!t.keywords.contains(term)) { match = false; break; }
            if (match) matched.add(t);
        }
        if (!cleaned.isEmpty()) matched.sort((a, b) -> Boolean.compare(V90Online.words(b.title).equals(cleaned), V90Online.words(a.title).equals(cleaned)));
        int skip = Math.max(0, Math.min(source.size(), offset));
        JSONArray results = new JSONArray(), genres = new JSONArray();
        for (int i = skip; i < Math.min(matched.size(), skip + PAGE_SIZE); i++) results.put(matched.get(i).item());
        for (Map.Entry<Integer, String> tag : GENRES.entrySet()) genres.put(new JSONObject().put("id", tag.getKey()).put("label", tag.getValue()));
        return new JSONObject().put("results", results).put("total", matched.size()).put("offset", skip)
                .put("more", skip + results.length() < matched.size()).put("genres", genres)
                .put("provider", "Incompetech").put("featuredOnly", live == null && !featured);
    }

    private void fetch() {
        synchronized (this) {
            if (fetching || nextFetch != 0 && nextFetch - System.nanoTime() > 0) return;
            fetching = true;
        }
        long delay = 45_000_000_000L;
        try {
            List<Track> loaded = parse(http.get(FEED, null));
            if (!loaded.isEmpty()) { catalogue = loaded; delay = 21_600_000_000_000L; }
        } catch (IOException | JSONException ignored) {
            // The pinned featured tracks are independent of availability of the metadata feed.
        } finally {
            synchronized (this) { nextFetch = System.nanoTime() + delay; fetching = false; }
        }
    }

    static List<Track> parse(String json) throws JSONException {
        JSONArray rows = new JSONArray(json);
        Map<String, Track> tracks = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(rows.length(), 4000); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String title = row.optString("title", "").trim(), file = row.optString("filename", "").trim();
            String id = row.optString("isrc", "").trim().toUpperCase(Locale.ROOT);
            if (title.isEmpty() || title.length() > 130 || file.length() > 180
                    || !file.matches("(?i)[^/\\\\:?#%\\p{Cc}\\p{Cf}]+\\.mp3")
                    || !id.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) continue;
            int seconds = duration(row.optString("length", ""));
            if (seconds < 1) continue;
            String keywords = V90Online.searchText(row.optString("instruments", "")) + " " + V90Online.searchText(row.optString("feel", ""));
            tracks.putIfAbsent(id, new Track(id, MediaEntry.cleanTitle(title), file, row.optInt("genre"), seconds, keywords));
        }
        return Collections.unmodifiableList(new ArrayList<>(tracks.values()));
    }

    private static int duration(String text) {
        if (!text.matches("[0-9]{1,2}:[0-5][0-9]:[0-5][0-9]")) return 0;
        String[] parts = text.split(":");
        return 3600 * Integer.parseInt(parts[0]) + 60 * Integer.parseInt(parts[1]) + Integer.parseInt(parts[2]);
    }
}
