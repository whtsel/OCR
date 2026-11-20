package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LanShareActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView hostStatus, copy, send, stop, receiveStatus;
    private LinearLayout remote;
    private EditText request;
    private boolean connecting;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean open = LanShareService.invitation() != null;
            boolean startup = LanShareService.starting;
            copy.setEnabled(open); copy.setAlpha(open ? 1 : .45f);
            stop.setEnabled(open || startup); stop.setAlpha(open || startup ? 1 : .45f);
            send.setText(open ? "Choose new files / rotate invitation" : "Choose media to share");
            hostStatus.setText(startup ? "Starting encrypted Local Share…" : open ? "Sharing only the selected files for 15 minutes. Send the invitation privately."
                    : LanShareService.notice == null ? "Off. Your media is private until you start sharing." : LanShareService.notice);
            ui.postDelayed(this, 400);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); Ui.fitWindow(this, root); setContentView(root);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout pane = Ui.column(this); Ui.pad(pane, 18, 16); scroll.addView(pane);
        pane.addView(Ui.action(this, "‹  Home", false, v -> finish()), new LinearLayout.LayoutParams(-2, -2));
        Ui.gap(pane, 19); pane.addView(Ui.kicker(this, "DEVICE TO DEVICE  /  NO INTERNET NEEDED")); Ui.gap(pane, 5);
        pane.addView(Ui.text(this, "Local Share", 36, Ui.INK, Ui.DISPLAY)); Ui.gap(pane, 8);
        pane.addView(Ui.text(this, "Stream or save selected media between BlackBox phones on the same private Wi-Fi. Invitations authorize only the chosen files and expire automatically.", 15, Ui.MUTED, null));
        Ui.gap(pane, 22); pane.addView(Ui.text(this, "Send", 24, Ui.INK, Ui.DISPLAY)); Ui.gap(pane, 8);
        hostStatus = Ui.text(this, "", 14, Ui.MUTED, null); pane.addView(hostStatus); Ui.gap(pane, 9);
        send = Ui.action(this, "Choose media to share", true, v -> choose()); pane.addView(send); Ui.gap(pane, 7);
        LinearLayout actions = Ui.row(this);
        copy = Ui.action(this, "Share invite", false, v -> share()); stop = Ui.action(this, "Stop sharing", false, v -> stopService(new Intent(this, LanShareService.class)));
        actions.addView(copy, new LinearLayout.LayoutParams(0,-2,1)); Ui.space(actions, 8); actions.addView(stop, new LinearLayout.LayoutParams(0,-2,1)); pane.addView(actions);
        Ui.gap(pane, 25); pane.addView(Ui.text(this, "Receive", 24, Ui.INK, Ui.DISPLAY)); Ui.gap(pane, 8);
        request = new EditText(this); request.setTextColor(Ui.INK); request.setHintTextColor(Ui.MUTED);
        request.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        request.setMaxLines(3); request.setHint("Paste a BBX-LAN-1 invitation"); request.setContentDescription("Local Share invitation");
        request.setSaveEnabled(false); Ui.card(request, Ui.CARD, 12, Ui.BORDER); Ui.pad(request, 12, 10); pane.addView(request);
        Ui.gap(pane, 7); pane.addView(Ui.action(this, "Connect securely", true, v -> connect())); Ui.gap(pane, 9);
        receiveStatus = Ui.text(this, "", 14, Ui.MUTED, null); pane.addView(receiveStatus); remote = Ui.column(this); pane.addView(remote);
    }

    @Override protected void onStart() { super.onStart(); ui.post(refresh); }
    @Override protected void onStop() { ui.removeCallbacks(refresh); super.onStop(); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }

    private void choose() {
        LibraryStore db = LibraryStore.get(this);
        List<MediaEntry> entries = new ArrayList<>();
        for (MediaEntry e : db.transfers()) if (db.isOffline(e) && !e.bundled()) entries.add(e);
        CharSequence[] names = new CharSequence[entries.size()];
        for (int i = 0; i < names.length; i++) names[i] = entries.get(i).title;
        boolean[] selected = new boolean[entries.size()];
        new AlertDialog.Builder(this).setTitle("Choose up to 16 completed files").setMultiChoiceItems(names, selected, (d, i, checked) -> selected[i] = checked)
                .setNegativeButton("Cancel", null).setPositiveButton("Start sharing", (dialog, which) -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < selected.length; i++) if (selected[i]) ids.add(entries.get(i).id);
                    if (ids.isEmpty() || ids.size() > LanShare.MAX_ITEMS) { toast("Choose between 1 and 16 files."); return; }
                    Intent start = new Intent(this, LanShareService.class).putExtra(LanShareService.EXTRA_FILES, ids.toArray(new String[0]));
                    try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start); }
                    catch (RuntimeException denied) { toast("Android couldn't start sharing. Try again while this screen is open."); }
                }).show();
    }

    private void share() {
        String invitation = LanShareService.invitation(); if (invitation == null) return;
        new AlertDialog.Builder(this).setTitle("Send the invitation privately").setMessage("Anyone with this invitation on the same Wi-Fi can access only the selected files until sharing stops. This invitation isn't a public link.")
                .setNeutralButton("Copy", (d, w) -> {
                    ClipData data = ClipData.newPlainText("Private BlackBox invitation", invitation);
                    if (Build.VERSION.SDK_INT >= 24) { android.os.PersistableBundle flags = new android.os.PersistableBundle(); flags.putBoolean("android.content.extra.IS_SENSITIVE", true); data.getDescription().setExtras(flags); }
                    getSystemService(ClipboardManager.class).setPrimaryClip(data); toast("Invitation copied");
                })
                .setNegativeButton("Cancel", null).setPositiveButton("Share…", (d, w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invitation), "Send private invitation"))).show();
    }

    private void connect() {
        if (connecting) return;
        String invite = request.getText().toString();
        connecting = true; receiveStatus.setText("Checking the sender's identity…"); remote.removeAllViews();
        io.execute(() -> {
            try {
                LanShare.Invite parsed = LanShare.parse(invite, false);
                WifiRoute wifi = WifiRoute.find(this, parsed.host);
                LanShare.SocketRoute route = wifi.network::bindSocket;
                List<MediaEntry> entries;
                try (LanShare.Peer peer = new LanShare.Peer(parsed, route)) { entries = LanSessions.set(parsed, peer.items(), route); }
                ui.post(() -> { if (!isDestroyed() && !isFinishing()) { connecting = false; request.setText(""); renderEntries(entries); } });
            } catch (IOException | RuntimeException err) {
                ui.post(() -> { if (!isDestroyed() && !isFinishing()) {
                    connecting = false;
                    receiveStatus.setText(err instanceof javax.net.ssl.SSLException ? "The sender's identity didn't match. Get a fresh invitation." :
                            err.getMessage() != null ? err.getMessage() : "Couldn't connect. Check that the sender is still sharing on this Wi-Fi.");
                }});
            }
        });
    }

    private void renderEntries(List<MediaEntry> entries) {
        receiveStatus.setText(entries.size() + " selected file" + (entries.size() == 1 ? "" : "s") + " from the sender");
        remote.removeAllViews(); Ui.gap(remote, 8);
        for (MediaEntry e : entries) {
            remote.addView(Ui.action(this, e.title + "  ›", false, v -> {
                LibraryStore db = LibraryStore.get(this);
                if (db.get(e.id) == null) db.save(e, false);
                startActivity(new Intent(this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, e.id));
            })); Ui.gap(remote, 7);
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
