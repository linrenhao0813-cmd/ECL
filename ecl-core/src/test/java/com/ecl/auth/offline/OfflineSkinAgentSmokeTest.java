package com.ecl.auth.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test: downloads the real authlib-injector jar, starts the local skin server,
 * and launches a child JVM with the agent attached. If the server metadata is incompatible,
 * authlib-injector logs an error line, which fails the assertion.
 *
 * <p>Network-dependent: skipped when the download sources are unreachable.</p>
 */
class OfflineSkinAgentSmokeTest {

    @TempDir
    Path directory;

    @Test
    void agentStartsCleanlyAgainstLocalServer() throws Exception {
        Path base = Files.createDirectories(directory.resolve("data"));
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());

        AuthlibInjectorManager manager = new AuthlibInjectorManager(base.resolve("authlib-injector.jar"));
        Path jar;
        try {
            jar = manager.ensureJar();
        } catch (IOException unavailable) {
            Assumptions.assumeTrue(false,
                    "authlib-injector download unavailable; skipping agent smoke test: " + unavailable.getMessage());
            return;
        }
        assertTrue(Files.isRegularFile(base.resolve("authlib-injector.jar.sha256")),
                "verified downloads persist their SHA-256 sidecar");

        OfflineSkinServer server = new OfflineSkinServer();
        try {
            server.registerCharacter("0123456789abcdef0123456789abcdef", "Steve", skin, false);

            Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            ProcessBuilder pb = new ProcessBuilder(
                    javaExecutable.toString(),
                    "-javaagent:" + jar.toAbsolutePath() + "=" + server.baseUrl(),
                    "-Dauthlibinjector.side=client",
                    "-cp", System.getProperty("java.class.path"),
                    AgentProbe.class.getName());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var input = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.append(new String(buffer, 0, read,
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                }
            });
            reader.start();

            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            boolean exited = false;
            while (Instant.now().isBefore(deadline) && !exited) {
                exited = process.waitFor(500, TimeUnit.MILLISECONDS);
            }
            if (!exited) {
                process.destroyForcibly();
            }
            reader.join(3000);

            String log = output.toString();
            assertFalse(log.toLowerCase().contains("[error]"),
                    "authlib-injector reported an error against the local server:\n" + log);
            assertTrue(log.contains("authlib-injector"),
                    "expected the agent banner in child output:\n" + log);
        } finally {
            server.close();
        }
    }

    /** Trivial main that keeps the JVM alive briefly so the agent can initialize. */
    public static final class AgentProbe {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(4000);
            System.out.println("probe-done");
        }
    }
}
