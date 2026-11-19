package com.blackboxstream.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TorrentMeta {
    static final int MAX_META = 2 * 1024 * 1024;
    static final int MAX_TRACKERS = 12;
    private static final int MAX_WEB_SEEDS = 4, MAX_EXACT = 2;
    static final long MAX_SWARM_BYTES = 256L << 30;
    final byte[] info, hash, hashes;
    final int pieceLength;
    final long length;
    final String name;
    final List<Item> items;
    final List<String> trackers, peers, webSeeds;
    final boolean privateSwarm, multiFile;

    static final class Item {
        final int index;
        final String name, seedPath;
        final long offset, length;
        final boolean padded;
        Item(int index, String name, long offset, long length, boolean padded, String seedPath) {
            this.index = index; this.name = name; this.offset = offset; this.length = length; this.padded = padded; this.seedPath = seedPath;
        }
        boolean playable() {
            return !padded && length > 0 && length <= OfflineTransfer.LIMIT_BYTES
                    && name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|webm|3gp|mov|mp3|m4a|aac|flac|ogg|opus|wav|avi|mpeg|mpg|ts)$");
        }
        MediaEntry.Kind kind() { return MediaSources.infer(null, "https://media.example/file" + extension()); }
        String extension() { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot); }
    }

    static final class Magnet {
        final byte[] hash;
        final List<String> trackers, peers, webSeeds, exact;
        Magnet(byte[] hash, List<String> trackers, List<String> peers, List<String> webSeeds, List<String> exact) {
            this.hash = hash; this.trackers = trackers; this.peers = peers; this.webSeeds = webSeeds; this.exact = exact;
        }
    }

    private TorrentMeta(byte[] info, Bencode.Node data, List<String> trackers, List<String> peers, List<String> webSeeds) throws IOException {
        this.info = info;
        hash = sha1(info);
        this.trackers = Collections.unmodifiableList(new ArrayList<>(trackers));
        this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
        this.webSeeds = Collections.unmodifiableList(new ArrayList<>(webSeeds));
        hashes = bytes(data, "pieces");
        if (hashes == null || hashes.length == 0 || hashes.length % 20 != 0) {
            throw new IOException("This file has no v1 pieces. Choose a BitTorrent v1 or hybrid torrent.");
        }
        long piece = num(data, "piece length", 0);
        if (piece <= 0 || piece > 16 * 1024 * 1024) throw new IOException("Unsupported torrent piece size.");
        pieceLength = (int) piece;
        String rawName = prefer(data, "name.utf-8", "name");
        name = MediaEntry.cleanTitle(rawName);
        String rootPath = encodedSegment(rawName);
        privateSwarm = num(data, "private", 0) == 1;
        List<Item> parsed = new ArrayList<>();
        long end = 0;
        Bencode.Node files = data.get("files");
        multiFile = files != null;
        if (!multiFile) {
            end = validLength(num(data, "length", -1));
            parsed.add(new Item(0, name, 0, end, false, rootPath));
        } else {
            if (files.list == null || files.list.isEmpty() || files.list.size() > 4096) throw invalid();
            for (Bencode.Node f : files.list) {
                Bencode.Node path = f.get("path.utf-8");
                if (path == null) path = f.get("path");
                if (path == null || path.list == null || path.list.isEmpty() || path.list.size() > 32) throw invalid();
                String file = path.list.get(path.list.size() - 1).text();
                long size = validLength(num(f, "length", -1));
                boolean pad = text(f, "attr").contains("p") || path.list.get(0).text().equals(".pad");
                String fullPath = rootPath;
                for (Bencode.Node component : path.list) {
                    String part = encodedSegment(component.text());
                    if (fullPath == null || part == null || fullPath.length() + part.length() > 3000) { fullPath = null; break; }
                    fullPath += "/" + part;
                }
                parsed.add(new Item(parsed.size(), MediaEntry.cleanTitle(file), end, size, pad, fullPath));
                end = validLength(end + size);
            }
        }
        if (end <= 0 || (end - 1) / pieceLength + 1 != hashes.length / 20) throw invalid();
        length = end;
        items = Collections.unmodifiableList(parsed);
    }

    static TorrentMeta read(byte[] raw) throws IOException {
        if (raw.length > MAX_META) throw invalid();
        Bencode.Node root = Bencode.parse(raw), node = root.get("info");
        if (node == null || node.map == null) throw invalid();
        Set<String> urls = new LinkedHashSet<>();
        addTracker(urls, text(root, "announce"));
        Bencode.Node groups = root.get("announce-list");
        if (groups != null && groups.list != null) for (Bencode.Node group : groups.list) {
            if (group.list != null) for (Bencode.Node candidate : group.list) addTracker(urls, candidate.text());
        }
        List<String> peers = new ArrayList<>();
        Bencode.Node hints = root.get("bbx-peers");
        if (hints != null && hints.list != null) for (Bencode.Node item : hints.list) {
            if (peers.size() < MAX_TRACKERS && validPeer(item.text())) peers.add(item.text());
        }
        Set<String> sources = new LinkedHashSet<>();
        Bencode.Node ws = root.get("url-list");
        if (ws != null) {
            if (ws.list == null) addHttps(sources, ws.text(), MAX_WEB_SEEDS);
            else for (Bencode.Node candidate : ws.list) addHttps(sources, candidate.text(), MAX_WEB_SEEDS);
        }
        return new TorrentMeta(Arrays.copyOfRange(raw, node.start, node.end), node, new ArrayList<>(urls), peers, new ArrayList<>(sources));
    }

    static TorrentMeta fromMagnet(Magnet magnet, byte[] exactInfo) throws IOException {
        if (exactInfo.length > MAX_META || !MessageDigest.isEqual(magnet.hash, sha1(exactInfo))) {
            throw new IOException("Metadata did not match the magnet's info hash.");
        }
        return new TorrentMeta(exactInfo, Bencode.parse(exactInfo), magnet.trackers, magnet.peers, magnet.webSeeds);
    }

    TorrentMeta withMagnet(Magnet magnet) throws IOException {
        if (!MessageDigest.isEqual(hash, magnet.hash)) throw new IOException("The descriptor did not match the magnet's info hash.");
        return new TorrentMeta(info, Bencode.parse(info), merge(magnet.trackers, trackers, MAX_TRACKERS),
                merge(magnet.peers, peers, MAX_TRACKERS), merge(magnet.webSeeds, webSeeds, MAX_WEB_SEEDS));
    }

    private static List<String> merge(List<String> first, List<String> rest, int max) {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(rest);
        List<String> list = new ArrayList<>(all);
        return list.subList(0, Math.min(max, list.size()));
    }

    int fileAt(long position) {
        int lo = 0, hi = items.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            Item f = items.get(mid);
            if (f.offset + f.length <= position) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    boolean checksPiece(int piece, byte[] data) {
        int at = piece * 20;
        return MessageDigest.isEqual(sha1(data), Arrays.copyOfRange(hashes, at, at + 20));
    }

    byte[] packed() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("info", new Bencode.Raw(info));
        if (!trackers.isEmpty()) {
            root.put("announce", trackers.get(0));
            List<List<String>> groups = new ArrayList<>();
            for (String tracker : trackers) groups.add(Collections.singletonList(tracker));
            root.put("announce-list", groups);
        }
        if (!peers.isEmpty()) root.put("bbx-peers", peers);
        if (!webSeeds.isEmpty()) root.put("url-list", webSeeds);
        return Bencode.encode(root);
    }

    static Magnet magnet(String input) throws IOException {
        if (input == null || input.length() > 12000 || !input.regionMatches(true, 0, "magnet:?", 0, 8)) {
            throw new IOException("Paste a BitTorrent magnet link.");
        }
        Set<String> trackers = new LinkedHashSet<>(), peers = new LinkedHashSet<>();
        Set<String> webSeeds = new LinkedHashSet<>(), exact = new LinkedHashSet<>();
        byte[] hash = null;
        for (String field : input.substring(8).split("&")) {
            int split = field.indexOf('=');
            if (split < 0) continue;
            String value;
            try { value = URLDecoder.decode(field.substring(split + 1), "UTF-8"); }
            catch (IllegalArgumentException malformed) { throw invalid(); }
            String key = field.substring(0, split);
            if (key.equals("xt") && value.regionMatches(true, 0, "urn:btih:", 0, 9)) {
                byte[] candidate = hash(value.substring(9));
                if (hash != null && !Arrays.equals(hash, candidate)) throw invalid();
                hash = candidate;
            } else if (key.equals("tr")) addTracker(trackers, value);
            else if (key.equals("ws")) addHttps(webSeeds, value, MAX_WEB_SEEDS);
            else if (key.equals("xs")) addHttps(exact, value, MAX_EXACT);
            else if (key.equals("x.pe") && peers.size() < MAX_TRACKERS && validPeer(value)) peers.add(value);
        }
        if (hash == null) throw new IOException("Use a v1 or hybrid magnet with a btih info hash.");
        return new Magnet(hash, new ArrayList<>(trackers), new ArrayList<>(peers), new ArrayList<>(webSeeds), new ArrayList<>(exact));
    }

    private static byte[] hash(String text) throws IOException {
        byte[] result = new byte[20];
        if (text.matches("[0-9a-fA-F]{40}")) {
            for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        } else if (text.matches("[a-zA-Z2-7]{32}")) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            int value = 0, bits = 0, pos = 0;
            for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
                value = (value << 5) | alphabet.indexOf(c); bits += 5;
                if (bits >= 8) { bits -= 8; result[pos++] = (byte) (value >>> bits); }
            }
        } else throw invalid();
        return result;
    }

    private static void addHttps(Set<String> urls, String text, int limit) {
        if (urls.size() >= limit || text.isEmpty() || text.length() > 2048) return;
        try { urls.add(MediaSources.https(text)); } catch (IllegalArgumentException ignored) { }
    }

    private static String encodedSegment(String value) {
        if (value.isEmpty() || value.equals(".") || value.equals("..") || value.length() > 255
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) return null;
        StringBuilder segment = new StringBuilder();
        String hex = "0123456789ABCDEF";
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c < 32 || c == 127) return null;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "-_.~".indexOf(c) >= 0) segment.append((char) c);
            else segment.append('%').append(hex.charAt(c >>> 4)).append(hex.charAt(c & 15));
        }
        return segment.toString();
    }

    private static void addTracker(Set<String> urls, String text) {
        if (text.isEmpty() || text.length() > 2048 || urls.size() >= MAX_TRACKERS) return;
        try {
            URI u = new URI(text);
            String scheme = u.getScheme();
            if (u.getHost() != null && u.getUserInfo() == null && u.getFragment() == null && u.getPort() <= 65535 && u.getPort() != 0
                    && ("http".equals(scheme) || "https".equals(scheme) || ("udp".equals(scheme) && u.getPort() > 0))) urls.add(u.toASCIIString());
        } catch (Exception ignored) { }
    }

    private static boolean validPeer(String text) {
        try {
            URI peer = new URI("tcp://" + text);
            return text.length() <= 260 && peer.getHost() != null && peer.getRawPath().isEmpty()
                    && peer.getPort() > 0 && peer.getPort() <= 65535 && peer.getRawUserInfo() == null
                    && peer.getRawQuery() == null && peer.getRawFragment() == null;
        } catch (Exception ignored) { return false; }
    }

    static byte[] slurp(InputStream input, int cap) throws IOException {
        if (input == null) throw new IOException("Could not open the source.");
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (out.size() + n > cap) throw new IOException("The source exceeds the allowed size.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    static String hex(byte[] value) {
        char[] chars = "0123456789abcdef".toCharArray(), out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) { out[i * 2] = chars[(value[i] & 255) >>> 4]; out[i * 2 + 1] = chars[value[i] & 15]; }
        return new String(out);
    }
    static byte[] sha1(byte[] value) {
        try { return MessageDigest.getInstance("SHA-1").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static byte[] bytes(Bencode.Node n, String key) { Bencode.Node field = n.get(key); return field == null ? null : field.bytes; }
    static long num(Bencode.Node n, String key, long fallback) { Bencode.Node field = n.get(key); return field == null ? fallback : field.value(fallback); }
    static String text(Bencode.Node n, String key) { Bencode.Node field = n.get(key); return field == null ? "" : field.text(); }
    private static String prefer(Bencode.Node n, String first, String second) { String result = text(n, first); return result.isEmpty() ? text(n, second) : result; }
    private static long validLength(long value) throws IOException { if (value < 0 || value > MAX_SWARM_BYTES) throw invalid(); return value; }
    private static IOException invalid() { return new IOException("Invalid, too large, or unsupported torrent metadata."); }
}
