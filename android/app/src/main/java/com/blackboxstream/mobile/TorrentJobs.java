package com.blackboxstream.mobile;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

final class TorrentJobs {
    private static final ConcurrentHashMap<Integer, TorrentTransfer.Pieces> STREAMS = new ConcurrentHashMap<>();
    private static final String PREFIX = "torrent:";
    private TorrentJobs() { }

    static File descriptor(Context context, String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{40}")) throw new IOException("Invalid torrent identifier.");
        File dir = new File(context.getFilesDir(), "torrent-info");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Could not open torrent storage.");
        return new File(dir, hash + ".torrent");
    }

    static MediaEntry save(Context context, TorrentMeta meta, TorrentMeta.Item file) throws IOException {
        return save(context, meta, file, file.name);
    }

    static synchronized MediaEntry save(Context context, TorrentMeta meta, TorrentMeta.Item file, String title) throws IOException {
        String hex = TorrentMeta.hex(meta.hash);
        File path = descriptor(context, hex);
        File temp = new File(path.getParentFile(), hex + ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(meta.packed()); out.getFD().sync(); }
            if (!temp.renameTo(path)) throw new IOException("Could not keep the verified torrent metadata.");
        } finally { temp.delete(); }
        return new MediaEntry(title, file.kind(), PREFIX + hex + ":" + file.index);
    }

    static void run(Context context, MediaEntry e, File part, OfflineTransfer.Monitor monitor) throws IOException {
        String[] fields = e.source.split(":");
        if (fields.length != 3 || !fields[2].matches("[0-9]{1,4}")) throw new OfflineTransfer.Rejected("Invalid torrent selection.");
        TorrentMeta meta;
        try { meta = TorrentMeta.read(TorrentMeta.slurp(new FileInputStream(descriptor(context, fields[1])), 4 * 1024 * 1024)); }
        catch (IOException missing) { throw new OfflineTransfer.Rejected("Open the magnet or .torrent again to restore its verified metadata."); }
        int index = Integer.parseInt(fields[2]);
        if (index >= meta.items.size() || !TorrentMeta.hex(meta.hash).equals(fields[1])) throw new OfflineTransfer.Rejected("Invalid torrent selection.");
        TorrentMeta.Item item = meta.items.get(index);
        if (!item.playable()) throw new OfflineTransfer.Rejected("This torrent file is not a supported media selection.");
        TorrentTransfer.Pieces pieces = new TorrentTransfer.Pieces(part, meta, item);
        // Create the path before exposing the blocking reader; its bytes are served only when verified.
        try (FileOutputStream file = new FileOutputStream(part, false)) { }
        monitor.total(item.length);
        STREAMS.put(e.transfer, pieces);
        try { TorrentTransfer.download(meta, item, pieces, part, new TorrentNetwork(monitor::stopped), monitor::stopped, verified -> { }); }
        catch (IOException failure) {
            pieces.abandon();
            if (monitor.stopped()) throw failure;
            throw new OfflineTransfer.Rejected(failure.getMessage() == null ? "The torrent transfer failed. Check available seeders and space." : failure.getMessage());
        } finally { STREAMS.remove(e.transfer, pieces); }
    }

    static boolean streaming(MediaEntry e) { return received(e) > 0; }
    static long received(MediaEntry e) { TorrentTransfer.Pieces p = STREAMS.get(e.transfer); return p == null ? 0 : p.received(); }
    static MediaBytes open(MediaEntry e) throws IOException {
        TorrentTransfer.Pieces p = STREAMS.get(e.transfer);
        if (p == null) throw new IOException("The download hasn't reached a seeder yet.");
        return p.reader();
    }
    static void cancel(MediaEntry e) { TorrentTransfer.Pieces p = STREAMS.get(e.transfer); if (p != null) p.abandon(); }

}
