package icu.weierstrass.vault7;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class BackupEngine {
    interface Progress {
        void update(String text);
    }

    private final Context context;
    private final BackupPlan plan;
    private final Uri folder;
    private final SevenZip.Checkpoint checkpoint;
    private final Progress progress;
    private final char[] password;
    private final List<String> omissions = new ArrayList<>();
    private int omissionCount;
    private int written;
    private int archives;
    private int alreadyBackedUp;
    private Set<String> archiveNames;

    BackupEngine(Context context, BackupPlan plan, Uri folder, char[] password,
                 SevenZip.Checkpoint checkpoint, Progress progress) {
        this.context = context;
        this.plan = plan;
        this.folder = folder;
        this.password = password;
        this.checkpoint = checkpoint;
        this.progress = progress;
    }

    void run() throws IOException, GeneralSecurityException {
        List<MediaCatalogue.Item> media = MediaCatalogue.read(context, checkpoint);
        if (media.isEmpty()) return;
        LocalDate anchor = plan.start != null ? plan.start : plan.date(media.get(0).time());
        try (BackupRecords records = new BackupRecords(context)) {
            Set<String> backedUp = records.backedUp();
            Pack pack = new Pack();
            long currentWindow = -1;
            for (MediaCatalogue.Item item : media) {
                checkpoint.check();
                LocalDate date = plan.date(item.time());
                if (!plan.includes(date)) continue;
                if (backedUp.contains(item.key())) {
                    alreadyBackedUp++;
                    continue;
                }

                if (item.size() < 0 || item.size() > Long.MAX_VALUE / 2) {
                    omit(item, "无法确定可用的文件大小");
                    if (plan.mode == BackupPlan.Mode.SIZE) break;
                    continue;
                }

                if (plan.mode == BackupPlan.Mode.EVERY_N_DAYS) {
                    long window = plan.window(anchor, date);
                    if (window != currentWindow && !pack.items.isEmpty()) {
                        publish(pack, records);
                        pack = new Pack();
                    }
                    currentWindow = window;
                }

                SevenZip.Entry entry = makeEntry(item, pack);
                if (plan.mode.sizeLimited() && pack.layout.sizeWith(entry) > plan.limit) {
                    if (!pack.items.isEmpty()) {
                        publish(pack, records);
                        pack = new Pack();
                        if (plan.mode == BackupPlan.Mode.SIZE) break;
                        entry = makeEntry(item, pack);
                    }
                    if (pack.layout.sizeWith(entry) > plan.limit) {
                        omit(item, "单独打包仍超出上限");
                        if (plan.mode == BackupPlan.Mode.SIZE) break;
                        continue;
                    }
                }
                pack.items.add(item);
                pack.entries.add(entry);
                pack.layout.add(entry);
            }
            if (!pack.items.isEmpty()) publish(pack, records);
        }
    }

    String summary() {
        String result = "已保存 " + archives + " 个压缩包、" + written + " 个文件。"
                + "\n已跳过 " + alreadyBackedUp + " 个已备份文件。";
        if (omissionCount > 0) {
            result += "\n有 " + omissionCount + " 个文件未备份。\n" + String.join("\n", omissions);
            if (omissionCount > omissions.size()) result += "\n还有 " + (omissionCount - omissions.size()) + " 个。";
        }
        return result;
    }

    private void omit(MediaCatalogue.Item item, String why) {
        omissionCount++;
        if (omissions.size() < 30) omissions.add(item.name() + " · " + why);
    }

    private SevenZip.Entry makeEntry(MediaCatalogue.Item item, Pack pack) {
        String name = item.name().replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").replaceAll("[ .]+$", "");
        if (name.isEmpty()) name = "media";
        // Prefixes keep same-named items distinct on case-insensitive filesystems.
        return item.entry((pack.entries.size() + 1) + "_" + name, context.getContentResolver());
    }

    private void publish(Pack pack, BackupRecords records) throws IOException, GeneralSecurityException {
        checkpoint.check();
        progress.update("正在写入第 " + (archives + 1) + " 个压缩包 · " + pack.entries.size() + " 个文件");
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
        LocalDate first = plan.date(pack.items.get(0).time());
        LocalDate last = plan.date(pack.items.get(pack.items.size() - 1).time());
        if (archiveNames == null) archiveNames = listNames(resolver);
        String name = archiveName(first, last, archiveNames);
        Uri temporary = DocumentsContract.createDocument(resolver, parent,
                "application/octet-stream", "Vault7_" + UUID.randomUUID() + ".partial");
        if (temporary == null) throw new IOException("无法创建临时压缩包，请检查输出目录");
        boolean published = false;
        try {
            ParcelFileDescriptor fd = resolver.openFileDescriptor(temporary, "rw");
            if (fd == null) throw new IOException("无法打开输出文件");
            try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                FileChannel channel = output.getChannel();
                SevenZip.write(channel, pack.entries, password, checkpoint);
                long size = channel.size();
                if (size != pack.layout.size() || (plan.mode.sizeLimited() && size > plan.limit)) {
                    throw new IOException("压缩包大小校验失败");
                }
                channel.force(true);
            }
            for (MediaCatalogue.Item item : pack.items) {
                checkpoint.check();
                MediaCatalogue.verify(context, item);
            }
            Uri finished = DocumentsContract.renameDocument(resolver, temporary, name);
            if (finished == null) throw new IOException("输出目录无法完成压缩包重命名");
            published = true;
            records.record(pack.items, finished.toString());
            written += pack.items.size();
            archives++;
        } finally {
            if (!published) {
                try {
                    DocumentsContract.deleteDocument(resolver, temporary);
                } catch (Exception ignored) {
                    // A revoked permission can also prevent temporary-file cleanup.
                }
            }
        }
    }

    private Set<String> listNames(ContentResolver resolver) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                folder, DocumentsContract.getTreeDocumentId(folder));
        Set<String> names = new HashSet<>();
        try (Cursor rows = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (rows == null) throw new IOException("无法读取输出目录");
            while (rows.moveToNext()) {
                checkpoint.check();
                String name = rows.getString(0);
                if (name != null) names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static String archiveName(LocalDate first, LocalDate last, Set<String> names) {
        DateTimeFormatter format = DateTimeFormatter.BASIC_ISO_DATE;
        String base = first.format(format) + "_" + last.format(format);
        String result = base + ".7z";
        for (int suffix = 2; !names.add(result); suffix++) {
            result = base + String.format(Locale.ROOT, "_%02d.7z", suffix);
        }
        return result;
    }

    private static final class Pack {
        final List<MediaCatalogue.Item> items = new ArrayList<>();
        final List<SevenZip.Entry> entries = new ArrayList<>();
        final SevenZip.Layout layout = new SevenZip.Layout();
    }
}
