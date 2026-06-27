package com.ecl;

import java.io.File;

public class ECLConfig {
    public static final String LAUNCHER_NAME = "ECL";
    public static final String LAUNCHER_VERSION = "1.0.0";
    public static final String MC_VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String AUTH_SERVER_URL = "https://authserver.mojang.com";
    public static final int OFFICIAL_SOURCE_TIMEOUT_MS = 8000;
    public static final int MIRROR_SOURCE_TIMEOUT_MS = 60000;

    private static File baseDir;

    public static File getBaseDir() {
        if (baseDir == null) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                baseDir = new File(System.getenv("APPDATA"), ".ecl");
            } else if (os.contains("mac")) {
                baseDir = new File(System.getProperty("user.home"), "Library/Application Support/.ecl");
            } else {
                baseDir = new File(System.getProperty("user.home"), ".ecl");
            }
        }
        return baseDir;
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
        return new File(getBaseDir(), "game");
    }

    public static void ensureDirs() {
        getBaseDir().mkdirs();
        getVersionsDir().mkdirs();
        getLibrariesDir().mkdirs();
        getAssetsDir().mkdirs();
        getGameDir().mkdirs();
    }
}
