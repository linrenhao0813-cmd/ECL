package com.ecl.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Runs a Loader installer JAR and converts process failures into launcher errors. */
final class InstallerProcessRunner {
    void run(String java, Path installer, Path minecraftDir, ModLoaderInstaller.Loader loader)
            throws IOException {
        String installArgument = ModLoaderInstaller.installerArgument(loader);
        Process process = new ProcessBuilder(java, "-jar", installer.toString(),
                installArgument, minecraftDir.toString())
                .directory(installer.getParent().toFile())
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
            }
        });
        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("加载器安装器运行超过 10 分钟，已终止");
            }
            drain.join(Duration.ofSeconds(5));
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("加载器安装器退出码 " + exitCode + "："
                        + tail(output.toString(), 2_000));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("加载器安装被中断", e);
        }
    }

    private static String tail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxChars);
    }
}
