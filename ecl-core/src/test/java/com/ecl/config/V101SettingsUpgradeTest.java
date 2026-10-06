package com.ecl.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic V1.0.1 data; never reads a user's settings. */
class V101SettingsUpgradeTest {
    @Test
    void preservesStartupSelectionDefaultsAndRetiredSettingsAcrossSave(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, """
                {"selectedVersion":"1.21.1-fabric","gameDir":"C:\\\\Minecraft",
                 "javaPath":"C:\\\\Java21\\\\bin\\\\javaw.exe","maxMemoryMb":4096,"jvmArgs":"-XX:+UseG1GC",
                 "downloadMaxConcurrent":1,"language":"zh-TW","theme":"LIGHT",
                 "curseforgeApiKey":"","versionCategory":"RELEASE"}
                """);
        SettingsManager manager = new SettingsManager(file.toFile());
        try {
            manager.load();
            assertEquals("1.21.1-fabric", manager.getString("selectedVersion", ""));
            assertEquals(4096, manager.getInt("maxMemoryMb", 0));
            assertEquals("-XX:+UseG1GC", manager.getString("jvmArgs", ""));
            assertEquals(1, manager.getInt("downloadMaxConcurrent", 0));
            assertEquals("zh-TW", manager.getString("language", ""));
            assertEquals("RELEASE", manager.getString("versionCategory2", ""));
            assertTrue(manager.save());
        } finally {
            manager.close();
        }
        SettingsManager reopened = new SettingsManager(file.toFile());
        try {
            reopened.load();
            assertEquals("C:\\Minecraft", reopened.getString("gameDir", ""));
            assertEquals("C:\\Java21\\bin\\javaw.exe", reopened.getString("javaPath", ""));
            assertEquals("1.21.1-fabric", reopened.getString("selectedVersion", ""));
            assertEquals("LIGHT", reopened.getString("theme", ""));
        } finally {
            reopened.close();
        }
    }
}
