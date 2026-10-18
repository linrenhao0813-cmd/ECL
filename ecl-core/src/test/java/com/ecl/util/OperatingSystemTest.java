package com.ecl.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void unknownPlatformsKeepTheHistoricalWindowsAssumption() {
        assertEquals("windows", OperatingSystem.minecraftName("Plan9"));
        assertEquals("windows", OperatingSystem.minecraftName(null));
        assertEquals("windows", OperatingSystem.minecraftName(""));
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
