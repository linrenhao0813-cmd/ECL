package com.ecl.game;

import com.ecl.ECLConfig;
import com.ecl.performance.PerformancePreset;
import com.ecl.util.GsonProvider;
import com.ecl.util.TextUtil;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Reads, migrates and atomically writes {@code <instance>/.ecl/config/launch-profile.json}. */
public final class InstanceLaunchProfileStore {
    public static final String PROFILE_RELATIVE_PATH = ".ecl/config/launch-profile.json";

    private final Supplier<LegacyLaunchSettings> legacySettings;

    public InstanceLaunchProfileStore() {
        this(LegacyLaunchSettings::defaults);
    }

    public InstanceLaunchProfileStore(Supplier<LegacyLaunchSettings> legacySettings) {
        this.legacySettings = Objects.requireNonNull(legacySettings, "legacySettings");
    }

    /**
     * Loads the instance profile. A missing profile is initialized once from legacy global values.
     * The legacy settings are intentionally left untouched for compatibility with older releases.
     */
    public synchronized InstanceLaunchProfile load(Path instanceRoot) throws IOException {
        Path file = profileFile(instanceRoot);
        if (!Files.isRegularFile(file)) {
            InstanceLaunchProfile migrated = migrate(legacySettings.get());
            save(instanceRoot, migrated);
            return migrated;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("Instance launch profile root must be a JSON object: " + file);
            }
            return fromJson(parsed.getAsJsonObject());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid instance launch profile: " + file, invalid);
        }
    }

    public synchronized void save(Path instanceRoot, InstanceLaunchProfile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Path file = profileFile(instanceRoot);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "launch-profile-", ".json.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GsonProvider.pretty().toJson(toJson(profile), writer);
            }
            moveAtomically(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path profileFile(Path instanceRoot) {
        Path root = Objects.requireNonNull(instanceRoot, "instanceRoot").toAbsolutePath().normalize();
        return root.resolve(PROFILE_RELATIVE_PATH).normalize();
    }

    private static InstanceLaunchProfile migrate(LegacyLaunchSettings legacy) throws IOException {
        LegacyLaunchSettings values = legacy == null ? LegacyLaunchSettings.defaults() : legacy;
        String javaPath = values.javaPath() == null ? "" : values.javaPath().trim();
        int maxMemoryMb = values.maxMemoryMb();
        if (maxMemoryMb != ECLConfig.AUTO_MEMORY_MB
                && (maxMemoryMb < ECLConfig.MIN_GAME_MEMORY_MB
                || maxMemoryMb > ECLConfig.MAX_GAME_MEMORY_MB)) {
            maxMemoryMb = ECLConfig.AUTO_MEMORY_MB;
        }
        List<String> arguments;
        try {
            arguments = TextUtil.parseCommandLine(values.jvmArguments());
        } catch (IllegalArgumentException invalidArguments) {
            throw new IOException("Cannot migrate invalid global JVM arguments", invalidArguments);
        }
        return new InstanceLaunchProfile(
                InstanceLaunchProfile.CURRENT_SCHEMA_VERSION,
                javaPath.isEmpty() ? InstanceLaunchProfile.JavaMode.AUTO
                        : InstanceLaunchProfile.JavaMode.CUSTOM,
                javaPath,
                PerformancePreset.BALANCED,
                maxMemoryMb == ECLConfig.AUTO_MEMORY_MB ? InstanceLaunchProfile.MemoryMode.AUTO
                        : InstanceLaunchProfile.MemoryMode.CUSTOM,
                maxMemoryMb,
                true,
                arguments,
                true,
                "default");
    }

    private static JsonObject toJson(InstanceLaunchProfile profile) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", profile.schemaVersion());
        json.addProperty("javaMode", profile.javaMode().name());
        json.addProperty("javaPath", profile.javaPath());
        json.addProperty("performancePreset", profile.performancePreset().name());
        json.addProperty("memoryMode", profile.memoryMode().name());
        json.addProperty("maxMemoryMb", profile.maxMemoryMb());
        json.addProperty("generatedJvmOptions", profile.generatedJvmOptions());
        JsonArray arguments = new JsonArray();
        profile.customJvmArguments().forEach(arguments::add);
        json.add("customJvmArguments", arguments);
        json.addProperty("autoRepair", profile.autoRepair());
        json.addProperty("backupPolicyId", profile.backupPolicyId());
        return json;
    }

    private static InstanceLaunchProfile fromJson(JsonObject json) {
        int schemaVersion = integer(json, "schemaVersion",
                InstanceLaunchProfile.CURRENT_SCHEMA_VERSION);
        return new InstanceLaunchProfile(
                schemaVersion,
                enumValue(json, "javaMode", InstanceLaunchProfile.JavaMode.class,
                        InstanceLaunchProfile.JavaMode.AUTO),
                string(json, "javaPath", ""),
                enumValue(json, "performancePreset", PerformancePreset.class,
                        PerformancePreset.BALANCED),
                enumValue(json, "memoryMode", InstanceLaunchProfile.MemoryMode.class,
                        InstanceLaunchProfile.MemoryMode.AUTO),
                integer(json, "maxMemoryMb", ECLConfig.AUTO_MEMORY_MB),
                bool(json, "generatedJvmOptions", true),
                stringList(json, "customJvmArguments"),
                bool(json, "autoRepair", true),
                string(json, "backupPolicyId", "default"));
    }

    private static String string(JsonObject json, String name, String fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : fallback;
    }

    private static int integer(JsonObject json, String name, int fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsInt() : fallback;
    }

    private static boolean bool(JsonObject json, String name, boolean fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsBoolean() : fallback;
    }

    private static <E extends Enum<E>> E enumValue(JsonObject json, String name, Class<E> type, E fallback) {
        String value = string(json, name, "").trim();
        return value.isEmpty() ? fallback : Enum.valueOf(type, value);
    }

    private static List<String> stringList(JsonObject json, String name) {
        if (!json.has(name) || json.get(name).isJsonNull()) {
            return List.of();
        }
        JsonArray array = json.getAsJsonArray(name);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Snapshot of the three global launch values used by pre-profile ECL releases. */
    public record LegacyLaunchSettings(String javaPath, int maxMemoryMb, String jvmArguments) {
        public static LegacyLaunchSettings defaults() {
            return new LegacyLaunchSettings("", ECLConfig.AUTO_MEMORY_MB, "");
        }
    }
}
