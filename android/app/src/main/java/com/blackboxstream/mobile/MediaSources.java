package com.blackboxstream.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class MediaSources {
    static final int MAX_PLAYLIST_BYTES = 1024 * 1024;
    static final int MAX_ENTRIES = 500;
    private static final Pattern GROUP = Pattern.compile("(?:^|\\s)group-title\\s*=\\s*(?:\"([^\"]*)\"|([^\\s]+))", Pattern.CASE_INSENSITIVE);

    private MediaSources() {}

    static String https(String input) {
        if (input == null || input.trim().length() > 4096) throw invalidLink();
        try {
            URI uri = new URI(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw invalidLink();
            // A fragment never reaches the server. Do not decode or rebuild the signed query.
            String ascii = uri.toASCIIString();
            int fragment = ascii.indexOf('#');
            if (fragment >= 0) ascii = ascii.substring(0, fragment);
            return "https" + ascii.substring(ascii.indexOf(':'));
        } catch (URISyntaxException bad) {
            throw invalidLink();
        }
    }

    private static IllegalArgumentException invalidLink() {
        return new IllegalArgumentException("Enter a direct HTTPS media or playlist link, without a username or password.");
    }

    static boolean hls(String url) { return path(url).toLowerCase(Locale.ROOT).endsWith(".m3u8"); }
    static boolean playlist(String url) { return path(url).toLowerCase(Locale.ROOT).endsWith(".m3u"); }

    static String titleFor(String url) {
        String p = path(url);
        int slash = p.lastIndexOf('/');
        String file = slash < 0 ? p : p.substring(slash + 1);
        if (file.isEmpty()) {
            try { return MediaEntry.cleanTitle(new URI(url).getHost()); }
            catch (URISyntaxException bad) { return "Untitled media"; }
        }
        return MediaEntry.cleanTitle(file.replaceFirst("\\.[A-Za-z0-9]{1,6}$", "").replace('_', ' '));
    }

    static MediaEntry.Kind infer(String group, String source) {
        String type = group == null ? "" : group.toLowerCase(Locale.ROOT).trim();
        if (type.contains("anime")) return MediaEntry.Kind.ANIME;
        if (type.contains("series") || type.contains("tv show")) return MediaEntry.Kind.SERIES;
        if (type.contains("music") || type.contains("audio") || type.contains("radio")
                || path(source).toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|m4b|aac|ogg|oga|flac|wav|opus)$")) {
            return MediaEntry.Kind.MUSIC;
        }
        return MediaEntry.Kind.MOVIE;
    }

    private static String path(String url) {
        if (url == null) return "";
        try {
            String p = new URI(url).getPath();
            return p == null ? "" : p;
        } catch (URISyntaxException e) { return ""; }
    }

    static final class ImportResult {
        final List<MediaEntry> entries;
        final int skipped;
        ImportResult(List<MediaEntry> entries, int skipped) { this.entries = entries; this.skipped = skipped; }
    }

    static ImportResult readPlaylist(InputStream stream, URI base) throws IOException {
        if (stream == null) throw new IOException("Android could not open the playlist.");
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (out.size() + read > MAX_PLAYLIST_BYTES) throw new IOException("This playlist exceeds the 1 MB limit.");
                out.write(chunk, 0, read);
            }
            return parsePlaylist(new String(out.toByteArray(), StandardCharsets.UTF_8), base);
        }
    }

    static ImportResult parsePlaylist(String content, URI base) throws IOException {
        // Some providers prefix their M3U with a UTF-8 BOM or use CRLF.
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        LinkedHashMap<String,MediaEntry> result = new LinkedHashMap<>();
        int skipped = 0;
        String title = null;
        String group = null;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("#EXT-X-")) {
                throw new IOException("This is an HLS stream, not a catalogue. Add its HTTPS URL as a media link.");
            }
            if (upper.startsWith("#EXTINF:")) {
                int i = titleComma(line);
                title = i >= 0 ? line.substring(i + 1) : null;
                Matcher groupMatch = GROUP.matcher(i >= 0 ? line.substring(0, i) : line);
                group = groupMatch.find() ? (groupMatch.group(1) == null ? groupMatch.group(2) : groupMatch.group(1)) : null;
            } else if (upper.startsWith("#EXTGRP:")) {
                group = line.substring(8).trim();
            } else if (!line.startsWith("#")) {
                try {
                    String candidate = base == null ? line : base.resolve(line).toString();
                    String url = https(candidate);
                    if (playlist(url)) throw invalidLink();
                    MediaEntry media = new MediaEntry(title == null || title.trim().isEmpty() ? titleFor(url) : title,
                            infer(group, url), url);
                    if (result.size() < MAX_ENTRIES && !result.containsKey(media.id)) result.put(media.id, media);
                    else skipped++;
                } catch (IllegalArgumentException bad) { skipped++; }
                title = null;
                group = null;
            }
        }
        if (result.isEmpty()) throw new IOException("No playable HTTPS links were found in this playlist.");
        return new ImportResult(new ArrayList<>(result.values()), skipped);
    }

    private static int titleComma(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) return i;
        }
        return -1;
    }

    static ImportResult fetchPlaylist(String input) throws IOException {
        String target = https(input);
        for (int hop = 0; hop <= 3; hop++) {
            URI uri = URI.create(target);
            HttpsURLConnection request = (HttpsURLConnection) uri.toURL().openConnection();
            request.setConnectTimeout(7000);
            request.setReadTimeout(7000);
            request.setInstanceFollowRedirects(false);
            try {
                int status = request.getResponseCode();
                if (status >= 300 && status < 400 && request.getHeaderField("Location") != null) {
                    target = https(uri.resolve(request.getHeaderField("Location")).toString());
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("The playlist server returned HTTP " + status + ".");
                if (request.getContentLengthLong() > MAX_PLAYLIST_BYTES) throw new IOException("This playlist exceeds the 1 MB limit.");
                return readPlaylist(request.getInputStream(), uri);
            } finally { request.disconnect(); }
        }
        throw new IOException("Too many redirects while opening the playlist.");
    }

    static boolean looksLikeMedia(byte[] b, int n) {
        if (n < 4) return false;
        if (n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return true;
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && ((b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E')
                || (b[8] == 'A' && b[9] == 'V' && b[10] == 'I' && b[11] == ' '))) return true;
        return (b[0] == 'I' && b[1] == 'D' && b[2] == '3')
                || (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S')
                || (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C')
                || ((b[0] & 255) == 0x1a && (b[1] & 255) == 0x45 && (b[2] & 255) == 0xdf && (b[3] & 255) == 0xa3)
                || ((b[0] & 255) == 255 && (b[1] & 0xe0) == 0xe0);
    }
}
