package com.ecl.modrinth.service;

import com.ecl.launch.GameProcessMarker;
import com.ecl.launcher.LoaderUpdateService;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** One-click loader upgrade followed by compatible updates of enabled, recognized mods. */
public final class InstanceUpdateService {
    private final LoaderUpdateService loaders;
    private final LocalModScanner scanner;
    private final ModUpdateService mods;
    private final Predicate<UUID> instanceRunning;

    public InstanceUpdateService(LoaderUpdateService loaders, LocalModScanner scanner, ModUpdateService mods,
                                 Predicate<UUID> instanceRunning) {
        this.loaders = Objects.requireNonNull(loaders);
        this.scanner = Objects.requireNonNull(scanner);
        this.mods = Objects.requireNonNull(mods);
        this.instanceRunning = Objects.requireNonNull(instanceRunning);
    }

    public CompletableFuture<Result> update(ModInstanceContext instance, ReleaseChannel channel, Consumer<Progress> listener) {
        Consumer<Progress> progress = listener == null ? ignored -> { } : listener;
        try {
            ensureStopped(instance);
            if (!instance.loader().supportsMods()) throw new IOException("当前实例没有模组加载器");
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }
        progress.accept(new Progress(Stage.LOADER, ""));
        return loaders.update(instance, new ModLoaderInstaller.Listener() {
            @Override public void onStatus(String message) {
                progress.accept(new Progress(Stage.LOADER, ""));
            }
        }).thenCompose(loader -> {
            ensureStoppedUnchecked(instance);
            progress.accept(new Progress(Stage.SCAN, ""));
            return scanner.scan(instance).thenCompose(scan -> updateMods(instance, channel, progress, loader, scan));
        });
    }

    private CompletableFuture<Result> updateMods(ModInstanceContext instance, ReleaseChannel channel,
                                                  Consumer<Progress> progress, LoaderUpdateService.Result loader,
                                                  LocalModScanResult scan) {
        List<InstalledMod> candidates = scan.installedMods().stream()
                .filter(InstalledMod::enabled)
                .filter(mod -> mod.projectId() != null && !mod.projectId().isBlank() && !mod.projectId().startsWith("local:"))
                .filter(mod -> !scan.duplicateProjects().contains(mod.projectId()))
                .filter(mod -> scan.items().stream().anyMatch(item -> item.recognized() && !item.damaged()
                        && mod.equals(item.installedMod()) && Files.isRegularFile(item.file())))
                .toList();
        Set<String> protectedProjects = scan.installedMods().stream().filter(mod -> !candidates.contains(mod))
                .map(InstalledMod::projectId).filter(Objects::nonNull).collect(Collectors.toSet());
        int skipped = scan.installedMods().size() - candidates.size();
        progress.accept(new Progress(Stage.CHECK, ""));
        return mods.checkUpdates(instance, candidates, channel).thenCompose(updates -> {
            List<Failure> failures = new ArrayList<>();
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            for (ModUpdate update : updates) {
                chain = chain.thenCompose(succeeded -> {
                    ensureStoppedUnchecked(instance);
                    progress.accept(new Progress(Stage.MOD, update.installedMod().displayName()));
                    return mods.applyUpdate(update, protectedProjects).handle((result, error) -> {
                        if (error != null) {
                            failures.add(new Failure(update.installedMod().displayName(), unwrap(error)));
                            return succeeded;
                        }
                        return succeeded + 1;
                    });
                });
            }
            return chain.thenApply(succeeded -> new Result(loader, succeeded, skipped,
                    List.copyOf(failures), scan.warnings()));
        });
    }

    private void ensureStoppedUnchecked(ModInstanceContext instance) {
        try {
            ensureStopped(instance);
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private void ensureStopped(ModInstanceContext instance) throws IOException {
        if (instanceRunning.test(instance.instanceId()) || GameProcessMarker.isRunning(instance.gameDirectory())) {
            throw new IOException("实例正在运行，请先退出游戏再升级");
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    public enum Stage { LOADER, SCAN, CHECK, MOD }
    public record Progress(Stage stage, String detail) { }
    public record Failure(String name, Throwable cause) { }
    public record Result(LoaderUpdateService.Result loader, int updatedMods, int skippedMods,
                         List<Failure> failures, List<String> warnings) { }
}
