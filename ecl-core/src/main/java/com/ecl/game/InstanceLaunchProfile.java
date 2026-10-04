package com.ecl.game;

import com.ecl.ECLConfig;
import com.ecl.util.JvmArgumentPolicy;

import java.util.List;
import java.util.Objects;

/** Immutable launch policy persisted separately for each Minecraft instance. */
public record InstanceLaunchProfile(
        int schemaVersion,
        JavaMode javaMode,
        String javaPath,
        MemoryMode memoryMode,
        int maxMemoryMb,
        List<String> customJvmArguments
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public InstanceLaunchProfile {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported launch profile schema: " + schemaVersion);
        }
        javaMode = Objects.requireNonNull(javaMode, "javaMode");
        javaPath = javaPath == null ? "" : javaPath.trim();
        memoryMode = Objects.requireNonNull(memoryMode, "memoryMode");
        customJvmArguments = JvmArgumentPolicy.requireSafe(customJvmArguments);

        if (javaMode == JavaMode.CUSTOM && javaPath.isEmpty()) {
            throw new IllegalArgumentException("Custom Java mode requires a Java path");
        }
        if (javaMode == JavaMode.AUTO && !javaPath.isEmpty()) {
            throw new IllegalArgumentException("Automatic Java mode cannot contain a Java path");
        }
        if (memoryMode == MemoryMode.AUTO && maxMemoryMb != ECLConfig.AUTO_MEMORY_MB) {
            throw new IllegalArgumentException("Automatic memory mode requires maxMemoryMb=0");
        }
        if (memoryMode == MemoryMode.CUSTOM
                && (maxMemoryMb < ECLConfig.MIN_GAME_MEMORY_MB
                || maxMemoryMb > ECLConfig.MAX_GAME_MEMORY_MB)) {
            throw new IllegalArgumentException("Custom memory is outside the supported range");
        }
    }

    public static InstanceLaunchProfile defaults() {
        return new InstanceLaunchProfile(
                CURRENT_SCHEMA_VERSION,
                JavaMode.AUTO,
                "",
                MemoryMode.AUTO,
                ECLConfig.AUTO_MEMORY_MB,
                List.of());
    }

    public enum JavaMode {
        AUTO,
        CUSTOM
    }

    public enum MemoryMode {
        AUTO,
        CUSTOM
    }
}
