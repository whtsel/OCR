package com.blackboxstream.mobile;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only view of one exact AAC transfer. Partial bytes never enter the offline cache. */
final class ProgressiveMusicAudio implements Closeable {
    static final String PROGRESS_TEMPLATE = "download:BBX_MEDIA:%(progress.status)s\n"
            + "BBX_PREFIX:%(progress.status)s:%(info.id)s:%(progress.total_bytes)s:%(info.ext)s:"
            + "%(info.protocol)s:%(info.vcodec)s:%(info.acodec)s:%(info.has_drm)s:%(info.filesize)s";
    private static final Pattern PREFIX = Pattern.compile(
            "BBX_PREFIX:downloading:([A-Za-z0-9_-]{11}):([1-9][0-9]{0,7}):m4a:https:none:mp4a\\.40\\.[0-9]{1,2}:False:(NA|[1-9][0-9]{0,7})");
    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]{0,9})-([0-9]{0,9})");
    private static final int MIN_PAYLOAD = 8 * 1024;
    private static final int PREFIX_LIMIT = 256 * 1024;
    private static final long READ_TIMEOUT_NS = 12_000_000_000L;
    private static final int MAX_READERS = 4;

    static final class Candidate {
        final String id;
        final long length;
        Candidate(String id, long length) { this.id = id; this.length = length; }
    }

    // Direct, known-size AAC only. Nothing from a fragmented downloader, a transcoder, or a DRM format.
    static Candidate candidate(String marker) {
        if (marker.length() > 160) return null;
        Matcher match = PREFIX.matcher(marker);
        if (!match.matches()) return null;
        long length = Long.parseLong(match.group(2));
        if (length < MIN_PAYLOAD || length > PreparedMusic.MAX_AUDIO
                || !"NA".equals(match.group(3)) && length != Long.parseLong(match.group(3))) return null;
        return new Candidate(match.group(1), length);
    }

    static final class Read {
        final int status;
        final long length;
        final String contentRange;
        final InputStream body;
        Read(int status, long length, String contentRange, InputStream body) {
            this.status = status; this.length = length; this.contentRange = contentRange; this.body = body;
        }
    }

    final String token = UUID.randomUUID().toString().replace("-", "");
    private final FileChannel bytes;
    private final long length;
    private volatile boolean closed;
    private int readers;

    private ProgressiveMusicAudio(FileChannel bytes, long length) {
        this.bytes = bytes; this.length = length;
    }

    static ProgressiveMusicAudio openPartial(File part, long length) {
        if (length <= MIN_PAYLOAD || length > PreparedMusic.MAX_AUDIO || !part.isFile()) return null;
        FileChannel source = null;
        try {
            source = new FileInputStream(part).getChannel();
            long available = source.size();
            if (available >= length || !hasFastStart(source, available, length)) return null;
            // Keep the original inode even when the helper renames, remuxes or removes the part.
            ProgressiveMusicAudio audio = new ProgressiveMusicAudio(source, length);
            source = null;
            return audio;
        } catch (IOException unavailable) { return null; }
        finally { if (source != null) try { source.close(); } catch (IOException ignored) { } }
    }

    private static boolean hasFastStart(FileChannel bytes, long available, long total) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8);
        boolean metadata = false;
        long end = Math.min(available, PREFIX_LIMIT);
        for (long pos = 0, boxes = 0; pos + 8 <= end && boxes < 64; boxes++) {
            header.clear();
            if (bytes.read(header, pos) != 8) return false;
            long size = header.getInt(0) & 0xffffffffL;
            int kind = header.getInt(4);
            if (pos == 0 && kind != 0x66747970 /* ftyp */ || size < 8 || size > total - pos) return false;
            if (kind == 0x6d646174 /* mdat */) return metadata && available - pos - 8 >= Math.min(MIN_PAYLOAD, size - 8);
            if (pos + size > end) return false;
            if (kind == 0x6d6f6f76 /* moov */) metadata = true;
            pos += size;
        }
        return false;
    }

    boolean hasAllBytes() {
        try { return !closed && bytes.size() == length; }
        catch (IOException stopped) { return false; }
    }

    synchronized Read read(String header) {
        if (closed || readers >= MAX_READERS) return null;
        long first = 0, last = length - 1;
        String span = null;
        if (header != null) {
            Matcher range = RANGE.matcher(header);
            if (!range.matches() || range.group(1).isEmpty() && range.group(2).isEmpty()) return unsatisfiable();
            if (range.group(1).isEmpty()) {
                long suffix = Long.parseLong(range.group(2));
                if (suffix == 0) return unsatisfiable();
                first = Math.max(0, length - suffix);
            } else {
                first = Long.parseLong(range.group(1));
                if (!range.group(2).isEmpty()) last = Math.min(last, Long.parseLong(range.group(2)));
            }
            if (first > last) return unsatisfiable();
            span = "bytes " + first + "-" + last + "/" + length;
        }
        readers++;
        return new Read(span == null ? 200 : 206, last - first + 1, span, new Window(first, last + 1));
    }

    private Read unsatisfiable() {
        return new Read(416, 0, "bytes */" + length, new ByteArrayInputStream(new byte[0]));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { bytes.close(); } catch (IOException ignored) { }
        notifyAll();
    }

    private final class Window extends InputStream {
        private final long end;
        private long pos;
        private volatile boolean released;
        Window(long pos, long end) { this.pos = pos; this.end = end; }

        @Override public int available() throws IOException {
            if (closed || released) return 0;
            return (int) Math.max(0, Math.min(end, bytes.size()) - pos);
        }

        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
        }

        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (offset < 0 || count < 0 || offset > buffer.length - count) throw new IndexOutOfBoundsException();
            if (count == 0) return 0;
            if (pos >= end) return -1;
            long started = System.nanoTime();
            while (!closed && !released) {
                long ready = bytes.size();
                if (ready > length) break;
                int n = (int) Math.min(count, Math.min(ready, end) - pos);
                if (n > 0) {
                    int read = bytes.read(ByteBuffer.wrap(buffer, offset, n), pos);
                    if (read > 0) { pos += read; return read; }
                }
                if (System.nanoTime() - started >= READ_TIMEOUT_NS) break;
                // Waiting for a not-yet-received seek position must not hold the engine monitor.
                synchronized (ProgressiveMusicAudio.this) {
                    if (closed || released) break;
                    try { ProgressiveMusicAudio.this.wait(75); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            throw new IOException("The exact audio buffer is no longer available.");
        }

        @Override public void close() {
            synchronized (ProgressiveMusicAudio.this) {
                if (released) return;
                released = true;
                readers--;
                ProgressiveMusicAudio.this.notifyAll();
            }
        }
    }
}
