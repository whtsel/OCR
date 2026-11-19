package com.blackboxstream.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A film page shows its exact playable sources, not title-only search results. */
public final class FilmActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy;
    private Cinema.Film film;
    private TextView status;

    static void show(Activity c, Cinema.Film film) {
        c.startActivity(new Intent(c, FilmActivity.class).putExtra("film", film.slug));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        film = Cinema.fromSlug(getIntent().getStringExtra("film"));
        if (film == null) { finish(); return; }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG); Ui.fitWindow(this, root); setContentView(root);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1,-1));
        LinearLayout pane = Ui.column(this); Ui.pad(pane, 18, 12); scroll.addView(pane);
        pane.addView(Ui.action(this, "‹  Back", false, v -> finish()), new LinearLayout.LayoutParams(-2,-2));
        Ui.gap(pane, 12);
        CinemaViews.title(this, pane, film, false);
        Ui.gap(pane, 17);
        pane.addView(Ui.action(this, "▶  Watch torrent", true, v -> play(true)));
        Ui.gap(pane, 8);
        pane.addView(Ui.action(this, "Play direct HTTPS", false, v -> play(false)));
        Ui.gap(pane, 8);
        pane.addView(Ui.action(this, "Copy magnet", false, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(film.title + " magnet", film.magnet()));
            Toast.makeText(this, "Magnet copied", Toast.LENGTH_SHORT).show();
        }));
        Ui.gap(pane, 12);
        status = Ui.text(this, "Torrent mode checks each piece before playing it. The selected file is saved offline if the transfer completes.", 13, Ui.MUTED, null);
        pane.addView(status);
        Ui.gap(pane, 21);
        pane.addView(Ui.kicker(this, "FILM & ARTWORK"));
        Ui.gap(pane, 7);
        pane.addView(Ui.text(this, "Blender Foundation / Blender Institute · CC BY 3.0. Artwork cropped for this screen. Torrent supplied by WebTorrent's open-film collection.", 13, Ui.MUTED, null));
        Ui.gap(pane, 8);
        pane.addView(Ui.action(this, "Official film and credits ›", false, v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.blender.org/projects/" + film.project + "/"))); }
            catch (android.content.ActivityNotFoundException ignored) { status.setText("No browser is installed."); }
        }));
    }

    private void play(boolean torrent) {
        if (busy) return;
        busy = true;
        status.setText(torrent ? "Preparing the exact torrent source…" : "Opening the original HTTPS file…");
        io.execute(() -> {
            try {
                MediaEntry entry = torrent ? Cinema.queue(getApplicationContext(), film) : film.directEntry(this);
                LibraryStore db = LibraryStore.get(this);
                if (db.get(entry.id) == null) db.save(entry, false);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    busy = false;
                    status.setText(torrent ? "Verified film selected · transfer in Offline" : "Direct HTTPS source selected");
                    startActivity(new Intent(this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, entry.id));
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) { busy = false; status.setText(failure.getMessage() == null ? "Could not open this source." : failure.getMessage()); }
                });
            }
        });
    }

    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
