package com.ecl.modrinth.service;

import com.ecl.operation.InstanceOperationCoordinator;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModSearchResult;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.download.ModFileDownloadService;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultModUpdateServiceTest {
    @Test
    void automaticUpdatesCannotEnableAProtectedDependency(@TempDir Path temp) {
        ModInstanceContext instance = TestFixtures.instance(temp);
        TestFixtures.FakeApi api = new TestFixtures.FakeApi();
        ModVersion root = TestFixtures.fabricVersion("root-new", "root", List.of(
                new com.ecl.modrinth.model.ModDependency(null, "disabled", null,
                        com.ecl.modrinth.model.DependencyType.REQUIRED)));
        api.projectVersions.put("disabled", List.of(TestFixtures.fabricVersion("disabled-new", "disabled", List.of())));
        DefaultModVersionSelector selector = new DefaultModVersionSelector();
        ModInstallationService installer = new ModInstallationService(new FileInstalledModRepository(),
                new ModFileDownloadService(java.util.concurrent.ForkJoinPool.commonPool(), new HashVerifier()),
                new InstanceOperationCoordinator(), Runnable::run, ignored -> false);
        DefaultModUpdateService service = new DefaultModUpdateService(api, selector,
                new DefaultModDependencyResolver(api, selector), new InstallationPlanBuilder(), installer, ignored -> instance);
        var update = new com.ecl.modrinth.model.ModUpdate(installed(temp, "hash"), root, root.files().getFirst());
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> service.applyUpdate(update, java.util.Set.of("disabled")).join());
        assertEquals(com.ecl.modrinth.api.ModConflictException.class, failure.getCause().getClass());
        assertEquals(false, java.nio.file.Files.exists(temp.resolve("mods")));
    }

    @Test
    void fallsBackToLatestAllowedReleaseWhenHashApiReturnsAlpha(@TempDir Path temp) {
        ModVersion release = release("42:200", false, "2026-02-01T00:00:00Z");
        ProjectVersionProvider provider = new ProjectVersionProvider(release);
        provider.supportsHashes = true;
        provider.hashVersion = TestFixtures.version("42:300", "42", "alpha", false,
                List.of("1.21.1"), List.of("fabric"), Instant.parse("2026-03-01T00:00:00Z"),
                release.files(), List.of());
        var updates = service(temp, provider).checkUpdates(TestFixtures.instance(temp),
                List.of(installed(temp, "hash")), ReleaseChannel.RELEASE_ONLY).join();
        assertEquals(1, updates.size());
        assertEquals("42:200", updates.getFirst().availableVersion().id());
        assertEquals(1, provider.projectLookups);
    }

    @Test
    void picksNewestCompatibleReleaseRatherThanAnOlderFeaturedVersion(@TempDir Path temp) {
        ProjectVersionProvider provider = new ProjectVersionProvider(release("42:150", true, "2026-01-01T00:00:00Z"));
        ModVersion incompatible = TestFixtures.version("42:300", "42", "release", true,
                List.of("1.20.1"), List.of("forge"), Instant.parse("2026-03-01T00:00:00Z"),
                List.of(TestFixtures.file("mod.jar", true)), List.of());
        provider.available = List.of(provider.version, incompatible, release("42:200", false, "2026-02-01T00:00:00Z"));
        var updates = service(temp, provider).checkUpdates(TestFixtures.instance(temp),
                List.of(installed(temp, "hash")), ReleaseChannel.RELEASE_ONLY).join();
        assertEquals("42:200", updates.getFirst().availableVersion().id());
    }

    @Test
    void checksPersistedProjectsWithoutHashes(@TempDir Path temp) {
        ProjectVersionProvider provider = new ProjectVersionProvider(release("42:200", false, "2026-02-01T00:00:00Z"));
        provider.supportsHashes = true;
        var updates = service(temp, provider).checkUpdates(TestFixtures.instance(temp),
                List.of(installed(temp, "")), ReleaseChannel.RELEASE_ONLY).join();
        assertEquals(1, updates.size());
        assertEquals(1, provider.projectLookups);
    }

    private static ModVersion release(String id, boolean featured, String published) {
        return TestFixtures.version(id, "42", "release", featured, List.of("1.21.1"), List.of("fabric"),
                Instant.parse(published), List.of(TestFixtures.file("mod.jar", true)), List.of());
    }

    private static InstalledMod installed(Path temp, String hash) {
        ModInstanceContext instance = TestFixtures.instance(temp);
        return new InstalledMod(instance.instanceId(), "42", "42:100", "", "Project", "1.0", "mod.jar",
                Path.of("mods/mod.jar"), hash, "", 10, "1.21.1", "fabric", "release", true,
                false, "", Instant.EPOCH, Instant.EPOCH);
    }

    private static DefaultModUpdateService service(Path temp, ProjectVersionProvider provider) {
        DefaultModVersionSelector selector = new DefaultModVersionSelector();
        ModInstallationService installer = new ModInstallationService(new FileInstalledModRepository(),
                new ModFileDownloadService(java.util.concurrent.ForkJoinPool.commonPool(), new HashVerifier()), new InstanceOperationCoordinator(),
                Runnable::run, ignored -> false);
        return new DefaultModUpdateService(provider, selector, new DefaultModDependencyResolver(provider, selector),
                new InstallationPlanBuilder(), installer, ignored -> TestFixtures.instance(temp));
    }

    @Test
    void checksNonHashProviderUpdatesByPersistedProjectId(@TempDir Path temp) {
        ModInstanceContext instance = TestFixtures.instance(temp);
        ModVersion latest = TestFixtures.fabricVersion("42:200", "42", List.of());
        ProjectVersionProvider provider = new ProjectVersionProvider(latest);
        DefaultModVersionSelector selector = new DefaultModVersionSelector();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            FileInstalledModRepository repository = new FileInstalledModRepository();
            ModInstallationService installer = new ModInstallationService(
                    repository, new ModFileDownloadService(executor, new HashVerifier()),
                    new InstanceOperationCoordinator(), Runnable::run, ignored -> false);
            DefaultModUpdateService service = new DefaultModUpdateService(
                    provider, selector, new DefaultModDependencyResolver(provider, selector),
                    new InstallationPlanBuilder(), installer, ignored -> instance);
            InstalledMod installed = new InstalledMod(
                    instance.instanceId(), "42", "42:100", "project", "Project", "1.0",
                    "project.jar", Path.of("mods/project.jar"), "sha1", "", 10,
                    instance.minecraftVersion(), instance.loaderName(), "release", true,
                    false, "", Instant.EPOCH, Instant.EPOCH);
            InstalledMod modrinthRecord = new InstalledMod(
                    instance.instanceId(), "fabric-api", "a-modrinth-version", "fabric-api",
                    "Fabric API", "1.0", "fabric-api.jar", Path.of("mods/fabric-api.jar"),
                    "other-sha1", "", 10, instance.minecraftVersion(), instance.loaderName(),
                    "release", true, false, "", Instant.EPOCH, Instant.EPOCH);

            var updates = service.checkUpdates(
                    instance, List.of(installed, modrinthRecord), ReleaseChannel.RELEASE_ONLY).join();

            assertEquals(1, updates.size());
            assertEquals("42:200", updates.getFirst().availableVersion().id());
            assertEquals(1, provider.projectLookups);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class ProjectVersionProvider implements ModMetadataProvider {
        private final ModVersion version;
        private List<ModVersion> available;
        private ModVersion hashVersion;
        private boolean supportsHashes;
        private int projectLookups;

        private ProjectVersionProvider(ModVersion version) {
            this.version = version;
            available = List.of(version);
        }

        @Override public String id() { return "legacy"; }
        @Override public boolean supportsSha1HashLookup() { return supportsHashes; }
        @Override public boolean canCheckUpdates(InstalledMod installedMod) {
            return installedMod.versionId().startsWith(installedMod.projectId() + ":");
        }
        @Override public CompletableFuture<ModSearchResult> search(ModSearchQuery query) {
            return CompletableFuture.completedFuture(new ModSearchResult(List.of(), 0, 20, 0));
        }
        @Override public CompletableFuture<ModProject> getProject(String idOrSlug) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public CompletableFuture<ModVersion> getVersion(String versionId) {
            return CompletableFuture.completedFuture(version);
        }
        @Override public CompletableFuture<List<ModVersion>> getVersions(
                String projectId, String minecraftVersion, String loader) {
            projectLookups++;
            return CompletableFuture.completedFuture(available);
        }
        @Override public CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(
                Collection<String> hashes, String algorithm) {
            return CompletableFuture.failedFuture(new AssertionError("hash lookup must not run"));
        }
        @Override public CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
                Collection<String> hashes, String algorithm, List<String> loaders,
                List<String> gameVersions) {
            return supportsHashes ? CompletableFuture.completedFuture(hashVersion == null ? Map.of() : Map.of("hash", hashVersion))
                    : CompletableFuture.failedFuture(new AssertionError("hash update must not run"));
        }
    }
}
