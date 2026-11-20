package com.blackboxstream.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Debug-only, same-app source exercising the actual offline job and content resolver. */
public final class SmokeMediaProvider extends ContentProvider {
    static volatile boolean available = true;

    @Override public boolean onCreate() { return true; }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if (!available || !"r".equals(mode) || !("/dawn.mp4".equals(path) || "/orbit.m4a".equals(path))) throw new FileNotFoundException();
        File file = new File(new File(getContext().getCacheDir(), "test-media"), path.substring(1));
        return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length());
    }

    @Override public String getType(Uri uri) { return "/orbit.m4a".equals(uri.getPath()) ? "audio/mp4" : "video/mp4"; }
    @Override public Cursor query(Uri uri, String[] columns, String query, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
