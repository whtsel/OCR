package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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

/** Browse-first cinema, with the user's library and source tools one screen away. */
public final class MainActivity extends Activity implements PlaybackService.Listener {
    private static final String[] TABS = {"Watch", "Library", "Offline"};
    private static final int PICK_MEDIA = 12;
    private static final int PICK_PLAYLIST = 13;
    private static final int PAGE_SIZE = 30;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final TextView[] nav = new TextView[TABS.length];
    private LibraryStore store;
    private ScrollView scroll;
    private LinearLayout body, results, mini;
    private TextView miniTitle, miniMeta, miniToggle, offlineBadge;
    private PlaybackService playback;
    private int tab, limit = PAGE_SIZE;
    private String query = "";
    private MediaEntry.Kind filter;
    private boolean bound, busy, startingFilm, resumed;
    private long previousOffline;

    private final Runnable update = new Runnable() {
        @Override public void run() {
            List<MediaEntry> list = store.transfers();
            long digest = 0L;
            int pending = 0;
            for (MediaEntry e : list) {
                if (e.transfer >= 0) {
                    Downloads.failIfMissing(MainActivity.this, e);
                    pending++;
                    digest = 31 * digest + e.transfer + 17L * (Downloads.received(store, e) / 65536) + e.totalBytes;
                } else if (e.transferError != null) digest = 31 * digest + e.transferError.hashCode();
                else if (store.isOffline(e)) digest = 31 * digest + e.id.hashCode();
            }
            offlineBadge.setText(pending == 0 ? "Offline" : "Offline · " + pending);
            if (tab == 2 && digest != previousOffline && results != null) {
                int y = scroll.getScrollY();
                renderResults();
                scroll.post(() -> scroll.scrollTo(0, y));
            }
            previousOffline = digest;
            handler.postDelayed(this, 1400);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).get();
            playback.observe(MainActivity.this);
            playerChanged();
        }
        @Override public void onServiceDisconnected(ComponentName name) { playback = null; playerChanged(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = LibraryStore.get(this);
        if (state != null) {
            tab = state.getInt("tab");
            query = state.getString("query", "");
            String value = state.getString("filter");
            filter = value == null ? null : MediaEntry.Kind.valueOf(value);
            limit = state.getInt("limit", PAGE_SIZE);
        }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        createLayout();
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(update);
    }

    @Override protected void onResume() { super.onResume(); resumed = true; showTab(tab, true); }
    @Override protected void onPause() { resumed = false; super.onPause(); }

    @Override protected void onStop() {
        handler.removeCallbacks(update);
        if (playback != null) { playback.unobserve(this); playback = null; }
        if (bound) { unbindService(connection); bound = false; }
        super.onStop();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
        state.putInt("limit", limit);
        state.putString("query", query);
        state.putString("filter", filter == null ? null : filter.name());
    }

    @Override public void onBackPressed() {
        if (tab != 0) showTab(0, false);
        else super.onBackPressed();
    }

    private void createLayout() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        Ui.fitWindow(this, root);
        setContentView(root);
        LinearLayout header = Ui.row(this);
        Ui.pad(header, 18, 8);
        ImageView mark = new ImageView(this);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mark.setImageResource(R.drawable.blackbox_icon);
        header.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        Ui.space(header, 10);
        LinearLayout label = Ui.column(this);
        TextView name = Ui.text(this, "BlackBox", 23, Ui.INK, Ui.DISPLAY);
        label.addView(name);
        Ui.gap(label, 3);
        label.addView(Ui.kicker(this, "S T R E A M"));
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(Ui.action(this, "+  Add media", false, v -> addMenu()));
        root.addView(header);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        body = Ui.column(this);
        body.setFocusableInTouchMode(true);
        Ui.pad(body, 18, 10);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        mini = Ui.row(this);
        Ui.pad(mini, 12, 5);
        Ui.card(mini, Ui.MINT_DARK, 14, 0xff2e5d52);
        LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(-1, -2);
        miniParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 3), Ui.dp(this, 12), Ui.dp(this, 3));
        mini.setVisibility(View.GONE);
        root.addView(mini, miniParams);
        LinearLayout miniLabel = Ui.column(this);
        miniLabel.setMinimumHeight(Ui.dp(this, 48));
        miniLabel.setGravity(Gravity.CENTER_VERTICAL);
        miniTitle = Ui.text(this, "", 15, Ui.INK, Ui.BOLD); Ui.singleLine(miniTitle);
        miniMeta = Ui.kicker(this, "BACKGROUND AUDIO");
        miniLabel.addView(miniTitle); Ui.gap(miniLabel, 4); miniLabel.addView(miniMeta);
        miniLabel.setOnClickListener(v -> { if (playback != null && playback.entry() != null) open(playback.entry(), false); });
        miniLabel.setFocusable(true);
        mini.addView(miniLabel, new LinearLayout.LayoutParams(0, -2, 1));
        miniToggle = Ui.action(this, "Pause", false, v -> { if (playback != null) playback.toggle(); });
        mini.addView(miniToggle);

        View line = new View(this); line.setBackgroundColor(Ui.BORDER);
        root.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        LinearLayout bar = Ui.row(this);
        Ui.pad(bar, 10, 6);
        for (int i = 0; i < TABS.length; i++) {
            nav[i] = Ui.text(this, TABS[i], 14, Ui.MUTED, Ui.BOLD);
            nav[i].setGravity(Gravity.CENTER);
            nav[i].setMinHeight(Ui.dp(this, 48));
            bar.addView(nav[i], new LinearLayout.LayoutParams(0, -2, 1));
            if (i + 1 != TABS.length) Ui.space(bar, 6);
        }
        offlineBadge = nav[2];
        Ui.space(bar, 6);
        TextView discover = Ui.text(this, "Discover", 14, Ui.MUTED, Ui.BOLD);
        discover.setGravity(Gravity.CENTER); discover.setMinHeight(Ui.dp(this, 48));
        Ui.click(discover, Ui.BG, 12, 0, v -> startActivity(new Intent(this, DiscoverActivity.class)));
        bar.addView(discover, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
    }

    private void showTab(int next, boolean keepScroll) {
        int y = keepScroll && next == tab ? scroll.getScrollY() : 0;
        if (next != tab) {
            limit = PAGE_SIZE;
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(body.getWindowToken(), 0);
        }
        tab = next;
        for (int i = 0; i < nav.length; i++) {
            final int index = i;
            TextView t = nav[i];
            t.setTextColor(i == tab ? Ui.MINT : Ui.MUTED);
            t.setSelected(i == tab);
            Ui.click(t, i == tab ? Ui.MINT_DARK : Ui.BG, 12, i == tab ? 0xff286054 : 0, v -> showTab(index, false));
        }
        body.removeAllViews(); results = null;
        if (tab == 0) home();
        else browse(tab == 2);
        body.requestFocus();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void home() {
        Ui.gap(body, 4);
        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout categories = Ui.row(this);
        categories.addView(Ui.action(this, "Find a title", false, v -> findTitle()));
        for (Catalogue.Filter filter : new Catalogue.Filter[] {Catalogue.Filter.MOVIE, Catalogue.Filter.TV, Catalogue.Filter.ANIME, Catalogue.Filter.AUDIO}) {
            Ui.space(categories, 6);
            categories.addView(Ui.action(this, filter.label, false, v -> CinemaViews.browse(this, filter, "")));
        }
        filters.addView(categories); body.addView(filters);
        Ui.gap(body, 15);
        Cinema.Film featured = Cinema.FILMS.get(0);
        LinearLayout hero = Ui.column(this);
        Ui.card(hero, Ui.CARD, 19, Ui.BORDER);
        Ui.pad(hero, 13, 13);
        CinemaViews.title(this, hero, featured, true);
        Ui.gap(hero, 14);
        LinearLayout actions = Ui.row(this);
        TextView watch = Ui.action(this, "▶  Watch now", true, v -> watchFilm(featured, (TextView) v));
        actions.addView(watch, new LinearLayout.LayoutParams(0, -2, 1.45f));
        Ui.space(actions, 8);
        actions.addView(Ui.action(this, "Sources", false, v -> FilmActivity.show(this, featured)), new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(actions);
        TextView foot = Ui.text(this, "Full film · Verified torrent stream", 11, Ui.MUTED, null);
        Ui.gap(hero, 8); hero.addView(foot);
        body.addView(hero);

        Ui.gap(body, 22);
        section(body, "Open cinema", "WATCH FROM TORRENTS");
        Ui.gap(body, 8);
        CinemaViews.shelf(this, body, Cinema.FILMS);

        List<MediaEntry> recents = store.recentlyPlayed();
        if (!recents.isEmpty()) {
            Ui.gap(body, 22);
            section(body, "Continue watching", "YOUR HISTORY");
            Ui.gap(body, 9);
            for (MediaEntry e : recents.subList(0, Math.min(3, recents.size()))) addRow(body, e, false);
        }
        Ui.gap(body, 24);
        section(body, "Your sources", "LINKS, FILES & SHARING");
        Ui.gap(body, 9);
        LinearLayout quick = Ui.row(this);
        quick.addView(quick("↗", "Open a link", "HTTPS media", v -> linkDialog(false)), new LinearLayout.LayoutParams(0, -1, 1));
        Ui.space(quick, 10);
        quick.addView(quick("▣", "From your phone", "Video or audio file", v -> pick(false)), new LinearLayout.LayoutParams(0, -1, 1));
        body.addView(quick, new LinearLayout.LayoutParams(-1, -2));
        Ui.gap(body, 10);
        TextView importButton = Ui.action(this, "Import a playlist  ·  M3U", false, v -> importMenu());
        body.addView(importButton);
        Ui.gap(body, 9);
        LinearLayout sources = Ui.row(this);
        sources.addView(Ui.action(this, "Torrents", false, v -> startActivity(new Intent(this, TorrentActivity.class))), new LinearLayout.LayoutParams(0, -2, 1));
        Ui.space(sources, 8);
        sources.addView(Ui.action(this, "Local Share", false, v -> startActivity(new Intent(this, LanShareActivity.class))), new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(sources);
        Ui.gap(body, 9);
        body.addView(Ui.action(this, "Discover movies, series & anime  ›", false, v -> startActivity(new Intent(this, DiscoverActivity.class))));
        Ui.gap(body, 8);
        body.addView(Ui.action(this, "Community rooms  ›", false, v -> startActivity(new Intent(this, CommunityActivity.class))));

        Ui.gap(body, 22);
        section(body, "On-device samples", "NO CONNECTION NEEDED");
        Ui.gap(body, 9);
        body.addView(Ui.action(this, "▶  Play sample", false, v -> open(LibraryStore.SAMPLE_VIDEO, false)));
        Ui.gap(body, 7);
        addRow(body, store.get(LibraryStore.SAMPLE_AUDIO.id), false);
        Ui.gap(body, 8);
    }

    private void watchFilm(Cinema.Film film, TextView control) {
        if (startingFilm) return;
        startingFilm = true;
        if (control != null) { control.setText("Opening…"); control.setEnabled(false); }
        io.execute(() -> {
            MediaEntry entry = null;
            String problem = null;
            try { entry = Cinema.queue(getApplicationContext(), film); }
            catch (IOException | RuntimeException failed) { problem = failed.getMessage() == null ? "Could not open this torrent." : failed.getMessage(); }
            MediaEntry selected = entry;
            String error = problem;
            handler.post(() -> {
                startingFilm = false;
                if (control != null) { control.setText("▶  Watch now"); control.setEnabled(true); }
                if (!resumed || isFinishing() || isDestroyed()) return;
                if (selected == null) {
                    new AlertDialog.Builder(this).setTitle("Couldn't start " + film.title).setMessage(error)
                            .setPositiveButton("View sources", (d, w) -> FilmActivity.show(this, film)).setNegativeButton("Close", null).show();
                } else startActivity(new Intent(this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, selected.id));
            });
        });
    }

    private void findTitle() {
        LinearLayout pane = Ui.column(this); Ui.pad(pane, 16, 10);
        EditText title = field("Search movies or a saved title", InputType.TYPE_CLASS_TEXT);
        pane.addView(title); Ui.gap(pane, 10);
        ScrollView scrollResults = new ScrollView(this);
        LinearLayout matches = Ui.column(this); scrollResults.addView(matches);
        pane.addView(scrollResults, new LinearLayout.LayoutParams(-1, Ui.dp(this, 240)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Find a title").setView(pane)
                .setNegativeButton("Close", null).setPositiveButton("Search TMDB", (d, w) -> CinemaViews.browse(this, Catalogue.Filter.MOVIE, title.getText().toString())).create();
        List<MediaEntry> saved = store.saved();
        Runnable render = () -> {
            String needle = title.getText().toString().trim().toLowerCase(Locale.ROOT);
            matches.removeAllViews();
            for (Cinema.Film film : Cinema.FILMS) if (film.title.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.addView(Ui.action(this, film.title + "  /  Watch", false, v -> { dialog.dismiss(); FilmActivity.show(this, film); }));
                Ui.gap(matches, 6);
            }
            int count = 0;
            if (!needle.isEmpty()) for (MediaEntry entry : saved) {
                if (!entry.title.toLowerCase(Locale.ROOT).contains(needle) || Cinema.fromTorrent(entry.source) != null) continue;
                matches.addView(Ui.action(this, entry.title + "  /  " + entry.sourceLabel(), false, v -> { dialog.dismiss(); open(entry, false); }));
                Ui.gap(matches, 6);
                if (++count == 20) break;
            }
            if (matches.getChildCount() == 0) matches.addView(Ui.text(this, "No match in Open cinema or your Library. Search TMDB for title details.", 14, Ui.MUTED, null));
        };
        title.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        render.run(); dialog.show();
    }

    private LinearLayout quick(String symbol, String text, String subtitle, View.OnClickListener action) {
        LinearLayout box = Ui.column(this);
        Ui.pad(box, 14, 12);
        Ui.click(box, Ui.CARD, 15, Ui.BORDER, action);
        box.setMinimumHeight(Ui.dp(this, 100));
        box.addView(Ui.text(this, symbol, 23, Ui.MINT, Ui.BOLD));
        Ui.gap(box, 5);
        box.addView(Ui.text(this, text, 15, Ui.INK, Ui.BOLD));
        Ui.gap(box, 4);
        box.addView(Ui.text(this, subtitle, 12, Ui.MUTED, null));
        box.setContentDescription(text + ", " + subtitle);
        return box;
    }

    private void section(LinearLayout root, String title, String eyebrow) {
        root.addView(Ui.kicker(this, eyebrow));
        Ui.gap(root, 4);
        root.addView(Ui.text(this, title, 26, Ui.INK, Ui.DISPLAY));
    }

    private void browse(boolean offline) {
        Ui.gap(body, 10);
        body.addView(Ui.kicker(this, offline ? "ON THIS DEVICE" : "YOUR COLLECTION"));
        Ui.gap(body, 5);
        body.addView(Ui.text(this, offline ? "Offline" : "Library", 37, Ui.INK, Ui.DISPLAY));
        Ui.gap(body, 7);
        body.addView(Ui.text(this, offline ? "Play finished copies without a connection." : "Find the links and files you've saved.", 14, Ui.MUTED, null));
        Ui.gap(body, 15);
        if (!offline) {
            EditText search = field("Search your library", InputType.TYPE_CLASS_TEXT);
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            search.setText(query);
            search.setContentDescription("Search library");
            body.addView(search);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    query = s.toString(); limit = PAGE_SIZE; renderResults();
                }
                @Override public void afterTextChanged(Editable editable) { }
            });
            Ui.gap(body, 10);
            buildFilters();
        }
        results = Ui.column(this);
        body.addView(results);
        renderResults();
    }

    private void buildFilters() {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        h.addView(chips);
        List<MediaEntry.Kind> filters = new ArrayList<>();
        filters.add(null);
        java.util.Collections.addAll(filters, MediaEntry.Kind.values());
        for (MediaEntry.Kind kind : filters) {
            TextView t = Ui.text(this, kind == null ? "All" : kind.label, 13, filter == kind ? Ui.MINT : Ui.MUTED, Ui.BOLD);
            Ui.pad(t, 12, 7);
            t.setMinHeight(Ui.dp(this, 48)); t.setGravity(Gravity.CENTER);
            t.setSelected(filter == kind);
            Ui.click(t, filter == kind ? Ui.MINT_DARK : Ui.CARD, 12, filter == kind ? 0xff286054 : Ui.BORDER, v -> {
                filter = kind; limit = PAGE_SIZE;
                for (int i = 0; i < chips.getChildCount(); i += 2) {
                    View child = chips.getChildAt(i);
                    boolean selected = child == t;
                    child.setSelected(selected);
                    ((TextView) child).setTextColor(selected ? Ui.MINT : Ui.MUTED);
                    child.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2976f2ce),
                            Ui.shape(this, selected ? Ui.MINT_DARK : Ui.CARD, 12, selected ? 0xff286054 : Ui.BORDER), null));
                }
                renderResults();
            });
            if (chips.getChildCount() != 0) Ui.space(chips, 6);
            chips.addView(t);
        }
        body.addView(h);
    }

    private void renderResults() {
        if (results == null) return;
        results.removeAllViews();
        Ui.gap(results, 14);
        boolean offline = tab == 2;
        String text = query.trim().toLowerCase(Locale.ROOT);
        List<MediaEntry> entries = new ArrayList<>();
        for (MediaEntry e : offline ? store.transfers() : store.saved()) {
            if (offline ? e.transfer >= 0 || e.transferError != null || store.isOffline(e)
                    : (filter == null || filter == e.kind) && (text.isEmpty() || e.title.toLowerCase(Locale.ROOT).contains(text))) {
                entries.add(e);
            }
        }
        if (entries.isEmpty()) {
            LinearLayout empty = Ui.column(this); Ui.pad(empty, 16, 17); Ui.card(empty, Ui.CARD, 16, Ui.BORDER);
            boolean hasFilter = !offline && (filter != null || !text.isEmpty());
            empty.addView(Ui.text(this, offline ? "No offline copies yet" : hasFilter ? "No matches" : "Nothing saved yet", 19, Ui.INK, Ui.BOLD));
            Ui.gap(empty, 7);
            empty.addView(Ui.text(this, offline ? "Open a direct media link or a phone file and tap Keep offline."
                    : hasFilter ? "Try a different title or category." : "Open a media link, choose a phone file, or import an M3U playlist.", 14, Ui.MUTED, null));
            if (!hasFilter) {
                Ui.gap(empty, 14);
                empty.addView(Ui.action(this, "Add media", true, v -> addMenu()));
            }
            results.addView(empty);
        } else {
            results.addView(Ui.kicker(this, entries.size() + (entries.size() == 1 ? " ITEM" : " ITEMS")));
            Ui.gap(results, 9);
            for (int i = 0; i < entries.size() && i < limit; i++) addRow(results, entries.get(i), offline);
            if (entries.size() > limit) results.addView(Ui.action(this, "Show more", false, v -> { limit += PAGE_SIZE; renderResults(); }));
        }
        Ui.gap(results, 8);
    }

    private void addRow(LinearLayout destination, MediaEntry item, boolean offlineView) {
        boolean done = store.isOffline(item);
        String status = done ? "OFFLINE" : item.sourceLabel();
        if (offlineView && !done && item.transfer >= 0) {
            long have = Downloads.received(store, item);
            status = item.totalBytes > 0 ? Math.min(99, have * 100 / item.totalBytes) + "% DOWNLOADED"
                    : have > 0 ? Ui.bytes(have) + " DOWNLOADED" : "QUEUED";
        } else if (offlineView && !done && item.transferError != null) status = "DOWNLOAD FAILED";
        else if (item.positionMs > 0) status += "  ·  " + Ui.clock(item.positionMs);
        LinearLayout row = Ui.row(this);
        Ui.pad(row, 8, 8);
        Ui.card(row, Ui.CARD, 13, Ui.BORDER);
        LinearLayout info = Ui.row(this);
        Ui.click(info, Ui.CARD, 9, 0, v -> {
            if (offlineView && !done) itemMenu(item, true);
            else open(item, false);
        });
        info.setContentDescription(item.title + ", " + status);
        TextView icon = Ui.text(this, item.music() ? "♫" : "▶", 19, item.music() ? 0xffc7bcff : Ui.MINT, Ui.BOLD);
        icon.setGravity(Gravity.CENTER);
        Ui.card(icon, item.music() ? 0xff292244 : Ui.MINT_DARK, 10, 0);
        info.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        Ui.space(info, 11);
        LinearLayout copy = Ui.column(this);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setMinimumHeight(Ui.dp(this, 48));
        TextView label = Ui.text(this, item.title, 15, Ui.INK, Ui.BOLD); Ui.singleLine(label); copy.addView(label);
        Ui.gap(copy, 5);
        TextView sub = Ui.kicker(this, item.kind.badge + "  /  " + status);
        if (item.transferError != null && offlineView) sub.setTextColor(Ui.RED);
        Ui.singleLine(sub); copy.addView(sub);
        info.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView menu = Ui.text(this, "⋮", 23, Ui.MUTED, Ui.BOLD);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Options for " + item.title);
        Ui.click(menu, Ui.CARD, 10, 0, v -> itemMenu(item, offlineView));
        row.addView(menu, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        destination.addView(row);
        Ui.gap(destination, 7);
    }

    @Override public void playerChanged() {
        boolean show = playback != null && playback.entry() != null && playback.ready() && playback.inBackgroundSession();
        mini.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        miniTitle.setText(playback.entry().title);
        miniMeta.setText(playback.playing() ? "BACKGROUND AUDIO" : "PAUSED");
        miniToggle.setText(playback.playing() ? "Pause" : "Play");
    }

    private void addMenu() {
        new AlertDialog.Builder(this).setTitle("Add media").setItems(new CharSequence[] {"Open a media link", "Choose a phone file", "Import an M3U playlist", "Open a magnet or .torrent", "Local Share", "Community"},
                (d, w) -> {
                    if (w == 0) linkDialog(false); else if (w == 1) pick(false); else if (w == 2) importMenu();
                    else if (w == 3) startActivity(new Intent(this, TorrentActivity.class));
                    else if (w == 4) startActivity(new Intent(this, LanShareActivity.class));
                    else startActivity(new Intent(this, CommunityActivity.class));
                }).show();
    }

    private void importMenu() {
        new AlertDialog.Builder(this).setTitle("Import playlist")
                .setItems(new CharSequence[] {"Enter an HTTPS M3U link", "Choose an M3U file"},
                        (d, w) -> { if (w == 0) linkDialog(true); else pick(true); }).show();
    }

    private EditText field(String hint, int flags) {
        EditText edit = new EditText(this);
        edit.setTextSize(16);
        edit.setTextColor(Ui.INK);
        edit.setHintTextColor(Ui.MUTED);
        edit.setSingleLine(true);
        edit.setInputType(flags);
        edit.setHint(hint);
        edit.setMinHeight(Ui.dp(this, 48));
        Ui.card(edit, Ui.CARD, 12, Ui.BORDER);
        Ui.pad(edit, 11, 10);
        return edit;
    }

    private void linkDialog(boolean playlist) {
        LinearLayout form = Ui.column(this);
        Ui.pad(form, 20, 12);
        form.addView(Ui.text(this, playlist ? "Add media from your own M3U list. Only HTTPS entries are imported."
                : "Paste a direct HTTPS link to a video, audio file, or HLS stream. Web pages aren't media links.", 14, Ui.MUTED, null));
        Ui.gap(form, 12);
        EditText url = field(playlist ? "https://…/playlist.m3u" : "https://…/video.mp4", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setContentDescription(playlist ? "Playlist URL" : "Media URL");
        form.addView(url);
        EditText name = field("Title (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Spinner type = new Spinner(this);
        if (!playlist) {
            Ui.gap(form, 10); form.addView(name); Ui.gap(form, 9);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                    new String[] {"Auto category", "Movie", "Series", "Anime", "Music"});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            type.setAdapter(adapter);
            type.setMinimumHeight(Ui.dp(this, 48)); type.setContentDescription("Media category");
            form.addView(type);
        }
        AlertDialog d = new AlertDialog.Builder(this).setTitle(playlist ? "Import playlist link" : "Open media link").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton(playlist ? "Import" : "Play", null).create();
        d.setOnShowListener(dialog -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String value = url.getText().toString().trim();
                if (!playlist && value.regionMatches(true, 0, "magnet:?", 0, 8)) {
                    d.dismiss(); startActivity(new Intent(this, TorrentActivity.class).putExtra("magnet", value)); return;
                }
                String link = MediaSources.https(value);
                d.dismiss();
                if (playlist || MediaSources.playlist(link)) importPlaylist(() -> MediaSources.fetchPlaylist(link));
                else {
                    int index = type.getSelectedItemPosition();
                    MediaEntry.Kind kind = index > 0 ? MediaEntry.Kind.values()[index - 1] : MediaSources.infer(null, link);
                    String title = name.getText().toString().trim();
                    open(new MediaEntry(title.isEmpty() ? MediaSources.titleFor(link) : title, kind, link), true);
                }
            } catch (IllegalArgumentException invalid) { url.setError(invalid.getMessage()); }
        }));
        d.show();
    }

    private void pick(boolean playlist) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (!playlist) intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"audio/*", "video/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, playlist ? PICK_PLAYLIST : PICK_MEDIA); }
        catch (android.content.ActivityNotFoundException noPicker) { toast("Android's file picker isn't available."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { toast("Choose a file from the Android file picker."); return; }
        if (request == PICK_PLAYLIST) {
            importPlaylist(() -> MediaSources.readPlaylist(getContentResolver().openInputStream(uri), null));
        } else if (request == PICK_MEDIA) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException unavailable) { toast("This provider cannot grant lasting access. Copy the file to your phone first."); return; }
            io.execute(() -> {
                String title = null;
                String mime = null;
                try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) title = c.getString(0);
                    mime = getContentResolver().getType(uri);
                } catch (RuntimeException ignored) { }
                MediaEntry.Kind kind = mime != null && mime.startsWith("audio/") ? MediaEntry.Kind.MUSIC : MediaEntry.Kind.MOVIE;
                MediaEntry e = new MediaEntry(title == null ? "Phone media" : title.replaceFirst("\\.[A-Za-z0-9]{1,5}$", ""), kind, uri.toString());
                store.save(e, true);
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) open(e, false); });
            });
        }
    }

    private interface Import { MediaSources.ImportResult read() throws IOException; }

    private void importPlaylist(Import source) {
        if (busy) { toast("A playlist is already opening."); return; }
        busy = true;
        toast("Opening playlist…");
        io.execute(() -> {
            try {
                MediaSources.ImportResult parsed = source.read();
                int added = store.importEntries(parsed.entries);
                handler.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    filter = null; query = "";
                    showTab(1, false);
                    toast(added + " saved · " + (parsed.entries.size() - added) + " already in library"
                            + (parsed.skipped == 0 ? "" : " · " + parsed.skipped + " skipped"));
                });
            } catch (IOException | RuntimeException error) {
                handler.post(() -> {
                    busy = false;
                    if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle("Couldn't import playlist")
                            .setMessage(error instanceof IOException && error.getClass() == IOException.class ? error.getMessage()
                                    : "Check your connection and use a valid HTTPS M3U playlist.").setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private void itemMenu(MediaEntry item, boolean offline) {
        MediaEntry e = store.get(item.id);
        if (e == null) return;
        List<String> choices = new ArrayList<>();
        if (!(offline && e.transfer >= 0 && !store.isOffline(e))) choices.add(e.positionMs > 0 ? "Resume" : "Play");
        if (store.isOffline(e)) { choices.add("Share offline copy"); choices.add("Remove offline copy"); }
        else if (e.transfer >= 0) choices.add("Cancel download");
        else if (e.transferError != null) choices.add("Download details");
        else if (Downloads.canKeep(e)) choices.add("Keep offline");
        if (e.saved) { choices.add("Rename / category"); choices.add("Remove from library"); }
        else choices.add("Save to library");
        new AlertDialog.Builder(this).setTitle(e.title).setItems(choices.toArray(new String[0]), (dialog, which) -> {
            String action = choices.get(which);
            switch (action) {
                case "Play": case "Resume": open(e, false); break;
                case "Share offline copy": startActivity(OfflineMediaProvider.share(this, e)); break;
                case "Remove offline copy": confirmRemove(e, false); break;
                case "Remove from library": confirmRemove(e, true); break;
                case "Cancel download": Downloads.cancel(this, e); showTab(tab, true); break;
                case "Keep offline": queue(e); break;
                case "Download details": new AlertDialog.Builder(this).setTitle("Download failed").setMessage(e.transferError)
                        .setNegativeButton("Dismiss", null).setPositiveButton("Retry", (d, w) -> queue(e)).show(); break;
                case "Rename / category": rename(e); break;
                case "Save to library": store.save(e, true); showTab(tab, true); toast("Saved to Library"); break;
                default: break;
            }
        }).show();
    }

    private void rename(MediaEntry entry) {
        LinearLayout form = Ui.column(this); Ui.pad(form, 18, 8);
        EditText newName = field("Title", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        newName.setText(entry.title); form.addView(newName);
        Spinner type = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[] {"Movie", "Series", "Anime", "Music"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        type.setAdapter(adapter); type.setSelection(entry.kind.ordinal()); type.setContentDescription("Category");
        form.addView(type);
        new AlertDialog.Builder(this).setTitle("Edit Library entry").setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    store.save(new MediaEntry(newName.getText().toString(), MediaEntry.Kind.values()[type.getSelectedItemPosition()], entry.source), true);
                    showTab(tab, true);
                }).show();
    }

    private void confirmRemove(MediaEntry e, boolean removeSaved) {
        boolean hasCopy = store.isOffline(e) || e.transfer >= 0;
        new AlertDialog.Builder(this).setTitle(removeSaved ? "Remove from Library?" : "Remove offline copy?")
                .setMessage(removeSaved ? hasCopy ? "This also removes its offline copy or queued download. The original file is not changed."
                        : "The original file or link is not changed." : "Your Library entry stays saved.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> {
                    try {
                        Downloads.deleteCopy(this, e);
                        if (removeSaved) store.save(e, false);
                        showTab(tab, true);
                    } catch (IOException failure) { toast(failure.getMessage()); }
                }).show();
    }

    private void queue(MediaEntry item) {
        try { Downloads.enqueue(this, item); toast("Queued in Offline"); }
        catch (IOException | IllegalArgumentException | IllegalStateException failed) { toast(failed.getMessage()); }
        showTab(tab, true);
    }

    private void open(MediaEntry item, boolean fromLink) {
        if (item != null && item.torrent() && item.transfer < 0 && !store.isOffline(item)) {
            Cinema.Film film = Cinema.fromTorrent(item.source);
            if (film != null) { watchFilm(film, null); return; }
        }
        if (store.get(item.id) == null || fromLink) store.save(item, true);
        startActivity(new Intent(this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, item.id));
    }

    private void toast(String message) { if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
