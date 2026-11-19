package com.blackboxstream.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
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

public final class CommunityActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final List<CommunityRoom.Event> messages = new ArrayList<>();
    private CommunityRoom room;
    private long cursor;
    private boolean visible, fetching, posting;
    private LinearLayout starter, connected, feed;
    private TextView status, roomStatus;
    private EditText relay, invite, author, content;

    private final Runnable polling = new Runnable() {
        @Override public void run() {
            if (visible && room != null) { if (!fetching) refresh(); ui.postDelayed(this, 8000); }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); Ui.fitWindow(this, root); setContentView(root);
        ScrollView scroll = new ScrollView(this); root.addView(scroll, new LinearLayout.LayoutParams(-1,-1));
        LinearLayout pane = Ui.column(this); Ui.pad(pane, 18, 16); scroll.addView(pane);
        pane.addView(Ui.action(this, "‹  Home", false, v -> finish()), new LinearLayout.LayoutParams(-2,-2));
        Ui.gap(pane, 19); pane.addView(Ui.kicker(this, "INVITE ONLY  /  ENCRYPTED ROOMS")); Ui.gap(pane, 5);
        pane.addView(Ui.text(this, "Community", 36, Ui.INK, Ui.DISPLAY)); Ui.gap(pane, 7);
        pane.addView(Ui.text(this, "Discuss media and recommend exact HTTPS links in a private room. Nothing is sent until you join a room or choose to post.", 15, Ui.MUTED, null));
        Ui.gap(pane, 13); status = Ui.text(this, "Not connected. Use an invitation or the HTTPS address of a BlackBox Community relay.", 14, Ui.MUTED, null); pane.addView(status);
        Ui.gap(pane, 10); starter = Ui.column(this); pane.addView(starter);
        invite = field("Paste a BBX-ROOM-1 invitation", 620, true, false); starter.addView(invite); Ui.gap(starter, 8);
        starter.addView(Ui.action(this, "Join room", true, v -> connect(false))); Ui.gap(starter, 17);
        starter.addView(Ui.text(this, "Or create a room on your existing relay", 15, Ui.INK, Ui.BOLD)); Ui.gap(starter, 7);
        relay = field("https://your-community-relay.example", 512, false, true); starter.addView(relay); Ui.gap(starter, 8);
        starter.addView(Ui.action(this, "Create room", false, v -> connect(true))); Ui.gap(starter, 15);
        starter.addView(Ui.text(this, "No public relay is configured. A BlackBox relay can link invited devices across networks. Use Local Share for media transfer on the same Wi-Fi.", 13, Ui.MUTED, null));

        connected = Ui.column(this); connected.setVisibility(View.GONE); pane.addView(connected);
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.action(this, "Invite member", false, v -> share()), new LinearLayout.LayoutParams(0,-2,1)); Ui.space(actions, 7);
        actions.addView(Ui.action(this, "Disconnect", false, v -> disconnect()), new LinearLayout.LayoutParams(0,-2,1)); connected.addView(actions);
        Ui.gap(connected, 12);
        author = field("Your display name (optional)", 40, false, false); connected.addView(author); Ui.gap(connected, 8);
        content = field("Message", 500, true, false); connected.addView(content); Ui.gap(connected, 8);
        LinearLayout compose = Ui.row(this);
        compose.addView(Ui.action(this, "Send message", true, v -> post(null, content.getText().toString())), new LinearLayout.LayoutParams(0,-2,1));
        Ui.space(compose, 7); compose.addView(Ui.action(this, "Recommend link", false, v -> recommend()), new LinearLayout.LayoutParams(0,-2,1));
        connected.addView(compose); Ui.gap(connected, 15);
        LinearLayout heading = Ui.row(this); heading.addView(Ui.text(this, "Room feed", 23, Ui.INK, Ui.DISPLAY)); Ui.flex(heading);
        heading.addView(Ui.action(this, "Refresh", false, v -> refresh())); connected.addView(heading); Ui.gap(connected, 7);
        roomStatus = Ui.text(this, "", 13, Ui.MUTED, null); connected.addView(roomStatus); Ui.gap(connected, 8);
        feed = Ui.column(this); connected.addView(feed);
        pane.addView(Ui.text(this, "Invited people can read and forward your messages. Display names aren't verified. The relay sees IP addresses, time and encrypted room traffic, not the message text.", 12, Ui.MUTED, null));
    }

    @Override protected void onStart() { super.onStart(); visible = true; ui.post(polling); }
    @Override protected void onStop() { visible = false; ui.removeCallbacks(polling); super.onStop(); }
    @Override protected void onDestroy() { room = null; io.shutdownNow(); super.onDestroy(); }

    private void connect(boolean create) {
        if (room != null) return;
        final CommunityRoom attempt;
        try { attempt = create ? CommunityRoom.create(relay.getText().toString()) : CommunityRoom.parse(invite.getText().toString()); }
        catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); return; }
        status.setText("Connecting to the relay over HTTPS…");
        room = attempt; cursor = 0; fetching = true;
        io.execute(() -> {
            try {
                CommunityRoom.Feed first = attempt.fetch(0);
                ui.post(() -> {
                    if (!valid(attempt)) return;
                    fetching = false; invite.setText(""); messages.clear(); update(first);
                    starter.setVisibility(View.GONE); connected.setVisibility(View.VISIBLE);
                    status.setText("Connected to " + java.net.URI.create(attempt.relay).getHost() + " · private room");
                    ui.removeCallbacks(polling); if (visible) ui.postDelayed(polling, 8000);
                });
            } catch (IOException | RuntimeException err) {
                ui.post(() -> { if (valid(attempt)) { room = null; fetching = false; status.setText(problem(err)); } });
            }
        });
    }

    private void disconnect() {
        room = null; cursor = 0; fetching = posting = false; messages.clear(); feed.removeAllViews(); content.setText("");
        ui.removeCallbacks(polling); connected.setVisibility(View.GONE); starter.setVisibility(View.VISIBLE);
        status.setText("Disconnected. Room traffic is off.");
    }

    private void refresh() {
        CommunityRoom active = room;
        if (active == null || fetching || !visible) return;
        fetching = true; long since = cursor;
        io.execute(() -> {
            try {
                CommunityRoom.Feed result = active.fetch(since);
                ui.post(() -> { if (valid(active)) { fetching = false; update(result); } });
            } catch (IOException | RuntimeException err) { ui.post(() -> { if (valid(active)) { fetching = false; roomStatus.setText(problem(err)); } }); }
        });
    }

    private void update(CommunityRoom.Feed newest) {
        cursor = newest.cursor; messages.addAll(newest.events);
        if (messages.size() > 60) messages.subList(0, messages.size() - 60).clear();
        roomStatus.setText(messages.isEmpty() ? "No posts yet. Send the first message or invite someone you trust." : messages.size() + " recent posts · encrypted in transit and on the relay");
        if (!newest.events.isEmpty() || messages.isEmpty()) {
            feed.removeAllViews();
            for (int i = messages.size() - 1; i >= 0; i--) {
                CommunityRoom.Event event = messages.get(i);
                LinearLayout card = Ui.column(this); Ui.pad(card, 13, 12); Ui.card(card, Ui.CARD, 13, Ui.BORDER);
                card.addView(Ui.kicker(this, event.name)); Ui.gap(card, 5);
                card.addView(Ui.text(this, event.text, 15, Ui.INK, null));
                if (event.link != null) { Ui.gap(card, 7); card.addView(Ui.action(this, "Review shared link", false, v -> open(event))); }
                feed.addView(card); Ui.gap(feed, 7);
            }
        }
    }

    private void recommend() {
        LinearLayout form = Ui.column(this); Ui.pad(form, 17, 9);
        EditText title = field("Media title / recommendation", 500, false, false); form.addView(title); Ui.gap(form, 7);
        EditText link = field("Direct HTTPS media URL", 2048, false, true); form.addView(link); Ui.gap(form, 8);
        form.addView(Ui.text(this, "Only send links you have permission to share. Everyone invited can read the URL, including its query string.", 13, Ui.MUTED, null));
        new AlertDialog.Builder(this).setTitle("Recommend a media link").setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Send recommendation", (d, w) -> post(link.getText().toString(), title.getText().toString())).show();
    }

    private void post(String link, String text) {
        CommunityRoom active = room;
        if (active == null || posting) return;
        final CommunityRoom.Event event;
        try { event = CommunityRoom.Event.make(author.getText().toString(), text, link); }
        catch (IllegalArgumentException invalid) { toast(invalid.getMessage()); return; }
        posting = true; roomStatus.setText("Encrypting and sending…");
        io.execute(() -> {
            try { active.post(event); ui.post(() -> { if (valid(active)) { posting = false; if (link == null) content.setText(""); roomStatus.setText("Sent"); refresh(); } }); }
            catch (IOException | RuntimeException err) { ui.post(() -> { if (valid(active)) { posting = false; roomStatus.setText(problem(err)); } }); }
        });
    }

    private void share() {
        CommunityRoom active = room; if (active == null) return;
        new AlertDialog.Builder(this).setTitle("Invite a trusted person").setMessage("The invitation contains the room key. Anyone who gets it can read this room and post as a member. Send it privately.")
                .setNegativeButton("Cancel", null).setPositiveButton("Share invitation", (d,w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, active.code()), "Invite to BlackBox Community"))).show();
    }

    private void open(CommunityRoom.Event event) {
        String host = java.net.URI.create(event.link).getHost();
        new AlertDialog.Builder(this).setTitle("Open link from " + event.name + "?")
                .setMessage("BlackBox will connect directly to " + host + ". Only open links you trust.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open media", (d, w) -> {
                    MediaEntry e = new MediaEntry(MediaSources.titleFor(event.link), MediaSources.infer(null, event.link), event.link);
                    LibraryStore db = LibraryStore.get(this); if (db.get(e.id) == null) db.save(e, false);
                    startActivity(new Intent(this, PlayerActivity.class).putExtra(PlaybackService.EXTRA_ID, e.id));
                }).show();
    }

    private EditText field(String hint, int max, boolean multiline, boolean uri) {
        EditText box = new EditText(this); box.setTextColor(Ui.INK); box.setHintTextColor(Ui.MUTED);
        box.setSingleLine(!multiline); box.setMaxLines(multiline ? 4 : 1); box.setHint(hint); box.setContentDescription(hint);
        box.setSaveEnabled(false); box.setFilters(new InputFilter[] {new InputFilter.LengthFilter(max)});
        box.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0)
                | (uri ? InputType.TYPE_TEXT_VARIATION_URI : 0) | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Ui.card(box, Ui.CARD, 12, Ui.BORDER); Ui.pad(box, 11, 8); box.setMinHeight(Ui.dp(this, 48)); return box;
    }

    private boolean valid(CommunityRoom active) { return room == active && !isFinishing() && !isDestroyed(); }
    private String problem(Exception error) {
        String msg = error.getMessage();
        return msg != null && (msg.startsWith("The Community relay returned") || msg.startsWith("This relay is rate-limited") || msg.equals("This address is not a BlackBox Community v1 relay.")) ? msg
                : "Could not reach this BlackBox HTTPS relay. Check the invitation, certificate and connection.";
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
