package com.blackboxstream.mobile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Complete historical anime films from their identified Commons files, not provider metadata guesses. */
final class OpenAnime {
    private static final String COMMONS = "https://commons.wikimedia.org/wiki/File:";
    private static final String FILES = "https://upload.wikimedia.org/wikipedia/commons/";
    private static final String THUMBS = "https://thumb.wikimedia.org/wikipedia/commons/thumb/";

    final String slug, title, romaji, original, director, year, overview, file, credits;
    final int seconds;

    private OpenAnime(String slug, String title, String romaji, String original, String director, String year,
                      int seconds, String overview, String file, String credits) {
        this.slug = slug; this.title = title; this.romaji = romaji; this.original = original; this.director = director;
        this.year = year; this.seconds = seconds; this.overview = overview; this.file = file; this.credits = credits;
    }

    static final List<OpenAnime> FILMS = Collections.unmodifiableList(Arrays.asList(
        new OpenAnime("dull-sword", "The Dull Sword", "Namakura Gatana", "なまくら刀", "Jun’ichi Kōuchi", "1917", 258,
                "A samurai buys a new sword and discovers that it is not quite what he expected. The complete surviving restored film, with a musical score.",
                "1/1d/Namakura_Gatana_with_music.webm", "Jun’ichi Kōuchi · public-domain film (Japan/US). “Ishikari Lore” by Kevin MacLeod (incompetech.com), CC BY 3.0."),
        new OpenAnime("animal-olympics", "The Animal Olympics", "Dōbutsu Orimupikku Taikai", "動物オリムピック大会", "Yasuji Murata", "1928", 792,
                "Animals compete in a series of sporting events in this complete silent animation from Japan.",
                "3/36/The_Animal_Olympics_%281928%29.webm", "Yasuji Murata · public-domain film (Japan/US). Digitized copy from Wikimedia Commons.")
    ));

    static OpenAnime named(String slug) {
        for (OpenAnime film : FILMS) if (film.slug.equals(slug)) return film;
        return null;
    }

    static OpenAnime fromSource(String source) {
        for (OpenAnime film : FILMS) if ((FILES + film.file).equals(source)) return film;
        return null;
    }

    MediaEntry entry() { return new MediaEntry(title, MediaEntry.Kind.ANIME, FILES + file); }
    String artwork() { return THUMBS + file + "/330px--" + filename() + ".jpg"; }
    String attribution() { return COMMONS + filename(); }
    private String filename() { return file.substring(file.lastIndexOf('/') + 1); }

    JSONObject item() throws JSONException {
        return new JSONObject().put("id", "open_" + slug.replace('-', '_')).put("title", title).put("romaji", romaji)
                .put("native", original).put("year", Integer.parseInt(year)).put("episodes", 1)
                .put("format", "MOVIE").put("status", "FINISHED").put("overview", overview)
                .put("cover", "/anime/" + slug + ".jpg").put("genres", new JSONArray().put("Historical anime"))
                .put("duration", seconds).put("playable", "anime:" + slug).put("credits", credits).put("reference", attribution());
    }

    static JSONArray search(String title) throws JSONException {
        String q = V90Online.words(title);
        JSONArray out = new JSONArray();
        if (q.isEmpty() && !title.trim().isEmpty()) return out;
        for (OpenAnime film : FILMS) {
            if (V90Online.words(film.title + " " + film.romaji + " " + film.original + " " + film.director + " " + film.year)
                    .contains(q)) out.put(film.item());
        }
        return out;
    }
}
