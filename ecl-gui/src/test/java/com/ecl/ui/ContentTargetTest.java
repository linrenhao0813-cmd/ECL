package com.ecl.ui;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTargetTest {
    @Test
    void categoriesKeepOrderCompatibilityAndInstanceDirectories() {
        var targets = ContentTarget.create(version -> new File("mods", version),
                version -> new File("instances", version));

        assertEquals(List.of("mod", "shader", "resourcepack", "modpack"),
                targets.stream().map(target -> target.projectType).toList());
        var mods = targets.getFirst();
        assertTrue(mods.usesLoader());
        assertTrue(mods.downloadDependencies);
        assertArrayEquals(new String[]{"fabric", "forge", "neoforge", "quilt"}, mods.loaders);
        assertArrayEquals(new String[]{".jar"}, mods.allowedExtensions);
        for (String version : List.of("one", "two")) {
            assertEquals(new File("mods", version), mods.folderResolver.apply(version));
            for (int index = 1; index < targets.size(); index++) {
                var target = targets.get(index);
                assertFalse(target.usesLoader());
                assertFalse(target.downloadDependencies);
                String folder = List.of("shaderpacks", "resourcepacks", "modpacks").get(index - 1);
                assertEquals(new File(new File("instances", version), folder), target.folderResolver.apply(version));
                assertArrayEquals(new String[]{index == 3 ? ".mrpack" : ".zip"}, target.allowedExtensions);
            }
        }
    }
}
