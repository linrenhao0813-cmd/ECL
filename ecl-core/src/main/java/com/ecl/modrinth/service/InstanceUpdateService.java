package com.ecl.modrinth.service;

import com.ecl.launch.GameProcessMarker;
import com.ecl.modrinth.pack.ModpackInstance;
import com.ecl.modrinth.pack.ModpackUpdateService;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonObject;
import com.ecl.launcher.LoaderUpdateService;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** One-click pack upgrades or compatible loader and enabled, recognized mod updates. */
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

    /** Pack profiles follow their author's versions rather than upgrading individual components. */
    public CompletableFuture<Result> update(ModInstanceContext instance, ReleaseChannel channel, Consumer<Progress> listener,
                                            Path metadataRoot, Path gameRoot, ModpackUpdateService packs) {
        Consumer<Progress> progress = listener == null ? ignored -> { } : listener;
        try {
            ensureStopped(instance);
            JsonObject profile = HttpUtil.readJson(FileUtil.safeVersionJson(metadataRoot.toFile(), instance.profileId()));
            if (!profile.has("eclModpackSource") && !profile.has("eclModpackName")) {
                return update(instance, channel, listener);
            }
            String project = JsonUtil.getString(profile, "eclModpackProjectId", "");
            String version = JsonUtil.getString(profile, "eclModpackVersionId", "");
            if (!"modrinth".equalsIgnoreCase(JsonUtil.getString(profile, "eclModpackSource", ""))
                    || project.isBlank() || version.isBlank()) {
                throw new IOException("整合包未记录可更新的 Modrinth 来源，请从下载页重新导入有来源的整合包");
            }
            ModpackInstance pack = new ModpackInstance(instance.instanceId(), instance.profileId(),
                    JsonUtil.getString(profile, "eclModpackName", instance.profileId()),
                    JsonUtil.getString(profile, "eclModpackVersion", version), instance.minecraftVersion(),
                    instance.loaderName(), project, version, instance.gameDirectory());
            progress.accept(new Progress(Stage.PACK_CHECK, pack.name()));
            return packs.checkUpdate(pack, channel).thenCompose(update -> {
                ensureStoppedUnchecked(instance);
                if (update == null) {
                    return CompletableFuture.completedFuture(new Result(null, 0, 0, List.of(), List.of(), ""));
                }
                progress.accept(new Progress(Stage.PACK, pack.name()));
                List<String> warnings = new ArrayList<>();
                return packs.applyUpdate(update, gameRoot, new MrpackInstaller.Listener() {
                    @Override public void onStatus(String message) {
                        progress.accept(new Progress(Stage.PACK, message));
                    }

                    @Override public void onWarning(String message) {
                        warnings.add(message);
                        onStatus(message);
                    }
                }).thenApply(result -> new Result(null, 0, 0, List.of(), List.copyOf(warnings), result.version()));
            });
        } catch (IOException | IllegalArgumentException error) {
            return CompletableFuture.failedFuture(error);
        }
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
                    List.copyOf(failures), scan.warnings(), null));
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

    public enum Stage { LOADER, SCAN, CHECK, MOD, PACK_CHECK, PACK }
    public record Progress(Stage stage, String detail) { }
    public record Failure(String name, Throwable cause) { }
    public record Result(LoaderUpdateService.Result loader, int updatedMods, int skippedMods,
                         List<Failure> failures, List<String> warnings, String packVersion) { }
}
