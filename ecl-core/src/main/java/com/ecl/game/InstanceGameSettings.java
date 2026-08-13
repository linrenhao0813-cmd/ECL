package com.ecl.game;

import java.nio.file.Path;

/** Per-instance run-directory decision stored alongside the instance data. */
public record InstanceGameSettings(boolean overridesRunningDirectory, String runningDirectory) {
    public static InstanceGameSettings inherited() {
        return new InstanceGameSettings(false, "");
    }

    public static InstanceGameSettings isolated() {
        return new InstanceGameSettings(true, "");
    }

    public static InstanceGameSettings custom(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("Custom run directory is required");
        }
        return new InstanceGameSettings(true, directory.toAbsolutePath().normalize().toString());
    }

    public boolean hasCustomDirectory() {
        return overridesRunningDirectory && runningDirectory != null && !runningDirectory.isBlank();
    }
}
