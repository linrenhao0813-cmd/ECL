package com.ecl.modrinth.service;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.api.DependencyResolutionException;
import com.ecl.modrinth.api.NoCompatibleVersionException;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ModDependencyResolverTest {
    @TempDir Path temp;
    private TestFixtures.FakeApi api;
    private ModInstanceContext instance;

    @BeforeEach
    void setUp() {
        api = new TestFixtures.FakeApi();
        instance = TestFixtures.instance(temp);
    }

    @Test
    void resolvesSingleAndMultiLayerRequiredInTopologicalOrder() {
        ModVersion leaf = version("leaf-v1", "leaf");
        ModVersion middle = version("middle-v1", "middle", depProject("leaf", DependencyType.REQUIRED));
        ModVersion root = version("root-v1", "root", depProject("middle", DependencyType.REQUIRED));
        api.projectVersions.put("leaf", List.of(leaf));
        api.projectVersions.put("middle", List.of(middle));

        DependencyResolutionResult result = resolver().resolve(instance, root).join();
        assertEquals(List.of("leaf", "middle", "root"),
                result.installOrder().stream().map(mod -> mod.version().projectId()).toList());
    }

    @Test
    void optionalIsListedButNotInstalledUnlessSelected() {
        ModVersion optional = version("optional-v1", "optional");
        ModVersion root = version("root-v1", "root", depProject("optional", DependencyType.OPTIONAL));
        api.projectVersions.put("optional", List.of(optional));

        DependencyResolutionResult defaultResult = resolver().resolve(instance, root).join();
        assertEquals(List.of("root"),
                defaultResult.installOrder().stream().map(mod -> mod.version().projectId()).toList());
        assertEquals("optional", defaultResult.optionalDependencies().getFirst().version().projectId());

        DependencyResolutionResult selected = resolver().resolve(instance, root, Set.of("optional")).join();
        assertEquals(List.of("optional", "root"),
                selected.installOrder().stream().map(mod -> mod.version().projectId()).toList());
    }

    @Test
    void unavailableUnselectedOptionalOnlyWarns() {
        ModVersion root = version("root-v1", "root", depProject("missing", DependencyType.OPTIONAL));
        DependencyResolutionResult result = resolver().resolve(instance, root).join();
        assertEquals(1, result.installOrder().size());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void detectsIncompatibleInstalledModAndRecordsEmbeddedDependency() {
        ModVersion root = version("root-v1", "root",
                depProject("bad", DependencyType.INCOMPATIBLE),
                depProject("bundled", DependencyType.EMBEDDED));
        InstalledMod bad = installed("bad", "", false);
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(bad), 16, 32);
        DependencyResolutionResult result = resolver.resolve(instance, root).join();
        assertEquals("bad", result.conflicts().getFirst().conflictingProjectId());
        assertTrue(result.warnings().stream().anyMatch(message -> message.contains("内嵌")));
    }

    @Test
    void detectsCyclesAndMissingRequiredDependencies() {
        ModVersion a = version("a-v1", "a", depProject("b", DependencyType.REQUIRED));
        ModVersion b = version("b-v1", "b", depProject("a", DependencyType.REQUIRED));
        api.projectVersions.put("a", List.of(a));
        api.projectVersions.put("b", List.of(b));
        CompletionException cycle = assertThrows(CompletionException.class,
                () -> resolver().resolve(instance, a).join());
        assertInstanceOf(DependencyResolutionException.class, rootCause(cycle));

        ModVersion missing = version("root-v1", "root", depProject("absent", DependencyType.REQUIRED));
        CompletionException unavailable = assertThrows(CompletionException.class,
                () -> resolver().resolve(instance, missing).join());
        assertTrue(rootCause(unavailable).getMessage().contains("absent"));
    }

    @Test
    void deduplicatesSameVersionAndReportsDifferentVersionRequirements() {
        ModVersion shared = version("shared-v1", "shared");
        ModVersion duplicateRoot = version("root-v1", "root",
                depVersion("shared-v1", "shared", DependencyType.REQUIRED),
                depVersion("shared-v1", "shared", DependencyType.REQUIRED));
        api.versions.put("shared-v1", shared);
        DependencyResolutionResult deduplicated = resolver().resolve(instance, duplicateRoot).join();
        assertEquals(2, deduplicated.installOrder().size());

        ModVersion sharedV2 = version("shared-v2", "shared");
        api.versions.put("shared-v2", sharedV2);
        ModVersion conflictRoot = version("conflict-root", "root",
                depVersion("shared-v1", "shared", DependencyType.REQUIRED),
                depVersion("shared-v2", "shared", DependencyType.REQUIRED));
        DependencyResolutionResult conflicted = resolver().resolve(instance, conflictRoot).join();
        assertEquals(1, conflicted.conflicts().size());
        assertFalse(new InstallationPlanBuilder()
                .build(instance, conflictRoot, conflicted)
                .installable());
    }

    @Test
    void dependencyChannelDoesNotExceedSelectedRootVersion() {
        ModVersion alphaDependency = versionOfType("dependency-alpha", "dependency", "alpha");
        ModVersion root = version("root-v1", "root",
                depProject("dependency", DependencyType.REQUIRED));
        api.projectVersions.put("dependency", List.of(alphaDependency));

        CompletionException rejected = assertThrows(CompletionException.class,
                () -> resolver().resolve(instance, root).join());
        assertInstanceOf(NoCompatibleVersionException.class, rootCause(rejected));

        DependencyResolutionResult explicitlyAllowed = resolver().resolve(
                instance, root, Set.of(), ReleaseChannel.ALL).join();
        assertEquals(List.of("dependency-alpha", "root-v1"),
                explicitlyAllowed.installOrder().stream()
                        .map(resolved -> resolved.version().id())
                .toList());
    }

    @Test
    void downloadsRequiredDependencyWhenIndexEntryExistsButModFileIsMissing() {
        ModVersion dependency = version("dependency-v1", "dependency");
        ModVersion root = version("root-v1", "root",
                depProject("dependency", DependencyType.REQUIRED));
        api.projectVersions.put("dependency", List.of(dependency));
        InstalledMod staleEntry = installed("dependency", "root", true);
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(staleEntry), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("dependency", "root"),
                result.installOrder().stream().map(mod -> mod.version().projectId()).toList());
    }

    @Test
    void skipsRequiredDependencyDownloadWhenItsFileExistsInModsDirectory() throws Exception {
        Files.createDirectories(instance.modsDirectory());
        Files.writeString(instance.modsDirectory().resolve("dependency.jar"), "x");
        ModVersion root = version("root-v1", "root",
                depProject("dependency", DependencyType.REQUIRED));
        InstalledMod installedDependency = installed("dependency", "root", true);
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(installedDependency), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("root"),
                result.installOrder().stream().map(mod -> mod.version().projectId()).toList());
    }

    private DefaultModDependencyResolver resolver() {
        return new DefaultModDependencyResolver(api, new DefaultModVersionSelector());
    }

    private static ModVersion version(String id, String project, ModDependency... dependencies) {
        return TestFixtures.fabricVersion(id, project, List.of(dependencies));
    }

    private static ModVersion versionOfType(String id, String project, String type) {
        return TestFixtures.version(id, project, type, false,
                List.of("1.21.1"), List.of("fabric"),
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(TestFixtures.file(project + ".jar", true)), List.of());
    }

    private static ModDependency depProject(String project, DependencyType type) {
        return new ModDependency("", project, "", type);
    }

    private static ModDependency depVersion(String version, String project, DependencyType type) {
        return new ModDependency(version, project, "", type);
    }

    private InstalledMod installed(String project, String requiredBy, boolean dependency) {
        Instant now = Instant.now();
        return new InstalledMod(instance.instanceId(), project, project + "-v1", project, project,
                "1", project + ".jar", Path.of("mods", project + ".jar"), "", "", 1,
                "1.21.1", "fabric", "release", true, dependency, requiredBy, now, now);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
