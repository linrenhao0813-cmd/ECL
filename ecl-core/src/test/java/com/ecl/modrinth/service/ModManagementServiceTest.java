package com.ecl.modrinth.service;

import com.ecl.operation.InstanceOperationCoordinator;

import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.api.ModConflictException;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import com.ecl.modrinth.repository.InstalledModRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ModManagementServiceTest {
    @TempDir Path temp;
    private ModInstanceContext instance;
    private InstalledModRepository repository;
    private DefaultModManagementService service;

    @BeforeEach
    void setUp() {
        instance = TestFixtures.instance(temp);
        repository = new FileInstalledModRepository();
        service = new DefaultModManagementService(repository, new InstanceOperationCoordinator(),
                Runnable::run, ignored -> false, new HashVerifier());
    }

    @Test
    void changingEnabledAndUninstallingHoldPhysicalLockWhileReadingIndex() throws Exception {
        createModFile("example.jar", true);
        repository.saveAll(instance, List.of(record("example", true, false, "")));
        InstalledModRepository guarded = (InstalledModRepository) java.lang.reflect.Proxy.newProxyInstance(
                InstalledModRepository.class.getClassLoader(), new Class<?>[] { InstalledModRepository.class },
                (proxy, method, arguments) -> {
                    if ("findAll".equals(method.getName())) {
                        try (var competing = com.ecl.util.InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
                            assertNull(competing, "a competing launcher must not enter between reading and writing the index");
                        }
                    }
                    return method.invoke(repository, arguments);
                });
        service = new DefaultModManagementService(guarded, new InstanceOperationCoordinator(),
                Runnable::run, ignored -> false, new HashVerifier());
        service.setEnabled(instance, List.of("example"), false).join();
        service.setEnabled(instance, List.of("example"), true).join();
        service.uninstall(instance, List.of("example")).join();
        assertTrue(repository.findAll(instance).isEmpty());
    }

    @Test
    void concurrentLauncherCannotOverwriteIndexPublishedAfterOurRead() throws Exception {
        createModFile("example.jar", true);
        repository.saveAll(instance, List.of(record("example", true, false, "")));
        Path secondJar = temp.resolve("second.jar");
        TestFixtures.createJar(secondJar, "second");
        // The competing launcher completes a whole import into the same instance while this
        // service is between acquiring the instance and reading the index. With a lost update the
        // commit below would publish its stale single-entry list and drop "local:...".
        InstalledModRepository competing = new FileInstalledModRepository();
        DefaultModManagementService other = new DefaultModManagementService(
                competing, new InstanceOperationCoordinator(), Runnable::run, ignored -> false, new HashVerifier());
        DefaultModManagementService racing = new DefaultModManagementService(
                repository, new InstanceOperationCoordinator(), Runnable::run, ignored -> false, new HashVerifier());
        other.importLocalJar(instance, secondJar).join();
        racing.setEnabled(instance, List.of("example"), false).join();

        List<InstalledMod> records = repository.findAll(instance);
        assertEquals(2, records.size(), "the index must keep both the imported and the managed mod");
        assertTrue(records.stream().anyMatch(mod -> mod.projectId().equals("example") && !mod.enabled()));
        assertTrue(records.stream().anyMatch(mod -> mod.projectId().startsWith("local:")));
    }

    @Test
    void localImportRecoversInterruptedTransactionBeforePublishingIndex() throws Exception {
        repository.saveAll(instance, List.of());
        Path transaction = Files.createDirectories(instance.gameDirectory().resolve(".ecl-mod-transactions/crashed/backups"))
                .getParent();
        Files.copy(repository.indexPath(instance), transaction.resolve("backups/0-final.bak"));
        Files.writeString(transaction.resolve("journal.json"), """
                {"status":"APPLYING","entries":[{
                  "stagedFile":".ecl-mod-transactions/crashed/index.tmp",
                  "finalFile":"launcher-mods.json","oldFile":null,
                  "finalBackup":".ecl-mod-transactions/crashed/backups/0-final.bak",
                  "oldBackup":".ecl-mod-transactions/crashed/backups/0-old.bak",
                  "finalFileExisted":true}]}
                """);
        Path jar = temp.resolve("import.jar");
        TestFixtures.createJar(jar, "import");
        InstalledMod imported = service.importLocalJar(instance, jar).join();
        assertFalse(Files.exists(transaction), "the interrupted transaction must be recovered before importing");
        com.ecl.modrinth.transaction.FileModInstallationTransaction.recoverIncompleteTransactions(instance.gameDirectory());
        assertEquals(List.of(imported), repository.findAll(instance));
        assertTrue(Files.isRegularFile(instance.modsDirectory().resolve("import.jar")));
    }

    @Test
    void reopenedLauncherCannotDisableUninstallOrImportWhileGameRuns() throws Exception {
        createModFile("example.jar", true);
        repository.saveAll(instance, List.of(record("example", true, false, "")));
        String index = Files.readString(repository.indexPath(instance));
        Path local = temp.resolve("import.jar");
        TestFixtures.createJar(local, "import");
        com.ecl.launch.GameProcessMarker.record(instance.gameDirectory(), ProcessHandle.current());

        assertThrows(CompletionException.class, () -> service.setEnabled(instance, List.of("example"), false).join());
        assertThrows(CompletionException.class, () -> service.uninstall(instance, List.of("example")).join());
        assertThrows(CompletionException.class, () -> service.importLocalJar(instance, local).join());
        assertEquals("example.jar", Files.readString(instance.modsDirectory().resolve("example.jar")));
        assertEquals(index, Files.readString(repository.indexPath(instance)));
        assertFalse(Files.exists(instance.modsDirectory().resolve("import.jar")));
    }

    @Test
    void rechecksRunningMarkerAfterAcquiringOperationLock() throws Exception {
        createModFile("example.jar", true);
        repository.saveAll(instance, List.of(record("example", true, false, "")));
        // Another launcher publishes the durable marker while this process owns the coordinator
        // but before it reads the marker; the check after lock acquisition must catch it.
        InstanceOperationCoordinator delegate = new InstanceOperationCoordinator();
        java.util.concurrent.atomic.AtomicBoolean markerPublished = new java.util.concurrent.atomic.AtomicBoolean();
        service = new DefaultModManagementService(repository, new InstanceOperationLock() {
            @Override
            public AutoCloseable acquire(UUID instanceId) {
                return delegate.acquire(instanceId);
            }

            @Override
            public com.ecl.operation.InstanceOperationSession acquireSession(UUID instanceId, Path directory)
                    throws java.io.IOException {
                com.ecl.operation.InstanceOperationSession session =
                        delegate.acquireSession(instanceId, directory);
                if (markerPublished.compareAndSet(false, true)) {
                    com.ecl.launch.GameProcessMarker.record(directory, ProcessHandle.current());
                }
                return session;
            }
        }, Runnable::run, ignored -> false, new HashVerifier());

        assertThrows(CompletionException.class, () -> service.setEnabled(instance, List.of("example"), false).join());
        assertTrue(Files.exists(instance.modsDirectory().resolve("example.jar")));
        assertTrue(repository.findAll(instance).getFirst().enabled());
        com.ecl.launch.GameProcessMarker.clear(instance.gameDirectory(), ProcessHandle.current());
        assertFalse(service.setEnabled(instance, List.of("example"), false).join().getFirst().enabled());
    }

    @Test
    void disablesAndEnablesUsingDedicatedDirectory() throws Exception {
        createModFile("example.jar", true);
        repository.saveAll(instance, List.of(record("example", true, false, "")));

        InstalledMod disabled = service.setEnabled(instance, List.of("example"), false).join().getFirst();
        assertFalse(disabled.enabled());
        assertFalse(Files.exists(instance.modsDirectory().resolve("example.jar")));
        assertTrue(Files.exists(instance.gameDirectory().resolve("disabled-mods/example.jar")));

        InstalledMod enabled = service.setEnabled(instance, List.of("example"), true).join().getFirst();
        assertTrue(enabled.enabled());
        assertTrue(Files.exists(instance.modsDirectory().resolve("example.jar")));
    }

    @Test
    void refusesEnableWhenTargetFileExists() throws Exception {
        createModFile("example.jar", false);
        Files.createDirectories(instance.modsDirectory());
        Files.writeString(instance.modsDirectory().resolve("example.jar"), "collision");
        repository.saveAll(instance, List.of(record("example", false, false, "")));
        assertConflict(() -> service.setEnabled(instance, List.of("example"), true).join());
        assertTrue(Files.exists(instance.gameDirectory().resolve("disabled-mods/example.jar")));
    }

    @Test
    void requiredDependencyCannotBeDisabledOrUninstalledWhileOwnerEnabled() throws Exception {
        createModFile("root.jar", true);
        createModFile("api.jar", true);
        repository.saveAll(instance, List.of(
                record("root", true, false, ""),
                record("api", true, true, "root")));

        assertConflict(() -> service.setEnabled(instance, List.of("api"), false).join());
        assertConflict(() -> service.uninstall(instance, List.of("api")).join());
        assertTrue(Files.exists(instance.modsDirectory().resolve("api.jar")));
    }

    @Test
    void sharedDependencyCannotBeRemovedWithOnlyOneOwner() throws Exception {
        createModFile("first.jar", true);
        createModFile("second.jar", true);
        createModFile("api.jar", true);
        repository.saveAll(instance, List.of(record("first", true, false, ""), record("second", true, false, ""),
                record("api", true, true, "first").withRequiredByProjectIds(Set.of("first", "second"))));

        assertConflict(() -> service.uninstall(instance, List.of("first", "api")).join());
        assertConflict(() -> service.setEnabled(instance, List.of("first", "api"), false).join());
        service.setEnabled(instance, List.of("first", "second", "api"), false).join();
        assertConflict(() -> service.setEnabled(instance, List.of("second"), true).join());
        assertEquals(Set.of("first", "second"), repository.findByProjectId(instance, "api").orElseThrow().requiredByProjectIds());
    }

    @Test
    void legacyUnknownDependenciesRequireScanOrStoppingAllUnknownOwners() throws Exception {
        createModFile("root.jar", true);
        createModFile("api.jar", true);
        repository.saveAll(instance, List.of(record("root", true, false, "").withDependencyMetadataKnown(false),
                record("api", true, false, "")));

        assertConflict(() -> service.uninstall(instance, List.of("api")).join());
        assertConflict(() -> service.setEnabled(instance, List.of("api"), false).join());
        service.setEnabled(instance, List.of("root"), false).join();
        service.uninstall(instance, List.of("api")).join();
        assertFalse(Files.exists(instance.modsDirectory().resolve("api.jar")));
    }

    @Test
    void uninstallRemovesOnlySelectedUnreferencedFile() throws Exception {
        createModFile("first.jar", true);
        createModFile("second.jar", true);
        repository.saveAll(instance, List.of(
                record("first", true, false, ""),
                record("second", true, false, "")));

        List<InstalledMod> remaining = service.uninstall(instance, List.of("first")).join();
        assertEquals(List.of("second"), remaining.stream().map(InstalledMod::projectId).toList());
        assertFalse(Files.exists(instance.modsDirectory().resolve("first.jar")));
        assertTrue(Files.exists(instance.modsDirectory().resolve("second.jar")));
    }

    @Test
    void enablingOwnerRequiresItsDependencyOrBatchEnable() throws Exception {
        createModFile("root.jar", false);
        createModFile("api.jar", false);
        repository.saveAll(instance, List.of(
                record("root", false, false, ""),
                record("api", false, true, "root")));
        assertConflict(() -> service.setEnabled(instance, List.of("root"), true).join());
        List<InstalledMod> result = service.setEnabled(instance, List.of("root", "api"), true).join();
        assertTrue(result.stream().allMatch(InstalledMod::enabled));
    }

    private void createModFile(String name, boolean enabled) throws Exception {
        Path directory = enabled ? instance.modsDirectory() : instance.gameDirectory().resolve("disabled-mods");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), name);
    }

    private InstalledMod record(String project, boolean enabled, boolean dependency, String requiredBy) {
        Instant now = Instant.now();
        Path relative = Path.of(enabled ? "mods" : "disabled-mods", project + ".jar");
        return new InstalledMod(instance.instanceId(), project, project + "-v1", project, project,
                "1.0", project + ".jar", relative, "", "", project.length(),
                instance.minecraftVersion(), instance.loaderName(), "release", enabled,
                dependency, requiredBy, now, now);
    }

    private static void assertConflict(Runnable action) {
        CompletionException thrown = assertThrows(CompletionException.class, action::run);
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertInstanceOf(ModConflictException.class, cause);
    }
}
