package com.ecl.modrinth.ui.viewmodel;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.service.LocalModScanner;
import com.ecl.modrinth.service.ModManagementService;
import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** Maintains the installed-mod list and executes local mod management operations. */
final class InstalledModController {
    private final ModManagementService managementService;
    private final Supplier<ModInstanceContext> instanceSupplier;
    private final ObservableList<InstalledMod> installedMods;
    private final ModBrowserOperationState operations;
    private final Function<Throwable, String> errorFormatter;
    private final java.util.function.Consumer<String> setError;
    private final java.util.function.Consumer<String> setOperation;
    private final Map<String, String> health = new LinkedHashMap<>();
    private LocalModScanner scanner;
    private boolean loaded;

    InstalledModController(ModManagementService managementService, LocalModScanner scanner,
                           Supplier<ModInstanceContext> instanceSupplier,
                           ObservableList<InstalledMod> installedMods,
                           ModBrowserOperationState operations,
                           Function<Throwable, String> errorFormatter,
                           java.util.function.Consumer<String> setError,
                           java.util.function.Consumer<String> setOperation) {
        this.managementService = managementService;
        this.scanner = scanner;
        this.instanceSupplier = instanceSupplier;
        this.installedMods = installedMods;
        this.operations = operations;
        this.errorFormatter = errorFormatter;
        this.setError = setError;
        this.setOperation = setOperation;
    }

    void setScanner(LocalModScanner value) {
        scanner = value;
    }

    boolean isLoaded() {
        return loaded;
    }

    void setLoaded(boolean value) {
        loaded = value;
    }

    String healthMessage(String projectId) {
        return health.getOrDefault(projectId, "");
    }

    void refresh() {
        ModInstanceContext context = instanceSupplier.get();
        if (context == null) {
            return;
        }
        managementService.list(context).whenComplete((mods, error) -> Platform.runLater(() -> {
            if (error != null) {
                setError.accept(errorFormatter.apply(error));
            } else {
                installedMods.setAll(mods);
                loaded = true;
            }
        }));
    }

    CompletableFuture<?> rescan() {
        operations.begin("正在扫描本地模组…", false);
        CompletableFuture<?> request = scanner.scan(instanceSupplier.get())
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    operations.finish();
                    if (error != null) {
                        setError.accept(errorFormatter.apply(error));
                        return;
                    }
                    installedMods.setAll(result.installedMods());
                    health.clear();
                    result.items().stream().filter(item -> item.installedMod() != null)
                            .filter(item -> item.damaged() || item.message().contains("缺失"))
                            .forEach(item -> health.put(item.installedMod().projectId(), item.message()));
                    setOperation.accept(result.warnings().isEmpty() ? "本地模组扫描完成"
                            : "扫描完成，发现 " + result.warnings().size() + " 个警告");
                }));
        operations.track(request);
        return request;
    }

    CompletableFuture<?> setEnabled(Collection<String> projectIds, boolean enabled) {
        operations.begin(enabled ? "正在启用模组…" : "正在禁用模组…", false);
        CompletableFuture<?> request = managementService.setEnabled(instanceSupplier.get(), projectIds, enabled)
                .whenComplete((mods, error) -> Platform.runLater(() -> {
                    operations.finish();
                    if (error != null) {
                        setError.accept(errorFormatter.apply(error));
                    } else {
                        installedMods.setAll(mods);
                        setOperation.accept(enabled ? "模组已启用" : "模组已禁用");
                    }
                }));
        operations.track(request);
        return request;
    }

    CompletableFuture<?> uninstall(Collection<String> projectIds) {
        operations.begin("正在卸载模组…", false);
        CompletableFuture<?> request = managementService.uninstall(instanceSupplier.get(), projectIds)
                .whenComplete((mods, error) -> Platform.runLater(() -> {
                    operations.finish();
                    if (error != null) {
                        setError.accept(errorFormatter.apply(error));
                    } else {
                        installedMods.setAll(mods);
                        setOperation.accept("模组已卸载");
                    }
                }));
        operations.track(request);
        return request;
    }

    CompletableFuture<?> importLocalJar(Path jarFile) {
        operations.begin("正在导入本地模组…", false);
        CompletableFuture<?> request = managementService.importLocalJar(instanceSupplier.get(), jarFile)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    operations.finish();
                    if (error != null) {
                        setError.accept(errorFormatter.apply(error));
                    } else {
                        refresh();
                        setOperation.accept("本地模组已导入");
                    }
                }));
        operations.track(request);
        return request;
    }
}
