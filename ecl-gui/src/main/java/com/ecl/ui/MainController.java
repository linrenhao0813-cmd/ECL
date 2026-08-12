package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.download.DownloadService;
import com.ecl.download.GameDownloader;
import com.ecl.download.ModrinthDownloader;
import com.ecl.launcher.GameLauncher;
import com.ecl.launcher.LaunchService;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.api.DefaultModrinthApiClient;
import com.ecl.modrinth.api.ModrinthApiClient;
import com.ecl.modrinth.download.HashVerifier;
import com.ecl.modrinth.download.ModFileDownloadService;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.repository.FileInstalledModRepository;
import com.ecl.modrinth.repository.InstalledModRepository;
import com.ecl.modrinth.service.DefaultInstanceOperationLock;
import com.ecl.modrinth.service.DefaultLocalModScanner;
import com.ecl.modrinth.service.DefaultModDependencyResolver;
import com.ecl.modrinth.service.DefaultModManagementService;
import com.ecl.modrinth.service.DefaultModUpdateService;
import com.ecl.modrinth.service.DefaultModVersionSelector;
import com.ecl.modrinth.service.InstanceOperationLock;
import com.ecl.modrinth.service.LocalModScanner;
import com.ecl.modrinth.service.ModDependencyResolver;
import com.ecl.modrinth.service.ModInstallationService;
import com.ecl.modrinth.service.ModManagementService;
import com.ecl.modrinth.service.ModUpdateService;
import com.ecl.modrinth.service.ModVersionSelector;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Owns application services and background task creation independently of JavaFX view construction. */
public final class MainController implements AutoCloseable {
    private final SettingsManager settingsManager;
    private final VersionManager versionManager;
    private final DownloadService gameDownloader;
    private final ModrinthDownloader modrinthDownloader;
    private final ModrinthApiClient modrinthApiClient;
    private final LaunchService gameLauncher;
    private final ExecutorService backgroundExecutor;
    private final ExecutorService modDownloadExecutor;
    private final InstalledModRepository installedModRepository;
    private final ModVersionSelector modVersionSelector;
    private final ModDependencyResolver modDependencyResolver;
    private final ModInstallationService modInstallationService;
    private final ModManagementService modManagementService;
    private final LocalModScanner localModScanner;
    private final ModUpdateService modUpdateService;
    private final InstallationPlanBuilder installationPlanBuilder;
    private final Map<UUID, ModInstanceContext> modInstances = new ConcurrentHashMap<>();
    private final Set<UUID> runningInstances = ConcurrentHashMap.newKeySet();

    public MainController() {
        ECLConfig.ensureDirs();
        settingsManager = new SettingsManager();
        settingsManager.load();
        versionManager = new VersionManager();
        gameDownloader = new GameDownloader();
        modrinthDownloader = new ModrinthDownloader();
        modrinthApiClient = new DefaultModrinthApiClient();
        gameLauncher = new GameLauncher();
        AtomicInteger threadNumber = new AtomicInteger();
        backgroundExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ecl-background-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        modDownloadExecutor = Executors.newFixedThreadPool(
                Math.max(1, ECLConfig.DOWNLOAD_THREADS), runnable -> {
                    Thread thread = new Thread(runnable,
                            "ecl-mod-download-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        installedModRepository = new FileInstalledModRepository();
        modVersionSelector = new DefaultModVersionSelector();
        InstanceOperationLock operationLock = new DefaultInstanceOperationLock();
        modDependencyResolver = new DefaultModDependencyResolver(
                modrinthApiClient, modVersionSelector, instance -> {
                    try {
                        return installedModRepository.findAll(instance);
                    } catch (IOException e) {
                        throw new IllegalStateException("无法读取实例模组索引", e);
                    }
                }, 32, 256);
        installationPlanBuilder = new InstallationPlanBuilder();
        HashVerifier hashVerifier = new HashVerifier();
        ModFileDownloadService fileDownloadService =
                new ModFileDownloadService(modDownloadExecutor, hashVerifier);
        modInstallationService = new ModInstallationService(
                installedModRepository, fileDownloadService, operationLock,
                backgroundExecutor, runningInstances::contains);
        modManagementService = new DefaultModManagementService(
                installedModRepository, operationLock, backgroundExecutor,
                runningInstances::contains, hashVerifier);
        localModScanner = new DefaultLocalModScanner(
                modrinthApiClient, installedModRepository, hashVerifier, modVersionSelector,
                operationLock, backgroundExecutor, runningInstances::contains);
        modUpdateService = new DefaultModUpdateService(
                modrinthApiClient, modVersionSelector, modDependencyResolver,
                installationPlanBuilder, modInstallationService, modInstances::get);
    }

    public SettingsManager settings() { return settingsManager; }
    public VersionManager versions() { return versionManager; }
    public DownloadService gameDownloader() { return gameDownloader; }
    public ModrinthDownloader modrinthDownloader() { return modrinthDownloader; }
    public ModrinthApiClient modrinthApi() { return modrinthApiClient; }
    public InstalledModRepository installedMods() { return installedModRepository; }
    public ModVersionSelector modVersionSelector() { return modVersionSelector; }
    public ModDependencyResolver modDependencyResolver() { return modDependencyResolver; }
    public InstallationPlanBuilder installationPlanBuilder() { return installationPlanBuilder; }
    public ModInstallationService modInstallationService() { return modInstallationService; }
    public ModManagementService modManagementService() { return modManagementService; }
    public LocalModScanner localModScanner() { return localModScanner; }
    public ModUpdateService modUpdateService() { return modUpdateService; }
    public LaunchService gameLauncher() { return gameLauncher; }

    public ReleaseChannel preferredModReleaseChannel() {
        String configured = settingsManager.get(ECLConfig.KEY_MOD_RELEASE_CHANNEL);
        try {
            return ReleaseChannel.valueOf(configured);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return ReleaseChannel.RELEASE_AND_BETA;
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
        backgroundExecutor.shutdownNow();
        modDownloadExecutor.shutdownNow();
        modrinthApiClient.close();
        gameDownloader.close();
    }
}
