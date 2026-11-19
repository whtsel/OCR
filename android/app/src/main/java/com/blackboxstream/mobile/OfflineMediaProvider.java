package com.blackboxstream.mobile;

import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** Only an explicitly shared, completed copy can be opened, and only for reading. */
public final class OfflineMediaProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    static Intent share(android.content.Context context, MediaEntry e) {
        Uri uri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".copies")
                .appendPath("offline").appendPath(e.id).build();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(type(e));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("BlackBox offline copy", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, "Share offline copy");
    }

    private MediaEntry entry(Uri uri) {
        if (uri.getPathSegments().size() != 2 || !"offline".equals(uri.getPathSegments().get(0))) return null;
        LibraryStore db = LibraryStore.get(getContext());
        MediaEntry e = db.get(uri.getLastPathSegment());
        return e != null && db.isOffline(e) ? e : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        MediaEntry e = entry(uri);
        if (!"r".equals(mode) || e == null) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(LibraryStore.get(getContext()).offline(e), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MediaEntry e = entry(uri);
        String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        if (e == null) return cursor;
        File file = LibraryStore.get(getContext()).offline(e);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = exportName(e, file);
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    private static String extension(MediaEntry e, File file) {
        String path = e.torrent() || e.lan() ? e.title : Uri.parse(e.source).getPath();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot != -1) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("mp4|m4a|m4v|mp3|mkv|webm|mov|ogg|oga|opus|wav|flac|aac|avi|3gp")) return ext;
            }
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] b = new byte[12];
            if (in.read(b) == 12) {
                if (b[4] == 'f' && b[5] == 't') return e.music() ? "m4a" : "mp4";
                if (b[0] == 'O' && b[1] == 'g') return "ogg";
                if (b[0] == 'R' && b[1] == 'I') return b[8] == 'W' ? "wav" : "avi";
                if (b[0] == 'f' && b[1] == 'L') return "flac";
                if ((b[0] & 255) == 0x1a) return "mkv";
                if (b[0] == 'I' && b[1] == 'D' || (b[0] & 255) == 255) return "mp3";
            }
        } catch (IOException ignored) { }
        return "media";
    }

    private static String exportName(MediaEntry e, File file) {
        String base = e.title.replaceAll("[\\\\/:*?\"<>|]", "_");
        return base + "." + extension(e, file);
    }

    private static String type(MediaEntry e) { return e.music() ? "audio/*" : "video/*"; }
    @Override public String getType(Uri uri) { MediaEntry e = entry(uri); return e != null ? type(e) : null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
