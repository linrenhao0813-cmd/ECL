package com.ecl.modrinth.model;

import java.net.URI;
import java.util.Map;

public record ModFile(
        URI url,
        String fileName,
        Map<String, String> hashes,
        boolean primary,
        long size,
        String fileType
) {
    public ModFile {
        hashes = hashes == null ? Map.of() : Map.copyOf(hashes);
    }

    public String sha1() {
        return hashes.getOrDefault("sha1", "");
    }

    public String sha512() {
        return hashes.getOrDefault("sha512", "");
    }
}
