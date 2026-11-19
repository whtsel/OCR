package com.blackboxstream.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import java.io.FileNotFoundException;

/** Debug-only, same-app source exercising the actual offline job and content resolver. */
public final class SmokeMediaProvider extends ContentProvider {
    static volatile boolean available = true;

    @Override public boolean onCreate() { return true; }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (!available || !"r".equals(mode) || !"/dawn.mp4".equals(uri.getPath())) throw new FileNotFoundException();
        return getContext().getResources().openRawResourceFd(R.raw.dawn);
    }

    @Override public String getType(Uri uri) { return "video/mp4"; }
    @Override public Cursor query(Uri uri, String[] columns, String query, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
