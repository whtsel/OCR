package com.blackboxstream.mobile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Only SHA-1-verified piece bytes become readable. A seek can prioritize an unreceived piece. */
final class TorrentTransfer {
    interface Progress { void update(long verified); }

    static final class Pieces {
        private final File file;
        private final TorrentMeta meta;
        private final TorrentMeta.Item item;
        private final int first, last;
        private final BitSet verified = new BitSet();
        private int urgent = -1;
        private long received;
        private boolean abandoned;

        Pieces(File file, TorrentMeta meta, TorrentMeta.Item item) {
            this.file = file; this.meta = meta; this.item = item;
            first = (int) (item.offset / meta.pieceLength);
            last = (int) ((item.offset + item.length - 1) / meta.pieceLength);
        }

        synchronized int wanted() {
            if (urgent >= first && urgent <= last && !verified.get(urgent)) return urgent;
            int p = verified.nextClearBit(first);
            return p > last ? -1 : p;
        }

        void accept(RandomAccessFile output, int piece, byte[] bytes) throws IOException {
            long pieceStart = (long) piece * meta.pieceLength;
            long start = Math.max(pieceStart, item.offset), end = Math.min(pieceStart + bytes.length, item.offset + item.length);
            int n = (int) (end - start);
            if (n <= 0) throw new IOException("Unexpected piece outside the selected media file");
            output.seek(start - item.offset);
            output.write(bytes, (int) (start - pieceStart), n);
            synchronized (this) {
                if (!verified.get(piece)) { verified.set(piece); received += n; }
                notifyAll();
            }
        }

        synchronized void abandon() { abandoned = true; notifyAll(); }
        synchronized long received() { return received; }

        MediaBytes reader() throws IOException {
            RandomAccessFile input = new RandomAccessFile(file, "r");
            return new MediaBytes() {
                private volatile boolean closed;
                @Override public long size() { return item.length; }
                @Override public int read(long offset, byte[] data, int start, int count) throws IOException {
                    if (offset < 0) throw new IOException("Invalid read");
                    if (count == 0) return 0;
                    if (offset >= item.length) return -1;
                    long point = item.offset + offset;
                    int piece = (int) (point / meta.pieceLength);
                    int n = (int) Math.min(Math.min(count, item.length - offset), meta.pieceLength - point % meta.pieceLength);
                    long deadline = System.nanoTime() + 40_000_000_000L;
                    synchronized (Pieces.this) {
                        if (!verified.get(piece)) urgent = piece;
                        while (!closed && !abandoned && !verified.get(piece)) {
                            try { Pieces.this.wait(1000); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Torrent read interrupted", e); }
                            if (System.nanoTime() - deadline >= 0) throw new IOException("Waiting for a torrent seeder timed out.");
                        }
                        if (closed || !verified.get(piece)) throw new IOException("This torrent is no longer downloading.");
                    }
                    synchronized (input) { input.seek(offset); input.readFully(data, start, n); }
                    return n;
                }
                @Override public void close() throws IOException {
                    closed = true;
                    synchronized (Pieces.this) { Pieces.this.notifyAll(); }
                    synchronized (input) { input.close(); }
                }
            };
        }
    }

    static void download(TorrentMeta meta, TorrentMeta.Item item, Pieces pieces, File target,
                         TorrentNetwork network, TorrentNetwork.Stop stop, Progress progress) throws IOException {
        List<InetSocketAddress> peers = network.discover(meta);
        try (RandomAccessFile out = new RandomAccessFile(target, "rw")) {
            out.setLength(item.length);
            if (consume(meta, pieces, out, network, stop, progress, peers)) return;
            List<InetSocketAddress> others = new ArrayList<>(network.fallback(meta));
            // One reconnect is useful if the tracker initially returned temporarily choked peers.
            for (InetSocketAddress peer : peers) if (!others.contains(peer)) others.add(peer);
            if (consume(meta, pieces, out, network, stop, progress, others)) return;
            throw new IOException("No available seeder could complete the selected file.");
        } catch (IOException err) {
            pieces.abandon();
            throw err;
        }
    }

    private static boolean consume(TorrentMeta meta, Pieces pieces, RandomAccessFile out, TorrentNetwork network,
                                   TorrentNetwork.Stop stop, Progress progress, List<InetSocketAddress> peers) throws IOException {
        for (InetSocketAddress address : peers) {
            if (stop.stopped()) throw new IOException("Torrent cancelled.");
            TorrentNetwork.Peer peer;
            try { peer = network.open(address, meta); }
            catch (IOException unavailable) { continue; }
            try (TorrentNetwork.Peer open = peer) {
                for (int wanted; (wanted = pieces.wanted()) != -1; ) {
                    if (stop.stopped()) throw new IOException("Torrent cancelled.");
                    byte[] verified;
                    try { verified = open.piece(wanted, meta); }
                    catch (IOException badPeer) { break; }
                    // A write/sync failure is local, not something retrying a different peer can fix.
                    pieces.accept(out, wanted, verified);
                    progress.update(pieces.received());
                }
                if (pieces.wanted() == -1) { out.getFD().sync(); return true; }
            }
        }
        return pieces.wanted() == -1;
    }
}
