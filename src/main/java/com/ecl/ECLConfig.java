package com.ecl;

import java.io.File;
import java.lang.management.ManagementFactory;

import com.ecl.util.PlatformUtil;

public class ECLConfig {
    public static final String LAUNCHER_NAME = "ECL";
    public static final String LAUNCHER_VERSION = "1.0.0";
    public static final String MC_VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String AUTH_SERVER_URL = "https://authserver.mojang.com";
    public static final int OFFICIAL_SOURCE_TIMEOUT_MS = 8000;
    public static final int MIRROR_SOURCE_TIMEOUT_MS = 60000;
    public static final int DOWNLOAD_THREADS = parseDownloadThreads();
    public static final int MAX_CAPTURED_GAME_LOG_CHARS = 80_000;

    /** A stored value of zero means that the launcher should calculate the heap automatically. */
    public static final int AUTO_MEMORY_MB = 0;
    public static final int DEFAULT_AUTO_MEMORY_MB = 2_048;
    public static final int MIN_GAME_MEMORY_MB = 512;
    public static final int MAX_GAME_MEMORY_MB = 65_536;
    public static final int MAX_AUTO_MEMORY_MB = 8_192;
    public static final int RESERVED_SYSTEM_MEMORY_MB = 2_048;

    public static final String SETTING_JAVA_PATH = "javaPath";
    public static final String SETTING_GAME_DIR = "gameDir";
    public static final String SETTING_JVM_ARGS = "jvmArgs";
    public static final String SETTING_MAX_MEMORY_MB = "maxMemoryMb";
    public static final String SETTING_MICROSOFT_REFRESH_TOKEN = "microsoftRefreshToken";
    public static final String SETTING_MICROSOFT_ACCESS_TOKEN = "microsoftAccessToken";
    public static final String SETTING_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT = "microsoftAccessTokenExpiresAt";
    public static final String SETTING_MICROSOFT_PROFILE_NAME = "microsoftProfileName";
    public static final String SETTING_MICROSOFT_PROFILE_UUID = "microsoftProfileUuid";

    private static volatile File baseDir;

    public static File getBaseDir() {
        File result = baseDir;
        if (result == null) {
            synchronized (ECLConfig.class) {
                result = baseDir;
                if (result == null) {
                    String userHome = System.getProperty("user.home");
                    if (PlatformUtil.isWindows()) {
                        String appData = System.getenv("APPDATA");
                        result = new File(appData == null || appData.isBlank() ? userHome : appData, ".ecl");
                    } else if (PlatformUtil.isMac()) {
                        result = new File(userHome, "Library/Application Support/.ecl");
                    } else {
                        result = new File(userHome, ".ecl");
                    }
                    baseDir = result;
                }
            }
        }
        return result;
    }

    public static File getVersionsDir() {
        return new File(getBaseDir(), "versions");
    }

    public static File getLibrariesDir() {
        return new File(getBaseDir(), "libraries");
    }

    public static File getAssetsDir() {
        return new File(getBaseDir(), "assets");
    }

    public static File getGameDir() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new File(getWindowsRoamingDir(), ".minecraft");
        } else if (os.contains("mac")) {
            return new File(System.getProperty("user.home"), "Library/Application Support/minecraft");
        }
        return new File(System.getProperty("user.home"), ".minecraft");
    }

    public static File getLegacyGameDir() {
        return new File(getBaseDir(), "game");
    }

    public static void ensureDirs() {
        getBaseDir().mkdirs();
        getVersionsDir().mkdirs();
        getLibrariesDir().mkdirs();
        getAssetsDir().mkdirs();
        getGameDir().mkdirs();
    }

    /**
     * Uses half of physical memory while retaining room for the OS, then rounds down to 256 MiB.
     */
    public static int calculateAutoMemoryMb() {
        long totalMemoryMb = getTotalPhysicalMemoryMb();
        if (totalMemoryMb <= 0) {
            return DEFAULT_AUTO_MEMORY_MB;
        }

        long proportional = totalMemoryMb / 2;
        long withSystemReserve = Math.max(MIN_GAME_MEMORY_MB, totalMemoryMb - RESERVED_SYSTEM_MEMORY_MB);
        long selected = Math.min(Math.min(proportional, withSystemReserve), MAX_AUTO_MEMORY_MB);
        selected = Math.max(MIN_GAME_MEMORY_MB, selected);
        return (int) Math.max(MIN_GAME_MEMORY_MB, (selected / 256) * 256);
    }

    private static long getTotalPhysicalMemoryMb() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean physicalMemoryBean) {
            return physicalMemoryBean.getTotalMemorySize() / (1024L * 1024L);
        }
        return -1;
    }

    private static int parseDownloadThreads() {
        int defaultValue = Math.min(8, Math.max(4, Runtime.getRuntime().availableProcessors()));
        String configured = System.getProperty("ecl.download.threads");
        if (configured == null || configured.isBlank()) return defaultValue;
        try {
            return Math.max(2, Math.min(16, Integer.parseInt(configured.trim())));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
