package com.blackboxstream.mobile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Invitation secrets only live in memory; permanent database identifiers do not contain them. */
final class LanSessions {
    private static final Map<String, Entry> LIVE = new LinkedHashMap<>();
    private static final long TTL = 20 * 60 * 1000L;
    private LanSessions() { }
    private static final class Entry {
        final LanShare.Invite invite;
        final LanShare.Item item;
        final LanShare.SocketRoute route;
        final long expiry = android.os.SystemClock.elapsedRealtime() + TTL;
        Entry(LanShare.Invite invite, LanShare.Item item, LanShare.SocketRoute route) { this.invite = invite; this.item = item; this.route = route; }
    }

    static synchronized List<MediaEntry> set(LanShare.Invite invite, List<LanShare.Item> items, LanShare.SocketRoute route) {
        prune(); List<MediaEntry> rows = new ArrayList<>();
        String session = TorrentMeta.hex(invite.pin).substring(0, 24);
        for (LanShare.Item item : items) {
            String address = "lan:" + session + ":" + item.id;
            MediaEntry e = new MediaEntry(item.title, item.kind, address);
            if (LIVE.size() >= 64 && !LIVE.containsKey(address)) LIVE.remove(LIVE.keySet().iterator().next());
            LIVE.put(address, new Entry(invite, item, route)); rows.add(e);
        }
        return rows;
    }

    private static void prune() { long now = android.os.SystemClock.elapsedRealtime(); LIVE.values().removeIf(e -> e.expiry <= now); }
    private static synchronized Entry get(MediaEntry e) { prune(); return LIVE.get(e.source); }
    static boolean available(MediaEntry e) { return get(e) != null; }
    static MediaBytes open(MediaEntry e) throws IOException {
        Entry entry = get(e);
        if (entry == null) throw new IOException("Pair with the sender again.");
        return LanShare.open(entry.invite, entry.item, entry.route);
    }
    static void copy(MediaEntry e, File part, OfflineTransfer.Monitor monitor) throws IOException {
        Entry entry = get(e);
        if (entry == null) throw new OfflineTransfer.Rejected("Reconnect to the sender with a new invitation.");
        LanShare.copy(entry.invite, entry.item, part, monitor, entry.route);
    }
}
