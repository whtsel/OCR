package com.blackboxstream.mobile;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Save-only adapter for the packaged v90 helper. It never creates a streaming reader. */
final class WholeFileMusicRuntime implements OfflineMusicLibrary.Downloader {
    private final BundledMusicRuntime resolver;
    private final boolean fixtureOnly;
    private final File fixtureListings;

    WholeFileMusicRuntime(Context app) {
        fixtureOnly = (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && new File(app.getFilesDir(), "offline-music/fixture-only").isFile();
        resolver = new BundledMusicRuntime(app, fixtureOnly);
        fixtureListings = new File(app.getFilesDir(), "offline-music/fixture-listings");
    }

    @Override public void published(PreparedMusic.Track track, File staging) {
        // Remember the resolved ID for this exact recording only after publication has succeeded.
        try { resolver.rememberSource(track, BundledMusicRuntime.sourceId(staging)); }
        catch (java.io.IOException ignored) { /* A missing hint only means another search next time. */ }
    }

    @Override public File fetch(PreparedMusic.Track track, File staging, String excludedSource,
            OfflineMusicLibrary.Progress progress) throws Exception {
        // A force-stop can leave audio.m4a or its partial files behind. A fresh attempt must
        // not let yt-dlp reuse or publish any output left by that uncommitted transfer.
        File[] leftovers = staging.listFiles();
        if (leftovers == null) throw new OfflineMusicLibrary.Refusal("storage");
        for (File file : leftovers) {
            if (!file.delete()) throw new OfflineMusicLibrary.Refusal("storage");
        }
        if (fixtureOnly) {
            // Test listings live outside staging so a second real helper process can read them.
            File listing = new File(fixtureListings, track.key + ".json");
            if (listing.isFile()) {
                try (FileInputStream in = new FileInputStream(listing);
                     FileOutputStream out = new FileOutputStream(new File(staging, "pc-fixture.json"))) {
                    out.write(TorrentMeta.slurp(in, 8192));
                }
            }
        }
        // Catalogue rows never supply a fixed provider ID. Search unless this exact row
        // has a remembered successful save; a refresh ignores even that remembered source.
        return resolver.saveOffline(track, staging, "", excludedSource, fixtureOnly ? track.key : null, progress);
    }

    static String lookupArtist(PreparedMusic.Track track) {
        // Both 3:59 rows omit the second performer. Keep their keys and releases;
        // use the full credits only when resolving those exact catalogue entries.
        if (track.seconds == 239 && track.artist.equals("Rema") && track.title.equals("Calm Down")
                && (track.ref.equals("pick:1873297197") && track.album.equals("Calm Down")
                    || track.ref.equals("album:d432510347:1:22") && track.album.equals("Rave & Roses Ultra"))) {
            return "Rema & Selena Gomez";
        }
        return track.artist;
    }
}
