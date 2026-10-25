package com.ecl.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DesktopShortcutServiceTest {

    @Test
    void fallbackLaunchesUnicodePathsAndLiteralArguments(@TempDir Path directory) throws Exception {
        assumeTrue(com.ecl.util.OperatingSystem.isWindows());
        Path unicode = Files.createDirectory(directory.resolve("中文 空格&目录"));
        Path received = unicode.resolve("参数.txt");
        String argument = "中文 %PATH% & literal";
        String classPath = Path.of(ArgumentRecorder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String line = "-cp " + DesktopShortcutService.quoteWindowsArgument(classPath)
                + " " + ArgumentRecorder.class.getName() + " "
                + DesktopShortcutService.quoteWindowsArgument(received.toString()) + " "
                + DesktopShortcutService.quoteWindowsArgument(argument);
        var fallback = DesktopShortcutService.class.getDeclaredMethod("createScriptFallback",
                Path.class, String.class, Path.class, String.class, IOException.class);
        fallback.setAccessible(true);
        Path script = (Path) fallback.invoke(new DesktopShortcutService(), unicode, "启动入口",
                Path.of(System.getProperty("java.home"), "bin", "java.exe"), line, new IOException("COM unavailable"));
        Process process = script.toString().endsWith(".vbs")
                ? new ProcessBuilder("cscript.exe", "//nologo", script.toString()).redirectErrorStream(true).start()
                : new ProcessBuilder("cmd.exe", "/c", script.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(received) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(received), "fallback did not launch the requested executable");
        assertEquals(argument, Files.readString(received));
    }

    public static final class ArgumentRecorder {
        public static void main(String[] arguments) throws IOException {
            Path target = Path.of(arguments[0]);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, arguments[1], StandardCharsets.UTF_8);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    @Test
    void quotesWindowsArgumentsWithTrailingBackslashes() {
        assertEquals("plain", DesktopShortcutService.quoteWindowsArgument("plain"));
        assertEquals("C:\\foo\\bar", DesktopShortcutService.quoteWindowsArgument("C:\\foo\\bar"));
        assertEquals("C:\\foo\\bar\\", DesktopShortcutService.quoteWindowsArgument("C:\\foo\\bar\\"));
        assertEquals("\"C:\\Program Files\\foo\\\\\"",
                DesktopShortcutService.quoteWindowsArgument("C:\\Program Files\\foo\\"));
        assertEquals("\"hello\\\"world\"",
                DesktopShortcutService.quoteWindowsArgument("hello\"world"));
        assertEquals("\"\"", DesktopShortcutService.quoteWindowsArgument(""));
        assertEquals("\"Program Files\"",
                DesktopShortcutService.quoteWindowsArgument("Program Files"));
    }
}
