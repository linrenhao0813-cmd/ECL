package com.ecl.cli;

import com.ecl.config.SettingsManager;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "set", description = "Write a string setting.")
final class SettingsSetCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Setting key")
    private String key;

    @Parameters(index = "1", description = "Setting value")
    private String value;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        SettingsManager settings = CliEnvironment.loadSettings();
        settings.setString(key, value);
        if (!settings.save()) {
            throw new IOException("Unable to save settings");
        }
        EclCli root = EclCli.root(spec);
        root.print(root.jsonOutput()
                ? Map.of("key", key, "value", value, "saved", true) : "saved");
        return 0;
    }
}
