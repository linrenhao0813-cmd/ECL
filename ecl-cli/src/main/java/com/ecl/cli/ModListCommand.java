package com.ecl.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "list", description = "List enabled and disabled mod files.")
final class ModListCommand extends InstanceModCommand implements Callable<Integer> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        Path mods = modsDirectory();
        List<Map<String, Object>> entries = new ArrayList<>();
        if (Files.isDirectory(mods)) {
            try (var stream = Files.list(mods)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jar")
                                || path.getFileName().toString().endsWith(".jar.disabled"))
                        .sorted()
                        .forEach(path -> entries.add(Map.of(
                                "file", path.getFileName().toString(),
                                "enabled", !path.getFileName().toString()
                                        .endsWith(".disabled"))));
            }
        }
        EclCli root = EclCli.root(spec);
        root.print(root.jsonOutput()
                ? Map.of("version", versionId, "mods", entries) : entries);
        return 0;
    }
}
