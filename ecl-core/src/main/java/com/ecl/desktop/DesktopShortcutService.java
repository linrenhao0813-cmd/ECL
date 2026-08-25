package com.ecl.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Creates Windows launcher shortcuts without adding a native library dependency. */
public final class DesktopShortcutService {
    private static final int MAX_POWERSHELL_OUTPUT_BYTES = 64 * 1024;
    public Path createDesktopShortcut(Path executable, String shortcutName, List<String> arguments)
            throws IOException {
        return createShortcut(desktopDirectory(), executable, shortcutName, arguments);
    }

    public Path createStartMenuShortcut(Path executable, String shortcutName, List<String> arguments)
            throws IOException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) throw new IOException("找不到 APPDATA 目录");
        return createShortcut(Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "ECL"),
                executable, shortcutName, arguments);
    }

    private Path createShortcut(Path directory, Path executable, String shortcutName,
                                List<String> arguments) throws IOException {
        Path target = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) throw new IOException("找不到 ECL.exe: " + target);
        String name = safeFileName(shortcutName);
        Files.createDirectories(directory);
        Path shortcut = directory.resolve(name + ".lnk");
        String argumentLine = arguments == null ? "" : arguments.stream()
                .filter(Objects::nonNull).map(DesktopShortcutService::quoteWindowsArgument)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right);
        try {
            String script = "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('"
                    + psLiteral(shortcut) + "');$s.TargetPath='" + psLiteral(target)
                    + "';$s.Arguments='" + psLiteral(argumentLine) + "';$s.Save()";
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-Command", script).redirectErrorStream(true).start();
            byte[] outputBytes = process.getInputStream()
                    .readNBytes(MAX_POWERSHELL_OUTPUT_BYTES + 1);
            String output = new String(outputBytes, 0,
                    Math.min(outputBytes.length, MAX_POWERSHELL_OUTPUT_BYTES), StandardCharsets.UTF_8);
            if (outputBytes.length > MAX_POWERSHELL_OUTPUT_BYTES) {
                output += "…(output truncated)";
            }
            if (process.waitFor() != 0 || !Files.isRegularFile(shortcut)) {
                throw new IOException("PowerShell 创建快捷方式失败: " + output.trim());
            }
            return shortcut;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("创建快捷方式时被中断", error);
        } catch (IOException error) {
            return createBatchFallback(directory, name, target, argumentLine, error);
        }
    }

    private Path createBatchFallback(Path directory, String name, Path target, String arguments,
                                     IOException originalError) throws IOException {
        Path batch = directory.resolve(name + ".bat");
        try {
            String escapedTarget = escapeBatchCommand(target.toString());
            String escapedArguments = escapeBatchCommand(arguments);
            Files.writeString(batch, "@echo off\r\nsetlocal DisableDelayedExpansion\r\nstart \"\" \""
                            + escapedTarget + "\" " + escapedArguments + "\r\n",
                    StandardCharsets.UTF_8);
            return batch;
        } catch (IOException fallbackError) {
            fallbackError.addSuppressed(originalError);
            throw fallbackError;
        }
    }

    private Path desktopDirectory() throws IOException {
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile == null || userProfile.isBlank()) throw new IOException("找不到用户桌面目录");
        return Path.of(userProfile, "Desktop");
    }

    private static String safeFileName(String value) throws IOException {
        String name = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) throw new IOException("快捷方式名称无效");
        return name;
    }

    private static String psLiteral(Path value) {
        return value.toString().replace("'", "''");
    }

    private static String psLiteral(String value) {
        return value.replace("'", "''");
    }

    private static String quoteWindowsArgument(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9._-]+")) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    /** Escape text that is written directly into a cmd batch command. */
    static String escapeBatchCommand(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '%' -> escaped.append("%%");
                case '^', '&', '|', '<', '>', '(', ')' -> escaped.append('^').append(character);
                case '\r', '\n' -> escaped.append(' ');
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
