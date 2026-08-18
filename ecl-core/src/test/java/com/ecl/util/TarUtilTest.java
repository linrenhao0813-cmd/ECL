package com.ecl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TarUtilTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsPaxPathAfterEarlierPaxRecords() throws Exception {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        String pax = paxRecord("mtime", "1.0") + paxRecord("path", "nested/file.txt");
        Path archive = writeArchive("./placeholder", payload, (byte) '0', pax.getBytes(StandardCharsets.UTF_8));
        Path target = tempDir.resolve("target");

        TarUtil.extractGzipTar(archive, target);

        assertEquals("hello", Files.readString(target.resolve("nested/file.txt")));
    }

    @Test
    void rejectsTraversalEntriesBeforeWritingOutsideTarget() throws Exception {
        Path archive = writeArchive("../../outside.txt", "bad".getBytes(StandardCharsets.UTF_8), (byte) '0', null);
        Path target = tempDir.resolve("target");

        assertThrows(IOException.class, () -> TarUtil.extractGzipTar(archive, target));
        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
    }

    @Test
    void rejectsBackslashTraversalAndAbsoluteEntries() throws Exception {
        Path backslash = writeArchive("..\\outside.txt", new byte[]{1}, (byte) '0', null);
        assertThrows(IOException.class,
                () -> TarUtil.extractGzipTar(backslash, tempDir.resolve("backslash-target")));

        Path absolute = writeArchive("/outside.txt", new byte[]{1}, (byte) '0', null);
        assertThrows(IOException.class,
                () -> TarUtil.extractGzipTar(absolute, tempDir.resolve("absolute-target")));
    }

    @Test
    void rejectsTraversalFromPaxPathOverride() throws Exception {
        byte[] pax = paxRecord("path", "../../outside.txt").getBytes(StandardCharsets.UTF_8);
        Path archive = writeArchive("placeholder", new byte[]{1}, (byte) '0', pax);

        assertThrows(IOException.class,
                () -> TarUtil.extractGzipTar(archive, tempDir.resolve("pax-target")));
        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
    }

    @Test
    void rejectsSymbolicAndHardLinkEntries() throws Exception {
        Path symbolic = writeArchive("link", new byte[0], (byte) '2', null);
        assertThrows(IOException.class,
                () -> TarUtil.extractGzipTar(symbolic, tempDir.resolve("symbolic-target")));

        Path hard = writeArchive("link", new byte[0], (byte) '1', null);
        assertThrows(IOException.class,
                () -> TarUtil.extractGzipTar(hard, tempDir.resolve("hard-target")));
    }

    @Test
    void rejectsInvalidHeaderChecksum() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        writeEntry(tar, "file.txt", new byte[]{1}, (byte) '0');
        byte[] bytes = tar.toByteArray();
        bytes[0] ^= 1;
        Path archive = tempDir.resolve("bad-checksum.tar.gz");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
            gzip.write(bytes);
            gzip.write(new byte[1024]);
        }

        assertThrows(IOException.class,
                () -> TarUtil.extractGzipTar(archive, tempDir.resolve("checksum-target")));
    }

    private Path writeArchive(String name, byte[] content, byte type, byte[] paxPayload) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        if (paxPayload != null) {
            writeEntry(tar, "pax", paxPayload, (byte) 'x');
        }
        writeEntry(tar, name, content, type);
        tar.write(new byte[1024]);

        Path archive = tempDir.resolve("archive.tar.gz");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
            gzip.write(tar.toByteArray());
        }
        return archive;
    }

    private static String paxRecord(String key, String value) {
        String record = key + "=" + value + "\n";
        int length = record.length() + 2;
        while (Integer.toString(length).length() + 1 + record.length() != length) {
            length++;
        }
        return length + " " + record;
    }

    private static void writeEntry(ByteArrayOutputStream output, String name, byte[] content, byte type)
            throws IOException {
        byte[] header = new byte[512];
        putString(header, 0, 100, name);
        putOctal(header, 100, 8, 0644);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, content.length);
        putOctal(header, 136, 12, 0);
        header[156] = type;
        putString(header, 257, 6, "ustar");
        putString(header, 263, 2, "00");
        for (int i = 148; i < 156; i++) header[i] = ' ';
        long checksum = 0;
        for (byte value : header) checksum += value & 0xff;
        String checksumText = String.format("%06o", checksum);
        putString(header, 148, 6, checksumText);
        header[154] = 0;
        header[155] = ' ';
        output.write(header);
        output.write(content);
        int padding = (512 - content.length % 512) % 512;
        output.write(new byte[padding]);
    }

    private static void putString(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }

    private static void putOctal(byte[] target, int offset, int length, long value) {
        String text = String.format("%0" + (length - 1) + "o", value);
        putString(target, offset, length - 1, text);
        target[offset + length - 1] = 0;
    }
}
