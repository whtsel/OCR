package com.blackboxstream.mobile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ServiceChecks {
    private static int assertions;
    private static void check(String title, boolean passed) {
        if (!passed) throw new AssertionError(title);
        System.out.println("PASS " + title);
        assertions++;
    }
    private interface Check { void run() throws Exception; }
    private static void rejected(String title, Check check) throws Exception {
        try { check.run(); } catch (IOException | IllegalArgumentException intended) { check(title, true); return; }
        throw new AssertionError(title);
    }
    public static void main(String[] args) throws Exception {
        String relay = args[0], key = "aabbccddeeff1100aabbccddeeff1100";
        URI base = URI.create(relay + "/3");
        String readToken = "header64.payload64.signature64";
        List<Catalogue.Card> cards = Catalogue.browse(base, Catalogue.Filter.MOVIE, Catalogue.Shelf.TRENDING, "A + B", key, 1);
        check("TMDB search encodes punctuation and uses v3 query auth", cards.size() == 1 && "A + B".equals(cards.get(0).title)
                && "2024".equals(cards.get(0).year) && cards.get(0).listing().equals("https://www.themoviedb.org/movie/76"));
        check("catalogue result never pretends metadata is a playable stream", cards.get(0).entry() == null);
        cards = Catalogue.browse(base, Catalogue.Filter.ANIME, Catalogue.Shelf.TOP_RATED, "", readToken, 2);
        check("Bearer auth and anime shelf filter use the correct parameters", cards.size() == 1 && cards.get(0).kind == MediaEntry.Kind.ANIME);
        check("saved catalogue detail round-trips", Catalogue.Card.saved(cards.get(0).json()).title.equals(cards.get(0).title));
        rejected("TMDB auth failure surfaces as a client error", () -> Catalogue.browse(base, Catalogue.Filter.TV, Catalogue.Shelf.UPCOMING, "", key, 1));
        cards = Catalogue.music(URI.create(relay + "/ws/2"), Catalogue.Music.TRACKS, "Low Orbit");
        check("MusicBrainz search returns real-shaped metadata and a canonical listing", cards.size() == 1 && cards.get(0).musicBrainz()
                && cards.get(0).listing().equals("https://musicbrainz.org/recording/a9b2b000-fef0-4444-9999-adababa00002"));
        check("open catalogue includes immediately available offline samples", Catalogue.open(false).get(0).entry().bundled()
                && Catalogue.open(true).get(0).entry().kind == MediaEntry.Kind.MUSIC);

        verifyV90Providers();

        CommunityRoom alice = CommunityRoom.create(relay), bob = CommunityRoom.parse(alice.code());
        check("invitation derives the same room without sending its key", alice.roomId.equals(bob.roomId) && !alice.code().contains(alice.roomId));
        check("empty room joins successfully", bob.fetch(0).events.isEmpty());
        CommunityRoom.Event review = CommunityRoom.Event.make("Alice", "Secret title Ω", "https://media.example/v.mp4?demo=1");
        byte[] sealed = alice.seal(review);
        check("posting uses a fresh nonce every time", !java.util.Arrays.equals(sealed, alice.seal(review)));
        rejected("an unrelated room cannot decrypt a valid envelope", () -> CommunityRoom.create(relay).open(1, sealed));
        byte[] changed = sealed.clone(); changed[changed.length - 1] ^= 1;
        rejected("modified envelopes do not authenticate", () -> bob.open(1, changed));
        alice.post(review);
        CommunityRoom.Feed page = bob.fetch(0);
        check("second client receives decrypted recommendation through the real relay", page.events.size() == 1
                && page.events.get(0).name.equals("Alice") && page.events.get(0).text.equals("Secret title Ω") && page.events.get(0).link.equals(review.link));
        bob.post(CommunityRoom.Event.make("Bob", "Found it", null));
        CommunityRoom.Feed newPage = alice.fetch(page.cursor);
        check("room polling returns only new encrypted messages", newPage.events.size() == 1
                && newPage.cursor > page.cursor && "Found it".equals(newPage.events.get(0).text));
        check("different room cannot fetch these messages", CommunityRoom.create(relay).fetch(0).events.isEmpty());
        rejected("Community does not permit insecure relay URLs", () -> CommunityRoom.create("http://127.0.0.1:1"));
        rejected("Community recommendations do not allow file access", () -> CommunityRoom.Event.make("Alice", "Bad URL", "file:///private"));
        System.out.println(assertions + " BlackBox catalogue and Community checks passed");
    }
    private static void verifyV90Providers() throws Exception {
        String first = "f0ad89e00112233445566778899aabbccddeeff0", second = "82bdafb00112233445566778899aabbccddeeff0";
        JSONObject best = new JSONObject().put("hash", first.toUpperCase(java.util.Locale.ROOT)).put("title", "Horizon Test.2024.1080p.mp4")
                .put("bytes", 42000L).put("seeders", 38).put("magnetUrl", "magnet:?xt=urn:btih:" + first + "&x.pe=127.0.0.1:8080&ws=https://example.invalid/file");
        JSONArray hits = new JSONArray().put(best)
                .put(new JSONObject(best.toString()).put("seeders", 2))
                .put(new JSONObject(best.toString()).put("hash", second))
                .put(new JSONObject(best.toString()).put("title", "Horizon Test.2021.1080p.mp4"))
                .put(new JSONObject(best.toString()).put("title", "Horizon Test.2024.exe"))
                .put(new JSONObject(best.toString()).put("category", "Games"));
        JSONArray found = V90Online.parseSources(new JSONObject().put("hits", hits).toString(), false, "Horizon Test", "2024", "movie", 0, 0);
        check("v90 Knaben adapter filters other years and executables, binds hashes, and deduplicates", found.length() == 1
                && found.getJSONObject(0).getInt("seeders") == 38 && found.getJSONObject(0).getLong("sizeBytes") == 42000L);
        check("index results never inject peer addresses or untrusted web seeds", !found.getJSONObject(0).getString("magnet").contains("127.0.0.1")
                && !found.getJSONObject(0).getString("magnet").contains("example.invalid"));
        JSONArray csv = new JSONArray()
                .put(new JSONObject().put("name", "Horizon.Test.S02E03.720p").put("infohash", second).put("size_bytes", 33554432L))
                .put(new JSONObject().put("name", "Horizon.Test.S02E030.720p").put("infohash", first).put("size_bytes", 33554432L));
        found = V90Online.parseSources(new JSONObject().put("torrents", csv).toString(), true, "Horizon Test", "", "tv", 2, 3);
        check("v90 Torrents-CSV adapter returns only the requested episode and keeps its byte size", found.length() == 1
                && found.getJSONObject(0).getString("magnet").contains(second) && found.getJSONObject(0).getLong("sizeBytes") == 33554432L);
        check("anime filename pattern matches the requested episode, not its sequel or resolution",
                V90Online.releaseMatches("Horizón Test", "", "anime", 1, 3, "[Studio] Horizon Test - 03v2 [1080p].mkv")
                && !V90Online.releaseMatches("Horizon Test", "", "anime", 1, 3, "[Studio] Horizon Test 1080p.mkv")
                && !V90Online.releaseMatches("Horizon Test", "", "anime", 1, 3, "[Studio] Horizon Test 2 - 03.mkv"));
        final int[] calls = {0};
        V90Online online = new V90Online((endpoint, body) -> {
            calls[0]++;
            check("only the selected title and episode go to the film index", V90Online.KNABEN.equals(endpoint)
                    && "Horizon Test S02E03".equals(body.optString("query")) && body.optBoolean("hide_unsafe") && body.optBoolean("hide_xxx"));
            return "{\"hits\":[]}";
        });
        online.sources(false, "Horizon Test", "2024", "tv", 2, 3);
        online.sources(false, "Horizon Test", "2024", "tv", 2, 3);
        check("reopening a title doesn't repeatedly query its index", calls[0] == 1);
        JSONObject anime = V90Online.parseAnime("{\"data\":[{\"mal_id\":489,\"title\":\"Horizon Test\",\"title_english\":null,\"aired\":{\"from\":\"2010-01-01T00:00:00Z\"},\"score\":8.1,\"episodes\":3,\"type\":\"TV\"}],\"pagination\":{\"last_visible_page\":2,\"has_next_page\":true}}");
        JSONObject title = anime.getJSONArray("items").getJSONObject(0);
        check("Jikan catalogue maps an actual title, normalizes year and score, and preserves v90 pagination",
                title.getInt("year") == 2010 && title.getInt("score") == 81 && "Horizon Test".equals(title.getString("title"))
                && anime.getJSONObject("pagination").getBoolean("hasNextPage") && anime.getJSONObject("pagination").getInt("lastPage") == 2);
    }
}
