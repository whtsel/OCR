package com.blackboxstream.mobile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

/** Download-only BitTorrent v1 transport: TCP peers, BEP 9 metadata, HTTP(S)/UDP trackers and BEP 5 lookup. */
final class TorrentNetwork {
    interface Stop { boolean stopped(); }
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] PROTOCOL = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PREFIX = "-BB0200-".getBytes(StandardCharsets.US_ASCII);
    private static final int BLOCK = 16 * 1024;
    private static final int TRACKER_LIMIT = 256 * 1024;
    private static final int MAX_PEERS = 60;
    private static final int IO_MS = 3500;
    private static final int PORT = 49317; // Download-only: no inbound peer port is opened.
    private final byte[] peerId = random(20), nodeId = random(20);
    private final Stop stop;
    private final List<String> bootstrap;

    TorrentNetwork(Stop stop) {
        this(stop, Arrays.asList("router.bittorrent.com:6881", "dht.transmissionbt.com:6881", "router.utorrent.com:6881"));
    }

    TorrentNetwork(Stop stop, List<String> bootstrap) {
        this.stop = stop; this.bootstrap = bootstrap;
        System.arraycopy(PREFIX, 0, peerId, 0, PREFIX.length);
    }

    TorrentMeta resolve(TorrentMeta.Magnet request) throws IOException {
        long until = deadline(55);
        List<InetSocketAddress> peers = announce(request.hash, request.trackers, request.peers, 1, until);
        Set<InetSocketAddress> tried = new HashSet<>();
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            for (InetSocketAddress address : peers) {
                if (!tried.add(address)) continue;
                check(until);
                try (Peer peer = new Peer(address, request.hash)) {
                    return TorrentMeta.fromMagnet(request, peer.metadata(until));
                } catch (IOException unavailable) { last = unavailable; check(until); }
            }
            if (attempt == 0) peers = dht(request.hash, Math.min(until, deadline(15)));
        }
        throw new IOException("No peer supplied verified magnet metadata. Try a link with active seeders or import its .torrent file.", last);
    }

    List<InetSocketAddress> discover(TorrentMeta meta) throws IOException {
        List<InetSocketAddress> peers = announce(meta.hash, meta.trackers, meta.peers, meta.length, deadline(22));
        if (!meta.privateSwarm && peers.isEmpty()) peers = dht(meta.hash, deadline(15));
        return peers;
    }

    List<InetSocketAddress> fallback(TorrentMeta meta) throws IOException {
        return meta.privateSwarm ? Collections.emptyList() : dht(meta.hash, deadline(15));
    }

    Peer open(InetSocketAddress address, TorrentMeta meta) throws IOException {
        return new Peer(address, meta.hash);
    }

    private List<InetSocketAddress> announce(byte[] hash, List<String> trackers, List<String> manual, long left, long until) throws IOException {
        Set<InetSocketAddress> list = new LinkedHashSet<>();
        for (String hint : manual) {
            if (expired(until)) break;
            try { list.add(address(hint)); } catch (IOException | IllegalArgumentException ignored) { }
        }
        // Query a few independent trackers; a dead tracker cannot block the entire swarm.
        for (String link : trackers) {
            checkStop();
            if (expired(until) || list.size() >= MAX_PEERS) break;
            try {
                URI u = URI.create(link);
                if (u.getScheme().equals("udp")) udpTracker(u, hash, left, list);
                else {
                    Bencode.Node response = Bencode.parse(trackerBody(u, hash, left));
                    compact(TorrentMeta.bytes(response, "peers"), 4, list);
                    compact(TorrentMeta.bytes(response, "peers6"), 16, list);
                    Bencode.Node plain = response.get("peers");
                    if (plain != null && plain.list != null) for (Bencode.Node p : plain.list) {
                        if (list.size() >= MAX_PEERS) break;
                        long port = TorrentMeta.num(p, "port", 0);
                        String ip = TorrentMeta.text(p, "ip");
                        if (port > 0 && port <= 65535 && ip.matches("[0-9a-fA-F.:]{3,48}")) {
                            try { offer(list, InetAddress.getByName(ip), (int) port); } catch (IOException ignored) { }
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) { }
        }
        return new ArrayList<>(list);
    }

    private byte[] trackerBody(URI u, byte[] hash, long left) throws IOException {
        String suffix = "info_hash=" + escaped(hash) + "&peer_id=" + escaped(peerId) + "&port=" + PORT
                + "&uploaded=0&downloaded=0&left=" + Math.max(1, left) + "&compact=1&numwant=" + MAX_PEERS + "&event=started";
        URI endpoint = URI.create(u.toASCIIString() + (u.getRawQuery() == null ? "?" : "&") + suffix);
        if (u.getScheme().equals("https")) {
            HttpsURLConnection response = (HttpsURLConnection) endpoint.toURL().openConnection();
            response.setInstanceFollowRedirects(false);
            response.setConnectTimeout(IO_MS); response.setReadTimeout(IO_MS);
            response.setRequestProperty("Accept-Encoding", "identity");
            try {
                if (response.getResponseCode() != 200) throw new IOException("Tracker HTTP failure");
                return TorrentMeta.slurp(response.getInputStream(), TRACKER_LIMIT);
            } finally { response.disconnect(); }
        }
        // Cleartext is confined to explicitly selected BitTorrent trackers. Media URLs remain HTTPS-only.
        String path = endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath();
        int port = endpoint.getPort() < 0 ? 80 : endpoint.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(checked(endpoint.getHost(), port), IO_MS); socket.setSoTimeout(IO_MS);
            String query = "GET " + path + "?" + endpoint.getRawQuery() + " HTTP/1.1\r\nHost: " + endpoint.getRawAuthority()
                    + "\r\nUser-Agent: BlackBox-Android/1.1\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(query.getBytes(StandardCharsets.US_ASCII));
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String status = line(in, 256);
            if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("Tracker HTTP failure");
            int headers = 0, length = -1;
            boolean chunked = false;
            for (String field; !(field = line(in, 4096)).isEmpty();) {
                if ((headers += field.length()) > 16384) throw new IOException("Tracker headers too large");
                String lower = field.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(field.substring(15).trim());
                if (lower.startsWith("transfer-encoding:")) chunked = lower.substring(18).trim().equals("chunked");
            }
            if (length > TRACKER_LIMIT) throw new IOException("Tracker reply too large");
            DataInputStream data = new DataInputStream(in);
            if (chunked) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                for (;;) {
                    int size = Integer.parseInt(line(in, 128).split(";", 2)[0].trim(), 16);
                    if (size == 0) return output.toByteArray();
                    if (size < 0 || size > TRACKER_LIMIT - output.size()) throw new IOException("Tracker reply too large");
                    byte[] chunk = new byte[size]; data.readFully(chunk); output.write(chunk);
                    if (!line(in, 4).isEmpty()) throw new IOException("Unexpected tracker chunk");
                }
            }
            if (length >= 0) { byte[] body = new byte[length]; data.readFully(body); return body; }
            return TorrentMeta.slurp(in, TRACKER_LIMIT);
        }
    }

    private static String line(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < limit) {
            int ch = in.read();
            if (ch == -1) throw new EOFException();
            if (ch == '\r') {
                if (in.read() != '\n') throw new IOException("Invalid tracker response");
                return new String(out.toByteArray(), StandardCharsets.US_ASCII);
            }
            out.write(ch);
        }
        throw new IOException("Tracker header too long");
    }

    private void udpTracker(URI uri, byte[] hash, long left, Set<InetSocketAddress> peers) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetSocketAddress server = checked(uri.getHost(), uri.getPort());
            socket.connect(server); socket.setSoTimeout(IO_MS);
            int tx = RANDOM.nextInt();
            byte[] hello = ByteBuffer.allocate(16).putLong(0x41727101980L).putInt(0).putInt(tx).array();
            socket.send(new DatagramPacket(hello, hello.length));
            byte[] bytes = new byte[2048];
            DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
            socket.receive(reply);
            ByteBuffer body = ByteBuffer.wrap(bytes, 0, reply.getLength());
            if (body.remaining() < 16 || body.getInt() != 0 || body.getInt() != tx) return;
            long id = body.getLong(); tx = RANDOM.nextInt();
            byte[] announce = ByteBuffer.allocate(98).putLong(id).putInt(1).putInt(tx).put(hash).put(peerId)
                    .putLong(0).putLong(Math.max(1, left)).putLong(0).putInt(2).putInt(0).putInt(RANDOM.nextInt())
                    .putInt(MAX_PEERS).putShort((short) PORT).array();
            socket.send(new DatagramPacket(announce, announce.length));
            reply.setLength(bytes.length); socket.receive(reply);
            body = ByteBuffer.wrap(bytes, 0, reply.getLength());
            if (body.remaining() >= 20 && body.getInt() == 1 && body.getInt() == tx) {
                compact(Arrays.copyOfRange(bytes, 20, reply.getLength()), server.getAddress().getAddress().length, peers);
            }
        }
    }

    private static void compact(byte[] bytes, int width, Set<InetSocketAddress> peers) {
        if (bytes == null) return;
        for (int i = 0; i + width + 2 <= bytes.length && peers.size() < MAX_PEERS; i += width + 2) {
            int port = ((bytes[i + width] & 255) << 8) | (bytes[i + width + 1] & 255);
            try { offer(peers, InetAddress.getByAddress(Arrays.copyOfRange(bytes, i, i + width)), port); }
            catch (IOException ignored) { }
        }
    }

    private static void offer(Set<InetSocketAddress> peers, InetAddress ip, int port) {
        if (allowed(ip) && port > 0 && port <= 65535) peers.add(new InetSocketAddress(ip, port));
    }

    List<InetSocketAddress> dht(byte[] hash, long until) throws IOException {
        if (bootstrap.isEmpty()) return Collections.emptyList();
        Set<InetSocketAddress> peers = new LinkedHashSet<>(), visited = new HashSet<>();
        List<DhtNode> candidates = new ArrayList<>();
        boolean publicOnly = bootstrap.size() == 3 && bootstrap.get(0).startsWith("router.bittorrent.com:");
        for (String endpoint : bootstrap) {
            try { candidates.add(new DhtNode(address(endpoint), null)); }
            catch (IOException | IllegalArgumentException ignored) { }
        }
        try (DatagramSocket udp = new DatagramSocket()) {
            int passes = 0;
            while (!candidates.isEmpty() && !expired(until) && passes < 36 && peers.size() < 12) {
                checkStop();
                DhtNode node = candidates.remove(0);
                if (!visited.add(node.address)) continue;
                passes++;
                byte[] tx = random(4);
                Map<String,Object> args = new HashMap<>(), request = new HashMap<>();
                args.put("id", nodeId); args.put("info_hash", hash);
                request.put("a", args); request.put("q", "get_peers"); request.put("t", tx); request.put("y", "q");
                byte[] query = Bencode.encode(request);
                udp.connect(node.address);
                udp.setSoTimeout(900);
                try {
                    udp.send(new DatagramPacket(query, query.length));
                    byte[] response = new byte[4096];
                    DatagramPacket packet = new DatagramPacket(response, response.length);
                    udp.receive(packet);
                    Bencode.Node message = Bencode.parse(Arrays.copyOf(response, packet.getLength())), body = message.get("r");
                    if (body == null || !Arrays.equals(tx, TorrentMeta.bytes(message, "t"))) continue;
                    Bencode.Node values = body.get("values");
                    if (values != null && values.list != null) for (Bencode.Node value : values.list) {
                        if (value.bytes != null && (value.bytes.length == 6 || value.bytes.length == 18)) compact(value.bytes, value.bytes.length - 2, peers);
                    }
                    byte[] raw = TorrentMeta.bytes(body, "nodes");
                    if (raw == null) continue;
                    for (int off = 0; off + 26 <= raw.length && candidates.size() < 100; off += 26) {
                        InetAddress ip = InetAddress.getByAddress(Arrays.copyOfRange(raw, off + 20, off + 24));
                        int port = (raw[off + 24] & 255) * 256 + (raw[off + 25] & 255);
                        if (!allowed(ip) || (publicOnly && !publicAddress(ip)) || port == 0) continue;
                        InetSocketAddress address = new InetSocketAddress(ip, port);
                        if (!visited.contains(address)) candidates.add(new DhtNode(address, Arrays.copyOfRange(raw, off, off + 20)));
                    }
                    candidates.sort((a, b) -> xorCompare(a.id, b.id, hash));
                } catch (IOException invalidOrTimeout) { /* try another node */ }
                finally { udp.disconnect(); }
            }
        }
        if (publicOnly) peers.removeIf(p -> !publicAddress(p.getAddress()));
        return new ArrayList<>(peers);
    }

    private static int xorCompare(byte[] a, byte[] b, byte[] hash) {
        if (a == null || b == null) return a == b ? 0 : a == null ? -1 : 1;
        for (int i = 0; i < 20; i++) {
            int compare = ((a[i] ^ hash[i]) & 255) - ((b[i] ^ hash[i]) & 255);
            if (compare != 0) return compare;
        }
        return 0;
    }

    private static final class DhtNode {
        final InetSocketAddress address;
        final byte[] id;
        DhtNode(InetSocketAddress address, byte[] id) { this.address = address; this.id = id; }
    }

    final class Peer implements Closeable {
        private final Socket socket = new Socket();
        private final DataInputStream in;
        private final DataOutputStream out;
        private final boolean extended;
        private boolean choked = true, interested, advertised;
        private final BitSet pieces = new BitSet();

        Peer(InetSocketAddress address, byte[] hash) throws IOException {
            checkStop();
            try {
                socket.connect(address, IO_MS); socket.setSoTimeout(IO_MS); socket.setTcpNoDelay(true);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                byte[] flags = new byte[8]; flags[5] = 0x10;
                out.writeByte(PROTOCOL.length); out.write(PROTOCOL); out.write(flags); out.write(hash); out.write(peerId); out.flush();
                if (in.readUnsignedByte() != PROTOCOL.length) throw new IOException("Invalid peer handshake");
                byte[] protocol = new byte[PROTOCOL.length]; in.readFully(protocol);
                if (!Arrays.equals(protocol, PROTOCOL)) throw new IOException("Invalid peer protocol");
                in.readFully(flags); extended = (flags[5] & 0x10) != 0;
                byte[] check = new byte[20]; in.readFully(check);
                if (!MessageDigest.isEqual(hash, check)) throw new IOException("The peer has a different torrent");
                in.readFully(check);
            } catch (IOException failed) { close(); throw failed; }
        }

        byte[] metadata(long until) throws IOException {
            if (!extended) throw new IOException("Peer does not support magnet metadata");
            Map<String,Object> hello = Collections.singletonMap("m", Collections.singletonMap("ut_metadata", 1));
            extension(0, Bencode.encode(hello)); out.flush();
            int remoteId = 0, length = 0;
            while (remoteId == 0) {
                byte[] msg = next(until);
                if (msg.length < 3 || msg[0] != 20 || msg[1] != 0) continue;
                Bencode.Node body = Bencode.prefix(msg, 2);
                Bencode.Node extensions = body.get("m");
                long id = extensions == null ? 0 : TorrentMeta.num(extensions, "ut_metadata", 0);
                long n = TorrentMeta.num(body, "metadata_size", 0);
                if (id <= 0 || id > 255 || n <= 0 || n > TorrentMeta.MAX_META) throw new IOException("Peer does not have supported metadata");
                remoteId = (int) id; length = (int) n;
            }
            byte[] info = new byte[length];
            for (int offset = 0; offset < length; offset += BLOCK) {
                int piece = offset / BLOCK, expected = Math.min(BLOCK, length - offset);
                Map<String,Object> request = new HashMap<>(); request.put("msg_type", 0); request.put("piece", piece);
                extension(remoteId, Bencode.encode(request)); out.flush();
                for (;;) {
                    byte[] reply = next(until);
                    if (reply.length < 3 || reply[0] != 20 || reply[1] != 1) continue;
                    Bencode.Node payload = Bencode.prefix(reply, 2);
                    if (TorrentMeta.num(payload, "piece", -1) != piece) continue;
                    if (TorrentMeta.num(payload, "msg_type", 0) != 1 || reply.length - payload.end != expected) {
                        throw new IOException("Peer rejected or truncated metadata");
                    }
                    System.arraycopy(reply, payload.end, info, offset, expected);
                    break;
                }
            }
            return info;
        }

        byte[] piece(int index, TorrentMeta meta) throws IOException {
            long until = deadline(35);
            if (!interested) { out.writeInt(1); out.writeByte(2); out.flush(); interested = true; }
            while (choked) update(next(until));
            if (advertised && !pieces.get(index)) throw new IOException("Seeder does not have the selected piece");
            int size = (int) Math.min(meta.pieceLength, meta.length - (long) index * meta.pieceLength);
            byte[] data = new byte[size];
            int blocks = (size + BLOCK - 1) / BLOCK, sent = 0, received = 0;
            BitSet got = new BitSet(blocks);
            while (received < blocks) {
                if (choked) throw new IOException("Peer stopped sending pieces");
                while (sent < blocks && sent - received < 8) {
                    int start = sent++ * BLOCK;
                    out.writeInt(13); out.writeByte(6); out.writeInt(index); out.writeInt(start); out.writeInt(Math.min(BLOCK, size - start));
                }
                out.flush();
                byte[] msg = next(until);
                if (msg[0] != 7) { update(msg); continue; }
                if (msg.length < 9) throw new IOException("Invalid block");
                ByteBuffer buf = ByteBuffer.wrap(msg, 1, msg.length - 1);
                int number = buf.getInt(), start = buf.getInt(), block = start / BLOCK;
                if (number != index || start < 0 || start >= size || start % BLOCK != 0 || block >= sent
                        || msg.length - 9 != Math.min(BLOCK, size - start)) throw new IOException("Unexpected torrent block");
                if (!got.get(block)) { got.set(block); received++; System.arraycopy(msg, 9, data, start, msg.length - 9); }
            }
            if (!MessageDigest.isEqual(TorrentMeta.sha1(data), Arrays.copyOfRange(meta.hashes, index * 20, index * 20 + 20))) {
                throw new IOException("A torrent piece failed its integrity check");
            }
            return data;
        }

        private void update(byte[] message) {
            int kind = message[0] & 255;
            if (kind == 0) choked = true;
            if (kind == 1) choked = false;
            if (kind == 4 && message.length == 5) {
                int p = ByteBuffer.wrap(message, 1, 4).getInt();
                if (p >= 0 && p < TorrentMeta.MAX_META / 20) pieces.set(p);
            }
            if (kind == 5) {
                advertised = true;
                for (int i = 1; i < message.length && i <= 16384; i++) {
                    for (int b = 0; b < 8; b++) if ((message[i] & (128 >>> b)) != 0) pieces.set((i - 1) * 8 + b);
                }
            }
        }

        private byte[] next(long until) throws IOException {
            for (;;) {
                check(until);
                int size = in.readInt();
                if (size == 0) continue;
                if (size < 0 || size > 128 * 1024) throw new IOException("Invalid peer frame");
                byte[] msg = new byte[size]; in.readFully(msg); return msg;
            }
        }

        private void extension(int id, byte[] payload) throws IOException {
            out.writeInt(payload.length + 2); out.writeByte(20); out.writeByte(id); out.write(payload);
        }
        @Override public void close() { try { socket.close(); } catch (IOException ignored) { } }
    }

    private static byte[] random(int count) { byte[] data = new byte[count]; RANDOM.nextBytes(data); return data; }
    private static String escaped(byte[] value) {
        String hex = TorrentMeta.hex(value); StringBuilder result = new StringBuilder(value.length * 3);
        for (int i = 0; i < hex.length(); i += 2) result.append('%').append(hex, i, i + 2);
        return result.toString();
    }
    private static InetSocketAddress address(String hostPort) throws IOException {
        URI u = URI.create("tcp://" + hostPort);
        return checked(u.getHost(), u.getPort());
    }
    private static InetSocketAddress checked(String host, int port) throws IOException {
        if (host == null || port <= 0 || port > 65535) throw new IOException("Invalid address");
        for (InetAddress a : InetAddress.getAllByName(host)) if (allowed(a)) return new InetSocketAddress(a, port);
        throw new IOException("Invalid peer address");
    }
    private static boolean allowed(InetAddress ip) { return !ip.isAnyLocalAddress() && !ip.isMulticastAddress(); }
    private static boolean publicAddress(InetAddress a) {
        return allowed(a) && !a.isLinkLocalAddress() && !a.isSiteLocalAddress() && !a.isLoopbackAddress();
    }
    private void checkStop() throws IOException {
        if (stop.stopped() || Thread.currentThread().isInterrupted()) throw new IOException("Torrent operation was cancelled.");
    }
    private void check(long until) throws IOException { checkStop(); if (expired(until)) throw new SocketTimeoutException("The torrent peers didn't respond in time."); }
    private static long deadline(int seconds) { return System.nanoTime() + seconds * 1000000000L; }
    private static boolean expired(long until) { return System.nanoTime() - until >= 0; }
}
