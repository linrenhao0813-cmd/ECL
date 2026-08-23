package com.ecl.modrinth.ui.viewmodel;

import com.ecl.download.DownloadTaskCenter;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.modrinth.api.ModrinthApiException;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.InstalledMod;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModDependency;
import com.ecl.modrinth.model.ModProject;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.nio.file.Path;

public final class ModBrowserViewModel implements AutoCloseable {
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
    private final ModBrowserOperationState operations;
    private final ModBrowserSearchController searchController;
    private final ModDependencyBrowserLoader dependencyLoader;
    private final ModInstallationWorkflow installationWorkflow;
    private final InstalledModController installedModController;
    private final ModBrowserUpdateCoordinator updateCoordinator;

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
        this.operations = new ModBrowserOperationState(loading, errorMessage, currentOperation,
                operationCancellable, overallProgress);
        this.searchController = new ModBrowserSearchController(metadataProvider, searchText, sortIndex,
                searchResults, this::requireInstance, operations, errorMessage::set,
                currentOperation::set, ModBrowserViewModel::userMessage);
        this.dependencyLoader = new ModDependencyBrowserLoader(metadataProvider,
                ModBrowserViewModel::userMessage);
        this.installedModController = new InstalledModController(managementService, localScanner,
                this::requireInstance, installedMods, operations, ModBrowserViewModel::userMessage,
                errorMessage::set, currentOperation::set);
        this.installationWorkflow = new ModInstallationWorkflow(dependencyResolver, planBuilder,
                installationService, downloadTaskCenter, this::requireInstance, operations,
                ModBrowserViewModel::userMessage, this::refreshInstalled, errorMessage::set,
                currentOperation::set);
        this.updateCoordinator = new ModBrowserUpdateCoordinator(
                updateService, managementService, downloadTaskCenter, this::requireInstance,
                () -> List.copyOf(installedMods), installedModController::isLoaded, installedMods::setAll,
                installedModController::setLoaded, operations::begin, operations::finish,
                errorMessage::set, currentOperation::set, updateCount::set,
                this::refreshInstalled, operations::track, operations::trackDownload,
                ModBrowserViewModel::userMessage);
    }

    public void setInstance(ModInstanceContext value) {
        instance.set(value);
        searchController.reset();
        updateCoordinator.reset();
        installedModController.setLoaded(false);
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
        cancelActiveRequest();
        metadataProvider = selected;
        dependencyResolver = Objects.requireNonNull(resolver, "resolver");
        localScanner = Objects.requireNonNull(scanner, "scanner");
        updateService = Objects.requireNonNull(updater, "updater");
        searchController.setProvider(selected);
        dependencyLoader.setMetadataProvider(selected);
        installedModController.setScanner(localScanner);
        updateCoordinator.setUpdateService(updateService);
        currentOperation.set("已切换数据源至 " + selected.source().name());
    }

    public ContentSource contentSource() {
        return metadataProvider.source();
    }

    public void setCategory(String value) {
        searchController.setCategory(value);
    }

    public void search(boolean append) {
        searchController.search(append);
    }

    public CompletableFuture<List<ModVersion>> loadVersions(ModProject project) {
        return loadVersions(project.projectId());
    }

    public CompletableFuture<List<ModVersion>> loadVersions(String projectId) {
        ModInstanceContext context = requireInstance();
        operations.begin("正在读取版本…", true);
        CompletableFuture<List<ModVersion>> request = metadataProvider.getVersions(
                projectId, context.minecraftVersion(), context.loaderName());
        operations.track(request);
        request.whenComplete((ignored, error) -> Platform.runLater(() -> {
            operations.finish();
            if (error != null) {
                errorMessage.set(userMessage(error));
            }
        }));
        return request;
    }

    public CompletableFuture<ModProject> loadProjectDetails(ModProject project) {
        CompletableFuture<ModProject> request = dependencyLoader.loadProject(project.projectId());
        operations.track(request);
        request.whenComplete((ignored, error) -> {
            if (error != null) {
                Platform.runLater(() -> errorMessage.set(userMessage(error)));
            }
        });
        return request;
    }

    public CompletableFuture<List<DependencyGroup>> loadDependencyGroups(ModVersion version) {
        return dependencyLoader.loadDependencyGroups(version);
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
        return installationWorkflow.prepare(version, selectedOptionalProjectIds, releaseChannel);
    }

    public CompletableFuture<ModInstallationResult> install(ModInstallationPlan plan) {
        return installationWorkflow.install(plan);
    }

    public void refreshInstalled() {
        installedModController.refresh();
    }

    public CompletableFuture<?> rescan() {
        return installedModController.rescan();
    }

    public CompletableFuture<?> setEnabled(Collection<String> projectIds, boolean enabled) {
        return installedModController.setEnabled(projectIds, enabled);
    }

    public CompletableFuture<?> uninstall(Collection<String> projectIds) {
        return installedModController.uninstall(projectIds);
    }

    public CompletableFuture<?> checkUpdates(ReleaseChannel channel) {
        return updateCoordinator.checkUpdates(channel);
    }

    public CompletableFuture<?> ensureUpdatesChecked(ReleaseChannel channel) {
        return updateCoordinator.ensureUpdatesChecked(channel);
    }

    public CompletableFuture<?> applyUpdate(String projectId) {
        return updateCoordinator.applyUpdate(projectId);
    }

    public List<ModUpdate> availableUpdates() {
        return updateCoordinator.availableUpdates();
    }

    public boolean hasUpdate(String projectId) {
        return updateCoordinator.hasUpdate(projectId);
    }

    public CompletableFuture<?> importLocalJar(Path jarFile) {
        return installedModController.importLocalJar(jarFile);
    }

    public String healthMessage(String projectId) {
        return installedModController.healthMessage(projectId);
    }

    public void cancelActiveRequest() {
        operations.cancel();
    }

    private ModInstanceContext requireInstance() {
        ModInstanceContext value = instance.get();
        if (value == null) {
            throw new IllegalStateException("未选择游戏实例");
        }
        return value;
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
        cancelActiveRequest();
    }
}
