package com.ecl.game;

import com.ecl.performance.PerformancePreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceLaunchProfileStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void initializesMissingProfileFromLegacySettingsOnce() throws Exception {
        AtomicReference<InstanceLaunchProfileStore.LegacyLaunchSettings> legacy =
                new AtomicReference<>(new InstanceLaunchProfileStore.LegacyLaunchSettings(
                        "C:\\Java 21\\bin\\javaw.exe", 6144,
                        "-Dmessage=\"hello world\" -XX:+UseG1GC"));
        InstanceLaunchProfileStore store = new InstanceLaunchProfileStore(legacy::get);

        InstanceLaunchProfile migrated = store.load(tempDir);

        assertEquals(InstanceLaunchProfile.JavaMode.CUSTOM, migrated.javaMode());
        assertEquals("C:\\Java 21\\bin\\javaw.exe", migrated.javaPath());
        assertEquals(InstanceLaunchProfile.MemoryMode.CUSTOM, migrated.memoryMode());
        assertEquals(6144, migrated.maxMemoryMb());
        assertEquals(List.of("-Dmessage=hello world", "-XX:+UseG1GC"),
                migrated.customJvmArguments());
        assertTrue(Files.isRegularFile(store.profileFile(tempDir)));

        legacy.set(InstanceLaunchProfileStore.LegacyLaunchSettings.defaults());
        assertEquals(migrated, store.load(tempDir));
    }

    @Test
    void roundTripsAllProfileFields() throws Exception {
        InstanceLaunchProfileStore store = new InstanceLaunchProfileStore();
        InstanceLaunchProfile expected = new InstanceLaunchProfile(
                1,
                InstanceLaunchProfile.JavaMode.CUSTOM,
                "C:\\JDK\\bin\\java.exe",
                PerformancePreset.HIGH,
                InstanceLaunchProfile.MemoryMode.CUSTOM,
                8192,
                false,
                List.of("-Dfile.encoding=UTF-8", "-XX:+UseG1GC"),
                false,
                "before-launch");

        store.save(tempDir, expected);

        assertEquals(expected, store.load(tempDir));
        try (var files = Files.list(store.profileFile(tempDir).getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsUnsupportedFutureSchemaWithoutOverwritingIt() throws Exception {
        InstanceLaunchProfileStore store = new InstanceLaunchProfileStore();
        Path file = store.profileFile(tempDir);
        Files.createDirectories(file.getParent());
        String futureProfile = "{\"schemaVersion\":2,\"javaMode\":\"AUTO\","
                + "\"memoryMode\":\"AUTO\",\"maxMemoryMb\":0}";
        Files.writeString(file, futureProfile);

        assertThrows(IOException.class, () -> store.load(tempDir));
        assertEquals(futureProfile, Files.readString(file));
    }

    @Test
    void validatesModeSpecificValues() {
        assertThrows(IllegalArgumentException.class, () -> new InstanceLaunchProfile(
                1, InstanceLaunchProfile.JavaMode.AUTO, "C:\\Java\\java.exe",
                PerformancePreset.BALANCED, InstanceLaunchProfile.MemoryMode.AUTO, 0,
                true, List.of(), true, "default"));
        assertThrows(IllegalArgumentException.class, () -> new InstanceLaunchProfile(
                1, InstanceLaunchProfile.JavaMode.AUTO, "",
                PerformancePreset.BALANCED, InstanceLaunchProfile.MemoryMode.CUSTOM, 256,
                true, List.of(), true, "default"));
        assertThrows(IllegalArgumentException.class, () -> new InstanceLaunchProfile(
                1, InstanceLaunchProfile.JavaMode.AUTO, "",
                PerformancePreset.BALANCED, InstanceLaunchProfile.MemoryMode.AUTO, 0,
                true, List.of("-javaagent:evil.jar"), true, "default"));
    }
}
