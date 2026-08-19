package com.ecl.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameProcessTest {

    @TempDir
    Path tempDir;

    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java.exe";
    }

    @Test
    void capturesProcessOutputAndReportsExitCode() throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "--version")
                .directory(tempDir.toFile())
                .start();
        GameProcess gameProcess = new GameProcess(process, "probe", tempDir);
        List<String> lines = new CopyOnWriteArrayList<>();
        gameProcess.attachOutputListener(lines::add);

        GameProcess finished = gameProcess.whenExited().get(20, TimeUnit.SECONDS);

        assertSame(gameProcess, finished);
        assertEquals(0, gameProcess.exitCode());
        assertFalse(gameProcess.isAlive());
        assertFalse(lines.isEmpty(), "`java --version` should emit at least one line");
        assertFalse(gameProcess.capturedOutput().isBlank());
        assertEquals("probe", gameProcess.versionId());
        gameProcess.close();
    }

    @Test
    void destroyingAProcessTerminatesIt() throws Exception {
        Process process = new ProcessBuilder(
                javaExecutable(), "-cp", System.getProperty("java.class.path"),
                GameProcessTest.class.getName() + "$SleepMain")
                .directory(tempDir.toFile())
                .start();
        GameProcess gameProcess = new GameProcess(process, "sleeper", tempDir);
        assertTrue(gameProcess.isAlive());
        assertEquals(-1, gameProcess.exitCode());

        gameProcess.destroyForcibly();
        GameProcess exited = gameProcess.whenExited().get(20, TimeUnit.SECONDS);

        assertSame(gameProcess, exited);
        assertFalse(exited.isAlive());
        gameProcess.close();
    }

    @Test
    void repeatingCloseIsSafe() throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "--version")
                .directory(tempDir.toFile()).start();
        GameProcess gameProcess = new GameProcess(process, "probe", tempDir);
        gameProcess.close();
        gameProcess.close();
        assertTrue(true, "double close must not throw");
    }

    @Test
    void drainsLargeOutputWithoutAListener() throws Exception {
        Process process = new ProcessBuilder(
                javaExecutable(), "-cp", System.getProperty("java.class.path"),
                GameProcessTest.class.getName() + "$SpamMain")
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        GameProcess gameProcess = new GameProcess(process, "spam", tempDir);

        gameProcess.whenExited().get(20, TimeUnit.SECONDS);

        assertEquals(0, gameProcess.exitCode());
        assertFalse(gameProcess.capturedOutput().isBlank());
        gameProcess.close();
    }

    @Test
    void listenerReceivesOutputCapturedBeforeItWasAttached() throws Exception {
        Process process = new ProcessBuilder(
                javaExecutable(), "-cp", System.getProperty("java.class.path"),
                GameProcessTest.class.getName() + "$OneLineMain")
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        GameProcess gameProcess = new GameProcess(process, "early-output", tempDir);
        process.waitFor(20, TimeUnit.SECONDS);
        CountDownLatch replayed = new CountDownLatch(1);
        List<String> lines = new CopyOnWriteArrayList<>();

        gameProcess.attachOutputListener(line -> {
            lines.add(line);
            replayed.countDown();
        });

        assertTrue(replayed.await(5, TimeUnit.SECONDS));
        assertTrue(lines.contains("early-line"));
        gameProcess.close();
    }

    /** Helper main that sleeps; used to verify force-termination of a live JVM. */
    public static final class SleepMain {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(60_000);
        }
    }

    public static final class SpamMain {
        public static void main(String[] args) {
            for (int i = 0; i < 200_000; i++) {
                System.out.println("minecraft-log-line-" + i);
            }
        }
    }

    public static final class OneLineMain {
        public static void main(String[] args) {
            System.out.println("early-line");
        }
    }
}
