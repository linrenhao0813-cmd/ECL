package com.ecl.cli;

import com.ecl.config.SettingsManager;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "get", description = "Read a setting.")
final class SettingsGetCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Setting key")
    private String key;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        SettingsManager settings = CliEnvironment.loadSettings();
        String value = settings.getString(key, "");
        EclCli root = EclCli.root(spec);
        root.print(root.jsonOutput() ? Map.of("key", key, "value", value) : value);
        return 0;
    }
}
