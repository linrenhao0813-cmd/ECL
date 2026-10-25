package com.ecl.modrinth.service;

import com.ecl.modrinth.TestFixtures;
import com.ecl.launcher.LoaderUpdateService;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.download.ModFileDownloadService;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import com.ecl.operation.InstanceOperationCoordinator;
import com.ecl.util.TestNetworkPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModInstallationServiceTest {
    @TempDir Path temp;
    private HttpServer server;
    private ExecutorService executor;
    private ExecutorService orchestration;
    private AutoCloseable downloadPolicy;
    private ModInstanceContext instance;
    private FileInstalledModRepository repository;
    private TestFixtures.FakeApi api;
    private DefaultModDependencyResolver resolver;
    private ModInstallationService installer;
    private DefaultModManagementService management;

    @Test
    void runningMarkerPreventsInstallationAfterLauncherRestart() throws Exception {
        com.ecl.launch.GameProcessMarker.record(instance.gameDirectory(), ProcessHandle.current());

        assertThrows(CompletionException.class, () -> install(version("a-v1", "a", "a.jar")));
        assertFalse(Files.exists(instance.modsDirectory().resolve("a.jar")));
        assertFalse(Files.exists(repository.indexPath(instance)));
    }

    @BeforeEach
    void setUp() throws Exception {
        downloadPolicy = TestNetworkPolicy.allowLoopbackArtifactDownloads();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        executor = Executors.newSingleThreadExecutor();
        instance = TestFixtures.instance(temp);
        repository = new FileInstalledModRepository();
        api = new TestFixtures.FakeApi();
        resolver = new DefaultModDependencyResolver(api, new DefaultModVersionSelector(), ignored -> {
            try {
                return repository.findAll(instance);
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }, 16, 32);
        var lock = new InstanceOperationCoordinator();
        installer = new ModInstallationService(repository, new ModFileDownloadService(executor, new HashVerifier()),
                lock, Runnable::run, ignored -> false);
        management = new DefaultModManagementService(repository, lock, Runnable::run, ignored -> false, new HashVerifier());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (orchestration != null) orchestration.shutdownNow();
        executor.shutdownNow();
        server.stop(0);
        downloadPolicy.close();
    }

    @Test
    void sameFilenameBelongingToAnotherProjectCannotBeOverwritten() throws Exception {
        install(version("a-v1", "a", "same.jar"));
        String before = Files.readString(instance.modsDirectory().resolve("same.jar"));

        assertThrows(CompletionException.class, () -> install(version("b-v1", "b", "same.jar")));

        assertEquals(before, Files.readString(instance.modsDirectory().resolve("same.jar")));
        assertEquals(List.of("a"), repository.findAll(instance).stream().map(mod -> mod.projectId()).toList());
    }

    @Test
    void preparedUpdateReplacesRecognizedUnindexedJar() throws Exception {
        Path oldJar = instance.modsDirectory().resolve("manual-old.jar");
        TestFixtures.createJar(oldJar, "manual");
        var hashes = new HashVerifier().calculate(oldJar);
        var oldVersion = version("manual-v1", "manual", "manual-old.jar");
        api.hashes.put(hashes.sha1(), oldVersion);
        var scanner = new DefaultLocalModScanner(api, repository, new HashVerifier(),
                new InstanceOperationCoordinator(), Runnable::run, ignored -> false);
        var scan = scanner.inspect(instance).join();
        var latest = version("manual-v2", "manual", "manual-new.jar");
        var updates = new DefaultModUpdateService(api, new DefaultModVersionSelector(), resolver,
                new InstallationPlanBuilder(), installer, ignored -> instance);
        var update = new ModUpdate(scan.installedMods().getFirst(), latest,
                latest.files().getFirst(), ReleaseChannel.RELEASE_ONLY);
        var prepared = updates.prepareUpdate(update, Set.of()).join();

        assertFalse(Files.exists(repository.indexPath(instance)));
        assertTrue(Files.exists(oldJar));
        updates.applyPreparedUpdate(prepared).join();

        assertFalse(Files.exists(oldJar));
        assertEquals("/manual-v2", Files.readString(instance.modsDirectory().resolve("manual-new.jar")));
        assertEquals("manual-v2", repository.findByProjectId(instance, "manual").orElseThrow().versionId());
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void previewedUpgradeReplacesUnindexedDependenciesAndCanRestoreThem() throws Exception {
        orchestration = Executors.newSingleThreadExecutor();
        for (String project : List.of("manual", "shared")) {
            Path jar = instance.modsDirectory().resolve(project + "-old.jar");
            TestFixtures.createJar(jar, project);
            api.hashes.put(new HashVerifier().calculate(jar).sha1(), version(project + "-v1", project, project + "-old.jar"));
        }
        byte[] original = Files.readAllBytes(instance.modsDirectory().resolve("manual-old.jar"));
        api.projectVersions.put("manual", List.of(version("manual-v2", "manual", "manual-new.jar", required("shared"))));
        api.projectVersions.put("shared", List.of(version("shared-v2", "shared", "shared-new.jar")));
        Path versions = temp.resolve("metadata");
        Path profile = versions.resolve(instance.profileId()).resolve(instance.profileId() + ".json");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, """
                {"id":"%s","eclMinecraftVersion":"1.21.1","eclModLoader":"fabric","eclModLoaderVersion":"1.0"}
                """.formatted(instance.profileId()));
        var lock = new InstanceOperationCoordinator();
        var loaders = new LoaderUpdateService(versions, new LoaderUpdateService.Backend() {
            @Override public List<String> listVersions(String minecraft, ModLoaderInstaller.Loader loader) {
                return List.of("1.0");
            }
            @Override public ModLoaderInstaller.InstallResult install(String minecraft, ModLoaderInstaller.Loader loader,
                                                                      String target, ModLoaderInstaller.Listener listener) {
                throw new AssertionError("Loader is already current");
            }
        }, lock, orchestration, ignored -> false);
        var scanner = new DefaultLocalModScanner(api, repository, new HashVerifier(), lock, orchestration, ignored -> false);
        var sessionInstaller = new ModInstallationService(repository,
                new ModFileDownloadService(executor, new HashVerifier()), lock, orchestration, ignored -> false);
        var updates = new DefaultModUpdateService(api, new DefaultModVersionSelector(), resolver,
                new InstallationPlanBuilder(), sessionInstaller, ignored -> instance);
        var recovery = new InstanceUpgradeRecoveryService(temp.resolve("recovery"), lock, ignored -> false);
        var service = new InstanceUpdateService(loaders, scanner, updates, ignored -> false, orchestration, recovery);
        var preview = service.preview(instance, ReleaseChannel.RELEASE_ONLY, null, versions, temp.resolve("game-root"), null).join();
        var prepared = service.prepare(preview, Set.of("manual")).join();
        assertFalse(Files.exists(repository.indexPath(instance)));

        var result = service.execute(prepared, progress -> {
            if (progress.stage() == InstanceUpdateService.Stage.MOD) {
                assertNull(lock.tryAcquire(instance.instanceId()));
                try (var physical = com.ecl.util.InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
                    assertNull(physical, "the complete upgrade must retain the physical lifecycle lock");
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }
        }, null).join();

        assertTrue(result.failures().isEmpty());
        assertFalse(Files.exists(instance.modsDirectory().resolve("manual-old.jar")));
        assertFalse(Files.exists(instance.modsDirectory().resolve("shared-old.jar")));
        assertTrue(Files.exists(instance.modsDirectory().resolve("manual-new.jar")));
        assertTrue(Files.exists(instance.modsDirectory().resolve("shared-new.jar")));
        assertTrue(recovery.restore(instance.profileId(), instance, versions, temp.resolve("game-root"), Set.of()).restored());
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(instance.modsDirectory().resolve("manual-old.jar"))));
        assertTrue(Files.exists(instance.modsDirectory().resolve("shared-old.jar")));
        assertFalse(Files.exists(instance.modsDirectory().resolve("manual-new.jar")));
        assertFalse(Files.exists(instance.modsDirectory().resolve("shared-new.jar")));
        assertFalse(Files.exists(repository.indexPath(instance)));
    }

    @Test
    void reusedSharedDependencyRetainsAllOwnersIncludingAfterUpdatingItAsRoot() throws Exception {
        api.projectVersions.put("shared", List.of(version("shared-v1", "shared", "shared.jar")));
        install(version("first-v1", "first", "first.jar", required("shared")));
        install(version("second-v1", "second", "second.jar", required("shared")));
        assertEquals(Set.of("first", "second"), repository.findByProjectId(instance, "shared").orElseThrow().requiredByProjectIds());

        install(version("shared-v2", "shared", "shared-new.jar"));

        var shared = repository.findByProjectId(instance, "shared").orElseThrow();
        assertEquals(Set.of("first", "second"), shared.requiredByProjectIds());
        assertTrue(shared.dependency());
        assertThrows(CompletionException.class, () -> management.uninstall(instance, List.of("first", "shared")).join());
        assertTrue(Files.isRegularFile(instance.modsDirectory().resolve("shared-new.jar")));
    }

    @Test
    void updatingAnOwnerRemovesItsObsoleteRequirementWithoutRemovingOtherOwners() throws Exception {
        api.projectVersions.put("shared", List.of(version("shared-v1", "shared", "shared.jar")));
        install(version("first-v1", "first", "first.jar", required("shared")));
        install(version("second-v1", "second", "second.jar", required("shared")));

        install(version("first-v2", "first", "first.jar"));

        assertEquals(Set.of("second"), repository.findByProjectId(instance, "shared").orElseThrow().requiredByProjectIds());
        assertThrows(CompletionException.class, () -> management.uninstall(instance, List.of("first", "shared")).join());
    }

    private void install(ModVersion version) {
        var plan = new InstallationPlanBuilder().build(instance, version, resolver.resolve(instance, version).join());
        installer.install(plan, null).join();
    }

    private ModVersion version(String id, String project, String filename, ModDependency... dependencies) throws Exception {
        byte[] content = ("/" + id).getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(content));
        ModFile file = new ModFile(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + id), filename,
                Map.of("sha512", hash), true, content.length, "");
        return TestFixtures.version(id, project, "release", false, List.of(instance.minecraftVersion()),
                List.of(instance.loaderName()), Instant.now(), List.of(file), List.of(dependencies));
    }

    private static ModDependency required(String project) {
        return new ModDependency("", project, "", DependencyType.REQUIRED);
    }
}
