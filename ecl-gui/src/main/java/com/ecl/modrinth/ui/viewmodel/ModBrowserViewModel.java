package com.ecl.modrinth.ui.viewmodel;

import com.ecl.download.DownloadTaskCenter;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModSearchQuery;
import com.ecl.modrinth.api.ModrinthApiException;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.provider.ContentSource;
import com.ecl.modrinth.provider.ModMetadataProvider;
import com.ecl.modrinth.service.LocalModScanner;
import com.ecl.modrinth.service.ModDependencyResolver;
import com.ecl.modrinth.service.ModInstallationResult;
import com.ecl.modrinth.service.ModInstallationService;
import com.ecl.modrinth.service.ModManagementService;
import com.ecl.modrinth.service.ModUpdateService;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import com.ecl.modrinth.transaction.ModInstallationPlan;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Collection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.time.Duration;

public final class ModBrowserViewModel implements AutoCloseable {
    private static final long UPDATE_CACHE_NANOS = Duration.ofMinutes(30).toNanos();
    private ModMetadataProvider metadataProvider;
    private ModDependencyResolver dependencyResolver;
    private final InstallationPlanBuilder planBuilder;
    private final ModInstallationService installationService;
    private final ModManagementService managementService;
    private final DownloadTaskCenter downloadTaskCenter;
    private LocalModScanner localScanner;
    private ModUpdateService updateService;

    private final StringProperty searchText = new SimpleStringProperty("");
    private final ObjectProperty<ModSearchIndex> sortIndex =
            new SimpleObjectProperty<>(ModSearchIndex.RELEVANCE);
    private final BooleanProperty loading = new SimpleBooleanProperty();
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final ObservableList<ModProject> searchResults = FXCollections.observableArrayList();
    private final ObservableList<InstalledMod> installedMods = FXCollections.observableArrayList();
    private final DoubleProperty overallProgress = new SimpleDoubleProperty(0);
    private final StringProperty currentOperation = new SimpleStringProperty("就绪");
    private final IntegerProperty updateCount = new SimpleIntegerProperty();
    private final BooleanProperty operationCancellable = new SimpleBooleanProperty();
    private final ObjectProperty<ModInstanceContext> instance = new SimpleObjectProperty<>();
    private final Map<String, ModUpdate> updates = new LinkedHashMap<>();
    private final Map<String, String> installedHealth = new LinkedHashMap<>();
    private final AtomicLong requestGeneration = new AtomicLong();
    private final Map<String, CompletableFuture<ModProject>> projectRequests = new ConcurrentHashMap<>();

    private volatile CompletableFuture<?> activeRequest;
    private volatile DownloadTaskCenter.TaskHandle<?> activeDownloadHandle;
    private volatile CompletableFuture<?> updateRequest;
    private volatile long lastUpdateCheckNanos;
    private volatile boolean installedLoaded;
    private int offset;
    private String category = "";

    public ModBrowserViewModel(
            ModMetadataProvider metadataProvider,
            ModDependencyResolver dependencyResolver,
            InstallationPlanBuilder planBuilder,
            ModInstallationService installationService,
            ModManagementService managementService,
            LocalModScanner localScanner,
            ModUpdateService updateService
    ) {
        this(metadataProvider, dependencyResolver, planBuilder, installationService,
                managementService, localScanner, updateService, null);
    }

    public ModBrowserViewModel(
            ModMetadataProvider metadataProvider,
            ModDependencyResolver dependencyResolver,
            InstallationPlanBuilder planBuilder,
            ModInstallationService installationService,
            ModManagementService managementService,
            LocalModScanner localScanner,
            ModUpdateService updateService,
            DownloadTaskCenter downloadTaskCenter
    ) {
        this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
        this.dependencyResolver = Objects.requireNonNull(dependencyResolver, "dependencyResolver");
        this.planBuilder = Objects.requireNonNull(planBuilder, "planBuilder");
        this.installationService = Objects.requireNonNull(installationService, "installationService");
        this.managementService = Objects.requireNonNull(managementService, "managementService");
        this.localScanner = Objects.requireNonNull(localScanner, "localScanner");
        this.updateService = Objects.requireNonNull(updateService, "updateService");
        this.downloadTaskCenter = downloadTaskCenter;
    }

    public void setInstance(ModInstanceContext value) {
        instance.set(value);
        offset = 0;
        searchResults.clear();
        updates.clear();
        updateCount.set(0);
        updateRequest = null;
        lastUpdateCheckNanos = 0;
        installedLoaded = false;
        refreshInstalled();
    }

    public void setMetadataProvider(ModMetadataProvider provider) {
        setMetadataProvider(provider, dependencyResolver, localScanner, updateService);
    }

    public void setMetadataProvider(ModMetadataProvider provider,
                                    ModDependencyResolver resolver,
                                    LocalModScanner scanner,
                                    ModUpdateService updater) {
        ModMetadataProvider selected = Objects.requireNonNull(provider, "provider");
        if (metadataProvider == selected && dependencyResolver == resolver
                && localScanner == scanner && updateService == updater) {
            return;
        }
        requestGeneration.incrementAndGet();
        cancelActiveRequest();
        metadataProvider = selected;
        dependencyResolver = Objects.requireNonNull(resolver, "resolver");
        localScanner = Objects.requireNonNull(scanner, "scanner");
        updateService = Objects.requireNonNull(updater, "updater");
        projectRequests.clear();
        offset = 0;
        searchResults.clear();
        updates.clear();
        updateCount.set(0);
        currentOperation.set("已切换数据源至 " + selected.source().name());
    }

    public ContentSource contentSource() {
        return metadataProvider.source();
    }

    public void setCategory(String value) {
        category = value == null ? "" : value.trim();
    }

    public void search(boolean append) {
        ModInstanceContext context = requireInstance();
        if (!context.loader().supportsMods()) {
            errorMessage.set("当前是原版实例，请先选择 Fabric、Quilt、Forge 或 NeoForge 实例。");
            searchResults.clear();
            return;
        }
        if (!append) {
            offset = 0;
            searchResults.clear();
        }
        long generation = requestGeneration.incrementAndGet();
        cancelActiveRequest();
        setBusy("正在搜索兼容模组…", true);
        Set<String> categories = category.isBlank() ? Set.of() : Set.of(category);
        ModSearchQuery query = new ModSearchQuery(
                searchText.get(), context.minecraftVersion(), context.loaderName(),
                categories, sortIndex.get(), offset, 20);
        CompletableFuture<?> request = metadataProvider.search(query)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (generation != requestGeneration.get()) {
                        return;
                    }
                    finishBusy();
                    if (error != null) {
                        errorMessage.set(userMessage(error));
                        return;
                    }
                    if (append) {
                        searchResults.addAll(result.hits());
                    } else {
                        searchResults.setAll(result.hits());
                    }
                    offset += result.hits().size();
                    currentOperation.set(result.hits().isEmpty()
                            ? "没有找到兼容结果" : "已加载 " + searchResults.size() + " 个结果");
                }));
        activeRequest = request;
    }

    public CompletableFuture<List<ModVersion>> loadVersions(ModProject project) {
        return loadVersions(project.projectId());
    }

    public CompletableFuture<List<ModVersion>> loadVersions(String projectId) {
        ModInstanceContext context = requireInstance();
        setBusy("正在读取版本…", true);
        CompletableFuture<List<ModVersion>> request = metadataProvider.getVersions(
                projectId, context.minecraftVersion(), context.loaderName());
        activeRequest = request;
        request.whenComplete((ignored, error) -> Platform.runLater(() -> {
            finishBusy();
            if (error != null) {
                errorMessage.set(userMessage(error));
            }
        }));
        return request;
    }

    public CompletableFuture<ModProject> loadProjectDetails(ModProject project) {
        CompletableFuture<ModProject> request = loadProject(project.projectId());
        activeRequest = request;
        request.whenComplete((ignored, error) -> {
            if (error != null) {
                Platform.runLater(() -> errorMessage.set(userMessage(error)));
            }
        });
        return request;
    }

    public CompletableFuture<List<DependencyGroup>> loadDependencyGroups(ModVersion version) {
        if (version == null || version.dependencies().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CompletableFuture<DependencyProject>> requests = version.dependencies().stream()
                .map(this::loadDependencyProject).toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<DependencyType, List<DependencyProject>> grouped =
                            new EnumMap<>(DependencyType.class);
                    requests.stream().map(CompletableFuture::join).forEach(item ->
                            grouped.computeIfAbsent(item.dependency().type(), key -> new ArrayList<>())
                                    .add(item));
                    List<DependencyGroup> result = new ArrayList<>();
                    for (DependencyType type : List.of(DependencyType.REQUIRED,
                            DependencyType.OPTIONAL, DependencyType.EMBEDDED,
                            DependencyType.INCOMPATIBLE, DependencyType.UNKNOWN)) {
                        List<DependencyProject> projects = grouped.get(type);
                        if (projects != null && !projects.isEmpty()) {
                            result.add(new DependencyGroup(type, List.copyOf(projects)));
                        }
                    }
                    return List.copyOf(result);
                });
    }

    private CompletableFuture<DependencyProject> loadDependencyProject(ModDependency dependency) {
        CompletableFuture<ModProject> project;
        if (dependency.projectId() != null && !dependency.projectId().isBlank()) {
            project = loadProject(dependency.projectId());
        } else if (dependency.versionId() != null && !dependency.versionId().isBlank()) {
            project = metadataProvider.getVersion(dependency.versionId())
                    .thenCompose(version -> loadProject(version.projectId()));
        } else {
            project = CompletableFuture.failedFuture(
                    new IllegalArgumentException("依赖缺少项目标识"));
        }
        return project.handle((value, error) -> new DependencyProject(
                dependency, value, error == null ? "" : userMessage(error)));
    }

    private CompletableFuture<ModProject> loadProject(String projectId) {
        return projectRequests.computeIfAbsent(projectId, key ->
                metadataProvider.getProject(key).whenComplete((value, error) -> {
                    if (error != null) {
                        projectRequests.remove(key);
                    }
                }));
    }

    public CompletableFuture<ModInstallationPlan> preparePlan(ModVersion version) {
        return preparePlan(version, Set.of());
    }

    public CompletableFuture<ModInstallationPlan> preparePlan(
            ModVersion version, Set<String> selectedOptionalProjectIds) {
        return preparePlan(
                version,
                selectedOptionalProjectIds,
                ReleaseChannel.forVersionType(version == null ? null : version.versionType()));
    }

    public CompletableFuture<ModInstallationPlan> preparePlan(
            ModVersion version,
            Set<String> selectedOptionalProjectIds,
            ReleaseChannel releaseChannel) {
        ModInstanceContext context = requireInstance();
        setBusy("正在解析依赖与冲突…", true);
        CompletableFuture<ModInstallationPlan> request =
                dependencyResolver.resolve(context, version, selectedOptionalProjectIds, releaseChannel)
                .thenApply(resolution -> planBuilder.build(context, version, resolution));
        activeRequest = request;
        request.whenComplete((ignored, error) -> Platform.runLater(() -> {
            finishBusy();
            if (error != null && !isCancellation(error)) {
                errorMessage.set(userMessage(error));
            }
        }));
        return request;
    }

    public CompletableFuture<ModInstallationResult> install(ModInstallationPlan plan) {
        setBusy("正在下载并事务安装…", true);
        overallProgress.set(0);
        CompletableFuture<ModInstallationResult> request = queueInstallation(plan, progress ->
                Platform.runLater(() -> {
                    overallProgress.set(progress.overallTotal() <= 0 ? -1
                            : Math.min(1, (double) progress.overallDownloaded() / progress.overallTotal()));
                    currentOperation.set("正在下载 " + progress.fileName());
                }));
        activeRequest = request;
        request.whenComplete((result, error) -> Platform.runLater(() -> {
            finishBusy();
            if (error == null) {
                currentOperation.set(result.updated() ? "模组更新完成" : "模组安装完成");
                refreshInstalled();
            } else if (!isCancellation(error)) {
                errorMessage.set(userMessage(error));
            }
        }));
        return request;
    }

    private CompletableFuture<ModInstallationResult> queueInstallation(
            ModInstallationPlan plan,
            java.util.function.Consumer<com.ecl.modrinth.download.ModDownloadProgress> progressListener) {
        if (downloadTaskCenter == null) {
            return installationService.install(plan, progressListener);
        }
        AtomicReference<CompletableFuture<ModInstallationResult>> underlying = new AtomicReference<>();
        DownloadTaskCenter.TaskHandle<ModInstallationResult> task = downloadTaskCenter.submit(
                "Mod installation", context -> {
                    CompletableFuture<ModInstallationResult> inner = installationService.install(plan, progress -> {
                        context.updateStatus("Downloading " + progress.fileName());
                        context.updateProgress(progress.overallDownloaded(), progress.overallTotal());
                        progressListener.accept(progress);
                    });
                    underlying.set(inner);
                    context.registerCancellation(() -> inner.cancel(true));
                    return inner.join();
                });
        activeDownloadHandle = task;
        return task.completion();
    }

    public void refreshInstalled() {
        ModInstanceContext context = instance.get();
        if (context == null) {
            return;
        }
        managementService.list(context).whenComplete((mods, error) -> Platform.runLater(() -> {
            if (error != null) {
                errorMessage.set(userMessage(error));
            } else {
                installedMods.setAll(mods);
                installedLoaded = true;
            }
        }));
    }

    public CompletableFuture<?> rescan() {
        setBusy("正在扫描本地模组…", false);
        CompletableFuture<?> request = localScanner.scan(requireInstance())
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    finishBusy();
                    if (error != null) {
                        errorMessage.set(userMessage(error));
                    } else {
                        installedMods.setAll(result.installedMods());
                        installedHealth.clear();
                        result.items().stream()
                                .filter(item -> item.installedMod() != null)
                                .filter(item -> item.damaged()
                                        || item.message().contains("缺失"))
                                .forEach(item -> installedHealth.put(
                                        item.installedMod().projectId(), item.message()));
                        currentOperation.set(result.warnings().isEmpty()
                                ? "本地模组扫描完成"
                                : "扫描完成，发现 " + result.warnings().size() + " 个警告");
                    }
                }));
        activeRequest = request;
        return request;
    }

    public CompletableFuture<?> setEnabled(Collection<String> projectIds, boolean enabled) {
        setBusy(enabled ? "正在启用模组…" : "正在禁用模组…", false);
        CompletableFuture<?> request = managementService
                .setEnabled(requireInstance(), projectIds, enabled)
                .whenComplete((mods, error) -> Platform.runLater(() -> {
                    finishBusy();
                    if (error != null) {
                        errorMessage.set(userMessage(error));
                    } else {
                        installedMods.setAll(mods);
                        currentOperation.set(enabled ? "模组已启用" : "模组已禁用");
                    }
                }));
        activeRequest = request;
        return request;
    }

    public CompletableFuture<?> uninstall(Collection<String> projectIds) {
        setBusy("正在卸载模组…", false);
        CompletableFuture<?> request = managementService.uninstall(requireInstance(), projectIds)
                .whenComplete((mods, error) -> Platform.runLater(() -> {
                    finishBusy();
                    if (error != null) {
                        errorMessage.set(userMessage(error));
                    } else {
                        installedMods.setAll(mods);
                        currentOperation.set("模组已卸载");
                    }
                }));
        activeRequest = request;
        return request;
    }

    public CompletableFuture<?> checkUpdates(ReleaseChannel channel) {
        return checkUpdates(channel, false, List.copyOf(installedMods));
    }

    public CompletableFuture<?> ensureUpdatesChecked(ReleaseChannel channel) {
        CompletableFuture<?> existing = updateRequest;
        long now = System.nanoTime();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        if (lastUpdateCheckNanos != 0 && now - lastUpdateCheckNanos < UPDATE_CACHE_NANOS) {
            return CompletableFuture.completedFuture(availableUpdates());
        }
        if (!installedLoaded) {
            setBusy("正在同步本地模组…", true);
            CompletableFuture<List<ModUpdate>> request = managementService.list(requireInstance())
                    .thenCompose(mods -> {
                        Platform.runLater(() -> {
                            installedMods.setAll(mods);
                            installedLoaded = true;
                        });
                        return updateService.checkUpdates(requireInstance(), mods, channel);
                    });
            return observeUpdateRequest(request, true);
        }
        return checkUpdates(channel, true, List.copyOf(installedMods));
    }

    private CompletableFuture<?> checkUpdates(ReleaseChannel channel, boolean automatic,
                                              List<InstalledMod> candidates) {
        setBusy("正在检查兼容更新…", true);
        CompletableFuture<List<ModUpdate>> request = updateService.checkUpdates(
                        requireInstance(), candidates, channel);
        return observeUpdateRequest(request, automatic);
    }

    private CompletableFuture<?> observeUpdateRequest(
            CompletableFuture<List<ModUpdate>> request, boolean automatic) {
        CompletableFuture<?> observed = request.whenComplete((result, error) -> Platform.runLater(() -> {
                    finishBusy();
                    if (error != null) {
                        errorMessage.set(userMessage(error));
                    } else {
                        updates.clear();
                        result.forEach(update -> updates.put(update.installedMod().projectId(), update));
                        updateCount.set(result.size());
                        lastUpdateCheckNanos = System.nanoTime();
                        currentOperation.set(result.isEmpty()
                                ? "所有模组均为最新兼容版本"
                                : (automatic ? "自动发现 " : "发现 ") + result.size() + " 个更新");
                        installedMods.setAll(List.copyOf(installedMods));
                    }
                }));
        activeRequest = observed;
        updateRequest = observed;
        return observed;
    }

    public CompletableFuture<?> applyUpdate(String projectId) {
        ModUpdate update = updates.get(projectId);
        if (update == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("该模组没有可用更新"));
        }
        setBusy("正在更新 " + update.installedMod().displayName() + "…", true);
        CompletableFuture<?> request = queueUpdate(update)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    finishBusy();
                    if (error != null) {
                        errorMessage.set(userMessage(error));
                    } else {
                        updates.remove(projectId);
                        updateCount.set(updates.size());
                        refreshInstalled();
                        currentOperation.set("模组更新完成");
                    }
                }));
        activeRequest = request;
        return request;
    }

    private CompletableFuture<?> queueUpdate(ModUpdate update) {
        if (downloadTaskCenter == null) {
            return updateService.applyUpdate(update);
        }
        DownloadTaskCenter.TaskHandle<Object> task = downloadTaskCenter.submit(
                "Mod update", context -> {
                    CompletableFuture<?> inner = updateService.applyUpdate(update);
                    context.registerCancellation(() -> inner.cancel(true));
                    return inner.join();
                });
        activeDownloadHandle = task;
        return task.completion();
    }

    public CompletableFuture<?> importLocalJar(Path jarFile) {
        setBusy("正在导入本地模组…", false);
        CompletableFuture<?> request = managementService.importLocalJar(requireInstance(), jarFile)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    finishBusy();
                    if (error != null) {
                        errorMessage.set(userMessage(error));
                    } else {
                        refreshInstalled();
                        currentOperation.set("本地模组已导入");
                    }
                }));
        activeRequest = request;
        return request;
    }

    public List<ModUpdate> availableUpdates() {
        return List.copyOf(updates.values());
    }

    public boolean hasUpdate(String projectId) {
        return updates.containsKey(projectId);
    }

    public String healthMessage(String projectId) {
        return installedHealth.getOrDefault(projectId, "");
    }

    public void cancelActiveRequest() {
        DownloadTaskCenter.TaskHandle<?> downloadTask = activeDownloadHandle;
        if (downloadTask != null) {
            downloadTask.cancel();
        }
        CompletableFuture<?> request = activeRequest;
        if (request != null && !request.isDone()) {
            request.cancel(true);
        }
    }

    private ModInstanceContext requireInstance() {
        ModInstanceContext value = instance.get();
        if (value == null) {
            throw new IllegalStateException("未选择游戏实例");
        }
        return value;
    }

    private void setBusy(String operation, boolean cancellable) {
        Platform.runLater(() -> {
            loading.set(true);
            errorMessage.set("");
            currentOperation.set(operation);
            operationCancellable.set(cancellable);
        });
    }

    private void finishBusy() {
        activeDownloadHandle = null;
        loading.set(false);
        operationCancellable.set(false);
        overallProgress.set(0);
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException
                    || current instanceof InterruptedException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String userMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        // 用户主动取消不是失败：清空错误提示而不是显示“操作失败/下载失败”
        if (current instanceof CancellationException
                || current instanceof InterruptedException) {
            return "";
        }
        if (current instanceof ModrinthApiException apiError && apiError.retryable()) {
            return "无法连接 Modrinth，请检查网络或代理设置后点击“重试”。";
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? "操作失败，请查看日志" : current.getMessage();
    }

    public StringProperty searchTextProperty() { return searchText; }
    public ObjectProperty<ModSearchIndex> sortIndexProperty() { return sortIndex; }
    public BooleanProperty loadingProperty() { return loading; }
    public StringProperty errorMessageProperty() { return errorMessage; }
    public ObservableList<ModProject> searchResults() { return searchResults; }
    public ObservableList<InstalledMod> installedMods() { return installedMods; }
    public DoubleProperty overallProgressProperty() { return overallProgress; }
    public StringProperty currentOperationProperty() { return currentOperation; }
    public BooleanProperty operationCancellableProperty() { return operationCancellable; }
    public IntegerProperty updateCountProperty() { return updateCount; }
    public ObjectProperty<ModInstanceContext> instanceProperty() { return instance; }

    public record DependencyGroup(DependencyType type, List<DependencyProject> projects) {
    }

    public record DependencyProject(ModDependency dependency, ModProject project, String errorMessage) {
    }

    @Override
    public void close() {
        requestGeneration.incrementAndGet();
        cancelActiveRequest();
    }
}
