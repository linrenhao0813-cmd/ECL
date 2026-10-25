package com.ecl.util;

import java.util.Locale;

/**
 * Single source of truth for operating-system detection.
 *
 * <p>Values are recomputed on every call instead of being cached, so tests that substitute
 * {@code os.name} keep working. Supported platforms are Windows, macOS and Linux; unknown
 * platforms are rejected before selecting platform-specific downloads.</p>
 */
public final class OperatingSystem {
    private OperatingSystem() {
    }

    public static boolean isWindows() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return name.contains("win") && !name.contains("darwin");
    }

    public static boolean isMac() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return name.contains("mac") || name.contains("darwin");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** OS name used by Minecraft rules and native classifiers: {@code windows}, {@code osx} or {@code linux}. */
    public static String minecraftName() {
        return minecraftName(System.getProperty("os.name", ""));
    }

    static String minecraftName(String rawOsName) {
        String name = rawOsName == null ? "" : rawOsName.toLowerCase(Locale.ROOT);
        // Check macOS first: "darwin" contains the substring "win" and is not Windows.
        if (name.contains("mac") || name.contains("darwin")) {
            return "osx";
        }
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("linux")) {
            return "linux";
        }
        throw new UnsupportedOperationException("Unsupported operating system: " + rawOsName);
    }

    /** OS identifier expected by the Adoptium API: {@code windows}, {@code mac} or {@code linux}. */
    public static String adoptiumName() {
        String name = minecraftName();
        return "osx".equals(name) ? "mac" : name;
    }

    /** File name of the Java launcher executable on this platform. */
    public static String javaExecutableName() {
        return isWindows() ? "java.exe" : "java";
    }
}
