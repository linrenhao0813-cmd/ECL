package com.ecl.game;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                  {"name":"example:native:2.0:natives-windows-arm64"}
                ]}
                """);

        JsonObject merged = repository().resolveRaw("child");

        assertEquals(3, merged.getAsJsonArray("libraries").size());
        assertEquals("example:shared:2.0",
                merged.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:1.0:natives-windows",
                merged.getAsJsonArray("libraries").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:2.0:natives-windows-arm64",
                merged.getAsJsonArray("libraries").get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void legacyForgeProfileKeepsParentPlatformNativeLibraries() throws Exception {
        writeVersion("1.7.10", """
                {"libraries":[{"name":"org.lwjgl.lwjgl:lwjgl:2.9.1",
                  "natives":{"windows":"natives-windows"},
                  "downloads":{
                    "artifact":{"url":"https://x/lwjgl.jar","path":"org/lwjgl/lwjgl/2.9.1/lwjgl.jar"},
                    "classifiers":{"natives-windows":{
                      "url":"https://x/lwjgl-natives-windows.jar",
                      "path":"org/lwjgl/lwjgl/2.9.1/lwjgl-natives-windows.jar"}}}}]}
                """);
        writeVersion("Forge10.13.4.1614", """
                {"inheritsFrom":"1.7.10","mainClass":"net.minecraft.launchwrapper.Launch",
                 "libraries":[{"name":"org.lwjgl.lwjgl:lwjgl:2.9.1-nightly-20140828",
                  "natives":{"linux":"natives-linux"},
                  "downloads":{"artifact":{"url":"https://x/lwjgl-nightly.jar",
                    "path":"org/lwjgl/lwjgl/2.9.1-nightly-20140828/lwjgl.jar"}}}]}
                """);

        VersionRepository repository = repository();
        VersionMetadata metadata = repository.resolve("Forge10.13.4.1614");

        List<Library> lwjglLibraries = metadata.libraries().stream()
                .filter(library -> library.name().startsWith("org.lwjgl.lwjgl:lwjgl:"))
                .toList();
        assertEquals(1, lwjglLibraries.size(), "the Forge profile overrides the same classpath slot");
        Library lwjgl = lwjglLibraries.getFirst();
        assertEquals("org.lwjgl.lwjgl:lwjgl:2.9.1-nightly-20140828", lwjgl.name(),
                "the Forge profile still wins the artifact version");
        assertEquals("org/lwjgl/lwjgl/2.9.1-nightly-20140828/lwjgl.jar", lwjgl.artifact().path());
        assertTrue(lwjgl.classifiers().containsKey("natives-windows"),
                "the parent Windows native classifier must survive the Forge profile override");
        assertEquals("natives-windows", lwjgl.natives().get("windows"),
                "the parent Windows natives mapping must survive the Forge profile override");
        assertEquals("natives-linux", lwjgl.natives().get("linux"),
                "the Forge profile's own natives mapping is kept");
    }

    @Test
    void bareChildCoordinateUsesItsOwnArtifactAndKeepsParentNatives() throws Exception {
        writeVersion("base", """
                {"libraries":[{"name":"example:lib:1","url":"https://repo.example/",
                  "natives":{"windows":"natives-windows"},
                  "downloads":{"artifact":{"path":"example/lib/1/lib-1.jar","sha1":"old-digest"},
                    "classifiers":{"natives-windows":{"path":"example/lib/1/native.jar"}}}}]}
                """);
        writeVersion("child", """
                {"inheritsFrom":"base","libraries":[{"name":"example:lib:2","url":"https://repo.example/"}]}
                """);

        VersionRepository repository = repository();
        Library library = repository.resolve("child").libraries().getFirst();

        assertEquals("example/lib/2/lib-2.jar", library.artifact().path());
        assertEquals("https://repo.example/example/lib/2/lib-2.jar", library.artifact().url());
        assertEquals("", library.artifact().sha1());
        assertTrue(library.classifiers().containsKey("natives-windows"));
        JsonObject raw = repository.resolveRaw("child").getAsJsonArray("libraries").get(0).getAsJsonObject();
        assertFalse(raw.getAsJsonObject("downloads").has("artifact"));
    }

    @Test
    void parsesTheLegacyClientLog4jLoggingConfigWithAFixedAssetsTarget() throws Exception {
        writeVersion("1.7.2", """
                {"mainClass":"net.minecraft.client.main.Main",
                 "assetIndex":{"id":"legacy","url":"https://example/legacy.json","sha1":"aa"},
                 "logging":{"client":{"argument":"-Dlog4j.configurationFile=${path}",
                   "file":{"id":"client-1.7.2.xml","sha1":"%s","size":%d,
                     "url":"https://launcher.example/client-1.7.2.xml"}}},
                 "downloads":{"client":{"url":"https://example/client.jar","path":"1.7.2/1.7.2.jar"}}}
                """.formatted("a".repeat(40), 1_507));

        ClientLoggingConfig logging = repository().resolve("1.7.2").clientLogging();

        assertNotNull(logging);
        assertEquals("-Dlog4j.configurationFile=${path}", logging.argument());
        assertEquals("log_configs/client-1.7.2.xml", logging.relativePath());
        assertEquals("client-1.7.2.xml", logging.file().fileName());
        assertEquals("a".repeat(40), logging.file().sha1());
        assertEquals(1_507L, logging.file().size());
        assertEquals("https://launcher.example/client-1.7.2.xml", logging.file().url());
        File assetsDir = new File(tempDir.toFile(), "assets");
        assertEquals(new File(assetsDir, "log_configs/client-1.7.2.xml"),
                logging.targetFile(assetsDir));
    }

    @Test
    void versionsWithoutLoggingExposeNoClientLoggingConfig() throws Exception {
        writeVersion("1.21", """
                {"mainClass":"net.minecraft.client.main.Main",
                 "downloads":{"client":{"url":"https://example/client.jar","path":"1.21/1.21.jar"}}}
                """);

        assertNull(repository().resolve("1.21").clientLogging());
    }

    @Test
    void rejectsUnsafeOrIncompleteLoggingConfigurations() throws Exception {
        String sha1 = "b".repeat(40);
        List<String> invalidDefinitions = List.of(
                // argument without the ${path} placeholder
                logging("{\"id\":\"client.xml\",\"sha1\":\"" + sha1
                        + "\",\"size\":10,\"url\":\"https://example/client.xml\"}", "-Dlog4j.configurationFile"),
                // argument with an additional unknown placeholder
                logging("{\"id\":\"client.xml\",\"sha1\":\"" + sha1
                        + "\",\"size\":10,\"url\":\"https://example/client.xml\"}",
                        "-Dlog4j.configurationFile=${path}${version_name}"),
                // file id that could escape the log_configs directory
                logging("{\"id\":\"../escape.xml\",\"sha1\":\"" + sha1
                        + "\",\"size\":10,\"url\":\"https://example/client.xml\"}",
                        "-Dlog4j.configurationFile=${path}"),
                // wrong SHA-1 length
                logging("{\"id\":\"client.xml\",\"sha1\":\"abc\",\"size\":10,"
                        + "\"url\":\"https://example/client.xml\"}",
                        "-Dlog4j.configurationFile=${path}"),
                // non-positive size
                logging("{\"id\":\"client.xml\",\"sha1\":\"" + sha1
                        + "\",\"size\":0,\"url\":\"https://example/client.xml\"}",
                        "-Dlog4j.configurationFile=${path}"),
                // plaintext remote URL
                logging("{\"id\":\"client.xml\",\"sha1\":\"" + sha1
                        + "\",\"size\":10,\"url\":\"http://example/client.xml\"}",
                        "-Dlog4j.configurationFile=${path}"));
        for (int index = 0; index < invalidDefinitions.size(); index++) {
            String versionId = "invalid-logging-" + index;
            writeVersion(versionId, invalidDefinitions.get(index));
            IOException error = assertThrows(IOException.class,
                    () -> repository().resolve(versionId), "definition #" + index + " must be rejected");
            assertNotNull(error.getMessage());
        }
    }

    private static String logging(String file, String argument) {
        return "{\"mainClass\":\"net.minecraft.client.main.Main\",\"logging\":{\"client\":{"
                + "\"argument\":\"" + argument + "\",\"file\":" + file + "}}}";
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
