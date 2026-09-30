package com.ecl.launcher;

import com.ecl.game.DefaultGameRepository;
import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import com.ecl.launch.GameProcessMarker;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.operation.InstanceOperationCoordinator;
import com.ecl.util.HttpUtil;
import com.ecl.util.InstanceOperationLease;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderUpdateServiceTest {
    @TempDir Path temp;

    @ParameterizedTest
    @EnumSource(ModLoaderInstaller.Loader.class)
    void preservesIdentityRunDirectoryAndSettingsAndReplacesLaunchMetadata(ModLoaderInstaller.Loader loader) throws Exception {
        ModInstanceContext instance = instance(loader, "1.0");
        Path save = instance.gameDirectory().resolve("saves/world/level.dat");
        Files.createDirectories(save.getParent());
        Files.writeString(save, "world");
        Path settings = instance.gameDirectory().resolve(".ecl/config/launch-profile.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "launch settings");
        FakeBackend backend = new FakeBackend(loader);

        LoaderUpdateService.Result result = service(backend, new AtomicBoolean()).update(instance, null).join();

        assertTrue(result.updated());
        VersionMetadata metadata = new VersionRepository(versions().toFile()).resolve(instance.profileId());
        assertEquals("2.0", metadata.modLoaderVersion());
        assertEquals("new.Main", metadata.mainClass());
        assertEquals("new-loader", HttpUtil.readJson(profile().toFile()).get("inheritsFrom").getAsString());
        assertFalse(metadata.libraries().stream().anyMatch(library -> library.name().contains("old")));
        ModInstanceContext after = VersionProfileModInstanceContext.load(instance.profileId(), versions(), gameRoot());
        assertEquals(instance.instanceId(), after.instanceId());
        assertEquals(instance.gameDirectory(), after.gameDirectory());
        assertEquals(instance.gameDirectory(), new DefaultGameRepository(versions(), gameRoot()).runDirectory(instance.profileId()));
        assertEquals("world", Files.readString(save));
        assertEquals("launch settings", Files.readString(settings));
        assertEquals("keep", HttpUtil.readJson(profile().toFile()).get("eclCustomSetting").getAsString());
    }

    @Test
    void keepsModpackSourceMetadata() throws Exception {
        ModInstanceContext instance = instance(ModLoaderInstaller.Loader.FABRIC, "1.0");
        JsonObject original = HttpUtil.readJson(profile().toFile());
        original.addProperty("eclModpackName", "Pack");
        original.addProperty("eclModpackVersionId", "pack-v1");
        HttpUtil.writeJson(profile().toFile(), original);
        service(new FakeBackend(ModLoaderInstaller.Loader.FABRIC), new AtomicBoolean()).update(instance, null).join();
        JsonObject updated = HttpUtil.readJson(profile().toFile());
        assertEquals("Pack", updated.get("eclModpackName").getAsString());
        assertEquals("pack-v1", updated.get("eclModpackVersionId").getAsString());
    }

    @Test
    void doesNotReinstallOrDowngradeAnUpToDateLoader() throws Exception {
        ModInstanceContext instance = instance(ModLoaderInstaller.Loader.FABRIC, "3.0");
        FakeBackend backend = new FakeBackend(ModLoaderInstaller.Loader.FABRIC);
        String original = Files.readString(profile());
        assertFalse(service(backend, new AtomicBoolean()).update(instance, null).join().updated());
        assertEquals(0, backend.installs);
        assertEquals(original, Files.readString(profile()));
    }

    @Test
    void failedInstallLeavesOriginalProfileIntact() throws Exception {
        ModInstanceContext instance = instance(ModLoaderInstaller.Loader.FABRIC, "1.0");
        FakeBackend backend = new FakeBackend(ModLoaderInstaller.Loader.FABRIC);
        backend.fail = true;
        String original = Files.readString(profile());
        assertThrows(CompletionException.class, () -> service(backend, new AtomicBoolean()).update(instance, null).join());
        assertEquals(original, Files.readString(profile()));
    }

    @Test
    void restoresOriginalMetadataIfInstallerOverwritesItBeforeFailing() throws Exception {
        ModInstanceContext instance = instance(ModLoaderInstaller.Loader.FABRIC, "1.0");
        FakeBackend backend = new FakeBackend(ModLoaderInstaller.Loader.FABRIC);
        backend.overwriteOriginal = true;
        backend.fail = true;
        JsonObject original = HttpUtil.readJson(profile().toFile());
        assertThrows(CompletionException.class, () -> service(backend, new AtomicBoolean()).update(instance, null).join());
        assertEquals(original, HttpUtil.readJson(profile().toFile()));
    }

    @Test
    void rejectsAnotherMinecraftVersionAndKeepsOriginalProfile() throws Exception {
        ModInstanceContext instance = instance(ModLoaderInstaller.Loader.FABRIC, "1.0");
        FakeBackend backend = new FakeBackend(ModLoaderInstaller.Loader.FABRIC);
        backend.minecraftVersion = "1.20.1";
        write("1.20.1", "{\"id\":\"1.20.1\"}");
        String original = Files.readString(profile());
        assertThrows(CompletionException.class, () -> service(backend, new AtomicBoolean()).update(instance, null).join());
        assertEquals(original, Files.readString(profile()));
    }

    @Test
    void rejectsRunningGameEvenAfterLauncherRestart() throws Exception {
        ModInstanceContext instance = instance(ModLoaderInstaller.Loader.FABRIC, "1.0");
        GameProcessMarker.record(instance.gameDirectory(), ProcessHandle.current());
        FakeBackend backend = new FakeBackend(ModLoaderInstaller.Loader.FABRIC);
        assertThrows(CompletionException.class, () -> service(backend, new AtomicBoolean()).update(instance, null).join());
        assertEquals(0, backend.installs);
    }

    @Test
    void rejectsBusyInstanceAndRechecksRunningStateBeforeReplacingProfile() throws Exception {
        ModInstanceContext instance = instance(ModLoaderInstaller.Loader.FABRIC, "1.0");
        FakeBackend backend = new FakeBackend(ModLoaderInstaller.Loader.FABRIC);
        AtomicBoolean running = new AtomicBoolean();
        LoaderUpdateService service = service(backend, running);
        try (InstanceOperationLease lease = InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
            assertTrue(lease != null);
            assertThrows(CompletionException.class, () -> service.update(instance, null).join());
            assertEquals(0, backend.installs);
        }
        String original = Files.readString(profile());
        backend.afterInstall = () -> running.set(true);
        assertThrows(CompletionException.class, () -> service.update(instance, null).join());
        assertEquals(original, Files.readString(profile()));
    }

    private LoaderUpdateService service(FakeBackend backend, AtomicBoolean running) {
        return new LoaderUpdateService(versions(), backend, new InstanceOperationCoordinator(), Runnable::run, ignored -> running.get());
    }

    private ModInstanceContext instance(ModLoaderInstaller.Loader loader, String version) throws IOException {
        write("1.21.1", "{\"id\":\"1.21.1\",\"mainClass\":\"base.Main\"}");
        write("original", """
                {"id":"original","inheritsFrom":"1.21.1","mainClass":"old.Main",
                 "eclMinecraftVersion":"1.21.1","eclModLoader":"%s","eclModLoaderVersion":"%s",
                 "eclCustomSetting":"keep","libraries":[{"name":"old:loader:1.0"}]}
                """.formatted(loader.id(), version));
        return VersionProfileModInstanceContext.load("original", versions(), gameRoot());
    }

    private Path versions() { return temp.resolve("metadata"); }
    private Path gameRoot() { return temp.resolve("minecraft"); }
    private Path profile() { return versions().resolve("original/original.json"); }

    private void write(String id, String json) throws IOException {
        Path file = versions().resolve(id).resolve(id + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private final class FakeBackend implements LoaderUpdateService.Backend {
        private final ModLoaderInstaller.Loader expectedLoader;
        private String minecraftVersion = "1.21.1";
        private int installs;
        private boolean fail;
        private boolean overwriteOriginal;
        private Runnable afterInstall = () -> { };

        private FakeBackend(ModLoaderInstaller.Loader expectedLoader) { this.expectedLoader = expectedLoader; }

        @Override
        public List<String> listVersions(String minecraftVersion, ModLoaderInstaller.Loader loader) {
            assertEquals("1.21.1", minecraftVersion);
            assertEquals(expectedLoader, loader);
            return List.of("2.0", "1.0");
        }

        @Override
        public ModLoaderInstaller.InstallResult install(String minecraftVersion, ModLoaderInstaller.Loader loader,
                                                        String version, ModLoaderInstaller.Listener listener) throws IOException {
            installs++;
            if (overwriteOriginal) Files.writeString(profile(), "broken metadata");
            if (fail) throw new IOException("download failed");
            write("new-loader", """
                    {"id":"new-loader","inheritsFrom":"%s","mainClass":"new.Main",
                     "eclMinecraftVersion":"%s","eclModLoader":"%s","eclModLoaderVersion":"%s"}
                    """.formatted(this.minecraftVersion, this.minecraftVersion, loader.id(), version));
            afterInstall.run();
            return new ModLoaderInstaller.InstallResult("new-loader", this.minecraftVersion, loader, version);
        }
    }
}
