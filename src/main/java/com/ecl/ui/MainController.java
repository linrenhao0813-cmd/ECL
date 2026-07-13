package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.download.GameDownloader;
import com.ecl.download.ModrinthDownloader;
import com.ecl.launcher.GameLauncher;
import com.ecl.launcher.VersionManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns application services and background task creation independently of JavaFX view construction. */
public final class MainController implements AutoCloseable {
    private final SettingsManager settingsManager;
    private final VersionManager versionManager;
    private final GameDownloader gameDownloader;
    private final ModrinthDownloader modrinthDownloader;
    private final GameLauncher gameLauncher;
    private final ExecutorService backgroundExecutor;

    public MainController() {
        ECLConfig.ensureDirs();
        settingsManager = new SettingsManager();
        settingsManager.load();
        versionManager = new VersionManager();
        gameDownloader = new GameDownloader();
        modrinthDownloader = new ModrinthDownloader();
        gameLauncher = new GameLauncher();
        AtomicInteger threadNumber = new AtomicInteger();
        backgroundExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ecl-background-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public SettingsManager settings() { return settingsManager; }
    public VersionManager versions() { return versionManager; }
    public GameDownloader gameDownloader() { return gameDownloader; }
    public ModrinthDownloader modrinthDownloader() { return modrinthDownloader; }
    public GameLauncher gameLauncher() { return gameLauncher; }

    public Future<?> runAsync(String threadName, Runnable action) {
        return backgroundExecutor.submit(() -> {
            Thread current = Thread.currentThread();
            String previousName = current.getName();
            current.setName(threadName);
            try {
                action.run();
            } finally {
                current.setName(previousName);
            }
        });
    }

    @Override
    public void close() {
        gameDownloader.close();
        backgroundExecutor.shutdownNow();
    }
}
