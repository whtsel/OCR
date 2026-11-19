package com.blackboxstream.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class MediaEntry {
    enum Kind {
        MOVIE("Movies", "FILM"), SERIES("Series", "SERIES"), ANIME("Anime", "ANIME"), MUSIC("Music", "AUDIO");
        final String label;
        final String badge;
        Kind(String label, String badge) { this.label = label; this.badge = badge; }
    }

    final String id;
    final String title;
    final Kind kind;
    final String source;
    final boolean saved;
    final long positionMs;
    final long lastPlayed;
    final int transfer;
    final String transferError;
    final long totalBytes;

    MediaEntry(String title, Kind kind, String source) {
        this(idFor(source), cleanTitle(title), kind, source, false, 0, 0, -1, null, -1);
    }

    MediaEntry(String id, String title, Kind kind, String source, boolean saved,
               long positionMs, long lastPlayed, int transfer, String transferError, long totalBytes) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.source = source;
        this.saved = saved;
        this.positionMs = positionMs;
        this.lastPlayed = lastPlayed;
        this.transfer = transfer;
        this.transferError = transferError;
        this.totalBytes = totalBytes;
    }

    boolean bundled() { return source.startsWith("demo:"); }
    boolean fromPhone() { return source.startsWith("content:"); }
    boolean remote() { return source.startsWith("https://"); }
    boolean torrent() { return source.startsWith("torrent:"); }
    boolean lan() { return source.startsWith("lan:"); }
    String sourceLabel() { return bundled() ? "BUILT-IN" : fromPhone() ? "PHONE FILE" : torrent() ? "TORRENT" : lan() ? "LOCAL SHARE" : "HTTPS"; }
    boolean music() { return kind == Kind.MUSIC; }

    static String cleanTitle(String title) {
        String clean = title == null ? "" : title.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        if (clean.isEmpty()) return "Untitled media";
        return clean.length() <= 140 ? clean : clean.substring(0, 140).trim();
    }

    private static String idFor(String source) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
