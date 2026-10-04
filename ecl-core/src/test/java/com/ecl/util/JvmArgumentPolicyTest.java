package com.ecl.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmArgumentPolicyTest {
    @Test
    void allowsOrdinaryMemoryAndPropertyFlags() {
        assertEquals(List.of("-Xmx2G", "-Dfile.encoding=UTF-8", "-XX:+UseG1GC"),
                JvmArgumentPolicy.requireSafe(List.of(
                        "-Xmx2G", "-Dfile.encoding=UTF-8", "-XX:+UseG1GC")));
    }

    @Test
    void rejectsAgentsBootClasspathAndCommandHooks() {
        assertThrows(IllegalArgumentException.class,
                () -> JvmArgumentPolicy.requireSafe("-javaagent:evil.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmArgumentPolicy.requireSafe("-agentpath:/tmp/x"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmArgumentPolicy.requireSafe("-agentlib:jdwp"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmArgumentPolicy.requireSafe("-Xbootclasspath/a:extra.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmArgumentPolicy.requireSafe("-XX:OnError=calc.exe"));
        assertThrows(IllegalArgumentException.class,
                () -> JvmArgumentPolicy.requireSafe("@args.txt"));
    }
}
