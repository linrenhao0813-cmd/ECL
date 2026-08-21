package com.ecl.cli;

import picocli.CommandLine.Command;

@Command(name = "settings", description = "Read or write launcher settings.",
        subcommands = {SettingsGetCommand.class, SettingsSetCommand.class})
final class SettingsCommand extends CommandGroupSupport {
}
