package com.ecl.cli;

import com.ecl.util.JavaRuntimeUtil;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "detect", description = "Detect the best available Java executable.")
final class JavaDetectCommand implements Callable<Integer> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        String executable = JavaRuntimeUtil.detectSystemJavaExecutable();
        EclCli.root(spec).print(Map.of(
                "found", executable != null,
                "executable", executable == null ? "" : executable));
        return executable == null ? 2 : 0;
    }
}
