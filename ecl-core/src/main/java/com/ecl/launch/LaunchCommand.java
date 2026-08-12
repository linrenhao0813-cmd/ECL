package com.ecl.launch;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * An OS-directable process launch: the executable, the full argument list, the working directory,
 * and any environment additions. Produced by {@link LaunchCommandBuilder}.
 *
 * @param executable      absolute path to the JVM executable
 * @param arguments       complete argument vector (JVM flags, `-cp`, main class, game args)
 * @param workingDirectory directory the process starts in; null keeps the launcher's current dir
 * @param environment     environment variables to add/override, may be empty
 */
public record LaunchCommand(
        String executable,
        List<String> arguments,
        File workingDirectory,
        Map<String, String> environment) {

    /** Full command line in the order {@link ProcessBuilder} expects: executable first. */
    public List<String> commandLine() {
        List<String> command = new java.util.ArrayList<>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);
        return command;
    }

    public LaunchCommand {
        arguments = List.copyOf(arguments);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}