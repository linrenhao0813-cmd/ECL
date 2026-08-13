package com.ecl.game;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryAnalyzerTest {
    @Test
    void detectsLoaderAndVersionFromMavenCoordinate() {
        Library library = Library.parse(JsonParser.parseString("""
                {"name":"net.minecraftforge:fmlloader:47.2.0",
                 "downloads":{"artifact":{"path":"forge.jar","url":"https://example/forge.jar"}}}
                """));

        ModLoaderInfo info = LibraryAnalyzer.analyze(null, null, "", List.of(library));

        assertEquals("forge", info.id());
        assertEquals("47.2.0", info.version());
        assertEquals(ModLoaderInfo.DetectionSource.LIBRARY, info.source());
    }

    @Test
    void explicitMetadataWinsAndLocalHintIsPreserved() {
        Library library = Library.parse(JsonParser.parseString("""
                {"name":"example:private:1.0","hint":"local",
                 "downloads":{"artifact":{"path":"private.jar","url":"https://example/private.jar"}}}
                """));

        ModLoaderInfo info = LibraryAnalyzer.analyze("quilt", "0.27.1", "", List.of(library));

        assertEquals("quilt", info.id());
        assertEquals("0.27.1", info.version());
        assertTrue(library.isLocal());
        assertNull(LibraryAnalyzer.analyze(null, null, "net.minecraft.client.main.Main",
                List.of(library)));
    }
}
