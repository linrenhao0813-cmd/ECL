package com.ecl.game;

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
                InstanceLaunchProfile.MemoryMode.CUSTOM,
                8192,
                List.of("-Dfile.encoding=UTF-8", "-XX:+UseG1GC"));

        store.save(tempDir, expected);

        assertEquals(expected, store.load(tempDir));
        try (var files = Files.list(store.profileFile(tempDir).getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void readsLegacyProfileWithRetiredFieldsWithoutRewritingIt() throws Exception {
        InstanceLaunchProfileStore store = new InstanceLaunchProfileStore();
        Path file = store.profileFile(tempDir);
        Files.createDirectories(file.getParent());
        String legacyProfile = """
                {"schemaVersion":1,"javaMode":"AUTO","memoryMode":"CUSTOM","maxMemoryMb":4096,
                 "customJvmArguments":["-Dlegacy=true"],"performancePreset":"HIGH",
                 "generatedJvmOptions":false,"autoRepair":false,"backupPolicyId":"before-launch"}
                """;
        Files.writeString(file, legacyProfile);

        InstanceLaunchProfile loaded = store.load(tempDir);

        assertEquals(4096, loaded.maxMemoryMb());
        assertEquals(List.of("-Dlegacy=true"), loaded.customJvmArguments());
        assertEquals(legacyProfile, Files.readString(file));
        store.save(tempDir, loaded);
        assertEquals(loaded, store.load(tempDir));
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
                InstanceLaunchProfile.MemoryMode.AUTO, 0,
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InstanceLaunchProfile(
                1, InstanceLaunchProfile.JavaMode.AUTO, "",
                InstanceLaunchProfile.MemoryMode.CUSTOM, 256,
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InstanceLaunchProfile(
                1, InstanceLaunchProfile.JavaMode.AUTO, "",
                InstanceLaunchProfile.MemoryMode.AUTO, 0,
                List.of("-javaagent:evil.jar")));
    }
}
