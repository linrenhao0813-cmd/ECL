package com.ecl.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedPackPathPolicyTest {
    @Test
    void rejectsAliasesAndUserDataWithoutConsultingTheWorkingDirectory() {
        for (String path : List.of("mods/../saves/x", "./saves/x", "a/../.ecl/config/x",
                "mods/../logs/x", "saves./x", "logs /x", "mods\\a.jar", "C:/mods/a.jar", "", ".", "/mods/a.jar")) {
            assertThrows(IOException.class, () -> ManagedPackPathPolicy.requireManagedRelativePath(path), path);
        }
    }

    @Test
    void normalizesAllowedPaths() throws Exception {
        assertEquals(Path.of("mods/a.jar"), ManagedPackPathPolicy.requireManagedRelativePath("./mods/a.jar"));
        assertEquals(Path.of("config/pack.txt"), ManagedPackPathPolicy.requireManagedRelativePath("config/pack.txt"));
    }
}
