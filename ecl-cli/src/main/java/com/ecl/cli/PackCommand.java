package com.ecl.cli;

import picocli.CommandLine.Command;

@Command(name = "pack", description = "Preview, import, and export instance packs.",
        subcommands = {PackPreviewCommand.class, PackImportCommand.class,
                PackExportCommand.class})
final class PackCommand extends CommandGroupSupport {
}
