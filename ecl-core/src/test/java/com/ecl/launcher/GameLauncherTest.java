package com.ecl.launcher;

import com.ecl.ECLConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLauncherTest {
    @TempDir
    Path tempDir;

    private Field baseDirField;
    private File previousBaseDir;

    @BeforeEach
    void useTemporaryBaseDirectory() throws Exception {
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, tempDir.toFile());
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        baseDirField.set(null, previousBaseDir);
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

        JsonObject merged = loadVersion("child");

        assertEquals(3, merged.getAsJsonArray("libraries").size());
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

        IOException error = assertLoadFails("a");

        assertTrue(error.getMessage().contains("a -> b -> a"));
    }

    @Test
    void reportsAMissingParent() throws Exception {
        writeVersion("child", "{\"inheritsFrom\":\"missing\"}");

        IOException error = assertLoadFails("child");

        assertTrue(error.getMessage().contains("missing"));
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

        JsonObject merged = loadVersion("child");

        assertEquals(3, merged.getAsJsonArray("libraries").size());
        assertEquals("example:shared:2.0",
                merged.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:1.0:natives-windows",
                merged.getAsJsonArray("libraries").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:2.0:natives-windows-arm64",
                merged.getAsJsonArray("libraries").get(2).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void infersJavaForReleaseCandidatesAndSnapshots() throws Exception {
        assertEquals(21, inferRequiredJavaMajor("1.21-rc1"));
        assertEquals(21, inferRequiredJavaMajor("24w14potato"));
        assertEquals(17, inferRequiredJavaMajor("21w37a"));
        assertEquals(8, inferRequiredJavaMajor("20w14infinite"));
    }

    @Test
    void clientJarAppearsOnlyOnceInClasspath() throws Exception {
        Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve("1.21");
        Files.createDirectories(versionDirectory);
        Path clientJar = versionDirectory.resolve("1.21.jar");
        Files.write(clientJar, new byte[]{1});

        JsonObject versionJson = new JsonObject();
        versionJson.add("libraries", new JsonArray());
        GameLauncher launcher = new GameLauncher();
        launcher.setVersion("1.21");

        Method method = GameLauncher.class.getDeclaredMethod("buildClassPath", JsonObject.class);
        method.setAccessible(true);
        assertEquals(clientJar.toFile().getAbsolutePath(), method.invoke(launcher, versionJson));
    }

    @Test
    void appendsResolutionFullscreenServerAndProcessorOptions() throws Exception {
        GameLauncher launcher = new GameLauncher();
        launcher.setGameResolution(1600, 900);
        launcher.setFullscreen(true);
        launcher.setServerAddress("play.example.com:25570");
        launcher.setProcessorCount(6);

        Method serverMethod = GameLauncher.class.getDeclaredMethod(
                "appendServerArguments", List.class);
        serverMethod.setAccessible(true);
        List<String> arguments = new java.util.ArrayList<>();
        serverMethod.invoke(launcher, arguments);

        assertEquals(List.of("--server", "play.example.com", "--port", "25570"), arguments);
        Field width = GameLauncher.class.getDeclaredField("gameWidth");
        Field height = GameLauncher.class.getDeclaredField("gameHeight");
        Field fullscreen = GameLauncher.class.getDeclaredField("fullscreen");
        Field processors = GameLauncher.class.getDeclaredField("processorCount");
        width.setAccessible(true);
        height.setAccessible(true);
        fullscreen.setAccessible(true);
        processors.setAccessible(true);
        assertEquals(1600, width.getInt(launcher));
        assertEquals(900, height.getInt(launcher));
        assertTrue(fullscreen.getBoolean(launcher));
        assertEquals(6, processors.getInt(launcher));
    }

    @Test
    void mainClassMustBeANonBlankString() throws Exception {
        GameLauncher launcher = new GameLauncher();
        launcher.setVersion("invalid-main-class");

        JsonObject valid = new JsonObject();
        valid.addProperty("mainClass", "  example.Main  ");
        assertEquals("example.Main", launcher.requireMainClass(valid));

        assertThrows(IOException.class, () -> launcher.requireMainClass(new JsonObject()));

        JsonObject nullValue = new JsonObject();
        nullValue.add("mainClass", JsonNull.INSTANCE);
        assertThrows(IOException.class, () -> launcher.requireMainClass(nullValue));

        JsonObject numberValue = new JsonObject();
        numberValue.addProperty("mainClass", 123);
        assertThrows(IOException.class, () -> launcher.requireMainClass(numberValue));

        JsonObject objectValue = new JsonObject();
        objectValue.add("mainClass", new JsonObject());
        assertThrows(IOException.class, () -> launcher.requireMainClass(objectValue));

        JsonObject blankValue = new JsonObject();
        blankValue.addProperty("mainClass", "   ");
        assertThrows(IOException.class, () -> launcher.requireMainClass(blankValue));
    }

    @Test
    void extractsNormalNativeEntriesWithinSharedBudget() throws Exception {
        Path jar = writeJar("normal.jar", Map.of("bin/example.dll", new byte[]{1, 2, 3}));
        Path output = tempDir.resolve("normal-output");
        GameLauncher launcher = new GameLauncher();
        GameLauncher.ExtractionBudget budget = new GameLauncher.ExtractionBudget(
                new GameLauncher.ExtractionLimits(20, 10, 3));

        launcher.extractJar(jar.toFile(), output.toFile(), budget);

        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(output.resolve("bin/example.dll")));
    }

    @Test
    void rejectsAndRemovesAnOversizedNativeEntry() throws Exception {
        Path jar = writeJar("single-limit.jar", Map.of("large.dll", new byte[11]));
        Path output = tempDir.resolve("single-limit-output");
        GameLauncher launcher = new GameLauncher();
        GameLauncher.ExtractionBudget budget = new GameLauncher.ExtractionBudget(
                new GameLauncher.ExtractionLimits(100, 10, 3));

        assertThrows(IOException.class, () -> launcher.extractJar(jar.toFile(), output.toFile(), budget));
        assertFalse(Files.exists(output.resolve("large.dll")));
    }

    @Test
    void enforcesTotalNativeSizeAcrossMultipleJars() throws Exception {
        Path firstJar = writeJar("total-first.jar", Map.of("first.dll", new byte[8]));
        Path secondJar = writeJar("total-second.jar", Map.of("second.dll", new byte[8]));
        Path output = tempDir.resolve("total-output");
        GameLauncher launcher = new GameLauncher();
        GameLauncher.ExtractionBudget budget = new GameLauncher.ExtractionBudget(
                new GameLauncher.ExtractionLimits(12, 10, 4));

        launcher.extractJar(firstJar.toFile(), output.toFile(), budget);
        assertThrows(IOException.class,
                () -> launcher.extractJar(secondJar.toFile(), output.toFile(), budget));

        assertTrue(Files.exists(output.resolve("first.dll")));
        assertFalse(Files.exists(output.resolve("second.dll")));
    }

    @Test
    void enforcesNativeEntryCountAcrossMultipleJars() throws Exception {
        Path firstJar = writeJar("count-first.jar", Map.of("first.dll", new byte[]{1}));
        Path secondJar = writeJar("count-second.jar", Map.of("second.dll", new byte[]{2}));
        Path output = tempDir.resolve("count-output");
        GameLauncher launcher = new GameLauncher();
        GameLauncher.ExtractionBudget budget = new GameLauncher.ExtractionBudget(
                new GameLauncher.ExtractionLimits(100, 50, 1));

        launcher.extractJar(firstJar.toFile(), output.toFile(), budget);
        assertThrows(IOException.class,
                () -> launcher.extractJar(secondJar.toFile(), output.toFile(), budget));
        assertFalse(Files.exists(output.resolve("second.dll")));
    }

    @Test
    void nativeFingerprintDetectsContentChangesWithIdenticalMetadata() throws Exception {
        Path nativeJar = tempDir.resolve("native.jar");
        Files.writeString(nativeJar, "abc", StandardCharsets.UTF_8);
        long originalTimestamp = Files.getLastModifiedTime(nativeJar).toMillis();

        GameLauncher launcher = new GameLauncher();
        Method method = GameLauncher.class.getDeclaredMethod("buildNativesFingerprint", Iterable.class);
        method.setAccessible(true);
        String first = (String) method.invoke(launcher, java.util.List.of(nativeJar.toFile()));

        Files.writeString(nativeJar, "xyz", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(nativeJar, java.nio.file.attribute.FileTime.fromMillis(originalTimestamp));
        String second = (String) method.invoke(launcher, java.util.List.of(nativeJar.toFile()));

        assertNotEquals(first, second);
    }

    @Test
    void nativeMarkerDetectsCorruptedExtractedFilesWithIdenticalMetadata() throws Exception {
        Path nativesDirectory = tempDir.resolve("natives");
        Files.createDirectories(nativesDirectory);
        Path extractedLibrary = nativesDirectory.resolve("example.dll");
        Path marker = nativesDirectory.resolve(".ecl-natives-extracted");
        Files.writeString(extractedLibrary, "abc", StandardCharsets.UTF_8);
        long originalTimestamp = Files.getLastModifiedTime(extractedLibrary).toMillis();

        GameLauncher launcher = new GameLauncher();
        Method method = GameLauncher.class.getDeclaredMethod(
                "buildNativesMarker", String.class, Path.class, Path.class);
        method.setAccessible(true);
        String first = (String) method.invoke(launcher, "source", nativesDirectory, marker);

        Files.writeString(extractedLibrary, "xyz", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(extractedLibrary, java.nio.file.attribute.FileTime.fromMillis(originalTimestamp));
        String second = (String) method.invoke(launcher, "source", nativesDirectory, marker);

        assertNotEquals(first, second);
    }

    private void writeVersion(String id, String json) throws IOException {
        Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve(id);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    private Path writeJar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = tempDir.resolve(name);
        Map<String, byte[]> orderedEntries = new LinkedHashMap<>(entries);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : orderedEntries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private JsonObject loadVersion(String id) throws Exception {
        GameLauncher launcher = new GameLauncher();
        launcher.setVersion(id);
        Method loader = GameLauncher.class.getDeclaredMethod("loadVersionJsonWithInheritance");
        loader.setAccessible(true);
        return (JsonObject) loader.invoke(launcher);
    }

    private IOException assertLoadFails(String id) throws Exception {
        try {
            loadVersion(id);
            throw new AssertionError("Expected version loading to fail");
        } catch (InvocationTargetException e) {
            return assertInstanceOf(IOException.class, e.getCause());
        }
    }

    private int inferRequiredJavaMajor(String version) throws Exception {
        GameLauncher launcher = new GameLauncher();
        launcher.setVersion(version);
        Method method = GameLauncher.class.getDeclaredMethod("inferRequiredJavaMajorFromVersionId");
        method.setAccessible(true);
        return (int) method.invoke(launcher);
    }
}
