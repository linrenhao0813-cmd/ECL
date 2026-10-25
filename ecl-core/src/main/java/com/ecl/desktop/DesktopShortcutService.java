package com.ecl.desktop;

import com.ecl.util.TextUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Creates Windows launcher shortcuts without adding a native library dependency. */
public final class DesktopShortcutService {
    private static final int MAX_POWERSHELL_OUTPUT_BYTES = 64 * 1024;
    private static final int POWERSHELL_TIMEOUT_SECONDS = 30;

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
            String output = runPowerShell(process);
            if (process.exitValue() != 0 || !Files.isRegularFile(shortcut)) {
                throw new IOException("PowerShell 创建快捷方式失败: " + output.trim());
            }
            return shortcut;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("创建快捷方式时被中断", error);
        } catch (IOException error) {
            return createScriptFallback(directory, name, target, argumentLine, error);
        }
    }

    /**
     * Waits for the helper PowerShell process with a hard timeout while draining its output in a
     * background thread, so a hung or chatty process cannot block the caller indefinitely.
     */
    private static String runPowerShell(Process process) throws IOException, InterruptedException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().name("ecl-shortcut-output").start(
                () -> drainBounded(process.getInputStream(), captured));
        try {
            if (!process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("PowerShell 创建快捷方式超时（" + POWERSHELL_TIMEOUT_SECONDS
                        + " 秒）");
            }
            drain.join(2_000);
            return capturedOutput(captured);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void drainBounded(InputStream input, ByteArrayOutputStream captured) {
        byte[] buffer = new byte[4096];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                int remaining = MAX_POWERSHELL_OUTPUT_BYTES + 1 - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(read, remaining));
                }
            }
        } catch (IOException ignored) {
            // The process ended or was destroyed; the captured prefix is still useful.
        }
    }

    private static String capturedOutput(ByteArrayOutputStream captured) {
        byte[] bytes = captured.toByteArray();
        String output = new String(bytes, 0, Math.min(bytes.length, MAX_POWERSHELL_OUTPUT_BYTES),
                StandardCharsets.UTF_8);
        return bytes.length > MAX_POWERSHELL_OUTPUT_BYTES ? output + "…(output truncated)" : output;
    }

    private Path createScriptFallback(Path directory, String name, Path target, String arguments,
                                     IOException originalError) throws IOException {
        Path script = directory.resolve(name + ".vbs");
        try {
            String command = target + arguments;
            if (command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) {
                throw new IOException("快捷方式参数包含换行");
            }
            // ShellExecute preserves literal percent signs; WSH requires a little-endian Unicode BOM.
            Files.writeString(script, "\ufeffCreateObject(\"Shell.Application\").ShellExecute \""
                    + target.toString().replace("\"", "\"\"") + "\", \""
                    + arguments.replace("\"", "\"\"") + "\", \"\", \"open\", 1\r\n", StandardCharsets.UTF_16LE);
            return script;
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
        String name = TextUtil.replaceInvalidFilenameChars(value == null ? "" : value.trim());
        if (name == null || name.isBlank() || "_".equals(name)) {
            throw new IOException("快捷方式名称无效");
        }
        return name;
    }

    private static String psLiteral(Path value) {
        return value.toString().replace("'", "''");
    }

    private static String psLiteral(String value) {
        return value.replace("'", "''");
    }

    static String quoteWindowsArgument(String value) {
        if (value.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isWhitespace(character) || character == '"') {
                needsQuotes = true;
                break;
            }
        }
        if (!needsQuotes) {
            return value;
        }
        StringBuilder quoted = new StringBuilder(value.length() + 8);
        quoted.append('"');
        int backslashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\') {
                backslashes++;
                continue;
            }
            if (character == '"') {
                quoted.repeat('\\', backslashes * 2 + 1).append('"');
                backslashes = 0;
                continue;
            }
            if (backslashes > 0) {
                quoted.repeat('\\', backslashes);
                backslashes = 0;
            }
            quoted.append(character);
        }
        quoted.repeat('\\', backslashes * 2);
        quoted.append('"');
        return quoted.toString();
    }

}
