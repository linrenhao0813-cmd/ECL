package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.util.JavaRuntimeUtil;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Inspect the Java runtime and ECL data directories.")
final class DoctorCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private EclCli parent;

    @Override
    public Integer call() {
        ECLConfig.ensureDirs();
        File baseDir = ECLConfig.getBaseDir();
        String javaExecutable = JavaRuntimeUtil.detectSystemJavaExecutable();
        Map<String, Object> result = new LinkedHashMap<>();
        boolean writable = Files.isWritable(baseDir.toPath());
        boolean healthy = javaExecutable != null && writable;
        result.put("status", healthy ? "ok" : "warning");
        result.put("java", javaExecutable == null ? "not-found" : javaExecutable);
        result.put("dataDirectory", baseDir.getAbsolutePath());
        result.put("dataDirectoryWritable", writable);
        result.put("os", System.getProperty("os.name"));
        result.put("architecture", System.getProperty("os.arch"));
        parent.print(result);
        return healthy ? 0 : 2;
    }
}
