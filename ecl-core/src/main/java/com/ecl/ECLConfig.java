package com.ecl;

import java.io.File;
import java.lang.management.ManagementFactory;
import javax.management.JMException;
import javax.management.ObjectName;

import com.ecl.config.SettingKey;
public class ECLConfig {
    public static final String LAUNCHER_NAME = "ECL";
    public static final String LAUNCHER_VERSION = "1.0.1";
    public static final String VERSION_DOWNLOAD_COMPLETE_MARKER = ".ecl-download-complete";
    public static final String MC_VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
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

    public static final String DEFAULT_YGGDRASIL_SERVER = "https://littleskin.cn/api/yggdrasil/";

    // ---- Type-safe SettingKey constants ----
    public static final SettingKey<String> KEY_JAVA_PATH = new SettingKey<>("javaPath", String.class, "");
    public static final SettingKey<String> KEY_GAME_DIR = new SettingKey<>("gameDir", String.class, "");
    public static final SettingKey<String> KEY_JVM_ARGS = new SettingKey<>("jvmArgs", String.class, "");
    public static final SettingKey<Integer> KEY_MAX_MEMORY_MB = new SettingKey<>("maxMemoryMb", Integer.class, AUTO_MEMORY_MB);
    public static final SettingKey<String> KEY_MICROSOFT_REFRESH_TOKEN = new SettingKey<>("microsoftRefreshToken", String.class, "");
    public static final SettingKey<String> KEY_MICROSOFT_ACCESS_TOKEN = new SettingKey<>("microsoftAccessToken", String.class, "");
    public static final SettingKey<Long> KEY_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT = new SettingKey<>("microsoftAccessTokenExpiresAt", Long.class, 0L);
    public static final SettingKey<String> KEY_MICROSOFT_PROFILE_NAME = new SettingKey<>("microsoftProfileName", String.class, "");
    public static final SettingKey<String> KEY_MICROSOFT_PROFILE_UUID = new SettingKey<>("microsoftProfileUuid", String.class, "");
    public static final SettingKey<String> KEY_SELECTED_VERSION = new SettingKey<>("selectedVersion", String.class, "");
    public static final SettingKey<String> KEY_VERSION_CATEGORY = new SettingKey<>("versionCategory2", String.class, "FEATURED");
    public static final SettingKey<String> KEY_AUTH_TYPE = new SettingKey<>("authType", String.class, "OFFLINE");
    public static final SettingKey<String> KEY_USERNAME = new SettingKey<>("username", String.class, "");
    public static final SettingKey<String> KEY_YGGDRASIL_SERVER = new SettingKey<>("yggdrasilServer", String.class, DEFAULT_YGGDRASIL_SERVER);
    public static final SettingKey<String> KEY_MOD_RELEASE_CHANNEL =
            new SettingKey<>("modReleaseChannel", String.class, "RELEASE_AND_BETA");
    public static final SettingKey<String> KEY_CURSEFORGE_API_KEY =
            new SettingKey<>("curseForgeApiKey", String.class, "");
    public static final SettingKey<String> KEY_DEFAULT_ISOLATION_TYPE =
            new SettingKey<>("defaultIsolationType", String.class, "MODDED");
    public static final SettingKey<Integer> KEY_GAME_WIDTH =
            new SettingKey<>("gameWidth", Integer.class, 1280);
    public static final SettingKey<Integer> KEY_GAME_HEIGHT =
            new SettingKey<>("gameHeight", Integer.class, 720);
    public static final SettingKey<Boolean> KEY_GAME_FULLSCREEN =
            new SettingKey<>("gameFullscreen", Boolean.class, false);
    public static final SettingKey<String> KEY_QUICK_SERVER =
            new SettingKey<>("quickServer", String.class, "");
    public static final SettingKey<Boolean> KEY_CLOSE_AFTER_LAUNCH =
            new SettingKey<>("closeAfterLaunch", Boolean.class, false);
    public static final SettingKey<Integer> KEY_PROCESSOR_COUNT =
            new SettingKey<>("processorCount", Integer.class, 0);
    public static final SettingKey<Boolean> KEY_SHOW_GAME_CONSOLE =
            new SettingKey<>("showGameConsole", Boolean.class, true);
    public static final SettingKey<Boolean> KEY_BACKUP_ON_LAUNCH =
            new SettingKey<>("backupOnLaunch", Boolean.class, true);
    public static final SettingKey<Integer> KEY_BACKUP_KEEP_COUNT =
            new SettingKey<>("backupKeepCount", Integer.class, 10);
    public static final SettingKey<Boolean> KEY_BACKUP_INCLUDE_MODS =
            new SettingKey<>("backupIncludeMods", Boolean.class, false);
    public static final SettingKey<Integer> KEY_DOWNLOAD_MAX_CONCURRENT =
            new SettingKey<>("downloadMaxConcurrent", Integer.class, 2);
    /** A value of zero means unlimited download speed. */
    public static final SettingKey<Integer> KEY_DOWNLOAD_RATE_LIMIT_KB =
            new SettingKey<>("downloadRateLimitKb", Integer.class, 0);
    public static final SettingKey<String> KEY_LANGUAGE =
            new SettingKey<>("language", String.class, "zh-CN");
    public static final SettingKey<String> KEY_THEME =
            new SettingKey<>("theme", String.class, "LIGHT");
    public static final SettingKey<Boolean> KEY_FIRST_RUN_COMPLETED =
            new SettingKey<>("firstRunCompleted", Boolean.class, false);

    private static volatile File baseDir;

    public static File getBaseDir() {
        File result = baseDir;
        if (result == null) {
            synchronized (ECLConfig.class) {
                result = baseDir;
                if (result == null) {
                    String userHome = System.getProperty("user.home");
                    String appData = System.getenv("APPDATA");
                    result = new File(appData == null || appData.isBlank() ? userHome : appData, ".ecl");
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

    public static File getBackupsDir() {
        return new File(getBaseDir(), "backups");
    }

    public static File getGameDir() {
        return new File(getWindowsRoamingDir(), ".minecraft");
    }

    public static File getLegacyGameDir() {
        return new File(getBaseDir(), "game");
    }

    private static File getWindowsRoamingDir() {
        String appData = System.getenv("APPDATA");
        return appData == null || appData.isBlank()
                ? new File(System.getProperty("user.home"), "AppData/Roaming")
                : new File(appData);
    }

    public static void ensureDirs() {
        ensureDirectory(getBaseDir());
        ensureDirectory(getVersionsDir());
        ensureDirectory(getLibrariesDir());
        ensureDirectory(getAssetsDir());
        ensureDirectory(getBackupsDir());
        ensureDirectory(getGameDir());
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create ECL directory: " + directory);
        }
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
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean physicalMemoryBean) {
                return physicalMemoryBean.getTotalMemorySize() / (1024L * 1024L);
            }
            Object totalBytes = ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(new ObjectName("java.lang", "type", "OperatingSystem"),
                            "TotalPhysicalMemorySize");
            if (totalBytes instanceof Number num) {
                return num.longValue() / (1024L * 1024L);
            }
        } catch (JMException | RuntimeException ignored) {
            // Unsupported management extensions fall back to the conservative default below.
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
