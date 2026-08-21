package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "inspect", description = "Resolve inherited metadata for an installed version.")
final class VersionInspectCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Installed version id")
    private String versionId;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        VersionMetadata metadata = new VersionRepository(ECLConfig.getVersionsDir())
                .resolve(versionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", metadata.id());
        result.put("minecraftVersion", metadata.minecraftVersion());
        result.put("loader", metadata.modLoader() == null ? "vanilla" : metadata.modLoader());
        result.put("mainClass", metadata.mainClass());
        result.put("javaVersion", metadata.javaMajorVersion());
        result.put("libraries", metadata.libraries().size());
        EclCli.root(spec).print(result);
        return 0;
    }
}
