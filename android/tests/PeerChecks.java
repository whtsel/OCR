package com.blackboxstream.mobile;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Real libtorrent seeder interoperability, plus a two-ended TLS share including rejection paths. */
public final class PeerChecks {
    private static int passed;
    private static void check(String line, boolean value) {
        if (!value) throw new AssertionError(line);
        System.out.println("PASS " + line); passed++;
    }
    private interface Call { void run() throws Exception; }
    private static void refused(String line, Call call) throws Exception {
        try { call.run(); }
        catch (IOException intended) { check(line, true); return; }
        throw new AssertionError(line);
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        File video = new File(args[1], "dawn.mp4");
        byte[] payload = Files.readAllBytes(video.toPath());
        TorrentMeta metainfo = TorrentMeta.read(Files.readAllBytes(new File(root, "selection.torrent").toPath()));
        TorrentMeta.Item chosen = null;
        for (TorrentMeta.Item file : metainfo.items) if (file.name.equals("dawn.mp4")) chosen = file;
        check("real multifile .torrent parsing and selection", chosen != null && chosen.offset > 0 && chosen.length == payload.length);
        TorrentMeta.Item media = chosen;
        String hex = TorrentMeta.hex(metainfo.hash);
        check("exact v1 info hash matches independent libtorrent fixture", hex.equals(args[2]));
        check("cached descriptor keeps info hash and trackers", TorrentMeta.hex(TorrentMeta.read(metainfo.packed()).hash).equals(hex)
                && TorrentMeta.read(metainfo.packed()).trackers.size() == 2);
        TorrentNetwork network = new TorrentNetwork(() -> false, Collections.emptyList());
        TorrentMeta magnet = network.resolve(TorrentMeta.magnet("magnet:?xt=urn:btih:" + hex + "&tr=" + args[3]));
        check("BEP 9 magnet returns exact metadata from libtorrent through HTTP tracker", Arrays.equals(metainfo.info, magnet.info));
        TorrentMeta udp = network.resolve(TorrentMeta.magnet("magnet:?xt=urn:btih:" + hex + "&tr=" + args[4]));
        check("BEP 15 UDP tracker discovers the same seeder", Arrays.equals(metainfo.info, udp.info));
        TorrentMeta dht = new TorrentNetwork(() -> false, Collections.singletonList(args[5]))
                .resolve(TorrentMeta.magnet("magnet:?xt=urn:btih:" + hex));
        check("trackerless magnet resolves via a BEP 5 lookup", Arrays.equals(metainfo.info, dht.info));
        refused("v2-only magnet fails explicitly", () -> TorrentMeta.magnet("magnet:?xt=urn:btmh:1220ff"));
        refused("tampered peer metadata is rejected", () -> {
            byte[] changed = magnet.info.clone(); changed[changed.length / 2] ^= 1;
            TorrentMeta.fromMagnet(TorrentMeta.magnet("magnet:?xt=urn:btih:" + hex), changed);
        });
        File part = new File(root, "selected-media.part");
        if (!part.createNewFile()) throw new AssertionError("Unexpected old fixture output");
        TorrentTransfer.Pieces pieces = new TorrentTransfer.Pieces(part, metainfo, media);
        long seek = payload.length - 5300;
        byte[] actual = new byte[4096];
        CountDownLatch entered = new CountDownLatch(1), gotProgress = new CountDownLatch(1), allowRemainder = new CountDownLatch(1);
        try (MediaBytes source = pieces.reader()) {
            FutureTask<Integer> reader = new FutureTask<>(() -> { entered.countDown(); return source.read(seek, actual, 0, actual.length); });
            Thread read = new Thread(reader, "test-torrent-seek"); read.start();
            check("reads wait for verified pieces instead of returning sparse zeros", entered.await(2, TimeUnit.SECONDS) && !reader.isDone());
            Thread.sleep(100); // let the read queue its priority before opening the independent seed
            FutureTask<Void> transfer = new FutureTask<>(() -> {
                TorrentTransfer.download(metainfo, media, pieces, part, network, () -> false, bytes -> {
                    if (gotProgress.getCount() != 0) {
                        gotProgress.countDown();
                        try { allowRemainder.await(8, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }); return null;
            });
            Thread run = new Thread(transfer, "test-torrent-download"); run.start();
            try {
                check("first piece reaches the progressive source", gotProgress.await(12, TimeUnit.SECONDS));
                int n = reader.get(5, TimeUnit.SECONDS);
                check("random seek into a torrent reads verified media before completion", n > 0 && pieces.received() < payload.length
                        && Arrays.equals(Arrays.copyOf(actual, n), Arrays.copyOfRange(payload, (int) seek, (int) seek + n)));
            } finally { allowRemainder.countDown(); }
            transfer.get(20, TimeUnit.SECONDS);
            long boundary = metainfo.pieceLength - media.offset - 2;
            byte[] header = new byte[16];
            check("reads fill the requested range across two verified torrent pieces", source.read(boundary, header, 0, header.length) == header.length
                    && Arrays.equals(header, Arrays.copyOfRange(payload, (int) boundary, (int) boundary + header.length)));
        }
        check("selected file with unaligned borders matches original bytes", Arrays.equals(payload, Files.readAllBytes(part.toPath()))
                && pieces.received() == payload.length);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        LanShare.Item selected = LanShare.Item.file("Signal at Dawn", MediaEntry.Kind.MOVIE, video);
        try (LanShare.Server server = new LanShare.Server(loopback, 8, Collections.singletonList(selected), 15000, true)) {
            LanShare.Invite invitation = LanShare.parse(server.invite.encode(), true);
            refused("production LAN invite parser rejects loopback and public addresses", () -> LanShare.parse(server.invite.encode(), false));
            try (LanShare.Peer receiver = new LanShare.Peer(invitation)) {
                List<LanShare.Item> files = receiver.items();
                check("paired TLS client receives only the selected item", files.size() == 1 && files.get(0).id.equals(selected.id));
                byte[] read = new byte[32768];
                check("LAN byte-range streaming exposes the exact seek bytes", receiver.read(files.get(0), 50000, read, 0, read.length) == read.length
                        && Arrays.equals(read, Arrays.copyOfRange(payload, 50000, 50000 + read.length)));
                refused("LAN listener refuses unselected file IDs", () -> receiver.read(new LanShare.Item("00000000", "not shared", MediaEntry.Kind.MOVIE,
                        payload.length, null), 0, read, 0, 32));
                File offline = new File(root, "lan-received.part");
                LanShare.copy(invitation, files.get(0), offline, new OfflineTransfer.Monitor() {
                    @Override public boolean stopped() { return false; }
                    @Override public void total(long bytes) throws IOException { if (bytes != payload.length) throw new IOException("Unexpected LAN size"); }
                }, null);
                check("LAN offline copy matches the source file", Arrays.equals(Files.readAllBytes(offline.toPath()), payload));
            }
            try (MediaBytes stream = LanShare.open(invitation, selected, null)) {
                byte[] largeRead = new byte[300000];
                check("LAN player reads spanning multiple transport chunks are complete", stream.read(10000, largeRead, 0, largeRead.length) == largeRead.length
                        && Arrays.equals(largeRead, Arrays.copyOfRange(payload, 10000, 310000)));
            }
            byte[] wrongKey = invitation.key.clone(); wrongKey[0] ^= 1;
            refused("wrong invitation secret is rejected", () -> new LanShare.Peer(new LanShare.Invite(loopback, invitation.port, invitation.pin, wrongKey)).close());
            byte[] wrongPin = invitation.pin.clone(); wrongPin[0] ^= 1;
            refused("wrong TLS certificate pin is rejected", () -> new LanShare.Peer(new LanShare.Invite(loopback, invitation.port, wrongPin, invitation.key)).close());
            server.close();
            refused("stop revokes access immediately", () -> new LanShare.Peer(invitation).close());
        }
        System.out.println(passed + " BlackBox peer checks passed");
    }
}
