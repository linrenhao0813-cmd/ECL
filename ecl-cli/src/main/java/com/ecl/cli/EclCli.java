package com.ecl.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Command(
        name = "ecl",
        mixinStandardHelpOptions = true,
        version = "ECL 1.0.0",
        description = "ECL headless command line interface.",
        subcommands = {
                DoctorCommand.class,
                JavaCommand.class,
                VersionCommand.class,
                AccountCommand.class,
                LaunchCommand.class,
                ModCommand.class,
                PackCommand.class,
                DiagnosticsCommand.class,
                SettingsCommand.class
        }
)
public final class EclCli implements Runnable {
    @Option(names = "--json", scope = ScopeType.INHERIT,
            description = "Write machine-readable JSON.")
    boolean json;

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    public static int execute(String... args) {
        return commandLine().execute(args);
    }

    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new EclCli());
        commandLine.setExecutionExceptionHandler((failure, failedCommand, parseResult) -> {
            EclCli root = (EclCli) failedCommand.getCommandSpec().root().userObject();
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            if (root.json) {
                failedCommand.getOut().println(CliOutput.toJson(Map.of(
                        "status", "error",
                        "error", message,
                        "type", failure.getClass().getSimpleName())));
            } else {
                failedCommand.getErr().println("Error: " + message);
            }
            return failedCommand.getCommandSpec().exitCodeOnExecutionException();
        });
        return commandLine;
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    void print(Object value) {
        CliOutput.print(value, json);
    }

    boolean jsonOutput() {
        return json;
    }

    static EclCli root(CommandLine.Model.CommandSpec spec) {
        return (EclCli) spec.root().userObject();
    }

    /** Compatibility entry retained for package-level callers and tests. */
    static void moveModWithoutOverwrite(Path source, Path target) throws IOException {
        ModFileToggle.moveWithoutOverwrite(source, target);
    }
}
