package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;

/** Resolves the mods directory shared by local mod commands. */
abstract class InstanceModCommand {
    @Parameters(index = "0", description = "Installed version id")
    protected String versionId;

    protected Path modsDirectory() {
        SettingsManager settings = CliEnvironment.loadSettings();
        DefaultGameRepository games = new DefaultGameRepository(
                ECLConfig.getVersionsDir().toPath(),
                CliEnvironment.configuredGameRoot(settings).toPath(),
                DefaultIsolationType.parse(
                        settings.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
        try {
            return games.runDirectory(versionId).resolve("mods");
        } catch (IOException error) {
            throw new IllegalStateException(
                    "无法解析实例运行目录: " + versionId, error);
        }
    }
}
