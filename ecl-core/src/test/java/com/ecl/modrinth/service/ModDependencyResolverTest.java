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
    void selectedOptionalFailureIncludesItsRequiredDependencyCause() {
        ModVersion optional = version("optional-v1", "optional", depProject("missing", DependencyType.REQUIRED));
        ModVersion root = version("root-v1", "root", depProject("optional", DependencyType.OPTIONAL));
        api.projectVersions.put("optional", List.of(optional));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> resolver().resolve(instance, root, Set.of("optional")).join());

        DependencyResolutionException selectedFailure = assertInstanceOf(
                DependencyResolutionException.class, failure.getCause());
        assertEquals("所选可选依赖无法解析: optional", selectedFailure.getMessage());
        assertInstanceOf(NoCompatibleVersionException.class, selectedFailure.getCause());
        assertTrue(selectedFailure.getCause().getMessage().contains("root -> optional -> missing"));
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
    void sharedDependencyKeepsFirstOwnerPathAndSiblingInstallOrder() {
        ModVersion shared = version("shared-v1", "shared");
        ModVersion first = version("first-v1", "first", depProject("shared", DependencyType.REQUIRED));
        ModVersion second = version("second-v1", "second", depProject("shared", DependencyType.REQUIRED));
        ModVersion root = version("root-v1", "root",
                depProject("first", DependencyType.REQUIRED), depProject("second", DependencyType.REQUIRED));
        api.projectVersions.put("shared", List.of(shared));
        api.projectVersions.put("first", List.of(first));
        api.projectVersions.put("second", List.of(second));

        DependencyResolutionResult result = resolver().resolve(instance, root).join();

        assertEquals(List.of("shared", "first", "second", "root"),
                result.installOrder().stream().map(mod -> mod.version().projectId()).toList());
        assertEquals("first", result.installOrder().getFirst().requiredByProjectId());
        assertEquals(Set.of("first", "second"), result.requiredByProjects().get("shared"));
        assertEquals(List.of("root", "first", "shared"), result.installOrder().getFirst().dependencyPath());
        assertEquals(List.of("root", "second"), result.installOrder().get(2).dependencyPath());
        assertEquals(List.of("root"), result.installOrder().getLast().dependencyPath());
    }

    @Test
    void dependencyDepthAllowsBoundaryButRejectsNextLevel() {
        ModVersion leaf = version("leaf-v1", "leaf");
        ModVersion middle = version("middle-v1", "middle", depProject("leaf", DependencyType.REQUIRED));
        ModVersion root = version("root-v1", "root", depProject("middle", DependencyType.REQUIRED));
        api.projectVersions.put("leaf", List.of(leaf));
        api.projectVersions.put("middle", List.of(middle));
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(), 1, 32);

        assertEquals(2, resolver.resolve(instance, middle).join().installOrder().size());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> resolver.resolve(instance, root).join());
        assertInstanceOf(DependencyResolutionException.class, rootCause(failure));
        assertEquals("依赖深度超过限制 1: root -> middle", rootCause(failure).getMessage());
    }

    @Test
    void dependencyCountIncludesRootAndDeduplicatesRepeatedVersions() {
        ModVersion shared = version("shared-v1", "shared");
        ModVersion root = version("root-v1", "root",
                depProject("shared", DependencyType.REQUIRED), depProject("shared", DependencyType.REQUIRED));
        api.projectVersions.put("shared", List.of(shared));
        DefaultModDependencyResolver atLimit = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(), 16, 2);
        DefaultModDependencyResolver belowLimit = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(), 16, 1);

        assertEquals(2, atLimit.resolve(instance, root).join().installOrder().size());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> belowLimit.resolve(instance, root).join());
        assertInstanceOf(DependencyResolutionException.class, rootCause(failure));
        assertEquals("依赖数量超过限制 1", rootCause(failure).getMessage());
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
        assertEquals(Set.of("root"), result.requiredByProjects().get("dependency"));
    }

    @Test
    void detectsIncompatibleProjectAddedLaterInTraversal() {
        ModVersion conflicting = version("bad-v1", "bad");
        api.projectVersions.put("bad", List.of(conflicting));
        ModVersion root = version("root-v1", "root", depProject("bad", DependencyType.INCOMPATIBLE),
                depProject("bad", DependencyType.REQUIRED));

        DependencyResolutionResult result = resolver().resolve(instance, root).join();

        assertEquals(1, result.conflicts().size());
        assertFalse(new InstallationPlanBuilder().build(instance, root, result).installable());
    }

    @Test
    void detectsPinnedIncompatibleVersionAddedLaterInTraversal() {
        ModVersion conflicting = version("bad-v1", "bad");
        api.projectVersions.put("bad", List.of(conflicting));
        ModVersion root = version("root-v1", "root", depVersion("bad-v1", "", DependencyType.INCOMPATIBLE),
                depProject("bad", DependencyType.REQUIRED));

        assertEquals(1, resolver().resolve(instance, root).join().conflicts().size());
    }

    @Test
    void pinnedRequiredDependencyDoesNotUseInstalledDifferentVersion() throws Exception {
        Files.createDirectories(instance.modsDirectory());
        Files.writeString(instance.modsDirectory().resolve("dependency.jar"), "x");
        ModVersion dependency = version("dependency-v2", "dependency");
        ModVersion root = version("root-v1", "root",
                depVersion("dependency-v2", "dependency", DependencyType.REQUIRED));
        api.versions.put("dependency-v2", dependency);
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(installed("dependency", "root", true)), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("dependency-v2", "root-v1"),
                result.installOrder().stream().map(mod -> mod.version().id()).toList());
    }

    @Test
    void doesNotReuseInstalledDependencyBuiltForAnotherMinecraftVersion() throws Exception {
        createModFile("dependency");
        ModVersion dependency = version("dependency-v1", "dependency");
        ModVersion root = version("root-v1", "root", depProject("dependency", DependencyType.REQUIRED));
        api.projectVersions.put("dependency", List.of(dependency));
        InstalledMod stale = installedRecord("dependency", "dependency-v1", "1.20.1", "fabric", "release");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(stale), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("dependency-v1", "root-v1"),
                result.installOrder().stream().map(mod -> mod.version().id()).toList());
        assertTrue(result.conflicts().isEmpty(), "已安装版本对实例不兼容时应重装而不是报冲突");
    }

    @Test
    void doesNotReuseInstalledDependencyWhoseReleaseChannelIsNotAllowed() throws Exception {
        createModFile("dependency");
        ModVersion dependency = version("dependency-v1", "dependency");
        ModVersion root = version("root-v1", "root", depProject("dependency", DependencyType.REQUIRED));
        api.projectVersions.put("dependency", List.of(dependency));
        InstalledMod alpha = installedRecord("dependency", "dependency-v1", "1.21.1", "fabric", "alpha");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(alpha), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("dependency-v1", "root-v1"),
                result.installOrder().stream().map(mod -> mod.version().id()).toList());
        assertTrue(result.conflicts().isEmpty(), "版本通道不允许时应重装而不是报冲突");
    }

    @Test
    void doesNotReuseInstalledDependencyBuiltForAnotherLoader() throws Exception {
        createModFile("dependency");
        ModVersion dependency = version("dependency-v1", "dependency");
        ModVersion root = version("root-v1", "root", depProject("dependency", DependencyType.REQUIRED));
        api.projectVersions.put("dependency", List.of(dependency));
        InstalledMod forgeRecord = installedRecord("dependency", "dependency-v1", "1.21.1", "forge", "release");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(forgeRecord), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("dependency-v1", "root-v1"),
                result.installOrder().stream().map(mod -> mod.version().id()).toList());
    }

    @Test
    void reusesInstalledDependencyWhoseLoaderIsRecordedAsEnumName() throws Exception {
        createModFile("dependency");
        ModVersion root = version("root-v1", "root", depProject("dependency", DependencyType.REQUIRED));
        InstalledMod fabricRecord = installedRecord("dependency", "dependency-v1", "1.21.1", "FABRIC", "release");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(fabricRecord), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("root-v1"),
                result.installOrder().stream().map(mod -> mod.version().id()).toList());
        assertEquals(Set.of("root"), result.requiredByProjects().get("dependency"));
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void doesNotReuseInstalledVersionOfAnotherProject() throws Exception {
        createModFile("other");
        ModVersion shared = version("shared-v1", "shared");
        api.versions.put("shared-v1", shared);
        ModVersion root = version("root-v1", "root",
                depVersion("shared-v1", "shared", DependencyType.REQUIRED));
        InstalledMod otherProject = installedRecord("other", "shared-v1", "1.21.1", "fabric", "release");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(otherProject), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("shared-v1", "root-v1"),
                result.installOrder().stream().map(mod -> mod.version().id()).toList());
    }

    @Test
    void detectsPinnedVersionConflictAgainstReusedInstalledDependency() throws Exception {
        createModFile("dependency");
        ModVersion first = version("first-v1", "first", depProject("dependency", DependencyType.REQUIRED));
        ModVersion second = version("second-v1", "second",
                depVersion("dependency-v2", "dependency", DependencyType.REQUIRED));
        ModVersion pinned = version("dependency-v2", "dependency");
        ModVersion root = version("root-v1", "root",
                depProject("first", DependencyType.REQUIRED), depProject("second", DependencyType.REQUIRED));
        api.projectVersions.put("first", List.of(first));
        api.projectVersions.put("second", List.of(second));
        api.versions.put("dependency-v2", pinned);
        InstalledMod reused = installedRecord("dependency", "dependency-v1", "1.21.1", "fabric", "release");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(reused), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(1, result.conflicts().size(), "复用已安装依赖后仍须检测同一项目的不同版本要求");
        ModConflict conflict = result.conflicts().getFirst();
        assertTrue(conflict.message().contains("dependency-v1"));
        assertTrue(conflict.message().contains("dependency-v2"));
        assertEquals(List.of("root", "second", "dependency"), conflict.dependencyPath());
        assertFalse(new InstallationPlanBuilder().build(instance, root, result).installable());
    }

    @Test
    void pinnedInstalledVersionUpgradeIsNotReportedAsConflict() throws Exception {
        createModFile("dependency");
        ModVersion pinned = version("dependency-v2", "dependency");
        ModVersion root = version("root-v1", "root",
                depVersion("dependency-v2", "dependency", DependencyType.REQUIRED));
        api.versions.put("dependency-v2", pinned);
        InstalledMod outdated = installedRecord("dependency", "dependency-v1", "1.21.1", "fabric", "release");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(outdated), 16, 32);

        DependencyResolutionResult result = resolver.resolve(instance, root).join();

        assertEquals(List.of("dependency-v2", "root-v1"),
                result.installOrder().stream().map(mod -> mod.version().id()).toList());
        assertTrue(result.conflicts().isEmpty(), "显式升级已安装依赖属于正常重装，不应误判为冲突");
    }

    private DefaultModDependencyResolver resolver() {
        return new DefaultModDependencyResolver(api, new DefaultModVersionSelector());
    }

    @Test
    void disabledRequiredDependencyIsIncludedInTheInstallPlan() throws Exception {
        Files.createDirectories(instance.modsDirectory());
        Files.writeString(instance.modsDirectory().resolve("dependency.jar.disabled"), "x");
        Instant now = Instant.now();
        InstalledMod disabled = new InstalledMod(instance.instanceId(), "dependency", "dependency-v1", "", "Dependency",
                "1", "dependency.jar", Path.of("mods/dependency.jar.disabled"), "", "", 1,
                instance.minecraftVersion(), "fabric", "release", false, true, "", now, now);
        ModVersion root = version("root-v1", "root", depProject("dependency", DependencyType.REQUIRED));
        api.projectVersions.put("dependency", List.of(version("dependency-v1", "dependency")));

        DependencyResolutionResult result = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(disabled), 16, 32).resolve(instance, root).join();

        assertEquals(List.of("dependency", "root"),
                result.installOrder().stream().map(mod -> mod.version().projectId()).toList());
        assertTrue(result.conflicts().isEmpty());
        ModVersion incompatible = version("root-v1", "root", depProject("dependency", DependencyType.INCOMPATIBLE));
        assertTrue(new DefaultModDependencyResolver(api, new DefaultModVersionSelector(),
                ignored -> List.of(disabled), 16, 32).resolve(instance, incompatible).join().conflicts().isEmpty());
    }

    @Test
    void versionSpecificIncompatibilityAllowsOtherInstalledAndPlannedVersions() {
        ModVersion root = version("root-v1", "root", depVersion("dependency-v1", "dependency", DependencyType.INCOMPATIBLE));
        InstalledMod installed = installedRecord("dependency", "dependency-v2", "1.21.1", "fabric", "release");
        DefaultModDependencyResolver resolver = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(installed), 16, 32);
        assertTrue(resolver.resolve(instance, root).join().conflicts().isEmpty());

        api.versions.put("dependency-v2", version("dependency-v2", "dependency"));
        ModVersion replacing = version("root-v1", "root",
                depVersion("dependency-v2", "dependency", DependencyType.REQUIRED),
                depVersion("dependency-v1", "dependency", DependencyType.INCOMPATIBLE));
        InstalledMod previous = installedRecord("dependency", "dependency-v1", "1.21.1", "fabric", "release");
        DefaultModDependencyResolver upgrade = new DefaultModDependencyResolver(
                api, new DefaultModVersionSelector(), ignored -> List.of(previous), 16, 32);
        assertTrue(upgrade.resolve(instance, replacing).join().conflicts().isEmpty());

        ModVersion matching = version("root-v1", "root", depVersion("dependency-v2", "dependency", DependencyType.INCOMPATIBLE));
        assertEquals(1, resolver.resolve(instance, matching).join().conflicts().size());
    }

    private void createModFile(String project) throws Exception {
        Files.createDirectories(instance.modsDirectory());
        Files.writeString(instance.modsDirectory().resolve(project + ".jar"), "x");
    }

    private InstalledMod installedRecord(String project, String versionId, String minecraftVersion,
                                         String loader, String versionType) {
        Instant now = Instant.now();
        return new InstalledMod(instance.instanceId(), project, versionId, project, project,
                "1", project + ".jar", Path.of("mods", project + ".jar"), "", "", 1,
                minecraftVersion, loader, versionType, true, true, "", now, now);
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
