package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MicrosoftAccountStore;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.backup.BackupEntry;
import com.ecl.backup.WorldBackupService;
import com.ecl.download.GameDownloader;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.game.WorldSave;
import com.ecl.game.WorldSaveService;
import com.ecl.game.WorldSaveSettings;
import com.ecl.launch.GameProcess;
import com.ecl.launch.GameProcessMarker;
import com.ecl.launch.LaunchOptions;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.util.InstanceOperationLease;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Opt-in live validation. Requires browser authorization and two real interactive game sessions. */
public final class LauncherRuntimeValidation {
    private static final String MINECRAFT_VERSION = "1.21.1";
    private final Path evidence;
    private final Map<String, String> results = new TreeMap<>();
    private String stage = "initialization";

    private LauncherRuntimeValidation(Path evidence) {
        this.evidence = evidence;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply the isolated evidence directory");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!ECLConfig.getBaseDir().toPath().toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalStateException("APPDATA must be inside the isolated evidence directory");
        }
        Files.createDirectories(root);
        LauncherRuntimeValidation validation = new LauncherRuntimeValidation(root);
        try {
            validation.run();
            validation.results.put("result", "PASS");
            validation.milestone("RUNTIME_VALIDATION_PASS");
        } catch (Exception failure) {
            Throwable rootCause = failure;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) rootCause = rootCause.getCause();
            validation.results.put("result", "FAIL");
            validation.results.put("failedStage", validation.stage);
            validation.results.put("failureType", rootCause.getClass().getSimpleName());
            System.out.println("RUNTIME_VALIDATION_FAIL stage=" + validation.stage
                    + " type=" + rootCause.getClass().getSimpleName());
        } finally {
            validation.writeEvidence();
        }
        if (!"PASS".equals(validation.results.get("result"))) System.exit(1);
    }

    private void run() throws Exception {
        stage = "microsoft-login";
        MicrosoftAuth auth = new MicrosoftAuth(MicrosoftAuth.CachedSession.empty(), new MicrosoftAuth.LoginListener() {
            @Override
            public void onDeviceCode(MicrosoftAuth.DeviceCode deviceCode) {
                System.out.println("MICROSOFT_DEVICE_CODE " + deviceCode.getUserCode());
                System.out.println("MICROSOFT_VERIFICATION_URI " + deviceCode.getVerificationUri());
            }
        });
        try (MainController controller = new MainController()) {
            auth.login();
            require(auth.isLoggedIn(), "Microsoft entitlement and profile validation did not complete");
            milestone("MICROSOFT_LOGIN_PASS");
            validateAccountReload(auth);
            installMinecraft(controller);
            stage = "fabric-install";
            ModLoaderInstaller installer = new ModLoaderInstaller();
            List<String> loaders = installer.listVersions(MINECRAFT_VERSION, ModLoaderInstaller.Loader.FABRIC);
            require(loaders.size() >= 2, "At least two Fabric loader versions are required for upgrade validation");
            String oldLoader = loaders.get(1);
            var installed = installer.install(MINECRAFT_VERSION, ModLoaderInstaller.Loader.FABRIC,
                    oldLoader, ignored -> { });
            String profileId = installed.profileId();
            ((GameDownloader) controller.gameDownloader()).downloadLibrariesForVersion(profileId, quietDownloadListener());
            controller.invalidateLaunchVersion(profileId);
            DefaultGameRepository repository = new DefaultGameRepository(ECLConfig.getVersionsDir().toPath(),
                    ECLConfig.getGameDir().toPath());
            repository.setIsolated(profileId);
            Path instanceRoot = repository.instanceRoot(profileId);
            Path runDirectory = repository.runDirectory(profileId);
            controller.instanceLaunchProfiles().save(instanceRoot, InstanceLaunchProfile.defaults());
            results.put("profileId", profileId);
            results.put("oldLoader", oldLoader);
            milestone("FABRIC_INSTALL_PASS");

            ModInstanceContext instance = new LiveInstance(UUID.nameUUIDFromBytes(profileId.getBytes(StandardCharsets.UTF_8)),
                    profileId, runDirectory);
            controller.registerModInstance(instance);
            stage = "first-game-session";
            System.out.println("USER_ACTION Create a new single-player test world named ECL Runtime Test, enter it, then quit Minecraft.");
            launchAndWait(controller, auth, instance, instanceRoot, oldLoader);
            milestone("FIRST_GAME_LAUNCH_AND_WORLD_CREATION_PASS");
            stage = "world-save-backup-restore";
            validateWorldOperations(repository, instance);

            stage = "instance-upgrade";
            Map<String, String> originalSaves = hashes(runDirectory.resolve("saves"));
            Map<String, String> originalConfig = hashes(instanceRoot.resolve(".ecl/config"));
            var updated = controller.instanceUpdateService().update(instance, ReleaseChannel.RELEASE_ONLY, ignored -> { })
                    .get(20, TimeUnit.MINUTES);
            require(updated.loader().updated(), "Loader was not upgraded");
            require(updated.failures().isEmpty(), "Mod upgrade had failures");
            controller.invalidateLaunchVersion(profileId);
            var metadata = repository.resolve(profileId);
            require(updated.loader().version().equals(metadata.modLoaderVersion()), "Updated metadata is stale");
            require(!oldLoader.equals(metadata.modLoaderVersion()), "Loader version did not change");
            require(runDirectory.equals(repository.runDirectory(profileId)), "Upgrade moved the game directory");
            require(originalSaves.equals(hashes(runDirectory.resolve("saves"))), "Upgrade changed world files");
            require(originalConfig.equals(hashes(instanceRoot.resolve(".ecl/config"))), "Upgrade changed launch configuration");
            results.put("newLoader", metadata.modLoaderVersion());
            milestone("INSTANCE_UPGRADE_AND_PRESERVATION_PASS");
            stage = "upgraded-game-session";
            System.out.println("USER_ACTION Open the ECL Runtime Test world in the upgraded game, enter it, then quit Minecraft.");
            launchAndWait(controller, auth, instance, instanceRoot, metadata.modLoaderVersion());
            require(!new WorldSaveService().scan(repository).isEmpty(), "World no longer scans after upgraded launch");
            milestone("UPGRADED_GAME_LAUNCH_AND_WORLD_LOAD_PASS");
        } finally {
            auth.logout();
        }
    }

    private void validateAccountReload(MicrosoftAuth auth) throws IOException {
        stage = "account-persistence-reload";
        MicrosoftAuth.CachedSession session = auth.getCachedSession();
        MicrosoftAccountStore store = new MicrosoftAccountStore();
        require(store.save(new MicrosoftAccountStore.Account(session.uuid(), session.username(), session.refreshToken(),
                session.accessToken(), session.accessTokenExpiresAt())), "Encrypted account save failed");
        var reloaded = new MicrosoftAccountStore().list().stream().filter(account -> session.uuid().equals(account.uuid()))
                .findFirst().orElseThrow(() -> new IOException("Saved account was not reloaded"));
        require(session.accessToken().equals(reloaded.accessToken())
                && session.refreshToken().equals(reloaded.refreshToken()), "Encrypted credentials did not round trip");
        MicrosoftAuth restored = new MicrosoftAuth(new MicrosoftAuth.CachedSession(reloaded.refreshToken(),
                reloaded.accessToken(), reloaded.accessTokenExpiresAt(), reloaded.username(), reloaded.uuid()), null);
        try {
            restored.login();
            require(restored.isLoggedIn(), "Reloaded Minecraft session failed live validation");
            milestone("ACCOUNT_ENCRYPTED_PERSISTENCE_AND_LIVE_RELOAD_PASS");
        } finally {
            restored.logout();
            require(store.remove(session.uuid()), "Could not remove the isolated test account");
        }
    }

    private void installMinecraft(MainController controller) throws Exception {
        stage = "minecraft-download";
        controller.versions().refresh();
        var target = controller.versions().resolveDownloadTarget(MINECRAFT_VERSION);
        controller.gameDownloader().setListener(quietDownloadListener());
        controller.gameDownloader().downloadVersionAsync(target.downloadVersionId(), target.versionUrl(), target.versionSha1())
                .get(30, TimeUnit.MINUTES);
        require(controller.versions().isVersionDownloaded(MINECRAFT_VERSION), "Game download is incomplete");
        milestone("MINECRAFT_DOWNLOAD_AND_INTEGRITY_PASS");
    }

    private static GameDownloader.DownloadListener quietDownloadListener() {
        return new GameDownloader.DownloadListener() {
            @Override public void onStatus(String message) { }
            @Override public void onProgress(long downloaded, long total) { }
            @Override public void onError(String message) { }
            @Override public void onComplete() { }
        };
    }

    private void launchAndWait(MainController controller, MicrosoftAuth auth, ModInstanceContext instance,
                               Path instanceRoot, String loaderVersion) throws Exception {
        Files.createDirectories(instance.gameDirectory());
        try (InstanceOperationLease lease = InstanceOperationLease.tryAcquire(instance.gameDirectory())) {
            require(lease != null, "Game directory is already running or busy");
            LaunchOptions options = LaunchOptions.builder().versionId(instance.profileId()).auth(auth)
                    .gameDirectory(instance.gameDirectory().toFile()).instanceDirectory(instanceRoot.toFile())
                    .environment(controller.launchEnvironment()).javaExecutablePath(System.getProperty("java.home") + "/bin/java.exe")
                    .maxMemoryMb(2048).gameResolution(960, 600).build();
            AtomicBoolean fabricStarted = new AtomicBoolean();
            AtomicBoolean integratedServerStarted = new AtomicBoolean();
            GameProcess process = controller.gameLauncher().launch(options);
            controller.setInstanceRunning(instance.instanceId(), true);
            try {
                GameProcessMarker.record(instance.gameDirectory(), process.process().toHandle());
                process.attachOutputListener(line -> {
                    if (line.contains("Loading Minecraft " + MINECRAFT_VERSION + " with Fabric Loader " + loaderVersion)) {
                        fabricStarted.set(true);
                    }
                    if (line.contains("Starting integrated minecraft server")) integratedServerStarted.set(true);
                });
                System.out.println("GAME_PROCESS_STARTED phase=" + stage);
                require(process.waitForExit(20, TimeUnit.MINUTES), "Timed out waiting for user to quit Minecraft");
                process.outputPump().awaitStopped(10, TimeUnit.SECONDS);
                require(process.exitCode() == 0, "Minecraft exited unsuccessfully");
                require(fabricStarted.get(), "Fabric loader startup was not observed");
                require(integratedServerStarted.get(), "A real single-player world session was not observed");
            } finally {
                if (process.isAlive()) {
                    process.process().destroy();
                    if (!process.waitForExit(10, TimeUnit.SECONDS)) process.process().destroyForcibly();
                }
                controller.setInstanceRunning(instance.instanceId(), false);
                GameProcessMarker.clear(instance.gameDirectory(), process.process().toHandle());
            }
        }
    }

    private void validateWorldOperations(DefaultGameRepository repository, ModInstanceContext instance) throws Exception {
        WorldSaveService worlds = new WorldSaveService();
        List<WorldSave> found = worlds.scan(repository).stream()
                .filter(save -> save.instanceId().equals(instance.profileId())).toList();
        require(found.size() == 1, "Create exactly one isolated test world for validation");
        WorldSave save = found.getFirst();
        try (Stream<Path> files = Files.walk(save.directory())) {
            require(files.anyMatch(file -> file.getFileName().toString().endsWith(".mca")), "No generated world region file found");
        }
        Map<String, String> original = hashes(instance.gameDirectory().resolve("saves"));
        WorldBackupService backups = new WorldBackupService(evidence.resolve("backups"));
        BackupEntry backup = backups.createBackup(instance.profileId(), MINECRAFT_VERSION, instance.gameDirectory(),
                Set.of(BackupEntry.Content.SAVES), null);
        require(backups.listBackups(instance.profileId()).size() == 1, "Created backup was not listed");
        WorldSaveSettings edited = new WorldSaveSettings(
                save.settings().difficulty() == WorldSaveSettings.Difficulty.PEACEFUL
                        ? WorldSaveSettings.Difficulty.HARD : WorldSaveSettings.Difficulty.PEACEFUL,
                save.settings().gameMode() == WorldSaveSettings.GameMode.CREATIVE
                        ? WorldSaveSettings.GameMode.SURVIVAL : WorldSaveSettings.GameMode.CREATIVE,
                !save.settings().allowCommands());
        worlds.update(save, edited);
        WorldSave rescanned = worlds.scan(repository).stream().filter(item -> item.directory().equals(save.directory()))
                .findFirst().orElseThrow(() -> new IOException("Edited world cannot be scanned"));
        require(edited.equals(rescanned.settings()), "Saved settings did not round trip through level.dat");
        require(!original.equals(hashes(instance.gameDirectory().resolve("saves"))), "World edit did not alter data");
        backups.restore(backup, instance.gameDirectory(), null);
        require(original.equals(hashes(instance.gameDirectory().resolve("saves"))), "Restore changed original world SHA-256 hashes");
        results.put("worldFileCount", Integer.toString(original.size()));
        milestone("REAL_WORLD_SCAN_EDIT_BACKUP_RESTORE_SHA256_PASS");
    }

    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        if (!Files.isDirectory(directory)) return hashes;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[65_536];
                    int read;
                    while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                }
                hashes.put(directory.relativize(file).toString(), HexFormat.of().formatHex(digest.digest()));
            }
        }
        return hashes;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private void milestone(String name) {
        results.put(name, "true");
        System.out.println(name);
    }

    private void writeEvidence() throws IOException {
        results.put("completedAt", Instant.now().toString());
        StringBuilder text = new StringBuilder();
        results.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.writeString(evidence.resolve("result.properties"), text, StandardCharsets.UTF_8);
    }

    private record LiveInstance(UUID instanceId, String profileId, Path gameDirectory) implements ModInstanceContext {
        @Override public String minecraftVersion() { return MINECRAFT_VERSION; }
        @Override public ModLoader loader() { return ModLoader.FABRIC; }
        @Override public Path modsDirectory() { return gameDirectory.resolve("mods"); }
    }
}
