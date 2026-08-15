package com.ecl.modrinth.service;

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
import com.ecl.modrinth.provider.ContentSource;
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

class DefaultModUpdateServiceTest {
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
                    new DefaultInstanceOperationLock(), Runnable::run, ignored -> false);
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
        private int projectLookups;

        private ProjectVersionProvider(ModVersion version) {
            this.version = version;
        }

        @Override public ContentSource source() { return ContentSource.CURSEFORGE; }
        @Override public boolean supportsSha1HashLookup() { return false; }
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
            return CompletableFuture.completedFuture(List.of(version));
        }
        @Override public CompletableFuture<Map<String, ModVersion>> getVersionsFromHashes(
                Collection<String> hashes, String algorithm) {
            return CompletableFuture.failedFuture(new AssertionError("hash lookup must not run"));
        }
        @Override public CompletableFuture<Map<String, ModVersion>> getLatestVersionsFromHashes(
                Collection<String> hashes, String algorithm, List<String> loaders,
                List<String> gameVersions) {
            return CompletableFuture.failedFuture(new AssertionError("hash update must not run"));
        }
    }
}
