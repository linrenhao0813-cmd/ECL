package com.ecl.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModrinthDownloaderTest {
    @Test
    void detectsCircularDependencyChains() {
        Deque<String> path = new ArrayDeque<>();
        path.add("version-a");
        path.add("version-b");

        IOException error = assertThrows(IOException.class,
                () -> ModrinthDownloader.ensureNoDependencyCycle(path, "version-a"));

        assertTrue(error.getMessage().contains("version-a -> version-b -> version-a"));
    }
}
