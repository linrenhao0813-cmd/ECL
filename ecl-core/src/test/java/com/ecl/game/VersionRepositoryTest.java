package com.ecl.game;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionRepositoryTest {

    @TempDir
    Path tempDir;

    private VersionRepository repository() {
        return new VersionRepository(tempDir.toFile());
    }

    private void writeVersion(String id, String json) throws IOException {
        Path versionDirectory = Files.createDirectories(tempDir.resolve(id));
        Files.writeString(versionDirectory.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void recursivelyMergesLibrariesAndArguments() throws Exception {
        writeVersion("base", """
                {"mainClass":"base.Main","libraries":[{"name":"base"}],
                 "arguments":{"jvm":["base-jvm"],"game":["base-game"]}}
                """);
        writeVersion("middle", """
                {"inheritsFrom":"base","libraries":[{"name":"middle"}],
                 "arguments":{"game":["middle-game"]}}
                """);
        writeVersion("child", """
                {"inheritsFrom":"middle","mainClass":"child.Main","libraries":[{"name":"child"}],
                 "arguments":{"jvm":["child-jvm"]}}
                """);

        JsonObject merged = repository().resolveRaw("child");

        assertEquals("base", merged.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("middle", merged.getAsJsonArray("libraries").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("child", merged.getAsJsonArray("libraries").get(2).getAsJsonObject().get("name").getAsString());
        assertEquals(2, merged.getAsJsonObject("arguments").getAsJsonArray("jvm").size());
        assertEquals(2, merged.getAsJsonObject("arguments").getAsJsonArray("game").size());
        assertEquals("child.Main", merged.get("mainClass").getAsString());
        assertEquals("base", merged.get("jar").getAsString());
        assertFalse(merged.has("inheritsFrom"));
    }

    @Test
    void detectsCircularInheritance() throws Exception {
        writeVersion("a", "{\"inheritsFrom\":\"b\"}");
        writeVersion("b", "{\"inheritsFrom\":\"a\"}");

        IOException error = assertThrows(IOException.class, () -> repository().resolve("a"));

        assertTrue(error.getMessage().contains("a -> b -> a"), error.getMessage());
    }

    @Test
    void reportsAMissingParent() throws Exception {
        writeVersion("child", "{\"inheritsFrom\":\"missing\"}");

        IOException error = assertThrows(IOException.class, () -> repository().resolve("child"));

        assertTrue(error.getMessage().contains("missing"), error.getMessage());
    }

    @Test
    void childLibraryVersionReplacesTheParentClasspathSlot() throws Exception {
        writeVersion("base", """
                {"libraries":[
                  {"name":"example:shared:1.0"},
                  {"name":"example:native:1.0:natives-windows"}
                ]}
                """);
        writeVersion("child", """
                {"inheritsFrom":"base","libraries":[
                  {"name":"example:shared:2.0"},
                  {"name":"example:native:2.0:natives-linux"}
                ]}
                """);

        JsonObject merged = repository().resolveRaw("child");

        assertEquals(3, merged.getAsJsonArray("libraries").size());
        assertEquals("example:shared:2.0",
                merged.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:1.0:natives-windows",
                merged.getAsJsonArray("libraries").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:2.0:natives-linux",
                merged.getAsJsonArray("libraries").get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void typedMetadataResolvesInheritedBaseParts() throws Exception {
        writeVersion("1.21", """
                {"mainClass":"net.minecraft.client.main.Main",
                 "javaVersion":{"majorVersion":21},
                 "assets":"1.21",
                 "assetIndex":{"id":"1.21","url":"https://example/index.json","sha1":"aa"},
                 "downloads":{"client":{"url":"https://example/client.jar","path":"1.21/1.21.jar","sha1":"bb"}},
                 "libraries":[{"name":"org.example:dep:1.0","downloads":{"artifact":{"url":"https://x/dep.jar","path":"org/example/dep/1.0/dep.jar"}}}],
                 "arguments":{"game":["--username","${auth_player_name}"]}}
                """);
        writeVersion("fabric-1.21", """
                {"id":"fabric-1.21","inheritsFrom":"1.21",
                 "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                 "eclModLoader":"fabric","eclModLoaderVersion":"0.16.10",
                 "eclMinecraftVersion":"1.21",
                 "arguments":{"jvm":["-Dfabric.skipMcProvider=true"]}}
                """);

        VersionMetadata profile = repository().resolve("fabric-1.21");

        assertEquals("fabric-1.21", profile.id());
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", profile.mainClass());
        assertEquals("1.21", profile.clientJarId());
        assertEquals("1.21", profile.minecraftVersion());
        assertEquals("fabric", profile.modLoader());
        assertEquals("0.16.10", profile.modLoaderVersion());
        assertEquals(ModLoaderInfo.DetectionSource.EXPLICIT, profile.modLoaderInfo().source());
        assertEquals(21, profile.javaMajorVersion());
        assertEquals(1, profile.arguments().jvm().size(), "loader JVM arguments merge in");
        assertEquals(2, profile.arguments().game().size(), "base game arguments are inherited");
    }

    @Test
    void typedClientJarDefaultsToBaseVersionWithoutExplicitJar() throws Exception {
        writeVersion("1.21", """
                {"mainClass":"net.minecraft.client.main.Main",
                 "downloads":{"client":{"url":"https://example/client.jar","path":"1.21/1.21.jar"}}}
                """);
        writeVersion("loader", """
                {"inheritsFrom":"1.21","mainClass":"some.LoaderMain"}
                """);

        VersionMetadata metadata = repository().resolve("loader");

        assertEquals("1.21", metadata.clientJarId());
        assertEquals("1.21", metadata.minecraftVersion());
        assertEquals("some.LoaderMain", metadata.mainClass());
    }

    @Test
    void plainReleaseKeepsOwnIdentity() throws Exception {
        writeVersion("1.21", """
                {"mainClass":"net.minecraft.client.main.Main",
                 "downloads":{"client":{"url":"https://example/client.jar","path":"1.21/1.21.jar"}}}
                """);

        VersionMetadata metadata = repository().resolve("1.21");

        assertEquals("1.21", metadata.clientJarId());
        assertEquals("1.21", metadata.minecraftVersion());
        assertEquals("net.minecraft.client.main.Main", metadata.mainClass());
        assertEquals(0, metadata.javaMajorVersion());
    }

    @Test
    void typedModelKeepsDownloadableLibrariesAndDropsBareEntries() throws Exception {
        writeVersion("1.21", """
                {"libraries":[
                  {"name":"plain:bare:1.0"},
                  {"name":"org.example:dep:1.0","downloads":{"artifact":{"url":"https://x/dep.jar","path":"org/example/dep/1.0/dep.jar","sha1":"cc"}}}
                ]}
                """);

        VersionMetadata metadata = repository().resolve("1.21");

        assertEquals(1, metadata.libraries().size());
        assertEquals("org.example:dep:1.0", metadata.libraries().get(0).name());
        assertEquals("org/example/dep/1.0/dep.jar", metadata.libraries().get(0).artifact().path());
        assertTrue(metadata.libraries().get(0).artifact().hasChecksum());
    }

    @Test
    void missingVersionThrowsVersionChainException() throws Exception {
        VersionRepository repository = repository();
        IOException error = assertThrows(IOException.class, () -> repository.resolve("nope"));
        assertInstanceOf(VersionChainException.class, error);
    }

    @Test
    void rejectsVersionIdsThatCouldEscapeTheVersionsDirectory() {
        VersionRepository repository = repository();
        assertThrows(IOException.class, () -> repository.resolve("../outside"));
        assertThrows(IOException.class, () -> repository.resolve("..\\outside"));
    }

    @Test
    void invalidateForcesReReadAfterMetadataChanges() throws Exception {
        writeVersion("1.21", """
                {"mainClass":"first.Main","downloads":{"client":{"url":"https://example/a.jar","path":"1.21/1.21.jar"}}}
                """);
        VersionRepository repository = repository();
        assertEquals("first.Main", repository.resolve("1.21").mainClass());

        writeVersion("1.21", """
                {"mainClass":"second.Main","downloads":{"client":{"url":"https://example/b.jar","path":"1.21/1.21.jar"}}}
                """);
        assertEquals("first.Main", repository.resolve("1.21").mainClass(), "cached value is served until invalidated");
        repository.invalidate("1.21");
        assertEquals("second.Main", repository.resolve("1.21").mainClass());
    }

    @Test
    void nativeClassifiersAreExposedOnTypedLibraries() throws Exception {
        writeVersion("1.21", """
                {"libraries":[{"name":"org.lwjgl:lwjgl:3.3.1",
                  "natives":{"windows":"natives-windows"},
                  "downloads":{
                    "artifact":{"url":"https://x/lwjgl.jar","path":"lwjgl/3.3.1/lwjgl.jar"},
                    "classifiers":{
                      "natives-windows":{"url":"https://x/natives.jar","path":"lwjgl/natives/natives-windows.jar"}
                    }}}]}
                """);

        VersionMetadata metadata = repository().resolve("1.21");
        Library library = metadata.libraries().get(0);

        assertTrue(library.hasNatives());
        assertEquals("natives-windows", library.natives().get("windows"));
        assertEquals("natives-windows",
                library.classifiers().keySet().iterator().next());
    }
}
