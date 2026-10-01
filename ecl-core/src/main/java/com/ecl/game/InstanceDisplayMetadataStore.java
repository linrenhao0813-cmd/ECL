package com.ecl.game;

import com.ecl.ECLConfig;
import com.ecl.util.GsonProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Reads and atomically writes {@code <data dir>/instances/display.json}. */
public final class InstanceDisplayMetadataStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final Path file;

    public InstanceDisplayMetadataStore() {
        this(ECLConfig.getBaseDir().toPath().resolve("instances").resolve("display.json"));
    }

    public InstanceDisplayMetadataStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    /** @return the stored entries keyed by profile id; empty when nothing has been customised. */
    public synchronized Map<String, InstanceDisplayMetadata> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("Instance display metadata root must be a JSON object: " + file);
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject instances = root.has("instances") && root.get("instances").isJsonObject()
                    ? root.getAsJsonObject("instances") : new JsonObject();
            Map<String, InstanceDisplayMetadata> entries = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : instances.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                try {
                    entries.put(entry.getKey(), new InstanceDisplayMetadata(
                            entry.getKey(),
                            string(value, "displayName"),
                            bool(value, "favorite"),
                            string(value, "cover")));
                } catch (IllegalArgumentException invalidId) {
                    // Skip entries whose key cannot be a profile id instead of failing the load.
                }
            }
            return entries;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid instance display metadata: " + file, invalid);
        }
    }

    /** Writes the entries, dropping ones that no longer override anything. */
    public synchronized void save(Map<String, InstanceDisplayMetadata> entries) throws IOException {
        JsonObject instances = new JsonObject();
        if (entries != null) {
            for (InstanceDisplayMetadata entry : entries.values()) {
                if (entry == null || !entry.isMeaningful()) {
                    continue;
                }
                JsonObject value = new JsonObject();
                value.addProperty("displayName", entry.displayName());
                value.addProperty("favorite", entry.favorite());
                value.addProperty("cover", entry.coverImage());
                instances.add(entry.profileId(), value);
            }
        }
        if (instances.isEmpty()) {
            // Nothing is customised any more: drop the file instead of leaving an empty document.
            Files.deleteIfExists(file);
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.add("instances", instances);

        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("Instance display metadata path has no parent directory: " + file);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "instance-display-", ".json.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(root, writer);
            }
            moveAtomically(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String string(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : "";
    }

    private static boolean bool(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() && json.get(name).getAsBoolean();
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
