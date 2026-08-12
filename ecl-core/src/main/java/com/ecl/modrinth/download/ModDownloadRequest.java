package com.ecl.modrinth.download;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

public record ModDownloadRequest(
        URI uri,
        String fileName,
        Path temporaryFile,
        Map<String, String> expectedHashes,
        long expectedSize
) {
    public ModDownloadRequest {
        expectedHashes = expectedHashes == null ? Map.of() : Map.copyOf(expectedHashes);
    }
}
