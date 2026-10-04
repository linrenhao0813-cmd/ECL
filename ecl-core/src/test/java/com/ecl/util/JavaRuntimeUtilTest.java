package com.ecl.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies that Java feature-version detection is cached per (path, mtime, size). */
class JavaRuntimeUtilTest {

    @AfterEach
    void resetCache() {
        JavaRuntimeUtil.clearVersionCache();
    }

    private static File currentJavaExecutable() {
        String home = System.getProperty("java.home", "");
        return new File(home, "bin" + File.separator + "java.exe");
    }

    @Test
    void repeatedDetectionHitsCacheAndSkipsChildProcess() {
        File executable = currentJavaExecutable();
        assumeTrue(executable.isFile(), "Test JVM must be a real java.exe");

        long before = JavaRuntimeUtil.DETECTION_PROBE_COUNT.get();
        int first = JavaRuntimeUtil.detectJavaFeatureVersion(executable);
        long afterFirst = JavaRuntimeUtil.DETECTION_PROBE_COUNT.get();
        assertEquals(1, afterFirst - before, "First detection should spawn exactly one probe");

        int second = JavaRuntimeUtil.detectJavaFeatureVersion(executable);
        long afterSecond = JavaRuntimeUtil.DETECTION_PROBE_COUNT.get();
        assertEquals(first, second, "Cached result must match the first detection");
        assertEquals(0, afterSecond - afterFirst,
                "Unchanged executable must be served from the cache without spawning a process");
        assertTrue(first > 0, "A real JVM should report a positive feature version");
    }

    @Test
    void clearVersionCacheForcesReDetection() {
        File executable = currentJavaExecutable();
        assumeTrue(executable.isFile(), "Test JVM must be a real java.exe");

        JavaRuntimeUtil.detectJavaFeatureVersion(executable);
        long afterFirst = JavaRuntimeUtil.DETECTION_PROBE_COUNT.get();
        JavaRuntimeUtil.clearVersionCache();
        JavaRuntimeUtil.detectJavaFeatureVersion(executable);
        long afterSecond = JavaRuntimeUtil.DETECTION_PROBE_COUNT.get();
        assertEquals(1, afterSecond - afterFirst,
                "Clearing the cache should force a fresh child-process probe");
    }

    @Test
    void usableJavaPathRequiresARealJvm() {
        File executable = currentJavaExecutable();
        assumeTrue(executable.isFile(), "Test JVM must be a real java.exe");
        assertTrue(JavaRuntimeUtil.isUsableJavaPath(executable.getAbsolutePath()));
        assertFalse(JavaRuntimeUtil.isUsableJavaPath(
                new File(System.getProperty("java.io.tmpdir"), "ecl-not-java.exe").getAbsolutePath()));
        File cmd = new File(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32\\cmd.exe");
        if (cmd.isFile()) {
            assertFalse(JavaRuntimeUtil.isUsableJavaPath(cmd.getAbsolutePath()));
        }
    }

    @Test
    void missingExecutableIsNotCachedAsAProbe() {
        File missing = new File(System.getProperty("java.io.tmpdir"), "ecl-no-such-java.exe");
        long before = JavaRuntimeUtil.DETECTION_PROBE_COUNT.get();
        assertEquals(-1, JavaRuntimeUtil.detectJavaFeatureVersion(missing));
        assertEquals(0, JavaRuntimeUtil.DETECTION_PROBE_COUNT.get() - before,
                "A missing file must be rejected before any child process is spawned");
    }

    @Test
    void resolveExactVersionPrefersCurrentJvmWithoutThrowing() throws Exception {
        File executable = currentJavaExecutable();
        assumeTrue(executable.isFile(), "Test JVM must be a real java.exe");
        int featureVersion = Runtime.version().feature();
        String resolved = JavaRuntimeUtil.resolveJavaExecutable(null, featureVersion);
        assertTrue(new File(resolved).isFile(), "Resolved java executable must exist");
    }
}
