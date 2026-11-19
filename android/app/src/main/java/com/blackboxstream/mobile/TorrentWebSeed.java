package com.blackboxstream.mobile;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;

/** BEP 19 HTTPS byte ranges, verified against the same v1 hashes as TCP peer pieces. */
final class TorrentWebSeed {
    private TorrentWebSeed() { }

    static byte[] descriptor(String href) throws IOException {
        return request(href, null, (response, status) -> {
            if (status != 200 || response.getContentLengthLong() > TorrentMeta.MAX_META) throw new IOException("Couldn't fetch the exact torrent descriptor (HTTP " + status + ").");
            return TorrentMeta.slurp(response.getInputStream(), TorrentMeta.MAX_META);
        });
    }

    static String itemUrl(TorrentMeta meta, TorrentMeta.Item item, String seed) throws IOException {
        URI uri = URI.create(seed);
        boolean directory = meta.multiFile || uri.getRawPath().endsWith("/");
        if (!directory) return seed;
        if (uri.getRawQuery() != null || item.seedPath == null) throw new IOException("The web seed has no usable directory path for this file.");
        return seed + (seed.endsWith("/") ? "" : "/") + item.seedPath;
    }

    static byte[] piece(TorrentMeta meta, String seed, int index, TorrentNetwork.Stop stop) throws IOException {
        long begin = (long) index * meta.pieceLength;
        if (index < 0 || begin >= meta.length) throw new IOException("Invalid torrent piece index.");
        byte[] data = new byte[(int) Math.min(meta.pieceLength, meta.length - begin)];
        int filled = 0;
        for (int i = meta.fileAt(begin); filled < data.length && i < meta.items.size(); i++) {
            if (stop.stopped() || Thread.currentThread().isInterrupted()) throw new IOException("Torrent cancelled.");
            TorrentMeta.Item f = meta.items.get(i);
            long offset = begin + filled - f.offset;
            int count = (int) Math.min(data.length - filled, f.length - offset);
            if (count <= 0) continue;
            // Padding files are defined as zeroes; real neighbouring files are fetched at the boundary.
            if (!f.padded) range(itemUrl(meta, f, seed), f.length, offset, count, data, filled);
            filled += count;
        }
        if (filled != data.length || !meta.checksPiece(index, data)) throw new IOException("Web seed bytes don't match this torrent's piece hash.");
        return data;
    }

    private static void range(String url, long length, long start, int count, byte[] data, int at) throws IOException {
        long last = start + count - 1;
        request(url, "bytes=" + start + "-" + last, (response, status) -> {
            boolean expected = status == 206 && ("bytes " + start + "-" + last + "/" + length).equals(response.getHeaderField("Content-Range"));
            if (!expected && !(status == 200 && start == 0 && count == length)) throw new IOException("Web seed didn't accept the exact byte range (HTTP " + status + ").");
            long size = response.getContentLengthLong();
            if (size >= 0 && size != count) throw new IOException("Web seed returned the wrong byte count.");
            try (InputStream in = response.getInputStream()) {
                for (int pos = at, end = at + count; pos < end; ) {
                    int n = in.read(data, pos, end - pos);
                    if (n == -1) throw new EOFException("Web seed range was incomplete.");
                    pos += n;
                }
                if (size < 0 && in.read() != -1) throw new IOException("Web seed range was too large.");
            }
            return null;
        });
    }

    private interface Read<T> { T handle(HttpsURLConnection response, int status) throws IOException; }

    private static <T> T request(String address, String range, Read<T> read) throws IOException {
        try {
            String target = MediaSources.https(address);
            for (int hop = 0; hop <= 3; hop++) {
                URI uri = URI.create(target);
                HttpsURLConnection response = (HttpsURLConnection) uri.toURL().openConnection();
                response.setConnectTimeout(4500); response.setReadTimeout(7000);
                response.setInstanceFollowRedirects(false);
                response.setRequestProperty("Accept-Encoding", "identity");
                response.setRequestProperty("User-Agent", "BlackBoxStream/1.2");
                if (range != null) response.setRequestProperty("Range", range);
                try {
                    int status = response.getResponseCode();
                    String location = response.getHeaderField("Location");
                    if (location != null && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                        target = MediaSources.https(uri.resolve(location).toString());
                        continue;
                    }
                    String encoding = response.getContentEncoding();
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Can't verify encoded web seed bytes.");
                    return read.handle(response, status);
                } finally { response.disconnect(); }
            }
            throw new IOException("Too many redirects from the web seed.");
        } catch (IllegalArgumentException invalid) { throw new IOException("This torrent requires a valid HTTPS web seed URL.", invalid); }
    }
}
