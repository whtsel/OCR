package com.blackboxstream.mobile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Host-side checks for the exact parsers and transport shipped in the APK. */
public final class HostChecks {
    private static int checks;
    private interface Action { void run() throws Exception; }

    private static void check(String name, boolean pass) {
        if (!pass) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    private static void expect(String name, Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); }
        catch (Exception thrown) {
            if (!type.isInstance(thrown)) throw new AssertionError(name, thrown);
            check(name, true);
            return;
        }
        throw new AssertionError(name + " did not reject invalid input");
    }

    private static void parsers() throws Exception {
        check("strip fragments without changing a signed query", "https://test.example/a%20b.mp4?token=x%3D%2B"
                .equals(MediaSources.https(" HTTPS://test.example/a%20b.mp4?token=x%3D%2B#t=4 ")));
        check("title from URL path", "a b".equals(MediaSources.titleFor("https://test.example/a%20b.mp4?token=2")));
        check("HLS recognized with case and query", MediaSources.hls("https://test.example/media.M3U8?sig=1"));
        check("audio inferred by URL path", MediaSources.infer(null, "https://test.example/a.AAC#live") == MediaEntry.Kind.MUSIC);
        expect("reject HTTP", IllegalArgumentException.class, () -> MediaSources.https("http://test.example/a.mp4"));
        expect("reject embedded login", IllegalArgumentException.class, () -> MediaSources.https("https://u:p@test.example/a.mp4"));
        expect("reject magnet as media link", IllegalArgumentException.class, () -> MediaSources.https("magnet:?xt=sample"));
        expect("reject local file URLs", IllegalArgumentException.class, () -> MediaSources.https("file:///data/a.mp4"));
        expect("reject impossible port", IllegalArgumentException.class, () -> MediaSources.https("https://test.example:70000/a.mp4"));
        MediaSources.ImportResult result = MediaSources.parsePlaylist("\uFEFF#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-name=\"A, B\" group-title=\"TV Series\", A, B\r\nseries/e01.mp4\r\n"
                + "#EXTINF:-1, Orbit\r\n#EXTGRP:Music\r\n/low-orbit.m4a?token=3\r\n"
                + "https://test.example/catalog/series/e01.mp4\r\nhttp://test.example/a.mp4\r\nfile:///secret.mp4\r\n",
                URI.create("https://test.example/catalog/list.m3u"));
        check("BOM, quoted metadata, category + relative resolution", result.entries.size() == 2
                && result.entries.get(0).source.equals("https://test.example/catalog/series/e01.mp4")
                && result.entries.get(0).title.equals("A, B") && result.entries.get(0).kind == MediaEntry.Kind.SERIES
                && result.entries.get(1).kind == MediaEntry.Kind.MUSIC);
        check("skips repeated and disallowed links", result.skipped == 3);
        StringBuilder large = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < MediaSources.MAX_ENTRIES + 1; i++) large.append("https://test.example/").append(i).append(".mp4\n");
        result = MediaSources.parsePlaylist(large.toString(), null);
        check("bounded catalog size", result.entries.size() == MediaSources.MAX_ENTRIES && result.skipped == 1);
        expect("HLS segments aren't imported as library items", IOException.class,
                () -> MediaSources.parsePlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:1\nhttps://test.example/s1.ts\n", null));
        expect("oversize playlist rejected while reading", IOException.class, () -> MediaSources.readPlaylist(
                new ByteArrayInputStream(new byte[MediaSources.MAX_PLAYLIST_BYTES + 1]), null));
    }

    public static void main(String[] args) throws Exception {
        parsers();
        File sourceDir = new File(args[1]);
        File part = new File(args[2], "file.part");
        File tag = new File(args[2], "file.tag");
        String base = args[0];
        byte[] video = Files.readAllBytes(new File(sourceDir, "dawn.mp4").toPath());
        byte[] audio = Files.readAllBytes(new File(sourceDir, "orbit.m4a").toPath());
        AtomicLong total = new AtomicLong();
        OfflineTransfer.Monitor monitor = new OfflineTransfer.Monitor() {
            @Override public boolean stopped() { return false; }
            @Override public void total(long size) { total.set(size); }
        };
        MediaSources.ImportResult playlist = MediaSources.fetchPlaylist(base + "/playlist-redirect");
        check("HTTPS M3U redirects and resolves against the final URL", playlist.entries.size() == 2
                && playlist.entries.get(0).source.equals(base + "/catalog/dawn.mp4") && playlist.skipped == 1);
        OfflineTransfer.download(base + "/redirect", part, tag, monitor);
        check("HTTPS file after redirect matches original bytes and length", total.get() == video.length
                && Arrays.equals(video, Files.readAllBytes(part.toPath())));
        expect("interrupted download does not finish", IOException.class, () -> OfflineTransfer.download(base + "/drop", part, tag, monitor));
        check("interruption left a resumable prefix", part.length() >= 32768 && part.length() < video.length && tag.length() > 0);
        OfflineTransfer.download(base + "/drop", part, tag, monitor);
        check("strong ETag + range resume reconstructs the full video", Arrays.equals(video, Files.readAllBytes(part.toPath())));
        expect("first connection for changed resource is interrupted", IOException.class,
                () -> OfflineTransfer.download(base + "/changed", part, tag, monitor));
        OfflineTransfer.download(base + "/changed", part, tag, monitor);
        check("changed ETag replaces partial data instead of appending", total.get() == audio.length
                && Arrays.equals(audio, Files.readAllBytes(part.toPath())));
        expect("first connection for stale range is interrupted", IOException.class,
                () -> OfflineTransfer.download(base + "/stale", part, tag, monitor));
        OfflineTransfer.download(base + "/stale", part, tag, monitor);
        check("416 falls back to a clean download", Arrays.equals(video, Files.readAllBytes(part.toPath())));
        expect("reject HTML response", OfflineTransfer.Rejected.class, () -> OfflineTransfer.download(base + "/html", part, tag, monitor));
        expect("reject playlist response on an extensionless link", OfflineTransfer.Rejected.class,
                () -> OfflineTransfer.download(base + "/manifest", part, tag, monitor));
        expect("reject HTTPS downgrade redirects", OfflineTransfer.Rejected.class,
                () -> OfflineTransfer.download(base + "/downgrade", part, tag, monitor));
        expect("reject missing files with a permanent error", OfflineTransfer.Rejected.class,
                () -> OfflineTransfer.download(base + "/404", part, tag, monitor));
        expect("reject oversized file before copying", OfflineTransfer.Rejected.class,
                () -> OfflineTransfer.download(base + "/huge", part, tag, monitor));
        expect("reject unexpected partial response", OfflineTransfer.Rejected.class,
                () -> OfflineTransfer.download(base + "/unsolicited", part, tag, monitor));
        File local = new File(args[2], "local.part");
        OfflineTransfer.copy(new ByteArrayInputStream(audio), local, monitor);
        check("copy phone-style InputStream byte for byte", Arrays.equals(audio, Files.readAllBytes(local.toPath())));
        expect("reject non-media bytes despite input source", OfflineTransfer.Rejected.class,
                () -> OfflineTransfer.copy(new ByteArrayInputStream("<html>wrong file</html>".getBytes(StandardCharsets.UTF_8)), local, monitor));
        expect("cancelled copy does not report success", IOException.class, () -> OfflineTransfer.copy(new ByteArrayInputStream(video), local,
                new OfflineTransfer.Monitor() {
                    @Override public boolean stopped() { return true; }
                    @Override public void total(long n) { }
                }));
        System.out.println(checks + " BlackBox host checks passed");
    }
}
