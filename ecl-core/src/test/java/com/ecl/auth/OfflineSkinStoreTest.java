package com.ecl.auth;

import com.ecl.exception.AuthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineSkinStoreTest {

    private static Path writeSkin(Path directory, String name) throws IOException {
        Path skin = directory.resolve(name);
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        return skin;
    }

    @Test
    void importsFindsAndRemovesSkin(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        Path skin = writeSkin(directory, "skin.png");
        OfflineSkinStore store = new OfflineSkinStore(base);

        String identity = OfflineSkinStore.identityForOffline("Steve");
        assertTrue(store.find(identity).isEmpty(), "no skin before import");

        OfflineSkin imported = store.importSkin(identity, skin, MinecraftSkinService.Variant.SLIM);
        assertTrue(Files.isRegularFile(imported.pngFile()));
        assertTrue(imported.slim());

        Optional<OfflineSkin> found = store.find(identity);
        assertTrue(found.isPresent());
        assertEquals(MinecraftSkinService.Variant.SLIM, found.get().variant());
        assertArrayEquals(Files.readAllBytes(skin), Files.readAllBytes(found.get().pngFile()));

        // The imported copy must survive the original file being deleted
        Files.delete(skin);
        assertTrue(store.find(identity).isPresent(), "imported copy is independent of the source");

        assertTrue(store.remove(identity));
        assertTrue(store.find(identity).isEmpty());
        assertFalse(store.remove(identity), "second remove finds nothing");
    }

    @Test
    void replacingSkinKeepsOneEntry(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        OfflineSkinStore store = new OfflineSkinStore(base);
        String identity = OfflineSkinStore.identityForOffline("Alex");

        store.importSkin(identity, writeSkin(directory, "one.png"), MinecraftSkinService.Variant.CLASSIC);
        OfflineSkin replaced = store.importSkin(identity, writeSkin(directory, "two.png"),
                MinecraftSkinService.Variant.SLIM);

        assertEquals(MinecraftSkinService.Variant.SLIM, store.find(identity).get().variant());
        assertTrue(Files.isRegularFile(replaced.pngFile()));
    }

    @Test
    void identityIsStablePerName() {
        assertEquals(OfflineSkinStore.identityForOffline("Steve"),
                OfflineSkinStore.identityForOffline("Steve"));
        assertNotEquals(OfflineSkinStore.identityForOffline("Steve"),
                OfflineSkinStore.identityForOffline("Alex"));
    }

    @Test
    void rejectsInvalidPng(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        Path bad = directory.resolve("bad.png");
        Files.writeString(bad, "definitely not a png");

        OfflineSkinStore store = new OfflineSkinStore(base);
        assertThrows(IOException.class, () ->
                store.importSkin("OFFLINE:x", bad, MinecraftSkinService.Variant.CLASSIC));
    }

    @Test
    void corruptedIndexDegradesToNoSkinAndCanRecover(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        Files.writeString(base.resolve("skins.json"), "{broken-json");
        OfflineSkinStore store = new OfflineSkinStore(base);
        String identity = OfflineSkinStore.identityForOffline("Steve");

        assertTrue(store.find(identity).isEmpty());
        store.importSkin(identity, writeSkin(directory, "skin.png"),
                MinecraftSkinService.Variant.CLASSIC);

        assertTrue(store.find(identity).isPresent());
    }

    @Test
    void refusesIndexPathsOutsideSkinDirectory(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        Path secret = base.resolve("secret.png");
        Files.copy(writeSkin(directory, "secret-source.png"), secret);
        String identity = OfflineSkinStore.identityForOffline("Steve");
        Files.writeString(base.resolve("skins.json"), """
                {"%s":{"file":"secret.png","variant":"classic"}}
                """.formatted(identity));

        assertTrue(new OfflineSkinStore(base).find(identity).isEmpty());
    }

    @Test
    void normalizesIdentityAndRecordsSkinOwner(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        OfflineSkinStore store = new OfflineSkinStore(base);

        OfflineSkin imported = store.importSkin("offline:ABC", writeSkin(directory, "skin.png"),
                MinecraftSkinService.Variant.CLASSIC);

        assertEquals("OFFLINE:abc", imported.identity());
        assertEquals("OFFLINE:abc", store.find("OFFLINE:ABC").orElseThrow().identity());
    }

    @Test
    void rollsBackPngWhenIndexCannotBeWritten(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        Files.createDirectory(base.resolve("skins.json"));
        OfflineSkinStore store = new OfflineSkinStore(base);

        assertThrows(AuthException.class, () -> store.importSkin(
                "OFFLINE:x", writeSkin(directory, "skin.png"), MinecraftSkinService.Variant.CLASSIC));

        try (var files = Files.list(base.resolve("skins"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".png")));
        }
    }

    @Test
    void serializesUpdatesAcrossStoreInstances(@TempDir Path directory) throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        OfflineSkinStore first = new OfflineSkinStore(base);
        OfflineSkinStore second = new OfflineSkinStore(base);
        Path firstSkin = writeSkin(directory, "first.png");
        Path secondSkin = writeSkin(directory, "second.png");

        CompletableFuture<Void> firstImport = CompletableFuture.runAsync(() -> importUnchecked(
                first, "OFFLINE:first", firstSkin));
        CompletableFuture<Void> secondImport = CompletableFuture.runAsync(() -> importUnchecked(
                second, "OFFLINE:second", secondSkin));
        CompletableFuture.allOf(firstImport, secondImport).join();

        assertTrue(first.find("OFFLINE:first").isPresent());
        assertTrue(second.find("OFFLINE:second").isPresent());
    }

    private static void importUnchecked(OfflineSkinStore store, String identity, Path skin) {
        try {
            store.importSkin(identity, skin, MinecraftSkinService.Variant.CLASSIC);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
