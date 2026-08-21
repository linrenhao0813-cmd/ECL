package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.diagnostic.DiagnosticBundleService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "diagnostics", description = "Export a redacted diagnostic ZIP.")
final class DiagnosticsCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Output ZIP")
    private Path output;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        SettingsManager settings = CliEnvironment.loadSettings();
        Path result = new DiagnosticBundleService().export(
                output, ECLConfig.getBaseDir().toPath(),
                CliEnvironment.configuredGameRoot(settings).toPath());
        EclCli.root(spec).print(Map.of("output", result.toString(), "redacted", true));
        return 0;
    }
}
