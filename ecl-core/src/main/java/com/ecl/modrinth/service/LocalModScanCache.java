package com.ecl.modrinth.service;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads and atomically replaces the persistent local mod scan cache. */
final class LocalModScanCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalModScanCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    Map<String, Entry> read(ModInstanceContext instance) {
        Path path = cachePath(instance);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            ScanCache dto = MAPPER.readValue(path.toFile(), ScanCache.class);
            Map<String, Entry> result = new HashMap<>();
            dto.entries().forEach(entry -> result.put(entry.relativePath(), entry));
            return result;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring invalid local mod scan cache {}", path, e);
            return Map.of();
        }
    }

    void write(ModInstanceContext instance, List<Entry> entries) throws IOException {
        Path target = cachePath(instance);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "mod-scan-", ".json.tmp");
        try {
            Files.writeString(temporary,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(new ScanCache(entries)),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path cachePath(ModInstanceContext instance) {
        return instance.gameDirectory().resolve("launcher-mod-scan.json");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScanCache(List<Entry> entries) {
        private ScanCache {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(
            String relativePath,
            long size,
            long modifiedAt,
            String sha1,
            String sha512,
            String modId,
            String modName,
            String modVersion,
            String loader
    ) {
        Entry {
            modId = modId == null ? "" : modId;
            modName = modName == null ? "" : modName;
            modVersion = modVersion == null ? "" : modVersion;
            loader = loader == null ? "" : loader;
        }
    }
}
