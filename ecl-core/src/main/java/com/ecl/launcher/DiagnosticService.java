package com.ecl.launcher;

import java.io.File;

/**
 * Service interface for analyzing Minecraft crash reports and process output.
 */
public interface DiagnosticService {

    /**
     * Analyze a game exit using its exit code, captured output, and crash reports.
     */
    CrashAnalyzer.Report analyzeGameExit(String version, int exitCode, String processOutput, File gameDir);

    /**
     * Analyze a game exit with an optional launch timestamp for crash report filtering.
     */
    CrashAnalyzer.Report analyzeGameExit(String version, int exitCode, String processOutput, File gameDir, long launchStartedAt);

    /**
     * Analyze a launch exception (pre-launch failure) for diagnostics.
     */
    CrashAnalyzer.Report analyzeLaunchException(String version, Throwable throwable, File gameDir);
}
