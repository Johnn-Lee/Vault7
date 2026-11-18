package icu.weierstrass.vault7;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BackupRecords extends SQLiteOpenHelper {
    BackupRecords(Context context) {
        super(context, "backups.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE media (uri TEXT PRIMARY KEY, version TEXT NOT NULL, archive TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("Unknown backup database upgrade");
    }

    Set<String> backedUp() {
        Set<String> result = new HashSet<>();
        try (Cursor rows = getReadableDatabase().query("media", new String[]{"version"},
                null, null, null, null, null)) {
            while (rows.moveToNext()) result.add(rows.getString(0));
        }
        return result;
    }

    int clear() {
        return getWritableDatabase().delete("media", null, null);
    }

    void record(List<MediaCatalogue.Item> items, String archive) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (MediaCatalogue.Item item : items) {
                ContentValues values = new ContentValues();
                values.put("uri", item.uri().toString());
                values.put("version", item.key());
                values.put("archive", archive);
                if (db.insertWithOnConflict("media", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                    throw new android.database.SQLException("无法保存备份记录");
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
