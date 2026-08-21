package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.runtime.DefaultJavaManager;
import com.ecl.runtime.JavaRuntimeInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "list", description = "List every detected Java runtime.")
final class JavaListCommand implements Callable<Integer> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        EclCli root = EclCli.root(spec);
        SettingsManager settings = CliEnvironment.loadSettings();
        List<JavaRuntimeInfo> runtimes = new DefaultJavaManager(
                settings.get(ECLConfig.KEY_JAVA_PATH)).detect();
        root.print(root.jsonOutput() ? Map.of("runtimes", runtimes) : runtimes);
        return runtimes.isEmpty() ? 2 : 0;
    }
}
