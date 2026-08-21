package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;

import java.io.File;

/** Loads launcher settings and resolves shared CLI paths. */
final class CliEnvironment {
    private CliEnvironment() {
    }

    static SettingsManager loadSettings() {
        SettingsManager settings = new SettingsManager();
        settings.load();
        return settings;
    }

    static File configuredGameRoot(SettingsManager settings) {
        String configured = settings.get(ECLConfig.KEY_GAME_DIR);
        return configured == null || configured.isBlank()
                ? ECLConfig.getGameDir() : new File(configured);
    }
}
