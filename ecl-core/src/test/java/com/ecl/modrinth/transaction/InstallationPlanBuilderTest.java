package com.ecl.modrinth.transaction;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.service.DependencyResolutionResult;
import com.ecl.modrinth.service.ResolvedMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstallationPlanBuilderTest {
    @Test
    void rejectsWhenModrinthFileHasNoVerificationHash(@TempDir Path tempDir) {
        ModFile file = new ModFile(
                URI.create("https://example.invalid/no-hash.jar"),
                "no-hash.jar", Map.of(), true, 42, "required-resource");
        ModVersion version = TestFixtures.version(
                "no-hash-version", "no-hash-project", "release", false,
                List.of("1.21.1"), List.of("fabric"),
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(file), List.of());
        DependencyResolutionResult resolution = new DependencyResolutionResult(
                List.of(new ResolvedMod(version, file, false, "", List.of("no-hash-project"))),
                List.of(), List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> new InstallationPlanBuilder()
                .build(TestFixtures.instance(tempDir), version, resolution));
    }

    @Test
    void doesNotWarnWhenAtLeastOneSupportedHashExists(@TempDir Path tempDir) {
        ModFile file = new ModFile(
                URI.create("https://example.invalid/verified.jar"),
                "verified.jar", Map.of("sha512", "0".repeat(128)), true, 42, "required-resource");
        ModVersion version = TestFixtures.version(
                "verified-version", "verified-project", "release", false,
                List.of("1.21.1"), List.of("fabric"),
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(file), List.of());
        DependencyResolutionResult resolution = new DependencyResolutionResult(
                List.of(new ResolvedMod(version, file, false, "", List.of("verified-project"))),
                List.of(), List.of(), List.of());

        ModInstallationPlan plan = new InstallationPlanBuilder()
                .build(TestFixtures.instance(tempDir), version, resolution);

        assertFalse(plan.warnings().stream().anyMatch(warning -> warning.contains("无法验证下载完整性")));
    }
}
