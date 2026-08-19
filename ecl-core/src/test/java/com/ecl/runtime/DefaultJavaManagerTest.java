package com.ecl.runtime;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultJavaManagerTest {
    @Test
    void detectsAndSelectsCurrentJava() {
        String executable = new File(System.getProperty("java.home"),
                "bin/java.exe").getAbsolutePath();
        DefaultJavaManager manager = new DefaultJavaManager(executable);

        assertFalse(manager.detect().isEmpty());
        assertTrue(manager.select(21).isPresent());
        int currentFeature = Runtime.version().feature();
        assertEquals(currentFeature, manager.select(currentFeature).orElseThrow().featureVersion());
    }
}
