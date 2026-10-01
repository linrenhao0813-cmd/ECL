package com.ecl.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDisplayMetadataStoreTest {
    @TempDir
    Path tempDir;

    private InstanceDisplayMetadataStore store() {
        return new InstanceDisplayMetadataStore(tempDir.resolve("instances/display.json"));
    }

    @Test
    void missingFileLoadsAsEmpty() throws IOException {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void roundTripsNameFavoriteAndCover() throws IOException {
        InstanceDisplayMetadataStore store = store();
        Map<String, InstanceDisplayMetadata> entries = new LinkedHashMap<>();
        entries.put("survival", InstanceDisplayMetadata.empty("survival")
                .withDisplayName("生存世界")
                .withFavorite(true)
                .withCoverImage("C:/covers/survival.png"));

        store.save(entries);
        Map<String, InstanceDisplayMetadata> loaded = store().load();

        assertEquals(1, loaded.size());
        InstanceDisplayMetadata entry = loaded.get("survival");
        assertEquals("生存世界", entry.displayName());
        assertTrue(entry.favorite());
        assertEquals("C:/covers/survival.png", entry.coverImage());
    }

    @Test
    void entriesWithoutOverridesAreNotPersisted() throws IOException {
        InstanceDisplayMetadataStore store = store();
        Map<String, InstanceDisplayMetadata> entries = new LinkedHashMap<>();
        entries.put("plain", InstanceDisplayMetadata.empty("plain"));

        store.save(entries);

        assertTrue(store().load().isEmpty());
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void longNamesAreTruncatedAndBlankCoverIsDropped() {
        InstanceDisplayMetadata entry = InstanceDisplayMetadata.empty("instance")
                .withDisplayName("x".repeat(200))
                .withCoverImage("   ");

        assertEquals(InstanceDisplayMetadata.MAX_DISPLAY_NAME_LENGTH, entry.displayName().length());
        assertTrue(entry.coverImage().isEmpty());
    }

    @Test
    void effectiveNameFallsBackWhenNoCustomNameIsSet() {
        assertEquals("fallback", InstanceDisplayMetadata.empty("instance").effectiveName("fallback"));
        assertEquals("Custom", InstanceDisplayMetadata.empty("instance")
                .withDisplayName("Custom").effectiveName("fallback"));
    }

    @Test
    void unsafeProfileIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> InstanceDisplayMetadata.empty(""));
        assertThrows(IllegalArgumentException.class, () -> InstanceDisplayMetadata.empty("../escape"));
        assertThrows(IllegalArgumentException.class, () -> InstanceDisplayMetadata.empty("a/b"));
    }

    @Test
    void interiorRepeatedDotsRemainCompatibleWithExistingVersionIds() throws IOException {
        InstanceDisplayMetadata entry = InstanceDisplayMetadata.empty("pack..test").withFavorite(true);
        InstanceDisplayMetadataStore store = store();

        store.save(Map.of(entry.profileId(), entry));

        assertEquals(entry, store().load().get("pack..test"));
    }

    @Test
    void corruptEntriesAreSkippedInsteadOfFailingTheLoad() throws IOException {
        Path file = tempDir.resolve("instances/display.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"schemaVersion":1,"instances":{"../bad":{"displayName":"x"},
                 "good":{"displayName":"Good","favorite":true,"cover":""}}}
                """);

        Map<String, InstanceDisplayMetadata> loaded = new InstanceDisplayMetadataStore(file).load();

        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey("good"));
    }
}
