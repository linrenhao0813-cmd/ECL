package com.ecl.cli;

import com.ecl.ECLConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "list", description = "List locally installed game versions.")
final class VersionListCommand implements Callable<Integer> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        File[] directories = ECLConfig.getVersionsDir().listFiles(File::isDirectory);
        List<String> versions = directories == null
                ? List.of()
                : Arrays.stream(directories).map(File::getName).sorted().toList();
        EclCli root = EclCli.root(spec);
        root.print(root.jsonOutput() ? Map.of("versions", versions) : versions);
        return 0;
    }
}
