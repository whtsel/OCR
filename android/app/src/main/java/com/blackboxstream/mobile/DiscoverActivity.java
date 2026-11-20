package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

public final class DiscoverActivity extends Activity {
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences settings, watchlist;
    private LinearLayout body, filters, results, browse;
    private EditText query;
    private Spinner shelf, music;
    private TextView status, next;
    private Catalogue.Filter filter = Catalogue.Filter.MOVIE;
    private int page, ticket;
    private final List<Catalogue.Card> current = new ArrayList<>();
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = getSharedPreferences("catalogue", MODE_PRIVATE); watchlist = getSharedPreferences("watchlist", MODE_PRIVATE);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); Ui.fitWindow(this, root); setContentView(root);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        body = Ui.column(this); Ui.pad(body, 18, 14); scroll.addView(body);
        LinearLayout top = Ui.row(this); top.addView(Ui.action(this, "‹  Home", false, v -> finish())); Ui.flex(top);
        top.addView(Ui.action(this, "Catalogue key", false, v -> credential())); body.addView(top);
        Ui.gap(body, 18); body.addView(Ui.kicker(this, "BLACKBOX  /  SCREENING ROOM")); Ui.gap(body, 6);
        body.addView(Ui.text(this, "Discover", 38, Ui.INK, Ui.DISPLAY)); Ui.gap(body, 6);
        body.addView(Ui.text(this, "Browse TMDB for films, series and anime. The catalogue provides information, not playable video.", 14, Ui.MUTED, null));
        Ui.gap(body, 12);
        HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false);
        filters = Ui.row(this); strip.addView(filters); body.addView(strip); Ui.gap(body, 10);
        browse = Ui.column(this); body.addView(browse);
        query = field("Search the catalogue", InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); browse.addView(query); Ui.gap(browse, 7);
        query.setOnEditorActionListener((view, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { load(true); return true; } return false; });
        LinearLayout options = Ui.row(this);
        shelf = new Spinner(this);
        ArrayAdapter<String> names = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[] {"Trending", "Top rated", "Upcoming"});
        names.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); shelf.setAdapter(names);
        shelf.setContentDescription("Catalogue shelf"); options.addView(shelf, new LinearLayout.LayoutParams(0,-2,1));
        music = new Spinner(this);
        ArrayAdapter<String> musicLabels = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[] {"Tracks", "Albums", "Artists"});
        musicLabels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); music.setAdapter(musicLabels);
        music.setContentDescription("Music search type"); options.addView(music, new LinearLayout.LayoutParams(0,-2,1));
        options.addView(Ui.action(this, "Browse / search", true, v -> load(true))); browse.addView(options);
        Ui.gap(body, 13); status = Ui.text(this, "", 14, Ui.MUTED, null); body.addView(status); Ui.gap(body, 9);
        results = Ui.column(this); body.addView(results);
        next = Ui.action(this, "Load next page", false, v -> load(false)); body.addView(next);
        Ui.gap(body, 15);
        body.addView(Ui.text(this, "This product uses the TMDB API but is not endorsed or certified by TMDB.", 12, Ui.MUTED, null));
        Ui.gap(body, 8); body.addView(Ui.action(this, "Visit The Movie Database", false, v -> browser("https://www.themoviedb.org/")));
        if (state != null) {
            try { filter = Catalogue.Filter.valueOf(state.getString("filter", "MOVIE")); } catch (IllegalArgumentException ignored) { }
            query.setText(state.getString("query", "")); shelf.setSelection(state.getInt("shelf", 0));
        }
        if (state == null) {
            String requested = getIntent().getStringExtra("catalogue_filter");
            if (requested != null) try { filter = Catalogue.Filter.valueOf(requested); } catch (IllegalArgumentException ignored) { }
            String search = getIntent().getStringExtra("catalogue_query");
            if (search != null) query.setText(search.substring(0, Math.min(search.length(), 130)));
        }
        switchTo(filter);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("filter", filter.name()); state.putString("query", query.getText().toString());
        state.putInt("shelf", shelf.getSelectedItemPosition());
    }

    private void switchTo(Catalogue.Filter selected) {
        filter = selected; ++ticket; busy = false; page = 0; current.clear();
        filters.removeAllViews();
        for (Catalogue.Filter f : Catalogue.Filter.values()) {
            filters.addView(Ui.action(this, f.label, f == selected, v -> switchTo(f))); Ui.space(filters, 6);
        }
        browse.setVisibility(filter.tmdb() || filter == Catalogue.Filter.AUDIO ? View.VISIBLE : View.GONE);
        shelf.setVisibility(filter.tmdb() ? View.VISIBLE : View.GONE);
        music.setVisibility(filter == Catalogue.Filter.AUDIO ? View.VISIBLE : View.GONE);
        if (filter == Catalogue.Filter.WATCHLIST) {
            for (Object raw : watchlist.getAll().values()) if (raw instanceof String) {
                try {
                    Catalogue.Card saved = Catalogue.Card.saved(new JSONObject((String) raw));
                    if (!saved.id.startsWith("open:")) current.add(saved);
                } catch (JSONException | IllegalArgumentException ignored) { }
            }
            current.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
            paint(current.isEmpty() ? "Save a title from its detail card to see it here." : "Your saved titles are kept on this device.", false);
        } else if (filter == Catalogue.Filter.AUDIO) {
            paint("Search MusicBrainz for tracks, albums and artists. Results are metadata only.", false);
        } else load(true);
    }

    private void load(boolean fresh) {
        if (filter == Catalogue.Filter.AUDIO) { musicSearch(); return; }
        if (!filter.tmdb() || !fresh && busy) return;
        String key = settings.getString("key", "");
        if (key.isEmpty()) { paint("Add your own TMDB key to browse or search these titles.", false); return; }
        int run = ++ticket, requestedPage = fresh ? 1 : page + 1;
        String text = query.getText().toString();
        Catalogue.Filter scope = filter;
        Catalogue.Shelf type = Catalogue.Shelf.values()[shelf.getSelectedItemPosition()];
        if (fresh) { current.clear(); page = 0; }
        busy = true; paint("Loading titles from TMDB…", false);
        io.execute(() -> {
            try {
                List<Catalogue.Card> cards = Catalogue.browse(scope, type, text, key, requestedPage);
                main.post(() -> {
                    if (!valid(run)) return;
                    busy = false; page = requestedPage;
                    for (Catalogue.Card card : cards) {
                        boolean found = false; for (Catalogue.Card old : current) if (old.id.equals(card.id)) { found = true; break; }
                        if (!found) current.add(card);
                    }
                    if (current.size() > 160) current.subList(0, current.size() - 160).clear();
                    paint(current.isEmpty() ? "No matches on this page. Try a different search or the next page."
                            : "TMDB titles · choose a title to see details or save it", page < 100 && (cards.size() >= 18 || scope == Catalogue.Filter.ANIME && page < 5));
                });
            } catch (IOException | RuntimeException failure) {
                main.post(() -> { if (valid(run)) { busy = false; paint(failure.getMessage() != null && !failure.getMessage().contains("http")
                        ? failure.getMessage() : "Could not reach TMDB. Check the connection and try again.", !fresh); } });
            }
        });
    }

    private void musicSearch() {
        if (busy) return;
        final String text = query.getText().toString().trim();
        if (text.isEmpty()) { switchTo(Catalogue.Filter.AUDIO); return; }
        final Catalogue.Music scope = Catalogue.Music.values()[music.getSelectedItemPosition()];
        final int run = ++ticket;
        busy = true; current.clear(); paint("Searching MusicBrainz…", false);
        io.execute(() -> {
            try {
                List<Catalogue.Card> found = Catalogue.music(scope, text);
                main.post(() -> { if (valid(run)) { busy = false; current.addAll(found);
                    paint(found.isEmpty() ? "No music matches. Try a different name." : "MusicBrainz matches · choose a title for details", false); } });
            } catch (IOException | RuntimeException unavailable) {
                main.post(() -> { if (valid(run)) { busy = false; paint(unavailable.getMessage() != null ? unavailable.getMessage() : "Could not reach MusicBrainz.", false); } });
            }
        });
    }

    private boolean valid(int run) { return run == ticket && !isFinishing() && !isDestroyed(); }
    private void paint(String text, boolean more) {
        status.setText(text); next.setVisibility(more ? View.VISIBLE : View.GONE); results.removeAllViews();
        for (Catalogue.Card item : current) {
            LinearLayout card = Ui.column(this); Ui.pad(card, 13, 13); Ui.click(card, Ui.CARD, 14, Ui.BORDER, v -> detail(item));
            card.addView(Ui.kicker(this, item.kind.badge + (item.year.isEmpty() ? "" : "  /  " + item.year)));
            Ui.gap(card, 4); card.addView(Ui.text(this, item.title, 19, Ui.INK, Ui.BOLD)); Ui.gap(card, 4);
            card.addView(Ui.text(this, "Details & watchlist  ›", 13, Ui.MUTED, null));
            results.addView(card); Ui.gap(results, 7);
        }
    }

    private void detail(Catalogue.Card card) {
        MediaEntry entry = card.entry();
        boolean saved = watchlist.contains(card.id);
        String details = (card.year.isEmpty() ? "" : card.year + " · ") + card.credit + "\n\n"
                + (card.detail.isEmpty() ? "No description is available for this title." : card.detail);
        AlertDialog.Builder box = new AlertDialog.Builder(this).setTitle(card.title).setMessage(details)
                .setNeutralButton(saved ? "Unsave" : "Save title", (d, w) -> {
                    try {
                        if (saved) watchlist.edit().remove(card.id).apply();
                        else if (watchlist.getAll().size() >= 150) { toast("Remove a saved title before adding another."); return; }
                        else watchlist.edit().putString(card.id, card.json().toString()).apply();
                        toast(saved ? "Title removed" : "Title saved");
                        if (filter == Catalogue.Filter.WATCHLIST) switchTo(filter);
                    } catch (JSONException failed) { toast("Could not save this title."); }
                });
        if (entry != null) box.setNegativeButton("Close",null).setPositiveButton("Play", (d, w) -> play(entry));
        else box.setNegativeButton("Find my sources", (d, w) -> localMatches(card)).setPositiveButton(card.musicBrainz() ? "MusicBrainz listing" : "TMDB listing", (d, w) -> browser(card.listing()));
        box.show();
    }

    private void localMatches(Catalogue.Card card) {
        List<MediaEntry> exact = new ArrayList<>();
        for (MediaEntry e : LibraryStore.get(this).saved()) {
            if (e.title.trim().equalsIgnoreCase(card.title) && (e.kind == card.kind || e.kind == MediaEntry.Kind.SERIES && card.kind == MediaEntry.Kind.ANIME)) exact.add(e);
        }
        Cinema.Film openFilm = card.kind == MediaEntry.Kind.MOVIE ? Cinema.exactTitle(card.title) : null;
        if (openFilm != null && openFilm.year.equals(card.year)) { FilmActivity.show(this, openFilm); return; }
        if (exact.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No matching saved sources").setMessage("Discovery shows title information only. Add an authorized file, direct link, or torrent for this title in BlackBox.")
                    .setNegativeButton("Close", null).setPositiveButton("Go to Home", (d,w) -> finish()).show(); return;
        }
        CharSequence[] names = new CharSequence[exact.size()];
        for (int i = 0; i < names.length; i++) names[i] = exact.get(i).title + " · " + exact.get(i).sourceLabel();
        new AlertDialog.Builder(this).setTitle("Matching titles in your Library").setItems(names, (d,w) -> play(exact.get(w))).setNegativeButton("Cancel",null).show();
    }

    private void play(MediaEntry e) {
        if (TorrentActivity.openSource(this, e)) return;
        LibraryStore store = LibraryStore.get(this);
        if (store.get(e.id) == null) store.save(e, false);
        startActivity(new Intent(this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, e.id));
    }

    private void credential() {
        LinearLayout form = Ui.column(this); Ui.pad(form, 17, 11);
        form.addView(Ui.text(this, "The key is stored on this device and sent only to api.themoviedb.org. A key isn't required for the open catalogue.", 14, Ui.MUTED, null)); Ui.gap(form, 8);
        EditText input = field("Your TMDB API credential", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false); form.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Catalogue key").setView(form).setNegativeButton("Cancel",null)
                .setNeutralButton("Remove key", (d,w) -> { settings.edit().remove("key").apply(); if (filter.tmdb()) switchTo(filter); }).setPositiveButton("Verify & save",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String key;
            try { key = Catalogue.credential(input.getText().toString()); } catch (IllegalArgumentException invalid) { input.setError(invalid.getMessage()); return; }
            v.setEnabled(false); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Checking…");
            io.execute(() -> {
                try {
                    Catalogue.validate(key);
                    main.post(() -> { if (!isDestroyed() && !isFinishing() && dialog.isShowing()) {
                        settings.edit().putString("key", key).apply(); dialog.dismiss(); toast("TMDB key verified"); if (filter.tmdb()) switchTo(filter);
                    }});
                } catch (IOException | RuntimeException failure) {
                    main.post(() -> { if (dialog.isShowing() && !isDestroyed()) { input.setError("TMDB could not verify this key. Check the key and connection.");
                        v.setEnabled(true); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Verify & save"); } });
                }
            });
        }));
        dialog.show();
    }

    private EditText field(String hint, int flag) {
        EditText edit = new EditText(this); edit.setTextColor(Ui.INK); edit.setHintTextColor(Ui.MUTED);
        edit.setSingleLine(true); edit.setInputType(flag); edit.setHint(hint); edit.setContentDescription(hint);
        edit.setMinHeight(Ui.dp(this, 48)); Ui.card(edit, Ui.CARD, 12, Ui.BORDER); Ui.pad(edit, 10, 8); return edit;
    }
    private void browser(String target) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target))); } catch (RuntimeException missing) { toast("No browser is available."); } }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { ++ticket; io.shutdownNow(); super.onDestroy(); }
}
