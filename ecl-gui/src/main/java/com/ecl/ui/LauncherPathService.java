package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import com.ecl.util.TextUtil;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Resolves the configured game root and isolated instance directories. */
final class LauncherPathService {
    private final LauncherUI ui;

    LauncherPathService(LauncherUI ui) {
        this.ui = ui;
    }

    File getConfiguredGameRootDir() {
        return ui.gameDir == null ? ECLConfig.getGameDir() : ui.gameDir;
    }

    File resolveConfiguredGameRootDir(File candidate) {
        if (candidate == null || candidate.getPath().isBlank()) {
            return ECLConfig.getGameDir();
        }
        if (isSamePath(candidate, ECLConfig.getLegacyGameDir())) {
            File defaultGameDir = ECLConfig.getGameDir();
            ui.settingsManager.set(ECLConfig.KEY_GAME_DIR, defaultGameDir.getAbsolutePath());
            ui.settingsManager.save();
            return defaultGameDir;
        }
        return candidate;
    }

    File getActiveGameDir() {
        return resolveVersionGameDir(ui.getSelectedVersion());
    }

    File resolveVersionGameDir(String gameVersion) {
        File rootDir = getConfiguredGameRootDir();
        if (gameVersion == null || gameVersion.isBlank()) {
            return rootDir;
        }
        try {
            return gameRepository().runDirectory(gameVersion).toFile();
        } catch (IOException error) {
            LauncherUI.LOGGER.warn(
                    "Cannot resolve run directory for {}; using isolated fallback", gameVersion, error);
            return resolveVersionInstanceRoot(gameVersion);
        }
    }

    File resolveVersionInstanceRoot(String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return getConfiguredGameRootDir();
        }
        return gameRepository().instanceRoot(sanitizeVersionDirectoryName(gameVersion)).toFile();
    }

    DefaultGameRepository gameRepository() {
        return new DefaultGameRepository(
                ECLConfig.getVersionsDir().toPath(), getConfiguredGameRootDir().toPath(),
                DefaultIsolationType.parse(
                        ui.settingsManager.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
    }

    void ensureDirectory(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
    }

    File resolveModsDir(String gameVersion) {
        return new File(resolveVersionGameDir(gameVersion), "mods");
    }

    static String loaderDisplayName(String loader) {
        if (loader == null) {
            return "原版";
        }
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> loader;
        };
    }

    private boolean isSamePath(File first, File second) {
        if (first == null || second == null) {
            return false;
        }
        String firstPath = first.getAbsoluteFile().toPath().normalize().toString();
        String secondPath = second.getAbsoluteFile().toPath().normalize().toString();
        return firstPath.equalsIgnoreCase(secondPath);
    }

    private String sanitizeVersionDirectoryName(String version) {
        String sanitized = TextUtil.replaceInvalidFilenameChars(version.trim());
        return sanitized.isBlank() ? "unknown-version" : sanitized;
    }
}
