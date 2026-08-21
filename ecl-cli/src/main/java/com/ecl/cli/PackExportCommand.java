package com.ecl.cli;

import com.ecl.pack.DefaultPackService;
import com.ecl.pack.PackFormat;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "export",
        description = "Export an instance to ECL, MultiMC, CurseForge, or MRPACK format.")
final class PackExportCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Instance directory")
    private Path instance;

    @Parameters(index = "1", description = "Output archive")
    private Path output;

    @Option(names = "--minecraft", required = true, description = "Minecraft version")
    private String minecraftVersion;

    @Option(names = "--format", defaultValue = "ECL",
            description = "ECL, MULTIMC, CURSEFORGE, or MRPACK")
    private PackFormat format;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        Path exported = new DefaultPackService().exportInstance(
                instance, minecraftVersion, format, output);
        EclCli.root(spec).print(Map.of("format", format,
                "output", exported.toAbsolutePath().toString()));
        return 0;
    }
}
