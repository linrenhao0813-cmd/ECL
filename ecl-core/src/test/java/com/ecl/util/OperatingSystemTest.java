package com.ecl.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperatingSystemTest {
    @Test
    void reportsTheCurrentPlatformConsistently() {
        String name = OperatingSystem.minecraftName();
        assertTrue(name.equals("windows") || name.equals("osx") || name.equals("linux"),
                "Unexpected Minecraft platform name: " + name);
        assertEquals(OperatingSystem.isWindows(), "windows".equals(name));
        assertEquals(OperatingSystem.isMac(), "osx".equals(name));
        assertEquals(OperatingSystem.isLinux(), "linux".equals(name));
    }

    @Test
    void mapsRawOperatingSystemNamesToMinecraftNames() {
        assertEquals("windows", OperatingSystem.minecraftName("Windows 11"));
        assertEquals("windows", OperatingSystem.minecraftName("Windows 10"));
        assertEquals("osx", OperatingSystem.minecraftName("Mac OS X"));
        assertEquals("osx", OperatingSystem.minecraftName("Darwin"));
        assertEquals("linux", OperatingSystem.minecraftName("Linux"));
    }

    @Test
    void unknownPlatformsCannotSelectWindowsDownloads() {
        assertThrows(UnsupportedOperationException.class, () -> OperatingSystem.minecraftName("Plan9"));
        assertThrows(UnsupportedOperationException.class, () -> OperatingSystem.minecraftName(null));
        assertThrows(UnsupportedOperationException.class, () -> OperatingSystem.minecraftName(""));
    }

    @Test
    void darwinIsNotDetectedAsWindows() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Darwin");
            assertFalse(OperatingSystem.isWindows());
            assertEquals("java", OperatingSystem.javaExecutableName());
        } finally {
            System.setProperty("os.name", original);
        }
    }

    @Test
    void adoptiumNameReplacesOsxWithMac() {
        String adoptium = OperatingSystem.adoptiumName();
        assertFalse(adoptium.contains("osx"), "Adoptium expects 'mac', not 'osx'");
        assertEquals(OperatingSystem.minecraftName().replace("osx", "mac"), adoptium);
    }

    @Test
    void javaExecutableNameDependsOnThePlatform() {
        assertEquals(OperatingSystem.isWindows() ? "java.exe" : "java",
                OperatingSystem.javaExecutableName());
    }
}
