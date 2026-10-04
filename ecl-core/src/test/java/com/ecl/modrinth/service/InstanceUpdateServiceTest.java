package com.ecl.modrinth.service;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
