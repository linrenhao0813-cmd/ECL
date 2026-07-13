package com.ecl.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CrashAnalyzerTest {
    @Test
    void identifiesMemoryFailures() {
        CrashAnalyzer.Report report = CrashAnalyzer.analyzeText("1.21", 1,
                "java.lang.OutOfMemoryError: Java heap space", null);
        assertEquals("内存不足", report.getTitle());
        assertFalse(report.getSuggestions().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(report.getExplanation().startsWith("Minecraft 1.21："));
    }
}
