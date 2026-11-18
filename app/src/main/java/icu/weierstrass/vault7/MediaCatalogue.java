package icu.weierstrass.vault7;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MediaCatalogue {
    private static final String[] COLUMNS = {
            MediaColumns._ID, MediaColumns.DISPLAY_NAME, MediaColumns.SIZE,
            MediaColumns.DATE_TAKEN, MediaColumns.DATE_ADDED, MediaColumns.DATE_MODIFIED,
            MediaColumns.GENERATION_ADDED, MediaColumns.GENERATION_MODIFIED
    };

    record Item(Uri uri, String version, String name, long size, long time, long added,
                long modified, long createdGeneration, long modifiedGeneration) {
        String key() {
            return uri + "|" + version + "|" + createdGeneration + "|" + modifiedGeneration
                    + "|" + added + "|" + modified + "|" + size;
        }

        SevenZip.Entry entry(String archiveName, ContentResolver resolver) {
            return new SevenZip.Entry(archiveName, size, time, () -> {
                if (size < 0) throw new IOException("媒体大小未知：" + name);
                InputStream input = resolver.openInputStream(MediaStore.setRequireOriginal(uri));
                if (input == null) throw new FileNotFoundException(name);
                return input;
            });
        }
    }

    static List<Item> read(Context context, SevenZip.Checkpoint checkpoint) throws IOException {
        List<Item> result = new ArrayList<>();
        for (String volume : MediaStore.getExternalVolumeNames(context)) {
            checkpoint.check();
            String version = MediaStore.getVersion(context, volume);
            collect(context, MediaStore.Images.Media.getContentUri(volume), version, result, checkpoint);
            collect(context, MediaStore.Video.Media.getContentUri(volume), version, result, checkpoint);
        }
        result.sort(Comparator.comparingLong(Item::time).thenComparing(item -> item.uri.toString()));
        return result;
    }

    private static void collect(Context context, Uri collection, String version,
                                List<Item> result, SevenZip.Checkpoint checkpoint) throws IOException {
        try (Cursor rows = context.getContentResolver().query(collection, COLUMNS, null, null, null)) {
            if (rows == null) throw new IOException("无法读取相册");
            while (rows.moveToNext()) {
                checkpoint.check();
                Uri uri = ContentUris.withAppendedId(collection, rows.getLong(0));
                String name = rows.getString(1);
                long added = rows.getLong(4);
                long modified = rows.getLong(5);
                long time = rows.getLong(3);
                if (time <= 0) time = (added > 0 ? added : Math.max(0, modified)) * 1000L;
                long size = rows.isNull(2) ? -1 : rows.getLong(2);
                result.add(new Item(uri, version, name == null ? "media" : name, size, time,
                        added, modified, rows.getLong(6), rows.getLong(7)));
            }
        }
    }

    static void verify(Context context, Item item) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor row = resolver.query(item.uri, COLUMNS, null, null, null)) {
            if (row == null || !row.moveToFirst() || row.getLong(2) != item.size
                    || row.getLong(4) != item.added || row.getLong(5) != item.modified
                    || row.getLong(6) != item.createdGeneration
                    || row.getLong(7) != item.modifiedGeneration) {
                throw new IOException("备份期间媒体已变化：" + item.name);
            }
        }
    }
}
