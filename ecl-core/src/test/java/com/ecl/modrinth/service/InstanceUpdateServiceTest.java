package com.ecl.modrinth.service;

import com.ecl.launch.GameProcessMarker;
import com.ecl.launcher.LoaderUpdateService;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.modrinth.TestFixtures;
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
                ReleaseChannel.RELEASE_ONLY, progress -> stages.add(progress.stage()));
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
