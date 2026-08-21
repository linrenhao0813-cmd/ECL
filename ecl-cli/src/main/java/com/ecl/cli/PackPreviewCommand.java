package com.ecl.cli;

import com.ecl.pack.DefaultPackService;
import com.ecl.pack.PackPreview;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "preview", description = "Inspect a pack without importing it.")
final class PackPreviewCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Pack archive")
    private Path archive;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        PackPreview preview = new DefaultPackService().preview(archive);
        EclCli.root(spec).print(preview);
        return 0;
    }
}
