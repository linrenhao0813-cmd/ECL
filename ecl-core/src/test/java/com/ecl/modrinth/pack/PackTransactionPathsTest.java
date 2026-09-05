package com.ecl.modrinth.pack;

import com.ecl.ECLConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackTransactionPathsTest {
    @TempDir Path temp;
    private Field baseDirField;
    private File previousBaseDir;
    private Path instance;
    private Path profile;
    private PackTransactionPaths paths;

    @BeforeEach
    void setUp() throws Exception {
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, temp.resolve("ecl").toFile());
        instance = temp.resolve("instance");
        profile = ECLConfig.getVersionsDir().toPath().resolve("pack/pack.json");
        paths = new PackTransactionPaths(instance, profile);
        paths.validateRoots();
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        baseDirField.set(null, previousBaseDir);
    }

    @Test
    void journalTargetsRoundTripAcrossAllAllowedRoots() throws Exception {
        List<Path> targets = List.of(instance.resolve("config/example.json"), profile,
                ECLConfig.getVersionsDir().toPath().resolve("loader/loader.json"),
                ECLConfig.getLibrariesDir().toPath().resolve("loader/library.jar"));
        List<String> scopes = List.of("INSTANCE", "PROFILE", "VERSIONS", "LIBRARIES");
        for (int index = 0; index < targets.size(); index++) {
            Path target = paths.normalizeTarget(targets.get(index));
            PackTransactionPaths.Target encoded = paths.describeTarget(target);
            assertEquals(scopes.get(index), encoded.scope().name());
            assertEquals(target, paths.resolveTarget(encoded.scope().name(), encoded.relative()));
        }
        assertEquals("config/example.json", paths.describeTarget(targets.getFirst()).relative());
        assertEquals("", paths.describeTarget(profile).relative());
    }

    @Test
    void journalCannotEscapeRootsOrRedirectTheProfile() {
        for (String scope : List.of("INSTANCE", "VERSIONS", "LIBRARIES")) {
            assertThrows(IOException.class, () -> paths.resolveTarget(scope, "../outside.txt"));
        }
        assertThrows(IOException.class, () -> paths.resolveTarget("PROFILE", "other.json"));
        assertThrows(IOException.class, () -> paths.resolveTarget("UNKNOWN", "file.txt"));
        assertThrows(IOException.class, () -> paths.resolveTarget(null, "file.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> paths.normalizeTarget(instance.resolve("../outside.txt")));
    }

    @Test
    void onlySharedVersionAndLibraryDirectoriesAreExternalRoots() throws Exception {
        Path versions = ECLConfig.getVersionsDir().toPath();
        Path libraries = ECLConfig.getLibrariesDir().toPath();
        paths.validateExternalRoot(versions);
        paths.validateExternalRoot(libraries);
        assertThrows(IOException.class, () -> paths.validateExternalRoot(instance));
        assertThrows(IOException.class, () -> paths.validateExternalRoot(versions.resolve("nested")));
        assertThrows(IOException.class, () -> new PackTransactionPaths(instance,
                temp.resolve("outside.json")).validateRoots());
    }
}
