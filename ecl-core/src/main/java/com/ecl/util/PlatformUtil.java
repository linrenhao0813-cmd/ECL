package com.ecl.util;

import java.util.Locale;

public final class PlatformUtil {
    public enum OperatingSystem {
        WINDOWS("windows"), MACOS("osx"), LINUX("linux"), OTHER("unknown");

        private final String minecraftName;

        OperatingSystem(String minecraftName) {
            this.minecraftName = minecraftName;
        }

        public String minecraftName() {
            return minecraftName;
        }
    }

    private static final OperatingSystem CURRENT = detect(System.getProperty("os.name", ""));

    private PlatformUtil() {
    }

    public static OperatingSystem current() {
        return CURRENT;
    }

    public static boolean isWindows() {
        return CURRENT == OperatingSystem.WINDOWS;
    }

    public static boolean isMac() {
        return CURRENT == OperatingSystem.MACOS;
    }

    public static OperatingSystem detect(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) return OperatingSystem.MACOS;
        if (normalized.contains("win")) return OperatingSystem.WINDOWS;
        if (normalized.contains("linux") || normalized.contains("nix") || normalized.contains("nux")) {
            return OperatingSystem.LINUX;
        }
        return OperatingSystem.OTHER;
    }
}
