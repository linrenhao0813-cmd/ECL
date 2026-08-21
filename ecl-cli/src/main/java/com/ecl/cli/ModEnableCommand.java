package com.ecl.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "enable", description = "Enable a .jar.disabled mod file.")
final class ModEnableCommand extends InstanceModCommand implements Callable<Integer> {
    @Parameters(index = "1", description = "Mod filename")
    private String filename;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        Path source = ModFileToggle.safePath(modsDirectory(),
                filename.endsWith(".disabled") ? filename : filename + ".disabled");
        String enabledName = source.getFileName().toString().replaceFirst("\\.disabled$", "");
        Path target = ModFileToggle.safePath(modsDirectory(), enabledName);
        ModFileToggle.moveWithoutOverwrite(source, target);
        EclCli.root(spec).print(Map.of("version", versionId,
                "file", target.getFileName().toString(), "enabled", true));
        return 0;
    }
}
