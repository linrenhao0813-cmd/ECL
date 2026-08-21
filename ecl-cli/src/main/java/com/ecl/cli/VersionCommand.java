package com.ecl.cli;

import picocli.CommandLine.Command;

@Command(name = "version", description = "Manage installed game versions.",
        subcommands = {VersionListCommand.class, VersionInspectCommand.class})
final class VersionCommand extends CommandGroupSupport {
}
