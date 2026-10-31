package com.ecl.game;

import com.ecl.launch.GameProcessMarker;
import com.ecl.util.InstanceOperationLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancePackServiceTest {
    @TempDir
    Path root;

    @Test
    void importsListsAndRemovesOnlyTheChosenPack() throws Exception {
        var service = new InstancePackService(root.resolve("game"), "resourcepacks");
        Path source = zip("example.zip");
        service.importPack(source);
        assertEquals(java.util.List.of("example.zip"), service.list().stream()
                .map(path -> path.getFileName().toString()).toList());
        assertThrows(IOException.class, () -> service.importPack(source));
        assertTrue(Files.exists(source));
        assertThrows(IOException.class, () -> service.remove("../example.zip"));
        service.remove("example.zip");
        assertTrue(service.list().isEmpty());
        assertTrue(Files.exists(source));
    }

    @Test
    void rejectsInvalidArchivesAndRunningOrLockedInstances() throws Exception {
        Path game = Files.createDirectories(root.resolve("game"));
        var service = new InstancePackService(game, "shaderpacks");
        Path bad = Files.writeString(root.resolve("bad.zip"), "invalid");
        assertThrows(IOException.class, () -> service.importPack(bad));
        Path source = zip("shader.zip");
        GameProcessMarker.record(game, ProcessHandle.current());
        try {
            assertThrows(IOException.class, () -> service.importPack(source));
        } finally {
            GameProcessMarker.clear(game, ProcessHandle.current());
        }
        service.importPack(source);
        try (var lock = InstanceOperationLease.tryAcquire(game)) {
            assertThrows(IOException.class, () -> service.remove("shader.zip"));
        }
        GameProcessMarker.record(game, ProcessHandle.current());
        try {
            assertThrows(IOException.class, () -> service.remove("shader.zip"));
        } finally {
            GameProcessMarker.clear(game, ProcessHandle.current());
        }
        assertEquals(1, service.list().size());
    }

    private Path zip(String name) throws IOException {
        Path source = root.resolve(name);
        try (var output = new ZipOutputStream(Files.newOutputStream(source))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return source;
    }
}
