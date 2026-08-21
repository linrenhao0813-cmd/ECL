package com.ecl.cli;

import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.pack.DefaultPackService;
import com.ecl.pack.PackFormat;
import com.ecl.pack.PackImportResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "import", description = "Import a pack transactionally.")
final class PackImportCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Pack archive")
    private Path archive;

    @Option(names = "--name", description = "Preferred instance name")
    private String name;

    @Option(names = "--instances", description = "Instances root directory")
    private Path instancesRoot;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        File gameRoot = CliEnvironment.configuredGameRoot(CliEnvironment.loadSettings());
        DefaultPackService packService = new DefaultPackService();
        if (instancesRoot == null && packService.preview(archive).format() == PackFormat.MRPACK) {
            MrpackInstaller.InstallResult installed = new MrpackInstaller().install(
                    archive.toFile(), gameRoot, name, System.err::println);
            PackImportResult result = new PackImportResult(PackFormat.MRPACK,
                    installed.profileId(), installed.instanceDirectory(),
                    installed.downloadedFiles());
            EclCli.root(spec).print(result);
            return 0;
        }
        Path rootDirectory = instancesRoot == null
                ? gameRoot.toPath().resolve("versions") : instancesRoot;
        PackImportResult result = packService.importPack(archive, rootDirectory, name);
        EclCli.root(spec).print(result);
        return 0;
    }
}
