package com.blackboxstream.mobile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

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
}
