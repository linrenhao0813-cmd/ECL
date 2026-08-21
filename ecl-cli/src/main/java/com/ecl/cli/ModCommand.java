package com.ecl.cli;

import picocli.CommandLine.Command;

@Command(name = "mod", description = "Manage local instance mods.",
        subcommands = {ModListCommand.class, ModEnableCommand.class, ModDisableCommand.class})
final class ModCommand extends CommandGroupSupport {
}
