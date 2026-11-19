package com.blackboxstream.mobile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/** HTTPS file transport, kept independent of Android so recovery can be exercised on a host. */
final class OfflineTransfer {
    static final long LIMIT_BYTES = 4L * 1024 * 1024 * 1024;
    static final long RESERVE_BYTES = 4L * 1024 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    interface Monitor {
        boolean stopped();
        void total(long bytes) throws IOException;
    }

    static final class Rejected extends IOException {
        private static final long serialVersionUID = 1L;
        Rejected(String message) { super(message); }
    }

    private OfflineTransfer() {}

    static void download(String link, File part, File tag, Monitor monitor) throws IOException {
        if (MediaSources.hls(link) || MediaSources.playlist(link)) {
            throw new Rejected("Only direct media files can be downloaded, not streaming playlists.");
        }
        String target = MediaSources.https(link);
        Validator previous = readValidator(tag);
        long partial = previous == null ? 0 : part.length();
        int redirects = 0;
        for (;;) {
            checkStopped(monitor);
            URI uri = URI.create(target);
            HttpsURLConnection request = (HttpsURLConnection) uri.toURL().openConnection();
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(10000);
            request.setReadTimeout(15000);
            request.setRequestProperty("Accept-Encoding", "identity");
            request.setRequestProperty("User-Agent", MediaSources.USER_AGENT);
            boolean candidate = partial > 0 && target.equals(previous.url);
            if (candidate) {
                request.setRequestProperty("Range", "bytes=" + partial + "-");
                request.setRequestProperty("If-Range", previous.etag);
            }
            try {
                int code = request.getResponseCode();
                if (code >= 300 && code < 400 && request.getHeaderField("Location") != null) {
                    if (++redirects > 4) throw new Rejected("The file server redirected too many times.");
                    try { target = MediaSources.https(uri.resolve(request.getHeaderField("Location")).toString()); }
                    catch (IllegalArgumentException bad) { throw new Rejected("The download redirected to an invalid or non-HTTPS address."); }
                    continue;
                }
                if (code == 416 && candidate) {
                    // A stale or already-complete partial must not be promoted on a 416 response.
                    partial = 0;
                    continue;
                }
                if (code >= 500 || code == 429 || code == 408) throw new IOException("Temporary HTTP " + code);
                if (code != 200 && code != 206) throw new Rejected("The file server returned HTTP " + code + ".");
                String contentType = request.getContentType();
                if (contentType != null) {
                    String type = contentType.toLowerCase(Locale.ROOT);
                    if (type.contains("mpegurl") || type.contains("dash+xml")) {
                        throw new Rejected("Only direct media files can be kept offline; this is a streaming playlist.");
                    }
                    if (type.contains("text/html") || type.contains("application/json")) {
                        throw new Rejected("This link returned a page, not a direct media file.");
                    }
                }
                String encoding = request.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new Rejected("The server used an unsupported file encoding.");
                }
                String etag = request.getHeaderField("ETag");
                long start = 0;
                long total = request.getContentLengthLong();
                long expectedEnd = total;
                if (code == 206) {
                    long[] range = contentRange(request.getHeaderField("Content-Range"));
                    if (!candidate || !previous.etag.equals(etag) || range == null || range[0] != partial
                            || (total >= 0 && total != range[1] + 1 - partial)) {
                        if (!candidate) throw new Rejected("The server sent an incomplete file without a matching request.");
                        partial = 0;
                        continue;
                    }
                    start = partial;
                    total = range[2];
                    expectedEnd = range[1] + 1;
                }
                if (total > LIMIT_BYTES) throw new Rejected("This file exceeds the 4 GB offline limit.");
                monitor.total(total < 0 ? -1 : total);
                try (InputStream input = request.getInputStream()) {
                    if (start == 0) {
                        // Invalidate the old validator before replacing its file, even after a process death.
                        invalidate(tag);
                        byte[] header = readHeader(input);
                        try (FileOutputStream out = new FileOutputStream(part, false)) {
                            if (strong(etag)) writeValidator(tag, target, etag);
                            out.write(header);
                            stream(input, out, header.length, expectedEnd, monitor, part);
                        }
                    } else {
                        try (FileOutputStream out = new FileOutputStream(part, true)) {
                            stream(input, out, start, expectedEnd, monitor, part);
                        }
                    }
                }
                if (total >= 0 && part.length() != total) throw new IOException("The server did not send the complete file.");
                return;
            } finally { request.disconnect(); }
        }
    }

    static void copy(InputStream input, File part, Monitor monitor) throws IOException {
        if (input == null) throw new Rejected("Android could not open this media file.");
        try (InputStream in = input) {
            checkStopped(monitor);
            byte[] header = readHeader(in);
            try (FileOutputStream out = new FileOutputStream(part, false)) {
                out.write(header);
                stream(in, out, header.length, -1, monitor, part);
            }
        }
    }

    private static void stream(InputStream input, FileOutputStream out, long written, long end, Monitor monitor, File file) throws IOException {
        byte[] data = new byte[64 * 1024];
        long checkpoint = written;
        while (true) {
            checkStopped(monitor);
            int read = input.read(data);
            if (read == -1) break;
            long next = written + read;
            if (next > LIMIT_BYTES) throw new Rejected("This file exceeds the 4 GB offline limit.");
            if (end >= 0 && next > end) throw new Rejected("The server sent more data than advertised.");
            if (next - checkpoint > 8L * 1024 * 1024) {
                if (file.getUsableSpace() < RESERVE_BYTES) throw new Rejected("Not enough free space to finish this download.");
                checkpoint = next;
            }
            out.write(data, 0, read);
            written = next;
        }
        if (end >= 0 && written != end) throw new IOException("The transfer ended early.");
        checkStopped(monitor);
        out.getFD().sync();
    }

    private static void checkStopped(Monitor monitor) throws IOException {
        if (monitor.stopped() || Thread.currentThread().isInterrupted()) throw new IOException("Transfer interrupted");
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        byte[] header = new byte[32];
        int filled = 0;
        while (filled < header.length) {
            int n = input.read(header, filled, header.length - filled);
            if (n == -1) break;
            filled += n;
        }
        if (!MediaSources.looksLikeMedia(header, filled)) {
            throw new Rejected("This is not a recognized audio or video file. Try an MP4, WebM, MP3, M4A, FLAC, Ogg or WAV.");
        }
        return filled == header.length ? header : java.util.Arrays.copyOf(header, filled);
    }

    private static long[] contentRange(String text) {
        if (text == null) return null;
        Matcher m = RANGE.matcher(text);
        if (!m.matches()) return null;
        try {
            long start = Long.parseLong(m.group(1));
            long end = Long.parseLong(m.group(2));
            long total = Long.parseLong(m.group(3));
            return end >= start && total > end ? new long[] {start, end, total} : null;
        } catch (NumberFormatException bad) { return null; }
    }

    private static boolean strong(String tag) {
        return tag != null && tag.length() >= 2 && tag.length() <= 512 && tag.startsWith("\"") && tag.endsWith("\"");
    }

    private static void invalidate(File tag) throws IOException {
        if (tag.exists() && !tag.delete()) throw new IOException("Could not reset the download file.");
    }

    private static final class Validator {
        final String url;
        final String etag;
        Validator(String url, String etag) { this.url = url; this.etag = etag; }
    }

    private static Validator readValidator(File tag) {
        if (!tag.isFile() || tag.length() > 8192) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(tag))) {
            String url = in.readUTF();
            String etag = in.readUTF();
            return strong(etag) ? new Validator(url, etag) : null;
        } catch (IOException invalid) { return null; }
    }

    private static void writeValidator(File tag, String url, String etag) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tag))) {
            out.writeUTF(url);
            out.writeUTF(etag);
        }
    }
}
