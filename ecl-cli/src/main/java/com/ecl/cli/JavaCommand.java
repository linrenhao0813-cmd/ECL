package com.ecl.cli;

import picocli.CommandLine.Command;

@Command(name = "java", description = "Inspect Java runtimes.",
        subcommands = {JavaDetectCommand.class, JavaListCommand.class})
final class JavaCommand extends CommandGroupSupport {
}
