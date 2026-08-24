package com.ecl.modrinth.ui.viewmodel;

import com.ecl.download.DownloadTaskCenter;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.service.ModManagementService;
import com.ecl.modrinth.service.ModUpdateService;
import javafx.application.Platform;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Coordinates update checks and updates independently from the browser view model. */
final class ModBrowserUpdateCoordinator {
    private static final long UPDATE_CACHE_NANOS = 30L * 60L * 1_000_000_000L;

    private final ModManagementService managementService;
    private final DownloadTaskCenter downloadTaskCenter;
    private final Supplier<ModInstanceContext> instanceSupplier;
    private final Supplier<List<InstalledMod>> installedSupplier;
    private final BooleanSupplier installedLoaded;
    private final Consumer<List<InstalledMod>> installedConsumer;
    private final Consumer<Boolean> installedLoadedConsumer;
    private final BiConsumer<String, Boolean> setBusy;
    private final Runnable finishBusy;
    private final Consumer<String> setError;
    private final Consumer<String> setOperation;
    private final Consumer<Integer> setUpdateCount;
    private final Runnable refreshInstalled;
    private final Consumer<CompletableFuture<?>> setActiveRequest;
    private final Consumer<DownloadTaskCenter.TaskHandle<?>> setActiveDownload;
    private final Function<Throwable, String> errorFormatter;
    private final Map<String, ModUpdate> updates = new LinkedHashMap<>();
    private ModUpdateService updateService;
    private CompletableFuture<?> updateRequest;
    private long lastUpdateCheckNanos;

    ModBrowserUpdateCoordinator(
            ModUpdateService updateService,
            ModManagementService managementService,
            DownloadTaskCenter downloadTaskCenter,
            Supplier<ModInstanceContext> instanceSupplier,
            Supplier<List<InstalledMod>> installedSupplier,
            BooleanSupplier installedLoaded,
            Consumer<List<InstalledMod>> installedConsumer,
            Consumer<Boolean> installedLoadedConsumer,
            BiConsumer<String, Boolean> setBusy,
            Runnable finishBusy,
            Consumer<String> setError,
            Consumer<String> setOperation,
            Consumer<Integer> setUpdateCount,
            Runnable refreshInstalled,
            Consumer<CompletableFuture<?>> setActiveRequest,
            Consumer<DownloadTaskCenter.TaskHandle<?>> setActiveDownload,
            Function<Throwable, String> errorFormatter) {
        this.updateService = updateService;
        this.managementService = managementService;
        this.downloadTaskCenter = downloadTaskCenter;
        this.instanceSupplier = instanceSupplier;
        this.installedSupplier = installedSupplier;
        this.installedLoaded = installedLoaded;
        this.installedConsumer = installedConsumer;
        this.installedLoadedConsumer = installedLoadedConsumer;
        this.setBusy = setBusy;
        this.finishBusy = finishBusy;
        this.setError = setError;
        this.setOperation = setOperation;
        this.setUpdateCount = setUpdateCount;
        this.refreshInstalled = refreshInstalled;
        this.setActiveRequest = setActiveRequest;
        this.setActiveDownload = setActiveDownload;
        this.errorFormatter = errorFormatter;
    }

    void reset() {
        updateRequest = null;
        lastUpdateCheckNanos = 0;
        updates.clear();
        setUpdateCount.accept(0);
    }

    void setUpdateService(ModUpdateService service) {
        updateService = service;
        updateRequest = null;
        lastUpdateCheckNanos = 0;
        updates.clear();
        setUpdateCount.accept(0);
    }

    CompletableFuture<?> checkUpdates(ReleaseChannel channel) {
        return checkUpdates(channel, false, installedSupplier.get());
    }

    CompletableFuture<?> ensureUpdatesChecked(ReleaseChannel channel) {
        CompletableFuture<?> existing = updateRequest;
        long now = System.nanoTime();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        if (lastUpdateCheckNanos != 0 && now - lastUpdateCheckNanos < UPDATE_CACHE_NANOS) {
            return CompletableFuture.completedFuture(availableUpdates());
        }
        if (!installedLoaded.getAsBoolean()) {
            setBusy.accept("正在同步本地模组…", true);
            CompletableFuture<List<ModUpdate>> request = managementService.list(instanceSupplier.get())
                    .thenCompose(mods -> {
                        Platform.runLater(() -> {
                            installedConsumer.accept(mods);
                            installedLoadedConsumer.accept(true);
                        });
                        return updateService.checkUpdates(instanceSupplier.get(), mods, channel);
                    });
            return observeUpdateRequest(request, true);
        }
        return checkUpdates(channel, true, installedSupplier.get());
    }

    CompletableFuture<?> applyUpdate(String projectId) {
        ModUpdate update = updates.get(projectId);
        if (update == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("该模组没有可用更新"));
        }
        setBusy.accept("正在更新 " + update.installedMod().displayName() + "…", true);
        CompletableFuture<?> request = queueUpdate(update)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    finishBusy.run();
                    if (error != null) {
                        setError.accept(errorFormatter.apply(error));
                    } else {
                        updates.remove(projectId);
                        setUpdateCount.accept(updates.size());
                        refreshInstalled.run();
                        setOperation.accept("模组更新完成");
                    }
                }));
        setActiveRequest.accept(request);
        return request;
    }

    List<ModUpdate> availableUpdates() {
        return List.copyOf(updates.values());
    }

    boolean hasUpdate(String projectId) {
        return updates.containsKey(projectId);
    }

    private CompletableFuture<?> checkUpdates(ReleaseChannel channel, boolean automatic,
                                               List<InstalledMod> candidates) {
        setBusy.accept("正在检查兼容更新…", true);
        CompletableFuture<List<ModUpdate>> request = updateService.checkUpdates(
                instanceSupplier.get(), candidates, channel);
        return observeUpdateRequest(request, automatic);
    }

    private CompletableFuture<?> observeUpdateRequest(
            CompletableFuture<List<ModUpdate>> request, boolean automatic) {
        CompletableFuture<?> observed = request.whenComplete((result, error) -> Platform.runLater(() -> {
            finishBusy.run();
            if (error != null) {
                setError.accept(errorFormatter.apply(error));
            } else {
                updates.clear();
                result.forEach(update -> updates.put(update.installedMod().projectId(), update));
                setUpdateCount.accept(result.size());
                lastUpdateCheckNanos = System.nanoTime();
                setOperation.accept(result.isEmpty()
                        ? "所有模组均为最新兼容版本"
                        : (automatic ? "自动发现 " : "发现 ") + result.size() + " 个更新");
                installedConsumer.accept(List.copyOf(installedSupplier.get()));
            }
        }));
        setActiveRequest.accept(observed);
        updateRequest = observed;
        return observed;
    }

    private CompletableFuture<?> queueUpdate(ModUpdate update) {
        if (downloadTaskCenter == null) {
            return updateService.applyUpdate(update);
        }
        DownloadTaskCenter.TaskHandle<Object> task = downloadTaskCenter.submit(
                "Mod update", () -> context -> {
                    CompletableFuture<?> inner = updateService.applyUpdate(update);
                    context.registerCancellation(() -> inner.cancel(true));
                    return inner.join();
                });
        setActiveDownload.accept(task);
        return task.completion();
    }
}
