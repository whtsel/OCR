package com.blackboxstream.mobile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLContext;

/** Expiring, authenticated LAN media. Invitation tokens are sent only after TLS pin verification. */
final class LanShare {
    static final int MAX_ITEMS = 16, COPY_CHUNK = 256 * 1024;
    private static final int MAGIC = 0x42425831, CATALOG = 1, READ = 2;
    private static final String INVITE = "BBX-LAN-1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Invite {
        final InetAddress host;
        final int port;
        final byte[] pin, key;
        Invite(InetAddress host, int port, byte[] pin, byte[] key) { this.host = host; this.port = port; this.pin = pin; this.key = key; }
        String encode() { return INVITE + host.getHostAddress() + ":" + port + ":" + TorrentMeta.hex(pin) + ":" + TorrentMeta.hex(key); }
    }

    static Invite parse(String input, boolean allowLoopback) throws IOException {
        String text = input == null ? "" : input.trim();
        if (text.length() > 180 || !text.startsWith(INVITE)) throw new IOException("Paste a BlackBox Local Share invitation.");
        String[] fields = text.substring(INVITE.length()).split(":", -1);
        if (fields.length != 4 || !fields[0].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}") || !fields[1].matches("[0-9]{1,5}")) throw invalid();
        byte[] ip = new byte[4];
        String[] digits = fields[0].split("\\.");
        for (int i = 0; i < ip.length; i++) {
            int digit = Integer.parseInt(digits[i]);
            if (digit > 255) throw invalid();
            ip[i] = (byte) digit;
        }
        InetAddress target = InetAddress.getByAddress(ip);
        int port = Integer.parseInt(fields[1]);
        if (!privateV4(target, allowLoopback) || port < 1024 || port > 65535) throw invalid();
        return new Invite(target, port, unhex(fields[2], 32), unhex(fields[3], 24));
    }

    static boolean privateV4(InetAddress a, boolean allowLoopback) {
        return a != null && a.getAddress().length == 4 && (a.isSiteLocalAddress() || a.isLinkLocalAddress() || allowLoopback && a.isLoopbackAddress());
    }

    static boolean sameNetwork(InetAddress a, InetAddress b, int prefix) {
        if (a == null || b == null || a.getAddress().length != 4 || b.getAddress().length != 4 || prefix < 1 || prefix > 32) return false;
        byte[] x = a.getAddress(), y = b.getAddress();
        for (int i = 0; i < 4 && prefix > 0; i++, prefix -= 8) {
            if (((x[i] ^ y[i]) & (0xff << Math.max(0, 8 - prefix)) & 255) != 0) return false;
        }
        return true;
    }

    static final class Item {
        final String id, title;
        final MediaEntry.Kind kind;
        final long length;
        final File file;
        Item(String id, String title, MediaEntry.Kind kind, long length, File file) {
            this.id = id; this.title = MediaEntry.cleanTitle(title); this.kind = kind; this.length = length; this.file = file;
        }
        static Item file(String title, MediaEntry.Kind kind, File path) { return new Item(TorrentMeta.hex(random(12)), title, kind, path.length(), path); }
    }

    static final class Server implements Closeable {
        private final SSLServerSocket listener;
        private final Thread accept;
        private final List<Item> items;
        private final Set<Socket> active = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
        final Invite invite;
        private final int prefix;
        private final boolean allowLoopback;
        private final long expires;
        private volatile boolean closed;

        Server(InetAddress bind, int prefix, List<Item> items, long ttlMillis, boolean allowLoopback) throws IOException {
            if (!privateV4(bind, allowLoopback) || prefix < 1 || prefix > 32 || items.isEmpty() || items.size() > MAX_ITEMS) throw invalid();
            this.prefix = prefix; this.allowLoopback = allowLoopback; this.items = new ArrayList<>(items);
            for (Item item : items) if (item.file == null || !item.file.isFile() || item.length <= 0 || item.length > OfflineTransfer.LIMIT_BYTES) throw invalid();
            try {
                LanIdentity identity = LanIdentity.create();
                listener = (SSLServerSocket) identity.server.getServerSocketFactory().createServerSocket();
                tls(listener);
                try { listener.bind(new InetSocketAddress(bind, 0)); listener.setSoTimeout(1000); }
                catch (IOException err) { listener.close(); throw err; }
                invite = new Invite(bind, listener.getLocalPort(), identity.pin, random(24));
            } catch (GeneralSecurityException error) { throw new IOException("Could not start encrypted Local Share.", error); }
            expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis);
            accept = new Thread(this::acceptLoop, "BlackBox-local-share");
            accept.setDaemon(true); accept.start();
        }

        private void acceptLoop() {
            try {
                while (!expired()) {
                    try {
                        SSLSocket sock = (SSLSocket) listener.accept();
                        if (!privateV4(sock.getInetAddress(), allowLoopback) || !sameNetwork(invite.host, sock.getInetAddress(), prefix)) {
                            sock.close(); continue;
                        }
                        active.add(sock);
                        try { workers.execute(() -> handle(sock)); }
                        catch (RejectedExecutionException full) { active.remove(sock); sock.close(); }
                    } catch (java.net.SocketTimeoutException waiting) { }
                }
            } catch (IOException stopped) { }
            finally { close(); }
        }

        private void handle(SSLSocket socket) {
            try (SSLSocket transfer = socket) {
                transfer.setSoTimeout(12000);
                transfer.startHandshake();
                DataInputStream in = new DataInputStream(new BufferedInputStream(transfer.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(transfer.getOutputStream()));
                if (in.readInt() != MAGIC) return;
                byte[] response = new byte[invite.key.length]; in.readFully(response);
                if (!MessageDigest.isEqual(invite.key, response) || expired()) { out.writeByte(0); out.flush(); return; }
                out.writeByte(1); out.flush();
                while (!expired()) {
                    int request = in.read();
                    if (request == -1) return;
                    if (request == CATALOG) {
                        List<Item> current = new ArrayList<>();
                        for (Item item : items) if (item.file.isFile() && item.file.length() == item.length) current.add(item);
                        out.writeInt(current.size());
                        for (Item item : current) {
                            out.writeUTF(item.id); out.writeUTF(item.title); out.writeByte(item.kind.ordinal()); out.writeLong(item.length);
                        }
                        out.flush();
                    } else if (request == READ) {
                        String id = in.readUTF(); long offset = in.readLong(); int want = in.readInt();
                        Item item = null;
                        for (Item allowed : items) if (allowed.id.equals(id)) item = allowed;
                        if (item == null || offset < 0 || offset >= item.length || want < 1 || want > COPY_CHUNK
                                || !item.file.isFile() || item.file.length() != item.length) {
                            out.writeInt(-1); out.flush(); continue;
                        }
                        int n = (int) Math.min(want, item.length - offset);
                        byte[] bytes = new byte[n];
                        try (RandomAccessFile input = new RandomAccessFile(item.file, "r")) {
                            input.seek(offset); input.readFully(bytes);
                        } catch (IOException missing) { out.writeInt(-1); out.flush(); continue; }
                        out.writeInt(n); out.write(bytes); out.flush();
                    } else return;
                }
            } catch (IOException disconnected) { }
            finally { active.remove(socket); }
        }

        boolean expired() { return closed || System.nanoTime() - expires >= 0; }
        @Override public void close() {
            closed = true;
            try { listener.close(); } catch (IOException ignored) { }
            for (Socket sock : active) { try { sock.close(); } catch (IOException ignored) { } }
            workers.shutdownNow();
        }
    }

    interface SocketRoute { void bind(Socket socket) throws IOException; }

    static final class Peer implements Closeable {
        private final SSLSocket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        Peer(Invite invite) throws IOException { this(invite, null); }
        Peer(Invite invite, SocketRoute route) throws IOException {
            try {
                SSLContext ssl = LanIdentity.pinned(invite.pin);
                socket = (SSLSocket) ssl.getSocketFactory().createSocket();
            } catch (GeneralSecurityException err) { throw new IOException("Could not verify the sender's identity.", err); }
            try {
                tls(socket);
                if (route != null) route.bind(socket);
                socket.connect(new InetSocketAddress(invite.host, invite.port), 4000);
                socket.setSoTimeout(12000);
                socket.startHandshake();
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                out.writeInt(MAGIC); out.write(invite.key); out.flush();
                if (in.readUnsignedByte() != 1) throw new IOException("Sender rejected the invitation. Get a fresh one.");
            } catch (IOException error) { close(); throw error; }
        }

        synchronized List<Item> items() throws IOException {
            out.writeByte(CATALOG); out.flush();
            int count = in.readInt();
            if (count < 0 || count > MAX_ITEMS) throw invalid();
            List<Item> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(), title = in.readUTF();
                int kind = in.readUnsignedByte(); long bytes = in.readLong();
                if (!id.matches("[0-9a-f]{24}") || title.length() > 512 || kind >= MediaEntry.Kind.values().length || bytes <= 0 || bytes > OfflineTransfer.LIMIT_BYTES) throw invalid();
                list.add(new Item(id, title, MediaEntry.Kind.values()[kind], bytes, null));
            }
            return list;
        }

        synchronized int read(Item item, long offset, byte[] dest, int start, int count) throws IOException {
            if (offset < 0) throw invalid();
            if (count == 0) return 0;
            if (offset >= item.length) return -1;
            int limit = (int) Math.min(Math.min(count, COPY_CHUNK), item.length - offset);
            out.writeByte(READ); out.writeUTF(item.id); out.writeLong(offset); out.writeInt(limit); out.flush();
            int n = in.readInt();
            if (n != limit) throw new EOFException("The sender's selected copy is no longer available.");
            in.readFully(dest, start, n); return n;
        }

        @Override public void close() { try { socket.close(); } catch (IOException ignored) { } }
    }

    static MediaBytes open(Invite invite, Item item, SocketRoute route) throws IOException {
        Peer peer = new Peer(invite, route);
        return new MediaBytes() {
            @Override public long size() { return item.length; }
            @Override public synchronized int read(long offset, byte[] data, int start, int count) throws IOException {
                return peer.read(item, offset, data, start, count);
            }
            @Override public void close() { peer.close(); }
        };
    }

    static void copy(Invite invite, Item item, File file, OfflineTransfer.Monitor monitor, SocketRoute route) throws IOException {
        monitor.total(item.length);
        try (Peer peer = new Peer(invite, route); FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[COPY_CHUNK]; long at = 0;
            while (at < item.length) {
                if (monitor.stopped()) throw new IOException("Local Share copy stopped.");
                int n = peer.read(item, at, buffer, 0, buffer.length);
                out.write(buffer, 0, n); at += n;
            }
            out.getFD().sync();
        }
    }

    private static byte[] random(int length) { byte[] out = new byte[length]; RANDOM.nextBytes(out); return out; }
    private static byte[] unhex(String hex, int bytes) throws IOException {
        if (hex.length() != bytes * 2 || !hex.toLowerCase(Locale.ROOT).matches("[0-9a-f]+")) throw invalid();
        byte[] value = new byte[bytes];
        for (int i = 0; i < bytes; i++) value[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return value;
    }
    private static void tls(SSLSocket socket) { socket.setEnabledProtocols(protocols(socket.getSupportedProtocols())); }
    private static void tls(SSLServerSocket socket) { socket.setEnabledProtocols(protocols(socket.getSupportedProtocols())); }
    private static String[] protocols(String[] supported) {
        return Arrays.asList(supported).contains("TLSv1.3") ? new String[] {"TLSv1.3", "TLSv1.2"} : new String[] {"TLSv1.2"};
    }
    private static IOException invalid() { return new IOException("Invalid Local Share invitation or response."); }
}
