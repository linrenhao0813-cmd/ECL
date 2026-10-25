package com.ecl.modrinth.service;

import com.ecl.ECLConfig;
import com.ecl.launch.GameProcessMarker;
import com.ecl.launcher.LoaderUpdateService;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.modrinth.TestFixtures;
import com.ecl.modrinth.pack.ModpackInstance;
import com.ecl.modrinth.pack.ModpackUpdate;
import com.ecl.modrinth.pack.ModpackUpdateService;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.operation.InstanceOperationCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceUpdateServiceTest {
    @TempDir Path temp;

    private Field baseDirField;
    private File previousBaseDir;

    @BeforeEach
    void useTemporaryBaseDirectory() throws Exception {
        // Initialize the process-wide file appender before replacing this test's data root.
        org.slf4j.LoggerFactory.getILoggerFactory();
        baseDirField = ECLConfig.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        previousBaseDir = (File) baseDirField.get(null);
        baseDirField.set(null, temp.resolve("ecl").toFile());
    }

    @AfterEach
    void restoreBaseDirectory() throws Exception {
        baseDirField.set(null, previousBaseDir);
    }

    @Test
    void updatesSequentiallyContinuesAfterFailureAndSkipsUnsafeCandidates() throws Exception {
        ModInstanceContext instance = instance();
        InstalledMod first = mod(instance, "first", true);
        InstalledMod second = mod(instance, "second", true);
        List<InstalledMod> records = List.of(first, second, mod(instance, "disabled", false),
                mod(instance, "local:unknown", true), mod(instance, "damaged", true),
                mod(instance, "unrecognized-old-record", true),
                mod(instance, "duplicate", true), mod(instance, "duplicate", true));
        List<LocalModScanItem> items = records.stream().map(mod -> new LocalModScanItem(
                instance.gameDirectory().resolve(mod.relativePath()), mod, !mod.projectId().equals("unrecognized-old-record"),
                mod.projectId().equals("damaged"), "")).toList();
        LocalModScanResult scan = new LocalModScanResult(records, items, List.of("duplicate"), List.of("warning"));
        FakeUpdates mods = new FakeUpdates();
        List<InstanceUpdateService.Stage> stages = new ArrayList<>();
        InstanceUpdateService service = new InstanceUpdateService(loaders(false),
                ignored -> CompletableFuture.completedFuture(scan), mods, ignored -> false);

        CompletableFuture<InstanceUpdateService.Result> work = service.update(instance,
                ReleaseChannel.RELEASE_ONLY, progress -> stages.add(progress.stage()),
                temp.resolve("versions"), temp.resolve("game"), new FakePacks());
        assertEquals(List.of("first", "second"), mods.checked);
        assertEquals(List.of("first"), mods.applied);
        assertFalse(work.isDone());
        mods.first.completeExceptionally(new IOException("download failed"));
        InstanceUpdateService.Result result = work.join();

        assertEquals(List.of("first", "second"), mods.applied);
        assertEquals(1, result.updatedMods());
        assertEquals(6, result.skippedMods());
        assertEquals(1, result.failures().size());
        assertEquals("first", result.failures().getFirst().name());
        assertEquals("download failed", result.failures().getFirst().cause().getMessage());
        assertEquals(List.of("warning"), result.warnings());
        assertEquals(List.of(InstanceUpdateService.Stage.LOADER, InstanceUpdateService.Stage.SCAN,
                InstanceUpdateService.Stage.CHECK, InstanceUpdateService.Stage.MOD, InstanceUpdateService.Stage.MOD), stages);
    }

    @Test
    void refusesRunningInstancesBeforeAnyNetworkOrFileWork() throws Exception {
        ModInstanceContext instance = instance();
        InstanceUpdateService service = new InstanceUpdateService(loaders(false),
                ignored -> { throw new AssertionError("scanner must not run"); }, new FakeUpdates(), ignored -> true);
        assertThrows(CompletionException.class, () -> service.update(instance, ReleaseChannel.ALL, null).join());
        GameProcessMarker.record(instance.gameDirectory(), ProcessHandle.current());
        InstanceUpdateService restarted = new InstanceUpdateService(loaders(false),
                ignored -> { throw new AssertionError("scanner must not run"); }, new FakeUpdates(), ignored -> false);
        assertThrows(CompletionException.class, () -> restarted.update(instance, ReleaseChannel.ALL, null).join());
    }

    @Test
    void stopsBeforeModUpdatesIfLoaderQueryFails() throws Exception {
        ModInstanceContext instance = instance();
        InstanceUpdateService service = new InstanceUpdateService(loaders(true),
                ignored -> { throw new AssertionError("scanner must not run"); }, new FakeUpdates(), ignored -> false);
        assertThrows(CompletionException.class, () -> service.update(instance, ReleaseChannel.ALL, null).join());
    }

    @Test
    void previewDoesNotWriteIndexesAndExpiresWhenManagedFilesChange() throws Exception {
        ModInstanceContext instance = instance();
        InstalledMod updateCandidate = mod(instance, "first", true);
        LocalModScanResult scan = new LocalModScanResult(List.of(updateCandidate), List.of(
                new LocalModScanItem(instance.gameDirectory().resolve(updateCandidate.relativePath()),
                        updateCandidate, true, false, "")), List.of(), List.of());
        FakeUpdates updates = new FakeUpdates();
        LocalModScanner scanner = new LocalModScanner() {
            @Override public CompletableFuture<LocalModScanResult> scan(ModInstanceContext target) {
                return CompletableFuture.completedFuture(scan);
            }

            @Override public CompletableFuture<LocalModScanResult> inspect(ModInstanceContext target) {
                return CompletableFuture.completedFuture(scan);
            }
        };
        InstanceUpdateService service = new InstanceUpdateService(loaders(false), scanner,
                updates, ignored -> false);

        InstanceUpdateService.Preview preview = service.preview(instance, ReleaseChannel.RELEASE_ONLY,
                null, temp.resolve("versions"), temp.resolve("game"), new FakePacks()).join();

        assertTrue(preview.hasUpdates());
        assertEquals("new-first", preview.modUpdates().getFirst().availableVersion().id());
        assertFalse(Files.exists(instance.gameDirectory().resolve("launcher-mods.json")));
        assertFalse(Files.exists(instance.gameDirectory().resolve("launcher-mod-scan.json")));

        Files.writeString(instance.gameDirectory().resolve(updateCandidate.relativePath()), "changed");
        assertThrows(CompletionException.class, () -> service.prepare(preview, Set.of()).join());
    }

    @Test
    void rechecksRunningStateBeforeInstallingAMod() throws Exception {
        ModInstanceContext instance = instance();
        InstalledMod mod = mod(instance, "first", true);
        AtomicBoolean running = new AtomicBoolean();
        FakeUpdates updates = new FakeUpdates();
        InstanceUpdateService service = new InstanceUpdateService(loaders(false), ignored -> {
            running.set(true);
            return CompletableFuture.completedFuture(new LocalModScanResult(List.of(mod), List.of(
                    new LocalModScanItem(instance.gameDirectory().resolve(mod.relativePath()), mod, true, false, "")),
                    List.of(), List.of()));
        }, updates, ignored -> running.get());
        assertThrows(CompletionException.class, () -> service.update(instance, ReleaseChannel.ALL, null).join());
        assertTrue(updates.applied.isEmpty());
    }

    @Test
    void packUpgradeUsesOnlySelectedPackAndKeepsAuthorComponents() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        List<InstanceUpdateService.Stage> stages = new ArrayList<>();
        InstanceUpdateService.Result result = packService().update(instance, ReleaseChannel.ALL,
                update -> stages.add(update.stage()), temp.resolve("versions"), temp.resolve("game"), packs).join();
        assertEquals(instance.profileId(), packs.checked.profileId());
        assertEquals(ReleaseChannel.ALL, packs.channel);
        assertEquals(1, packs.applied);
        assertEquals("2.0", result.packVersion());
        assertEquals(List.of(InstanceUpdateService.Stage.PACK_CHECK, InstanceUpdateService.Stage.PACK), stages);
    }

    @Test
    void packPreviewChecksThePackVersionWithoutApplyingIt() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        Path profile = temp.resolve("versions").resolve(instance.profileId())
                .resolve(instance.profileId() + ".json");
        String before = Files.readString(profile);
        InstanceUpdateService service = packService();

        InstanceUpdateService.Preview preview = service.preview(instance, ReleaseChannel.ALL, null,
                temp.resolve("versions"), temp.resolve("game"), packs).join();

        assertTrue(preview.packProfile());
        assertTrue(preview.hasUpdates());
        assertEquals("new", preview.packUpdate().availableVersion().versionNumber());
        assertEquals(0, packs.applied);
        assertEquals(before, Files.readString(profile));
    }

    @Test
    void packUpdateRunsPreviewPrepareAndExecuteWithPackService() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        InstanceUpdateService service = packService();
        Path metadataRoot = temp.resolve("versions");
        Path gameRoot = temp.resolve("game");

        InstanceUpdateService.Preview preview = service.preview(instance, ReleaseChannel.ALL, null,
                metadataRoot, gameRoot, packs).join();
        assertTrue(preview.packProfile());
        assertTrue(preview.hasUpdates());
        assertEquals(0, packs.prepared);
        assertEquals(0, packs.appliedPrepared);

        InstanceUpdateService.PreparedExecution execution = service
                .prepare(preview, Set.of(), packs, null).join();
        assertEquals(1, packs.prepared);
        assertNotNull(execution.packUpdate());
        assertEquals(instance.profileId(), execution.packUpdate().preview().profileId());

        InstanceUpdateService.Result result = service.execute(execution, null, packs).join();
        assertEquals(1, packs.appliedPrepared);
        assertEquals("new", result.packVersion());
        assertEquals(0, result.updatedMods());
        assertTrue(result.failures().isEmpty());
        assertFalse(Files.exists(packs.preparedWorkingDirectory),
                "Prepared pack staging must be cleaned up after execute");
    }

    @Test
    void cancellingBeforeSnapshotRunsCannotSkipInternalCleanup() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        InstanceUpdateService service = packService();
        var preview = service.preview(instance, ReleaseChannel.ALL, null,
                temp.resolve("versions"), temp.resolve("game"), packs).join();
        var prepared = service.prepare(preview, Set.of(), packs, null).join();
        List<Runnable> scheduled = new ArrayList<>();
        var queuedService = new InstanceUpdateService(loaders(true),
                ignored -> { throw new AssertionError("pack scanner"); }, new FakeUpdates(), ignored -> false,
                scheduled::add, service.recoveryPoints());
        var result = queuedService.execute(prepared, null, packs);
        assertTrue(result.cancel(false));
        scheduled.removeFirst().run();
        try (var lease = com.ecl.util.InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
            assertNotNull(lease, "cancelling the observer must not strand snapshot ownership");
        }
        assertEquals(1, packs.appliedPrepared);
        assertFalse(Files.exists(packs.preparedWorkingDirectory));
    }

    @Test
    void acceptedPreparationDoesNotExpireWhileWaitingForFinalConfirmation() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        var service = packService();
        var preview = service.preview(instance, ReleaseChannel.ALL, null,
                temp.resolve("versions"), temp.resolve("game"), packs).join();
        var prepared = service.prepare(preview, Set.of(), packs, null).join();
        var old = new InstanceUpdateService.Preview(preview.instance(), Instant.EPOCH,
                preview.metadataRoot(), preview.gameRoot(), preview.fingerprint(), preview.loaderPlan(),
                preview.modUpdates(), preview.installedMods(), preview.protectedProjects(), preview.skippedMods(),
                preview.packInstance(), preview.packUpdate());
        assertThrows(CompletionException.class, () -> service.prepare(old, Set.of(), packs, null).join());
        var accepted = new InstanceUpdateService.PreparedExecution(old, prepared.modUpdates(), prepared.packUpdate());
        assertEquals("new", service.execute(accepted, null, packs).join().packVersion());
    }

    @Test
    void packUpgradePreservesWarningsInProgressAndResult() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        packs.warnings = List.of("警告：旧版文件已被用户修改，更新时予以保留: mods/removed.jar",
                "未找到旧整合包文件清单；本次更新不会删除旧版遗留文件");
        List<String> messages = new ArrayList<>();
        InstanceUpdateService.Result result = packService().update(instance, ReleaseChannel.ALL,
                update -> messages.add(update.detail()), temp.resolve("versions"), temp.resolve("game"), packs).join();
        assertEquals(packs.warnings, result.warnings());
        assertTrue(messages.containsAll(packs.warnings));
        assertTrue(messages.contains("正在下载整合包文件"));
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().add("extra"));
    }

    @Test
    void currentPackDoesNotInstallOrUpgradeComponents() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        packs.current = true;
        InstanceUpdateService.Result result = packService().update(instance, null, null,
                temp.resolve("versions"), temp.resolve("game"), packs).join();
        assertEquals("", result.packVersion());
        assertEquals(0, packs.applied);
    }

    @Test
    void packCheckFailureAndMissingSourceDoNotFallBackToComponentUpgrades() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        packs.fail = true;
        assertThrows(CompletionException.class, () -> packService().update(instance, ReleaseChannel.ALL, null,
                temp.resolve("versions"), temp.resolve("game"), packs).join());
        assertEquals(0, packs.applied);
        assertNotNull(packs.checked);
        ModInstanceContext withoutSource = packInstance(false);
        FakePacks unused = new FakePacks();
        assertThrows(CompletionException.class, () -> packService().update(withoutSource, ReleaseChannel.ALL, null,
                temp.resolve("versions"), temp.resolve("game"), unused).join());
        assertNull(unused.checked);
    }

    @Test
    void runningPackIsRejectedBeforeCheckingUpdates() throws Exception {
        ModInstanceContext instance = packInstance(true);
        FakePacks packs = new FakePacks();
        InstanceUpdateService service = new InstanceUpdateService(loaders(true),
                ignored -> { throw new AssertionError("scanner must not run"); }, new FakeUpdates(), ignored -> true);
        assertThrows(CompletionException.class, () -> service.update(instance, ReleaseChannel.ALL, null,
                temp.resolve("versions"), temp.resolve("game"), packs).join());
        assertNull(packs.checked);
        assertEquals(0, packs.applied);
    }

    private ModInstanceContext packInstance(boolean withSource) throws IOException {
        ModInstanceContext instance = instance();
        Path profile = temp.resolve("versions").resolve(instance.profileId()).resolve(instance.profileId() + ".json");
        Files.writeString(profile, """
                {"id":"%s","eclModpackName":"Example Pack", "eclModpackSource":"%s",
                 "eclModpackProjectId":"pack-project", "eclModpackVersionId":"old"}
                """.formatted(instance.profileId(), withSource ? "modrinth" : ""));
        return instance;
    }

    private InstanceUpdateService packService() {
        return new InstanceUpdateService(loaders(true),
                ignored -> { throw new AssertionError("pack components must not be scanned"); }, new FakeUpdates(), ignored -> false);
    }

    private static final class FakePacks implements ModpackUpdateService {
        private ModpackInstance checked;
        private ReleaseChannel channel;
        private int applied;
        private int prepared;
        private int appliedPrepared;
        private Path preparedWorkingDirectory;
        private boolean current;
        private boolean fail;
        private List<String> warnings = List.of();

        @Override public CompletableFuture<ModpackUpdate> checkUpdate(ModpackInstance instance, ReleaseChannel releaseChannel) {
            checked = instance;
            channel = releaseChannel;
            if (fail) return CompletableFuture.failedFuture(new IOException("metadata failed"));
            if (current) return CompletableFuture.completedFuture(null);
            var version = TestFixtures.fabricVersion("new", instance.projectId(), List.of());
            return CompletableFuture.completedFuture(new ModpackUpdate(instance, version, version.files().getFirst()));
        }

        @Override public CompletableFuture<MrpackInstaller.InstallResult> applyUpdate(
                ModpackUpdate update, Path root, MrpackInstaller.Listener listener) {
            applied++;
            if (!warnings.isEmpty()) {
                listener.onStatus("正在下载整合包文件");
                warnings.forEach(listener::onWarning);
            }
            return CompletableFuture.completedFuture(new MrpackInstaller.InstallResult(
                    update.instance().profileId(), "Example Pack", "2.0", "1.21.1", "fabric", root, 0));
        }

        @Override public CompletableFuture<PreparedUpdate> prepareUpdate(
                ModpackUpdate update, Path root, MrpackInstaller.Listener listener) {
            prepared++;
            try {
                Path base = ECLConfig.getBaseDir().toPath();
                Files.createDirectories(base);
                Path work = Files.createTempDirectory(base, MrpackInstaller.PREVIEW_DIRECTORY_PREFIX);
                preparedWorkingDirectory = work;
                Path stagedFiles = Files.createDirectory(work.resolve("files"));
                Path stagedVersions = Files.createDirectory(work.resolve("versions"));
                Path stagedLibraries = Files.createDirectory(work.resolve("libraries"));
                Path archive = Files.writeString(work.resolve("update.mrpack"), "fake pack");
                if (!warnings.isEmpty()) {
                    listener.onStatus("正在准备整合包文件");
                    warnings.forEach(listener::onWarning);
                }
                MrpackInstaller.UpdatePreview preview = new MrpackInstaller.UpdatePreview(
                        work, update.instance().profileId(), update.instance().projectId(),
                        update.availableVersion().id(), update.instance().minecraftVersion(),
                        update.availableVersion().versionNumber(), "fabric", "1.0", "", archive,
                        stagedFiles, stagedVersions, stagedLibraries, "", Map.of(), List.of(), 0, Map.of());
                return CompletableFuture.completedFuture(new PreparedUpdate(update, preview));
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public CompletableFuture<MrpackInstaller.InstallResult> applyPreparedUpdate(
                PreparedUpdate prepared, Path root, MrpackInstaller.Listener listener) {
            appliedPrepared++;
            return CompletableFuture.completedFuture(new MrpackInstaller.InstallResult(
                    prepared.update().instance().profileId(), "Example Pack",
                    prepared.preview().packVersion(), "1.21.1", "fabric", root, 0));
        }

        @Override public CompletableFuture<MrpackInstaller.InstallResult> applyPreparedUpdate(
                PreparedUpdate prepared, Path root, MrpackInstaller.Listener listener,
                com.ecl.operation.InstanceOperationSession session) {
            try (var borrow = session.borrow(prepared.update().instance().instanceId(),
                    prepared.update().instance().instanceDirectory())) {
                return applyPreparedUpdate(prepared, root, listener);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private ModInstanceContext instance() throws IOException {
        ModInstanceContext instance = TestFixtures.instance(temp.resolve("game"));
        Path profile = temp.resolve("versions").resolve(instance.profileId()).resolve(instance.profileId() + ".json");
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, """
                {"id":"%s","eclMinecraftVersion":"1.21.1","eclModLoader":"fabric","eclModLoaderVersion":"1.0"}
                """.formatted(instance.profileId()));
        return instance;
    }

    private LoaderUpdateService loaders(boolean fail) {
        return new LoaderUpdateService(temp.resolve("versions"), new LoaderUpdateService.Backend() {
            @Override public List<String> listVersions(String minecraftVersion, ModLoaderInstaller.Loader loader) throws IOException {
                if (fail) throw new IOException("metadata failed");
                return List.of("1.0");
            }
            @Override public ModLoaderInstaller.InstallResult install(String minecraftVersion, ModLoaderInstaller.Loader loader,
                                                                      String version, ModLoaderInstaller.Listener listener) {
                throw new AssertionError("up-to-date loader must not be reinstalled");
            }
        }, new InstanceOperationCoordinator(), Runnable::run, ignored -> false);
    }

    private InstalledMod mod(ModInstanceContext instance, String project, boolean enabled) throws IOException {
        Path relative = Path.of(enabled ? "mods" : "disabled-mods", project.replace(':', '_') + ".jar");
        Path file = instance.gameDirectory().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "mod");
        return new InstalledMod(instance.instanceId(), project, "old", "", project, "1.0", file.getFileName().toString(),
                relative, "hash", "", 3, instance.minecraftVersion(), instance.loaderName(), "release", enabled,
                false, "", Instant.EPOCH, Instant.EPOCH);
    }

    private static final class FakeUpdates implements ModUpdateService {
        private List<String> checked;
        private final List<String> applied = new ArrayList<>();
        private final CompletableFuture<ModInstallationResult> first = new CompletableFuture<>();

        @Override
        public CompletableFuture<ModInstallationResult> applyUpdate(ModUpdate update, java.util.Set<String> protectedProjects) {
            assertTrue(protectedProjects.contains("disabled"));
            assertTrue(protectedProjects.contains("duplicate"));
            assertTrue(protectedProjects.contains("unrecognized-old-record"));
            return applyUpdate(update);
        }

        @Override
        public CompletableFuture<List<ModUpdate>> checkUpdates(ModInstanceContext instance,
                                                               Collection<InstalledMod> installed, ReleaseChannel channel) {
            checked = installed.stream().map(InstalledMod::projectId).toList();
            return CompletableFuture.completedFuture(installed.stream().map(mod -> {
                var version = TestFixtures.fabricVersion("new-" + mod.projectId(), mod.projectId(), List.of());
                return new ModUpdate(mod, version, version.files().getFirst(), channel);
            }).toList());
        }

        @Override
        public CompletableFuture<ModInstallationResult> applyUpdate(ModUpdate update) {
            applied.add(update.installedMod().projectId());
            return applied.size() == 1 ? first : CompletableFuture.completedFuture(new ModInstallationResult(List.of(), true));
        }
    }
}
