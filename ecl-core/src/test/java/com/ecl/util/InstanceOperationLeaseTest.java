package com.ecl.util;

import com.ecl.launch.GameProcessMarker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceOperationLeaseTest {
    @Test
    void crashedOwnerReleasesOsLockAndDeadGameMarkerDoesNotBlock(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("LeaseOwner.java");
        Files.writeString(source, """
                import com.ecl.util.InstanceOperationLease;
                import java.nio.file.Path;
                class LeaseOwner {
                    public static void main(String[] args) throws Exception {
                        try (var lease = InstanceOperationLease.tryAcquire(Path.of(args[0]))) {
                            if (lease == null) throw new IllegalStateException("busy");
                            System.out.println("READY");
                            System.out.flush();
                            Thread.sleep(60_000);
                        }
                    }
                }
                """);
        Path game = directory.resolve("game");
        String classes = Path.of(InstanceOperationLease.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path output = directory.resolve("owner.log");
        Process owner = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
                OperatingSystem.javaExecutableName()).toString(), "--class-path", classes,
                source.toString(), game.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (owner.isAlive() && !Files.readString(output).contains("READY") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(owner.isAlive() && Files.readString(output).contains("READY"), Files.readString(output));
            assertNull(InstanceOperationLease.tryAcquire(game));
            GameProcessMarker.record(game, owner.toHandle());
            assertTrue(GameProcessMarker.isRunning(game));
        } finally {
            owner.destroyForcibly();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
        }
        assertFalse(GameProcessMarker.isRunning(game));
        InstanceOperationLease recovered = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (recovered == null && System.nanoTime() < deadline) {
            recovered = InstanceOperationLease.tryAcquire(game);
            if (recovered == null) Thread.sleep(20);
        }
        try (var acquired = recovered) {
            assertNotNull(acquired, "stale lock files must not prevent reacquisition after process death");
        }
    }
}
