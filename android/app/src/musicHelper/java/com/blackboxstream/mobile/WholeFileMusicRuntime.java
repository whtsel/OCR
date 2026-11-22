package com.blackboxstream.mobile;

import android.content.Context;
import java.io.File;
import java.util.Locale;

/** Save-only adapter for the packaged v90 helper. It never creates a streaming reader. */
final class WholeFileMusicRuntime implements OfflineMusicLibrary.Downloader {
    private final BundledMusicRuntime resolver;
    private final boolean fixtureOnly;

    WholeFileMusicRuntime(Context app) {
        resolver = new BundledMusicRuntime(app);
        fixtureOnly = (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && new File(app.getFilesDir(), "offline-music/fixture-only").isFile();
    }

    @Override public File fetch(PreparedMusic.Track track, File staging, OfflineMusicLibrary.Progress progress) throws Exception {
        File candidate = new File(staging, "audio.m4a");
        // A force-stop can leave a fully staged candidate. The caller verifies it before publish.
        if (candidate.isFile()) return candidate;
        String video = pinned(track);
        return resolver.saveOffline(track, staging, video, fixtureOnly ? (video.isEmpty() ? track.key : video) : null, progress);
    }

    private static String wordKey(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    // Restore the recording map from the v90 Music build. The Offline bridge still
    // cannot supply a provider ID, and the helper checks the pinned ID and duration.
    static String pinned(PreparedMusic.Track track) {
        String title = wordKey(track.title), artist = wordKey(track.artist), album = wordKey(track.album);
        if (title.equals("espresso") && artist.equals("sabrina carpenter")
                && (album.equals("short n sweet") || album.equals("espresso")) && Math.abs(175 - track.seconds) <= 3) return "51zjlMhdSTE";
        if (title.equals("kwaku the traveller") && artist.equals("black sherif")
                && (album.equals("kwaku the traveller") || album.equals("the villain i never was"))
                && Math.abs(185 - track.seconds) <= 3) return "Hb6I7GoTvzI";
        if (title.equals("bad guy") && artist.equals("billie eilish")
                && album.equals("when we all fall asleep where do we go") && Math.abs(194 - track.seconds) <= 3) return "ZD6rXLXZOEI";
        if (title.equals("on the low") && artist.equals("burna boy")
                && (album.equals("on the low") || album.equals("african giant")) && Math.abs(185 - track.seconds) <= 3) return "UFsskuvj8GQ";
        if (title.equals("blinding lights") && artist.equals("the weeknd")
                && (album.equals("after hours") || album.equals("after hours deluxe") || album.equals("the highlights"))
                && Math.abs(200 - track.seconds) <= 3) return "fHI8X4OXluQ";
        return "";
    }

    static String lookupArtist(PreparedMusic.Track track) {
        // The original single pick omits the second performer. Only correct that
        // exact row for the lookup; keep the packaged catalogue and saved key intact.
        if (track.ref.equals("pick:1873297197") && track.seconds == 239 && track.artist.equals("Rema")
                && track.title.equals("Calm Down") && track.album.equals("Calm Down")) return "Rema & Selena Gomez";
        return track.artist;
    }
}
