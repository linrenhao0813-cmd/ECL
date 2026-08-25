package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.download.DownloadService;
import com.ecl.download.GameDownloader;
import com.ecl.download.ContentDownloader;
import com.ecl.download.CurseForgeDownloader;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.ModrinthDownloader;
import com.ecl.launch.DefaultLauncher;
import com.ecl.launch.LaunchEnvironment;
import com.ecl.launch.Launcher;
import com.ecl.game.InstanceLaunchProfileStore;
import com.ecl.game.VersionRepository;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.api.DefaultModrinthApiClient;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.download.ModFileDownloadService;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ContentSource;
import com.ecl.modrinth.provider.CurseForgeMetadataProvider;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.modrinth.provider.ModMetadataProviderRegistry;
import com.ecl.modrinth.provider.ModrinthMetadataProvider;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import com.ecl.modrinth.repository.InstalledModRepository;
import com.ecl.modrinth.service.DefaultLocalModScanner;
import com.ecl.modrinth.service.DefaultModDependencyResolver;
import com.ecl.modrinth.service.DefaultModManagementService;
import com.ecl.modrinth.service.DefaultModUpdateService;
import com.ecl.modrinth.service.DefaultModVersionSelector;
import com.ecl.modrinth.service.LocalModScanner;
import com.ecl.modrinth.service.ModDependencyResolver;
import com.ecl.modrinth.service.ModInstallationService;
import com.ecl.modrinth.service.ModManagementService;
import com.ecl.modrinth.service.ModUpdateService;
import com.ecl.modrinth.service.ModVersionSelector;
import com.ecl.modrinth.pack.DefaultModpackUpdateService;
import com.ecl.modrinth.pack.ModpackUpdateService;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import com.ecl.operation.InstanceOperationCoordinator;
import com.ecl.util.ThreadFactories;
import com.ecl.util.HttpUtil;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Owns application services and background task creation independently of JavaFX view construction. */
public final class MainController implements AutoCloseable {
    private final SettingsManager settingsManager;
    private final VersionManager versionManager;
    private final DownloadService gameDownloader;
    private final DownloadTaskCenter downloadTaskCenter;
    private final ModrinthDownloader modrinthDownloader;
    private final CurseForgeDownloader curseForgeDownloader;
    private final ModrinthApiClient modrinthApiClient;
    private final ModMetadataProviderRegistry metadataProviders;
    private final VersionRepository versionRepository;
    private final LaunchEnvironment launchEnvironment;
    private final Launcher gameLauncher;
    private final ExecutorService backgroundExecutor;
    private final ExecutorService modDownloadExecutor;
    private final InstalledModRepository installedModRepository;
    private final ModVersionSelector modVersionSelector;
    private final ModDependencyResolver modDependencyResolver;
    private final ModInstallationService modInstallationService;
    private final ModManagementService modManagementService;
    private final LocalModScanner localModScanner;
    private final ModUpdateService modUpdateService;
    private final ModpackUpdateService modpackUpdateService;
    private final InstallationPlanBuilder installationPlanBuilder;
    private final InstanceLaunchProfileStore instanceLaunchProfiles;
    private final InstanceOperationCoordinator instanceOperations;
    private final Map<ContentSource, ModSourceServices> sourceServices = new ConcurrentHashMap<>();
    private final Map<UUID, ModInstanceContext> modInstances = new ConcurrentHashMap<>();
    private final Set<UUID> runningInstances = ConcurrentHashMap.newKeySet();

    public MainController() {
        this(defaultDependencies());
    }

    private MainController(Dependencies dependencies) {
        this(dependencies.settingsManager(), dependencies.instanceLaunchProfiles(),
                dependencies.instanceOperations());
    }

    /** Constructor boundary for instance-scoped services that need deterministic wiring. */
    public MainController(SettingsManager settingsManager,
                          InstanceLaunchProfileStore instanceLaunchProfiles,
                          InstanceOperationCoordinator instanceOperations) {
        ECLConfig.ensureDirs();
        this.settingsManager = java.util.Objects.requireNonNull(settingsManager, "settingsManager");
        this.instanceLaunchProfiles = java.util.Objects.requireNonNull(
                instanceLaunchProfiles, "instanceLaunchProfiles");
        this.instanceOperations = java.util.Objects.requireNonNull(
                instanceOperations, "instanceOperations");
        this.settingsManager.load();
        migrateLegacySecrets();
        settingsManager.enableAutoSave(); // GUI 设置变更自动落盘（防抖 500ms）
        versionManager = new VersionManager();
        versionRepository = new VersionRepository(ECLConfig.getVersionsDir());
        int configuredConcurrency = settingsManager.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT);
        configuredConcurrency = Math.max(1, Math.min(8, configuredConcurrency));
        long configuredRate = Math.max(0L, settingsManager.get(ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB)) * 1024L;
        HttpUtil.setDownloadMaxConcurrent(configuredConcurrency);
        HttpUtil.setDownloadRateLimitBytesPerSecond(configuredRate);
        gameDownloader = new GameDownloader(configuredConcurrency);
        downloadTaskCenter = new DownloadTaskCenter(configuredConcurrency, configuredRate);
        curseForgeDownloader = new CurseForgeDownloader(this::curseForgeApiKey);
        modrinthApiClient = new DefaultModrinthApiClient();
        modrinthDownloader = new ModrinthDownloader(modrinthApiClient);
        metadataProviders = new ModMetadataProviderRegistry(
                new ModrinthMetadataProvider(modrinthApiClient, false),
                new CurseForgeMetadataProvider(curseForgeDownloader.api()));
        ModMetadataProvider metadataProvider = metadataProviders.require(ContentSource.MODRINTH);
        launchEnvironment = new LaunchEnvironment(
                ECLConfig.getVersionsDir(), ECLConfig.getLibrariesDir(), ECLConfig.getAssetsDir(),
                ECLConfig.LAUNCHER_NAME, ECLConfig.LAUNCHER_VERSION);
        gameLauncher = new DefaultLauncher(versionRepository, launchEnvironment);
        int backgroundThreads = Math.min(16,
                Math.max(2, Runtime.getRuntime().availableProcessors() * 2));
        backgroundExecutor = new ThreadPoolExecutor(
                backgroundThreads, backgroundThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), ThreadFactories.daemon("ecl-background"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        modDownloadExecutor = Executors.newFixedThreadPool(
                configuredConcurrency, ThreadFactories.daemon("ecl-mod-download"));
        installedModRepository = new FileInstalledModRepository();
        modVersionSelector = new DefaultModVersionSelector();
        modDependencyResolver = new DefaultModDependencyResolver(
                metadataProvider, modVersionSelector, instance -> {
                    try {
                        return installedModRepository.findAll(instance);
                    } catch (IOException e) {
                        throw new IllegalStateException("无法读取实例模组索引", e);
                    }
                }, 32, 256);
        installationPlanBuilder = new InstallationPlanBuilder();
        HashVerifier hashVerifier = new HashVerifier();
        ModFileDownloadService fileDownloadService =
                new ModFileDownloadService(modDownloadExecutor, hashVerifier, uri -> {
                    if (!"curseforge".equalsIgnoreCase(uri.getScheme())) {
                        return uri;
                    }
                    String projectId = uri.getHost();
                    String path = uri.getPath();
                    String fileId = path == null ? "" : path.replaceFirst("^/", "");
                    return java.net.URI.create(curseForgeDownloader.api()
                            .getDownloadUrl(projectId, fileId));
                });
        modInstallationService = new ModInstallationService(
                installedModRepository, fileDownloadService, instanceOperations,
                backgroundExecutor, runningInstances::contains);
        modManagementService = new DefaultModManagementService(
                installedModRepository, instanceOperations, backgroundExecutor,
                runningInstances::contains, hashVerifier);
        localModScanner = new DefaultLocalModScanner(
                metadataProvider, installedModRepository, hashVerifier, modVersionSelector,
                instanceOperations, backgroundExecutor, runningInstances::contains);
        modUpdateService = new DefaultModUpdateService(
                metadataProvider, modVersionSelector, modDependencyResolver,
                installationPlanBuilder, modInstallationService, modInstances::get);
        modpackUpdateService = new DefaultModpackUpdateService(
                metadataProvider, backgroundExecutor, instanceOperations,
                runningInstances::contains);
        sourceServices.put(ContentSource.MODRINTH,
                new ModSourceServices(modDependencyResolver, localModScanner, modUpdateService));
    }

    public SettingsManager settings() { return settingsManager; }
    public VersionManager versions() { return versionManager; }
    public DownloadService gameDownloader() { return gameDownloader; }
    public DownloadTaskCenter downloadTasks() { return downloadTaskCenter; }
    public ModrinthDownloader modrinthDownloader() { return modrinthDownloader; }
    public CurseForgeDownloader curseForgeDownloader() { return curseForgeDownloader; }
    public ContentDownloader contentDownloader(ContentSource source) {
        return source == ContentSource.CURSEFORGE ? curseForgeDownloader : modrinthDownloader;
    }
    public ModrinthApiClient modrinthApi() { return modrinthApiClient; }
    public ModMetadataProvider metadataProvider(ContentSource source) {
        return metadataProviders.require(source);
    }
    public java.util.List<ModMetadataProvider> metadataProviders() {
        return metadataProviders.providers();
    }
    public ModSourceServices modSourceServices(ModMetadataProvider provider) {
        return sourceServices.computeIfAbsent(provider.source(), ignored -> {
            ModDependencyResolver resolver = new DefaultModDependencyResolver(
                    provider, modVersionSelector, instance -> {
                        try {
                            return installedModRepository.findAll(instance);
                        } catch (IOException error) {
                            throw new IllegalStateException("无法读取实例模组索引", error);
                        }
                    }, 32, 256);
            LocalModScanner scanner = new DefaultLocalModScanner(
                    provider, installedModRepository, new HashVerifier(), modVersionSelector,
                    instanceOperations, backgroundExecutor, runningInstances::contains);
            ModUpdateService updater = new DefaultModUpdateService(
                    provider, modVersionSelector, resolver, installationPlanBuilder,
                    modInstallationService, modInstances::get);
            return new ModSourceServices(resolver, scanner, updater);
        });
    }
    public InstalledModRepository installedMods() { return installedModRepository; }
    public ModVersionSelector modVersionSelector() { return modVersionSelector; }
    public ModDependencyResolver modDependencyResolver() { return modDependencyResolver; }
    public InstallationPlanBuilder installationPlanBuilder() { return installationPlanBuilder; }
    public ModInstallationService modInstallationService() { return modInstallationService; }
    public ModManagementService modManagementService() { return modManagementService; }
    public LocalModScanner localModScanner() { return localModScanner; }
    public ModUpdateService modUpdateService() { return modUpdateService; }
    public ModpackUpdateService modpackUpdateService() { return modpackUpdateService; }
    public InstanceLaunchProfileStore instanceLaunchProfiles() { return instanceLaunchProfiles; }
    public InstanceOperationCoordinator instanceOperations() { return instanceOperations; }
    public Launcher gameLauncher() { return gameLauncher; }
    public LaunchEnvironment launchEnvironment() { return launchEnvironment; }

    /** Invalidate launch metadata after an install or profile rewrite. */
    public void invalidateLaunchVersion(String versionId) {
        versionRepository.invalidate(versionId);
    }

    public ReleaseChannel preferredModReleaseChannel() {
        String configured = settingsManager.get(ECLConfig.KEY_MOD_RELEASE_CHANNEL);
        try {
            return ReleaseChannel.valueOf(configured);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return ReleaseChannel.RELEASE_AND_BETA;
        }
    }

    private String curseForgeApiKey() {
        String stored = settingsManager.getEncrypted(ECLConfig.KEY_CURSEFORGE_API_KEY);
        if (stored != null && !stored.isBlank()) return stored;
        String property = System.getProperty("ecl.curseforge.apiKey", "");
        if (!property.isBlank()) return property;
        String environment = System.getenv("CURSEFORGE_API_KEY");
        return environment == null ? "" : environment;
    }

    private void migrateLegacySecrets() {
        boolean migrated = settingsManager.migrateToEncrypted("microsoftRefreshToken");
        migrated |= settingsManager.migrateToEncrypted("microsoftAccessToken");
        migrated |= settingsManager.migrateToEncrypted(ECLConfig.KEY_CURSEFORGE_API_KEY.key());
        migrated |= settingsManager.removeEncryptedByPrefix("yggdrasilPassword");
        if (migrated) {
            settingsManager.save();
        }
    }

    public void registerModInstance(ModInstanceContext instance) {
        modInstances.put(instance.instanceId(), instance);
    }

    public void setInstanceRunning(UUID instanceId, boolean running) {
        if (running) {
            runningInstances.add(instanceId);
        } else {
            runningInstances.remove(instanceId);
        }
    }

    public Future<?> runAsync(String threadName, Runnable action) {
        return backgroundExecutor.submit(() -> withThreadName(threadName, () -> {
            action.run();
            return null;
        }));
    }

    public <T> CompletableFuture<T> supplyAsync(String threadName, Supplier<T> action) {
        return CompletableFuture.supplyAsync(() -> withThreadName(threadName, action), backgroundExecutor);
    }

    private <T> T withThreadName(String threadName, Supplier<T> action) {
            Thread current = Thread.currentThread();
            String previousName = current.getName();
            current.setName(threadName);
            try {
                return action.get();
            } finally {
                current.setName(previousName);
            }
    }

    @Override
    public void close() {
        // Interrupt user-visible work first; downloader shutdown may wait for its own workers.
        settingsManager.close(); // flush pending auto-save before stopping
        downloadTaskCenter.close();
        backgroundExecutor.shutdownNow();
        modDownloadExecutor.shutdownNow();
        awaitTermination(backgroundExecutor);
        awaitTermination(modDownloadExecutor);
        metadataProviders.close();
        modrinthApiClient.close();
        gameDownloader.close();
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static Dependencies defaultDependencies() {
        SettingsManager settings = new SettingsManager();
        InstanceLaunchProfileStore profiles = new InstanceLaunchProfileStore(() ->
                new InstanceLaunchProfileStore.LegacyLaunchSettings(
                        settings.get(ECLConfig.KEY_JAVA_PATH),
                        settings.get(ECLConfig.KEY_MAX_MEMORY_MB),
                        settings.get(ECLConfig.KEY_JVM_ARGS)));
        return new Dependencies(settings, profiles, new InstanceOperationCoordinator());
    }

    private record Dependencies(
            SettingsManager settingsManager,
            InstanceLaunchProfileStore instanceLaunchProfiles,
            InstanceOperationCoordinator instanceOperations) {
    }

    public record ModSourceServices(ModDependencyResolver dependencyResolver,
                                    LocalModScanner localScanner,
                                    ModUpdateService updateService) {
    }
}
