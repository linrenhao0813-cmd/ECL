package com.ecl.launcher;

import com.ecl.ECLConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
                  {"name":"example:native:2.0:natives-linux"}
                ]}
                """);

        JsonObject merged = loadVersion("child");

        assertEquals(3, merged.getAsJsonArray("libraries").size());
        assertEquals("example:shared:2.0",
                merged.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:1.0:natives-windows",
                merged.getAsJsonArray("libraries").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("example:native:2.0:natives-linux",
                merged.getAsJsonArray("libraries").get(2).getAsJsonObject().get("name").getAsString());
    }

    private void writeVersion(String id, String json) throws IOException {
        Path versionDirectory = ECLConfig.getVersionsDir().toPath().resolve(id);
        Files.createDirectories(versionDirectory);
        Files.writeString(versionDirectory.resolve(id + ".json"), json, StandardCharsets.UTF_8);
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
}
