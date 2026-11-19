package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TorrentActivity extends Activity {
    private static final int PICK_TORRENT = 60;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int request;
    private TextView status, resolve;
    private EditText magnet;
    private LinearLayout files;
    private boolean busy;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); Ui.fitWindow(this, root); setContentView(root);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout pane = Ui.column(this); Ui.pad(pane, 18, 16); scroll.addView(pane);
        pane.addView(Ui.action(this, "‹  Home", false, v -> finish()), new LinearLayout.LayoutParams(-2,-2));
        Ui.gap(pane, 19); pane.addView(Ui.kicker(this, "PEER-TO-PEER  /  BITTORRENT")); Ui.gap(pane, 5);
        pane.addView(Ui.text(this, "Open a torrent", 34, Ui.INK, Ui.DISPLAY)); Ui.gap(pane, 7);
        pane.addView(Ui.text(this, "Paste a magnet or choose a .torrent file, then select the exact audio or video. Only use media you have permission to download.", 15, Ui.MUTED, null));
        Ui.gap(pane, 18);
        magnet = new EditText(this); magnet.setTextColor(Ui.INK); magnet.setHintTextColor(Ui.MUTED);
        magnet.setSingleLine(true); magnet.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        magnet.setHint("magnet:?xt=urn:btih:…"); magnet.setContentDescription("Magnet link");
        Ui.card(magnet, Ui.CARD, 12, Ui.BORDER); Ui.pad(magnet, 12, 10); pane.addView(magnet);
        Ui.gap(pane, 9); resolve = Ui.action(this, "Find torrent files", true, v -> openMagnet()); pane.addView(resolve);
        Ui.gap(pane, 8); pane.addView(Ui.action(this, "Choose .torrent file", false, v -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"); picker.addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(picker, PICK_TORRENT); }
            catch (android.content.ActivityNotFoundException missing) { status.setText("Android's file picker isn't available."); }
        }));
        Ui.gap(pane, 13); status = Ui.text(this, "Peers and trackers can see your network IP. BlackBox doesn't seed or open inbound ports.", 13, Ui.MUTED, null);
        pane.addView(status); Ui.gap(pane, 11); files = Ui.column(this); pane.addView(files);
        String link = getIntent().getStringExtra("magnet"); if (link != null) magnet.setText(link);
    }

    private interface Load { TorrentMeta load(int generation) throws IOException; }
    private void openMagnet() {
        String link = magnet.getText().toString().trim();
        if (busy) { request++; busy = false; resolve.setText("Find torrent files"); status.setText("Lookup cancelled."); return; }
        start("Finding peers and checking the magnet's metadata…", ticket -> {
            TorrentMeta.Magnet target = TorrentMeta.magnet(link);
            java.io.File cached = TorrentJobs.descriptor(this, TorrentMeta.hex(target.hash));
            if (cached.isFile()) {
                try {
                    TorrentMeta local = TorrentMeta.read(TorrentMeta.slurp(new java.io.FileInputStream(cached), 4 * 1024 * 1024));
                    if (target.trackers.isEmpty() && target.peers.isEmpty()) return local;
                    return TorrentMeta.fromMagnet(target, local.info);
                } catch (IOException notUsable) { }
            }
            return new TorrentNetwork(() -> ticket != request).resolve(target);
        });
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PICK_TORRENT || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        start("Reading the complete .torrent file…", ticket -> TorrentMeta.read(TorrentMeta.slurp(getContentResolver().openInputStream(uri), TorrentMeta.MAX_META)));
    }

    private void start(String text, Load load) {
        int ticket = ++request; busy = true; files.removeAllViews(); status.setTextColor(Ui.MUTED); status.setText(text); resolve.setText("Cancel lookup");
        io.execute(() -> {
            try {
                TorrentMeta meta = load.load(ticket);
                ui.post(() -> { if (ticket == request && !isDestroyed() && !isFinishing()) show(meta); });
            } catch (IOException | RuntimeException err) {
                ui.post(() -> {
                    if (ticket != request || isDestroyed() || isFinishing()) return;
                    busy = false; resolve.setText("Find torrent files"); status.setTextColor(Ui.RED);
                    status.setText(err instanceof IOException && err.getMessage() != null ? err.getMessage() : "Couldn't open this torrent.");
                });
            }
        });
    }

    private void show(TorrentMeta meta) {
        busy = false; resolve.setText("Find torrent files"); files.removeAllViews();
        int count = 0;
        for (TorrentMeta.Item item : meta.items) if (item.playable()) count++;
        status.setText(count == 0 ? "No supported audio or video files under 4 GiB were found."
                : count + " media file" + (count == 1 ? "" : "s") + " · " + meta.name);
        renderRows(meta, 40);
    }

    private void renderRows(TorrentMeta meta, int limit) {
        files.removeAllViews(); int shown = 0;
        for (TorrentMeta.Item item : meta.items) {
            if (!item.playable()) continue;
            if (shown++ == limit) {
                files.addView(Ui.action(this, "Show more files", false, v -> renderRows(meta, limit + 40))); break;
            }
            files.addView(Ui.action(this, item.name + "  ·  " + Ui.bytes(item.length), false, v -> select(meta, item)));
            Ui.gap(files, 7);
        }
    }

    private void select(TorrentMeta meta, TorrentMeta.Item file) {
        new AlertDialog.Builder(this).setTitle("Open selected file?").setMessage(file.name + "\n" + Ui.bytes(file.length)
                + " · Verified pieces can play as they arrive. The completed file stays in Offline.")
                .setNegativeButton("Cancel", null).setPositiveButton("Start", (dialog, action) -> io.execute(() -> {
                    try {
                        MediaEntry entry = TorrentJobs.save(getApplicationContext(), meta, file);
                        LibraryStore store = LibraryStore.get(this);
                        MediaEntry current = store.get(entry.id);
                        if (current == null || current.transfer == -1 && !store.isOffline(current)) Downloads.enqueue(this, entry);
                        ui.post(() -> { if (!isFinishing() && !isDestroyed()) startActivity(new Intent(this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, entry.id)); });
                    } catch (IOException | RuntimeException err) {
                        ui.post(() -> { if (!isDestroyed() && !isFinishing()) status.setText(err.getMessage() == null ? "Could not queue this file." : err.getMessage()); });
                    }
                })).show();
    }

    @Override protected void onDestroy() { request++; io.shutdownNow(); super.onDestroy(); }
}
