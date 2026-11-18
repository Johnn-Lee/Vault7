package icu.weierstrass.vault7;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A 7z writer for stored files. All nonempty files share one encrypted stream. */
final class SevenZip {
    private static final int SIGNATURE_SIZE = 32;
    private static final int AES_PROPERTIES_SIZE = 34;
    private static final int CYCLES_POWER = 19;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] SIGNATURE = {'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c, 0, 4};

    interface Source {
        InputStream open() throws IOException;
    }

    interface Checkpoint {
        void check() throws IOException;
    }

    record Entry(String name, long size, long time, Source source) {}

    /** The exact stored size is computable before opening any media. */
    static final class Layout {
        private long bytes;
        private long names = 1;
        private long subSizes;
        private long lastSize;
        private int count;
        private int streams;

        long sizeWith(Entry entry) {
            return size(bytes + entry.size, names + nameSize(entry), count + 1,
                    streams + (entry.size == 0 ? 0 : 1),
                    subSizes + (entry.size == 0 || streams == 0 ? 0 : numberSize(lastSize)));
        }

        void add(Entry entry) {
            count++;
            names += nameSize(entry);
            bytes += entry.size;
            if (entry.size > 0) {
                if (streams > 0) subSizes += numberSize(lastSize);
                lastSize = entry.size;
                streams++;
            }
        }

        long size() {
            return size(bytes, names, count, streams, subSizes);
        }

        private static long size(long bytes, long names, int count, int streams, long subSizes) {
            long main = 1 + 1 + numberSize(count) + propertySize(names)
                    + propertySize(2L + 8L * count) + 2;
            int empty = count - streams;
            if (empty > 0) {
                main += propertySize(bitBytes(count)) + propertySize(bitBytes(empty));
            }
            long packed = padded(bytes);
            if (streams > 0) {
                main += 1 + packInfoSize(0, packed) + unpackInfoSize(bytes)
                        + 6 + numberSize(streams) + subSizes + 4L * streams + 1;
            }
            long packedHeader = padded(main);
            long outer = 1 + packInfoSize(packed, packedHeader) + unpackInfoSize(main) + 6 + 1;
            return SIGNATURE_SIZE + packed + packedHeader + outer;
        }

        private static long nameSize(Entry entry) {
            return 2L * (entry.name.length() + 1L);
        }
    }

    static void write(SeekableByteChannel channel, List<Entry> entries, char[] password,
                      Checkpoint checkpoint) throws IOException, GeneralSecurityException {
        if (entries.isEmpty() || password.length == 0) {
            throw new IllegalArgumentException("7z requires files and a password");
        }
        Layout layout = new Layout();
        for (Entry entry : entries) layout.add(entry);

        byte[] salt = randomBytes();
        byte[] dataIv = randomBytes();
        byte[] headerIv = randomBytes();
        byte[] keyBytes = deriveKey(password, salt);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);

        channel.position(SIGNATURE_SIZE);
        int[] checksums = writeMedia(channel, entries, key, dataIv, layout.bytes, checkpoint);
        checkpoint.check();
        long packed = padded(layout.bytes);
        byte[] header = header(entries, checksums, layout, salt, dataIv);
        byte[] paddedHeader = Arrays.copyOf(header, Math.toIntExact(padded(header.length)));
        byte[] encryptedHeader = cipher(key, headerIv).doFinal(paddedHeader);
        writeAll(channel, ByteBuffer.wrap(encryptedHeader));

        var encodedHeader = new Header();
        encodedHeader.id(0x17);
        encodedHeader.packInfo(packed, encryptedHeader.length);
        encodedHeader.unpackInfo(header.length, salt, headerIv, crc(header));
        encodedHeader.id(0);
        byte[] footer = encodedHeader.toByteArray();
        writeAll(channel, ByteBuffer.wrap(footer));

        if (channel.position() != layout.size()) throw new IOException("7z size mismatch");
        ByteBuffer signature = ByteBuffer.allocate(SIGNATURE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        signature.put(SIGNATURE).putInt(0).putLong(packed + encryptedHeader.length)
                .putLong(footer.length).putInt(crc(footer));
        CRC32 startCrc = new CRC32();
        startCrc.update(signature.array(), 12, 20);
        signature.putInt(8, (int) startCrc.getValue());
        signature.flip();
        channel.position(0);
        writeAll(channel, signature);
    }

    private static int[] writeMedia(SeekableByteChannel channel, List<Entry> entries,
                                    SecretKeySpec key, byte[] iv, long bytes, Checkpoint checkpoint)
            throws IOException, GeneralSecurityException {
        Cipher cipher = cipher(key, iv);
        byte[] plain = new byte[BUFFER_SIZE];
        byte[] encrypted = new byte[BUFFER_SIZE + 16];
        int[] checksums = new int[entries.size()];
        CRC32 crc = new CRC32();
        for (int i = 0; i < entries.size(); i++) {
            checkpoint.check();
            Entry entry = entries.get(i);
            crc.reset();
            try (InputStream input = entry.source.open()) {
                long remaining = entry.size;
                while (remaining > 0) {
                    checkpoint.check();
                    int read = input.read(plain, 0, (int) Math.min(remaining, plain.length));
                    if (read <= 0) throw new IOException("媒体已变化或无法完整读取：" + entry.name);
                    remaining -= read;
                    crc.update(plain, 0, read);
                    int written = cipher.update(plain, 0, read, encrypted, 0);
                    writeAll(channel, ByteBuffer.wrap(encrypted, 0, written));
                }
                if (input.read() != -1) throw new IOException("媒体大小已变化：" + entry.name);
            }
            checksums[i] = (int) crc.getValue();
        }
        int padding = (int) (padded(bytes) - bytes);
        writeAll(channel, ByteBuffer.wrap(cipher.doFinal(new byte[padding])));
        return checksums;
    }

    private static byte[] header(List<Entry> entries, int[] checksums, Layout layout,
                                 byte[] salt, byte[] iv) {
        var header = new Header();
        header.id(1);
        if (layout.streams > 0) {
            header.id(4);
            header.packInfo(0, padded(layout.bytes));
            header.unpackInfo(layout.bytes, salt, iv, null);
            header.id(8); // SubStreamsInfo
            header.id(0x0d);
            header.number(layout.streams);
            header.id(9);
            int remaining = layout.streams;
            for (Entry entry : entries) {
                if (entry.size > 0 && --remaining > 0) header.number(entry.size);
            }
            header.id(0x0a);
            header.id(1); // All CRCs defined
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).size > 0) header.le(checksums[i], 4);
            }
            header.id(0); // SubStreamsInfo
            header.id(0); // StreamsInfo
        }
        header.id(5); // FilesInfo
        header.number(entries.size());
        int empty = entries.size() - layout.streams;
        if (empty > 0) {
            byte[] emptyStreams = new byte[bitBytes(entries.size())];
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).size == 0) emptyStreams[i / 8] |= (byte) (128 >>> (i % 8));
            }
            header.id(0x0e);
            header.number(emptyStreams.length);
            header.raw(emptyStreams);
            byte[] emptyFiles = new byte[bitBytes(empty)];
            Arrays.fill(emptyFiles, (byte) 0xff);
            if (empty % 8 != 0) emptyFiles[emptyFiles.length - 1] = (byte) (0xff << (8 - empty % 8));
            header.id(0x0f);
            header.number(emptyFiles.length);
            header.raw(emptyFiles);
        }
        header.id(0x11); // Names
        header.number(layout.names);
        header.id(0); // Inline
        for (Entry entry : entries) {
            for (int i = 0; i < entry.name.length(); i++) header.le(entry.name.charAt(i), 2);
            header.le(0, 2);
        }
        header.id(0x14); // Modification times
        header.number(2L + entries.size() * 8L);
        header.id(1); // All times defined
        header.id(0); // Inline
        for (Entry entry : entries) header.le((entry.time + 11_644_473_600_000L) * 10_000L, 8);
        header.id(0); // FilesInfo
        header.id(0); // Header
        return header.toByteArray();
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        ByteBuffer encoded = StandardCharsets.UTF_16LE.encode(CharBuffer.wrap(password));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] counter = new byte[8];
        try {
            for (int i = 0; i < (1 << CYCLES_POWER); i++) {
                digest.update(salt);
                digest.update(encoded);
                encoded.rewind();
                digest.update(counter);
                for (int j = 0; ++counter[j] == 0; j++) {
                    // Increment the 7z little-endian counter.
                }
            }
            return digest.digest();
        } finally {
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    private static Cipher cipher(SecretKeySpec key, byte[] iv) throws GeneralSecurityException {
        Cipher result = Cipher.getInstance("AES/CBC/NoPadding");
        result.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        return result;
    }

    private static void writeAll(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw new IOException("无法写入存档");
        }
    }

    private static int crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static long padded(long size) {
        return (size + 15) & ~15L;
    }

    private static int bitBytes(int bits) {
        return (bits + 7) / 8;
    }

    private static long propertySize(long bytes) {
        return 1 + numberSize(bytes) + bytes;
    }

    private static int packInfoSize(long position, long packedSize) {
        return 4 + numberSize(position) + numberSize(packedSize);
    }

    private static int unpackInfoSize(long unpacked) {
        return 13 + AES_PROPERTIES_SIZE + numberSize(unpacked);
    }

    private static int numberSize(long value) {
        int extra = 0;
        while (extra < 8 && value >= (1L << (7 * (extra + 1)))) extra++;
        return extra + 1;
    }

    private static final class Header extends ByteArrayOutputStream {
        void raw(byte[] bytes) {
            write(bytes, 0, bytes.length);
        }

        void id(int value) {
            write(value);
        }

        void number(long value) {
            int extra = numberSize(value) - 1;
            write(extra == 8 ? 0xff : ((0xff << (8 - extra)) | (int) (value >>> (8 * extra))) & 0xff);
            le(value, extra);
        }

        void le(long value, int bytes) {
            for (int i = 0; i < bytes; i++) write((int) (value >>> (8 * i)) & 0xff);
        }

        void packInfo(long position, long size) {
            id(6);
            number(position);
            number(1);
            id(9);
            number(size);
            id(0);
        }

        void unpackInfo(long size, byte[] salt, byte[] iv, Integer checksum) {
            id(7);
            id(0x0b);
            number(1); // One folder, defined inline
            id(0);
            number(1); // One coder, 7z AES
            id(0x24);
            raw(new byte[]{6, (byte) 0xf1, 7, 1});
            number(AES_PROPERTIES_SIZE);
            id(0xc0 | CYCLES_POWER);
            id(0xff); // Both salt and IV are 16 bytes
            raw(salt);
            raw(iv);
            id(0x0c);
            number(size);
            if (checksum != null) {
                id(0x0a);
                id(1);
                le(checksum, 4);
            }
            id(0);
        }
    }
}
