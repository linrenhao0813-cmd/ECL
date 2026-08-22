package com.ecl.modrinth.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the MRPACK index while enforcing the archive metadata size limit. */
final class MrpackIndexReader {
    private static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;

    private MrpackIndexReader() {
    }

    static JsonObject read(ZipFile zip, String missingMessage,
                           String tooLargeMessage, String invalidMessage) throws IOException {
        ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
        if (indexEntry == null) {
            throw new IOException(missingMessage);
        }
        if (indexEntry.getSize() > MAX_INDEX_BYTES) {
            throw new IOException(tooLargeMessage);
        }
        try (InputStream input = zip.getInputStream(indexEntry)) {
            byte[] bytes = input.readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) {
                throw new IOException(tooLargeMessage);
            }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException(invalidMessage, failure);
        }
    }
}
