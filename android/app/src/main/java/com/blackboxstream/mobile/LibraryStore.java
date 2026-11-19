package com.blackboxstream.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** One database shared by the UI, player and download jobs. Never store partial files as playable. */
final class LibraryStore extends SQLiteOpenHelper {
    static final int MAX_PENDING = 12;
    static final MediaEntry SAMPLE_VIDEO = new MediaEntry("Signal at Dawn", MediaEntry.Kind.MOVIE, "demo:signal");
    static final MediaEntry SAMPLE_AUDIO = new MediaEntry("Low Orbit", MediaEntry.Kind.MUSIC, "demo:orbit");
    private static LibraryStore instance;
    private final File offlineDir;

    static synchronized LibraryStore get(Context context) {
        if (instance == null) instance = new LibraryStore(context.getApplicationContext());
        return instance;
    }

    private LibraryStore(Context context) {
        super(context, "blackbox-library.db", null, 1);
        offlineDir = new File(context.getFilesDir(), "offline");
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE media (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, "
                + "source TEXT NOT NULL, saved INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0, "
                + "played INTEGER NOT NULL DEFAULT 0, job INTEGER NOT NULL DEFAULT -1, error TEXT, "
                + "total INTEGER NOT NULL DEFAULT -1, has_copy INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX media_played ON media(played DESC)");
        db.execSQL("CREATE INDEX media_saved ON media(saved, kind, title COLLATE NOCASE)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No migration for library version " + oldVersion);
    }

    private static MediaEntry read(Cursor c) {
        return new MediaEntry(c.getString(0), c.getString(1), MediaEntry.Kind.valueOf(c.getString(2)), c.getString(3),
                c.getInt(4) == 1, c.getLong(5), c.getLong(6), c.getInt(7), c.getString(8), c.getLong(9));
    }

    private static final String COLUMNS = "SELECT id,title,kind,source,saved,position,played,job,error,total FROM media ";

    private List<MediaEntry> select(String suffix, String... values) {
        List<MediaEntry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(COLUMNS + suffix, values)) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    MediaEntry get(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) return null;
        List<MediaEntry> rows = select("WHERE id=?", key);
        if (!rows.isEmpty()) return rows.get(0);
        if (SAMPLE_VIDEO.id.equals(key)) return SAMPLE_VIDEO;
        if (SAMPLE_AUDIO.id.equals(key)) return SAMPLE_AUDIO;
        return null;
    }

    List<MediaEntry> recentlyPlayed() { return select("WHERE played>0 ORDER BY played DESC LIMIT 8"); }
    List<MediaEntry> saved() { return select("WHERE saved=1 ORDER BY title COLLATE NOCASE"); }
    List<MediaEntry> transfers() { return select("WHERE job>=0 OR error IS NOT NULL OR has_copy=1 ORDER BY job DESC,title COLLATE NOCASE"); }

    private static String[] key(MediaEntry e) { return new String[] {e.id}; }

    private static void ensure(SQLiteDatabase db, MediaEntry e) {
        ContentValues v = new ContentValues();
        v.put("id", e.id);
        v.put("title", e.title);
        v.put("kind", e.kind.name());
        v.put("source", e.source);
        db.insertWithOnConflict("media", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void save(MediaEntry e, boolean saved) {
        SQLiteDatabase db = getWritableDatabase();
        ensure(db, e);
        ContentValues v = new ContentValues();
        v.put("saved", saved ? 1 : 0);
        if (!saved) v.putNull("error");
        else { v.put("title", e.title); v.put("kind", e.kind.name()); }
        db.update("media", v, "id=?", key(e));
    }

    int importEntries(List<MediaEntry> entries) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("saved", 1);
            for (MediaEntry e : entries) {
                ensure(db, e);
                added += db.update("media", v, "id=? AND saved=0", key(e));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return added;
    }

    void played(MediaEntry e, long position) {
        SQLiteDatabase db = getWritableDatabase();
        ensure(db, e);
        ContentValues v = new ContentValues();
        v.put("position", Math.max(0, position));
        v.put("played", System.currentTimeMillis());
        db.update("media", v, "id=?", key(e));
    }

    int reserveTransfer(MediaEntry e) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ensure(db, e);
            int job;
            try (Cursor c = db.rawQuery("SELECT job FROM media WHERE id=?", key(e))) {
                if (!c.moveToFirst()) throw new IllegalStateException("Media not found");
                job = c.getInt(0);
            }
            if (job != -1) throw new IllegalStateException("This item is already in the download queue.");
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM media WHERE job>=0", null)) {
                if (c.moveToFirst() && c.getInt(0) >= MAX_PENDING) throw new IllegalStateException("The download queue is full.");
            }
            do { job = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE); }
            while (!select("WHERE job=?", Integer.toString(job)).isEmpty());
            ContentValues v = new ContentValues();
            v.put("job", job);
            v.put("saved", 1);
            v.put("total", -1L);
            v.put("attempts", 0);
            v.putNull("error");
            db.update("media", v, "id=?", key(e));
            db.setTransactionSuccessful();
            return job;
        } finally { db.endTransaction(); }
    }

    private int updateTransfer(MediaEntry e, int job, ContentValues v) {
        return getWritableDatabase().update("media", v, "id=? AND job=?", new String[] {e.id, Integer.toString(job)});
    }

    void transferTotal(MediaEntry e, int job, long total) {
        ContentValues v = new ContentValues();
        v.put("total", total);
        updateTransfer(e, job, v);
    }

    boolean retryTransfer(MediaEntry e, int job) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE media SET attempts=attempts+1 WHERE id=? AND job=?", new Object[] {e.id, job});
        try (Cursor c = db.rawQuery("SELECT attempts FROM media WHERE id=? AND job=?", new String[] {e.id, Integer.toString(job)})) {
            return c.moveToFirst() && c.getInt(0) < 3;
        }
    }

    void finishTransfer(MediaEntry e, int job, String error) {
        ContentValues v = new ContentValues();
        v.put("job", -1);
        v.put("total", -1L);
        v.put("has_copy", isOffline(e) ? 1 : 0);
        if (error == null) v.putNull("error"); else v.put("error", error);
        updateTransfer(e, job, v);
    }

    File offline(MediaEntry e) { return new File(offlineDir, e.id + ".media"); }
    File partial(MediaEntry e, int job) { return new File(offlineDir, e.id + "." + job + ".part"); }
    File validator(MediaEntry e, int job) { return new File(offlineDir, e.id + "." + job + ".tag"); }
    boolean isOffline(MediaEntry e) { return offline(e).isFile() && offline(e).length() > 0; }
    boolean prepareDirectory() { return offlineDir.isDirectory() || offlineDir.mkdirs() || offlineDir.isDirectory(); }
    long availableBytes() { return offlineDir.getUsableSpace(); }

    void clearTransferError(MediaEntry e) {
        ContentValues v = new ContentValues();
        v.putNull("error");
        v.put("has_copy", isOffline(e) ? 1 : 0);
        getWritableDatabase().update("media", v, "id=? AND job=-1", key(e));
    }
}
