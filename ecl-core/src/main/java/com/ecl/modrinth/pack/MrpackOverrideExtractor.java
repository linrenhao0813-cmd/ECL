package com.ecl.modrinth.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts MRPACK override files while enforcing archive safety limits. */
final class MrpackOverrideExtractor {
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final double MAX_COMPRESSION_RATIO = 200.0;

    private MrpackOverrideExtractor() {
    }

    static int extract(ZipFile zip, String prefix, Path instanceRoot, ExtractionBudget budget)
            throws IOException {
        int extracted = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().replace('\\', '/');
            if (!name.startsWith(prefix) || name.equals(prefix)) {
                continue;
            }
            String relative = name.substring(prefix.length());
            Path destination = MrpackPathPolicy.safeResolve(instanceRoot, relative);
            if (entry.isDirectory()) {
                Files.createDirectories(destination);
                continue;
            }
            if (++budget.entries > MAX_ENTRIES) {
                throw new IOException("MRPACK override entry count exceeds the safety limit");
            }
            long declaredSize = entry.getSize();
            if (declaredSize > MAX_ENTRY_BYTES) {
                throw new IOException("整合包覆盖文件过大: " + relative);
            }
            if (declaredSize >= 0 && budget.total + declaredSize > MAX_TOTAL_BYTES) {
                throw new IOException("整合包覆盖文件总大小超过安全限制");
            }
            long compressedSize = entry.getCompressedSize();
            if (declaredSize > 0 && (compressedSize == 0 || (compressedSize > 0
                    && (double) declaredSize / compressedSize > MAX_COMPRESSION_RATIO))) {
                throw new IOException("MRPACK override compression ratio exceeds the safety limit: " + relative);
            }
            Files.createDirectories(destination.getParent());
            try (InputStream input = zip.getInputStream(entry);
                 var output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    written += read;
                    if (written > MAX_ENTRY_BYTES) {
                        throw new IOException("整合包覆盖文件解压后过大: " + relative);
                    }
                    output.write(buffer, 0, read);
                }
                budget.total += written;
                if (budget.total > MAX_TOTAL_BYTES) {
                    throw new IOException("整合包覆盖文件总大小超过安全限制");
                }
            }
            extracted++;
        }
        return extracted;
    }

    static final class ExtractionBudget {
        private long total;
        private int entries;
    }
}
