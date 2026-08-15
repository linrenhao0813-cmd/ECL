package com.ecl.game;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LibraryTest {

    private static Library parse(String json) {
        return Library.parse(JsonParser.parseString(json));
    }

    @Test
    void resolvesBareMavenCoordinateLibrary() {
        Library library = parse("""
                {"name":"net.fabricmc:fabric-loader:0.19.3","url":"https://maven.fabricmc.net/"}
                """);
        assertNotNull(library);
        assertEquals("net.fabricmc:fabric-loader:0.19.3", library.name());
        DownloadObject artifact = library.artifact();
        assertNotNull(artifact);
        assertEquals("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar", artifact.path());
        assertEquals("fabric-loader-0.19.3.jar", artifact.fileName());
        assertEquals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar",
                artifact.url());
    }

    @Test
    void resolvesMavenCoordinateWithoutTrailingSlashInRepositoryUrl() {
        Library library = parse("""
                {"name":"org.ow2.asm:asm:9.10.1","url":"https://maven.fabricmc.net"}
                """);
        assertNotNull(library);
        assertEquals("org/ow2/asm/asm/9.10.1/asm-9.10.1.jar", library.artifact().path());
        assertEquals("https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
                library.artifact().url());
    }

    @Test
    void dropsCoordinateLibraryWithoutRepositoryUrl() {
        assertNull(parse("""
                {"name":"net.fabricmc:fabric-loader:0.19.3"}
                """));
    }

    @Test
    void dropsNonCoordinateLibraryWithoutDownloads() {
        assertNull(parse("""
                {"name":"net.fabricmc:fabric-loader:0.19.3:classifier","url":"https://maven.fabricmc.net/"}
                """));
        assertNull(parse("""
                {"url":"https://maven.fabricmc.net/"}
                """));
    }

    @Test
    void stillParsesMojangStyleDownloads() {
        Library library = parse("""
                {"name":"a.b:c:1.0","downloads":{"artifact":{"path":"a/b/c/1.0/c-1.0.jar",
                "url":"https://example.com/a/b/c/1.0/c-1.0.jar","sha1":"abc","size":10}}}
                """);
        assertNotNull(library);
        assertEquals("a/b/c/1.0/c-1.0.jar", library.artifact().path());
        assertEquals("https://example.com/a/b/c/1.0/c-1.0.jar", library.artifact().url());
    }
}
