package com.blackboxstream.mobile;

import android.content.Context;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact, licensed full films. No metadata card implies a source unless this file identity is known. */
final class Cinema {
    private static final String DESCRIPTORS = "https://raw.githubusercontent.com/webtorrent/webtorrent.io/58abb9ee72a59aa7f303386314b8c16940b26819/static/torrents/";
    private static final String WEB_SEED = "https://webtorrent.io/torrents/";
    private Cinema() { }

    static final class Film {
        final String slug, title, year, duration, genre, story, hash, fileName, project;
        final int descriptor, art, index;
        Film(String slug, String title, String year, String duration, String genre, String story, String hash,
             String fileName, String project, int descriptor, int art, int index) {
            this.slug = slug; this.title = title; this.year = year; this.duration = duration; this.genre = genre;
            this.story = story; this.hash = hash; this.fileName = fileName; this.project = project;
            this.descriptor = descriptor; this.art = art; this.index = index;
        }

        String byline() { return year + "  ·  " + duration + "  ·  " + genre; }
        String torrentSource() { return "torrent:" + hash + ":" + index; }
        MediaEntry torrentEntry() { return new MediaEntry(title, MediaEntry.Kind.MOVIE, torrentSource()); }
        String magnet() {
            return "magnet:?xt=urn:btih:" + hash + "&dn=" + url(title) + "&tr=" + url("udp://tracker.opentrackr.org:1337")
                    + "&ws=" + url(WEB_SEED) + "&xs=" + url(DESCRIPTORS + slug + ".torrent");
        }
        TorrentMeta metadata(Context context) throws IOException {
            TorrentMeta meta = TorrentMeta.read(TorrentMeta.slurp(context.getResources().openRawResource(descriptor), TorrentMeta.MAX_META));
            if (!hash.equals(TorrentMeta.hex(meta.hash)) || index >= meta.items.size() || meta.webSeeds.isEmpty()) throw new IOException("This film's torrent catalogue entry could not be verified.");
            TorrentMeta.Item item = meta.items.get(index);
            if (!item.playable() || !fileName.equals(item.name)) throw new IOException("This torrent doesn't contain the expected film.");
            return meta;
        }
        MediaEntry directEntry(Context context) throws IOException {
            TorrentMeta meta = metadata(context);
            return new MediaEntry(title, MediaEntry.Kind.MOVIE, TorrentWebSeed.itemUrl(meta, meta.items.get(index), meta.webSeeds.get(0)));
        }
    }

    static final List<Film> FILMS = Collections.unmodifiableList(Arrays.asList(
        new Film("sintel", "Sintel", "2010", "15 min", "Fantasy",
                "A young traveler crosses a strange world to find the dragon she once rescued.",
                "08ada5a7a6183aae1e09d831df6748d566095a10", "Sintel.mp4", "sintel", R.raw.cinema_sintel, R.drawable.cinema_sintel, 5),
        new Film("big-buck-bunny", "Big Buck Bunny", "2008", "10 min", "Animation",
                "A gentle giant of a rabbit has had it with three woodland troublemakers.",
                "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", "Big Buck Bunny.mp4", "big-buck-bunny", R.raw.cinema_big_buck_bunny, R.drawable.cinema_big_buck_bunny, 1),
        new Film("tears-of-steel", "Tears of Steel", "2012", "12 min", "Sci-fi",
                "A team of soldiers and scientists tries to save Amsterdam from a future overrun by robots.",
                "209c8226b299b308beaf2b9cd3fb49212dbd13ec", "Tears of Steel.webm", "tears-of-steel", R.raw.cinema_tears_of_steel, R.drawable.cinema_tears_of_steel, 8),
        new Film("cosmos-laundromat", "Cosmos Laundromat", "2015", "12 min", "Adventure",
                "On a remote island, a sheep named Franck meets a salesman with a very unusual offer.",
                "c9e15763f722f23e98a29decdfae341b98d53056", "Cosmos Laundromat.mp4", "cosmos-laundromat", R.raw.cinema_cosmos_laundromat, R.drawable.cinema_cosmos_laundromat, 4)
    ));

    static Film fromSlug(String slug) {
        for (Film film : FILMS) if (film.slug.equals(slug)) return film;
        return null;
    }
    static Film fromTorrent(String source) {
        for (Film film : FILMS) if (film.torrentSource().equals(source)) return film;
        return null;
    }
    static Film exactTitle(String title) {
        for (Film film : FILMS) if (film.title.equalsIgnoreCase(title.trim())) return film;
        return null;
    }

    static MediaEntry queue(Context context, Film film) throws IOException {
        LibraryStore db = LibraryStore.get(context);
        MediaEntry entry = film.torrentEntry(), existing = db.get(entry.id);
        if (db.isOffline(entry) || (existing != null && existing.transfer >= 0)) return entry;
        TorrentMeta meta = film.metadata(context);
        TorrentJobs.save(context, meta, meta.items.get(film.index), film.title);
        Downloads.enqueue(context, entry);
        return entry;
    }

    private static String url(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
