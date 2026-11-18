package icu.weierstrass.vault7;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class ArchiveTest {
    private static final long TIME = 1_764_000_000_000L;

    @Test public void exactLayoutAndIndependentExtractionFixtures() throws Exception {
        Path fixture = Path.of("build", "verification");
        Path expected = Files.createDirectories(fixture.resolve("expected"));
        byte[] photo = new byte[129];
        byte[] video = new byte[1024 * 1024 + 13];
        new Random(71).nextBytes(photo);
        new Random(19).nextBytes(video);
        Files.write(expected.resolve("照片.JPG"), photo);
        Files.write(expected.resolve("video.mp4"), video);
        Files.write(expected.resolve("empty.bin"), new byte[0]);
        write(fixture.resolve("sample.7z"), List.of(
                entry("照片.JPG", photo), entry("empty.bin", new byte[0]), entry("video.mp4", video)));
        write(fixture.resolve("empty.7z"), List.of(entry("empty.bin", new byte[0])));
        write(fixture.resolve("single.7z"), List.of(entry("照片.JPG", photo)));
        List<SevenZip.Entry> many = new ArrayList<>();
        for (int i = 0; i < 130; i++) many.add(entry(i + "_sample", new byte[i]));
        write(fixture.resolve("many.7z"), many);
    }

    @Test public void rejectsChangedMediaInsteadOfFinishingAnArchive() throws Exception {
        Path path = Files.createTempFile("vault7-changed", ".7z");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            SevenZip.Entry stale = new SevenZip.Entry("changed", 1, TIME,
                    () -> new ByteArrayInputStream(new byte[2]));
            assertThrows(IOException.class, () -> SevenZip.write(
                    channel, List.of(stale), "test".toCharArray(), () -> {}));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test public void dayWindowsFollowLocalCalendarAcrossDst() {
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate anchor = LocalDate.of(2025, 3, 8);
        BackupPlan plan = new BackupPlan(BackupPlan.Mode.EVERY_N_DAYS, anchor, null, 2, 0, zone);
        assertEquals(0, plan.window(anchor, LocalDate.of(2025, 3, 9)));
        LocalDate secondWindow = LocalDate.of(2025, 3, 10);
        assertEquals(1, plan.window(anchor, plan.date(secondWindow.atStartOfDay(zone).toInstant().toEpochMilli())));
    }

    private static SevenZip.Entry entry(String name, byte[] bytes) {
        return new SevenZip.Entry(name, bytes.length, TIME, () -> new ByteArrayInputStream(bytes));
    }

    private static void write(Path path, List<SevenZip.Entry> entries) throws Exception {
        var layout = new SevenZip.Layout();
        for (SevenZip.Entry entry : entries) {
            long next = layout.sizeWith(entry);
            layout.add(entry);
            assertEquals(next, layout.size());
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            SevenZip.write(channel, entries, "Vault7测试!2026".toCharArray(), () -> {});
        }
        assertEquals(layout.size(), Files.size(path));
    }
}
