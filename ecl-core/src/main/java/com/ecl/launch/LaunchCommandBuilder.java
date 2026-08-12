package com.ecl.launch;

import com.ecl.game.ArgumentToken;
import com.ecl.game.DownloadObject;
import com.ecl.game.Library;
import com.ecl.game.VersionMetadata;
import com.ecl.util.RuleEvaluator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the OS command that launches a Minecraft instance from {@link LaunchOptions} plus the
 * resolved {@link VersionMetadata}. Pure logic: no filesystem writes besides reading library
 * existence, which makes the ordering rules directly unit-testable.
 */
public final class LaunchCommandBuilder {

    /** Largest command line we attempt to hand to the OS before failing loudly. */
    private static final int MAX_COMMAND_LENGTH = 32_000; // Windows CREATE_NO_WINDOW practical limit

    /**
     * Build the full process command.
     *
     * @param javaExecutable absolute path of the JVM executable the launcher resolved for this
     *                       version; this is the value the process actually starts with
     */
    public LaunchCommand build(LaunchOptions options, VersionMetadata version, String javaExecutable)
            throws IOException {
        String mainClass = requireMainClass(options, version);
        Map<String, String> variables = LaunchVariables.of(options, version);
        List<String> arguments = buildArguments(options, version, mainClass, variables);
        arguments.addAll(gameArguments(version, variables));
        appendResolutionAndServer(options, arguments);

        int estimate = joinLength(javaExecutable, arguments);
        if (estimate > MAX_COMMAND_LENGTH) {
            throw new LaunchException(LaunchException.Kind.COMMAND_TOO_LONG,
                    "生成的启动命令超过系统长度限制，请减少 JVM 参数或改用手动启动。");
        }
        return new LaunchCommand(javaExecutable, arguments,
                options.gameDirectory(), environmentAdditions(options));
    }

    private Map<String, String> environmentAdditions(LaunchOptions options) {
        Map<String, String> additions = new java.util.HashMap<>();
        // The game reads %APPDATA% to locate its launcher profile manages; point it at the working
        // directory's parent so the instance owns its own configuration.
        File parent = options.gameDirectory() == null ? null : options.gameDirectory().getParentFile();
        if (parent != null) {
            additions.put("APPDATA", parent.getAbsolutePath());
        }
        return additions;
    }

    private List<String> buildArguments(LaunchOptions options, VersionMetadata version,
                                        String mainClass, Map<String, String> variables) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("-Xms" + options.minMemoryMb() + "m");
        args.add("-Xmx" + options.maxMemoryMb() + "m");
        if (options.processorCount() > 0) {
            args.add("-XX:ActiveProcessorCount=" + options.processorCount());
        }
        for (String userArgument : options.jvmArguments()) {
            if (!userArgument.isBlank()) {
                args.add(LaunchVariables.substitute(userArgument, variables));
            }
        }
        args.addAll(jvmArgumentsFromVersion(version, variables));
        args.add("-cp");
        args.add(buildClassPath(options, version));
        args.add(mainClass);
        return args;
    }

    private List<String> jvmArgumentsFromVersion(VersionMetadata version,
                                                 Map<String, String> variables) {
        List<String> args = new ArrayList<>();
        List<ArgumentToken> structured = version.arguments().jvm();
        for (ArgumentToken token : structured) {
            if (token instanceof ArgumentToken.Literal literal) {
                String argument = LaunchVariables.substitute(literal.value(), variables);
                if (!argument.isEmpty()) {
                    args.add(argument);
                }
            } else if (token instanceof ArgumentToken.Conditional conditional) {
                if (matchRules(conditional)) {
                    for (String value : conditional.values()) {
                        String argument = LaunchVariables.substitute(value, variables);
                        if (!argument.isEmpty()) {
                            args.add(argument);
                        }
                    }
                }
            }
        }
        if (args.isEmpty()) {
            for (String legacy : version.arguments().legacyJvmArguments()) {
                String argument = LaunchVariables.substitute(legacy, variables);
                if (!argument.isEmpty()) {
                    args.add(argument);
                }
            }
        }
        return args;
    }

    private List<String> gameArguments(VersionMetadata version,
                                       Map<String, String> variables) {
        List<String> args = new ArrayList<>();
        List<ArgumentToken> structured = version.arguments().game();
        for (ArgumentToken token : structured) {
            if (token instanceof ArgumentToken.Literal literal) {
                String argument = LaunchVariables.substitute(literal.value(), variables);
                if (!argument.isEmpty()) {
                    args.add(argument);
                }
            } else if (token instanceof ArgumentToken.Conditional conditional) {
                if (matchRules(conditional)) {
                    for (String value : conditional.values()) {
                        String argument = LaunchVariables.substitute(value, variables);
                        if (!argument.isEmpty()) {
                            args.add(argument);
                        }
                    }
                }
            }
        }
        if (args.isEmpty() && version.arguments().legacyMinecraftArguments() != null) {
            String[] legacy = version.arguments().legacyMinecraftArguments().trim().split("\\s+");
            for (String raw : legacy) {
                String argument = LaunchVariables.substitute(raw, variables);
                if (!argument.isEmpty()) {
                    args.add(argument);
                }
            }
        }
        return args;
    }

    private boolean matchRules(ArgumentToken.Conditional token) {
        return token.rules() == null || RuleEvaluator.isAllowed(token.rules());
    }

    private String buildClassPath(LaunchOptions options, VersionMetadata version) throws IOException {
        File librariesDir = options.environment().librariesDirectory();
        Set<String> classpath = new LinkedHashSet<>();
        for (Library library : version.libraries()) {
            DownloadObject artifact = library.artifact();
            if (artifact == null || !libraryAllowed(library)) {
                continue;
            }
            File file = new File(librariesDir, artifact.path());
            if (!file.exists()) {
                throw new LaunchException(LaunchException.Kind.MISSING_FILES,
                        "缺少依赖库: " + artifact.path() + "（版本 " + version.id() + "）");
            }
            classpath.add(file.getAbsolutePath());
        }

        File clientJar = new File(options.environment().versionsDirectory(),
                version.clientJarId() + "/" + version.clientJarId() + ".jar");
        if (!clientJar.exists()) {
            throw new LaunchException(LaunchException.Kind.MISSING_FILES,
                    "Missing client JAR for version " + version.id() + ": "
                            + clientJar.getAbsolutePath());
        }
        classpath.add(clientJar.getAbsolutePath());
        return String.join(File.pathSeparator, classpath);
    }

    private boolean libraryAllowed(Library library) {
        return library.raw() == null || !library.raw().has("rules")
                || RuleEvaluator.isAllowed(library.raw().getAsJsonArray("rules"));
    }

    private String requireMainClass(LaunchOptions options, VersionMetadata version)
            throws LaunchException {
        String mainClass = version == null ? "" : version.mainClass().trim();
        if (mainClass.isEmpty()) {
            throw new LaunchException(LaunchException.Kind.VERSION_INVALID,
                    "版本 JSON 的 mainClass 字段缺失或不是非空字符串: " + options.versionId());
        }
        return mainClass;
    }

    private void appendResolutionAndServer(LaunchOptions options, List<String> args) {
        args.add("--width");
        args.add(Integer.toString(options.gameWidth()));
        args.add("--height");
        args.add(Integer.toString(options.gameHeight()));
        if (options.fullscreen()) {
            args.add("--fullscreen");
        }
        ServerAddress server = ServerAddress.parse(options.serverAddress());
        if (server.hasServer()) {
            args.add("--server");
            args.add(server.host());
            if (server.port() != null) {
                args.add("--port");
                args.add(server.port().toString());
            }
        }
    }

    private int joinLength(String javaExecutable, List<String> arguments) {
        int length = (javaExecutable == null ? 4 : javaExecutable.length()) + 1;
        for (String argument : arguments) {
            length += argument.length() + 1;
        }
        return length;
    }
}