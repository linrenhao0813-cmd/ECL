package com.ecl.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a Loader installer JAR and converts process failures into launcher errors. */
final class InstallerProcessRunner {
    void run(String java, Path installer, Path minecraftDir, ModLoaderInstaller.Loader loader)
            throws IOException {
        run(java, installer, installer.getParent(),
                List.of(ModLoaderInstaller.installerArgument(loader), minecraftDir.toString()));
    }

    void run(String java, Path installer, Path workingDirectory, List<String> arguments) throws IOException {
        ServerInstaller.checkCancelled();
        List<String> command = new ArrayList<>(List.of(java, "-jar", installer.toString()));
        command.addAll(arguments);
        if (arguments.stream().noneMatch(argument -> argument.contains("\""))) {
            runCommand(command, workingDirectory);
            return;
        }
        // Java 9+ argument files preserve literal quotes across Windows CreateProcess parsing.
        // Quilt's Java 17+ CLI needs those quotes when it joins and reparses argv.
        Path argumentFile = Files.createTempFile(workingDirectory, ".ecl-installer-args-", ".txt");
        try {
            StringBuilder content = new StringBuilder();
            for (String argument : command.subList(1, command.size())) {
                if (argument.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid installer argument");
                content.append('"').append(argument.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\"\n");
            }
            Files.writeString(argumentFile, content, StandardCharsets.UTF_8);
            runCommand(List.of(java, "@" + argumentFile), workingDirectory);
        } finally {
            Files.deleteIfExists(argumentFile);
        }
    }

    private void runCommand(List<String> command, Path workingDirectory) throws IOException {
        ServerInstaller.checkCancelled();
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuffer output = new StringBuffer();
        Thread drain = Thread.ofVirtual().name("ecl-loader-installer-output").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 24_000) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // The installer process ended or its pipe broke; nothing meaningful left to read.
            }
        });
        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                terminate(process);
                throw new IOException("加载器安装器运行超过 10 分钟，已终止");
            }
            drain.join(Duration.ofSeconds(5));
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("加载器安装器退出码 " + exitCode + "："
                        + tail(output.toString(), 2_000));
            }
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("加载器安装被中断", e);
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String tail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxChars);
    }
}
