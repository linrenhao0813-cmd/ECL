package com.ecl.modrinth.ui.viewmodel;

import com.ecl.download.DownloadTaskCenter;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.ModVersion;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.modrinth.service.ModInstallationResult;
import com.ecl.modrinth.service.ModInstallationService;
import com.ecl.modrinth.service.ModDependencyResolver;
import com.ecl.modrinth.transaction.InstallationPlanBuilder;
import com.ecl.modrinth.transaction.ModInstallationPlan;
import javafx.application.Platform;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** Prepares dependency plans and runs their transactional installation. */
final class ModInstallationWorkflow {
    private final ModDependencyResolver dependencyResolver;
    private final InstallationPlanBuilder planBuilder;
    private final ModInstallationService installationService;
    private final DownloadTaskCenter taskCenter;
    private final Supplier<ModInstanceContext> instanceSupplier;
    private final ModBrowserOperationState operations;
    private final Function<Throwable, String> errorFormatter;
    private final Runnable refreshInstalled;
    private final java.util.function.Consumer<String> setError;
    private final java.util.function.Consumer<String> setOperation;

    ModInstallationWorkflow(ModDependencyResolver dependencyResolver,
                            InstallationPlanBuilder planBuilder,
                            ModInstallationService installationService,
                            DownloadTaskCenter taskCenter,
                            Supplier<ModInstanceContext> instanceSupplier,
                            ModBrowserOperationState operations,
                            Function<Throwable, String> errorFormatter,
                            Runnable refreshInstalled,
                            java.util.function.Consumer<String> setError,
                            java.util.function.Consumer<String> setOperation) {
        this.dependencyResolver = dependencyResolver;
        this.planBuilder = planBuilder;
        this.installationService = installationService;
        this.taskCenter = taskCenter;
        this.instanceSupplier = instanceSupplier;
        this.operations = operations;
        this.errorFormatter = errorFormatter;
        this.refreshInstalled = refreshInstalled;
        this.setError = setError;
        this.setOperation = setOperation;
    }

    CompletableFuture<ModInstallationPlan> prepare(ModVersion version, Set<String> optionalIds,
                                                    ReleaseChannel channel) {
        ModInstanceContext instance = instanceSupplier.get();
        operations.begin("正在解析依赖与冲突…", true);
        CompletableFuture<ModInstallationPlan> request = dependencyResolver
                .resolve(instance, version, optionalIds, channel)
                .thenApply(resolution -> planBuilder.build(instance, version, resolution));
        operations.track(request);
        request.whenComplete((ignored, error) -> Platform.runLater(() -> {
            operations.finish();
            if (error != null && !isCancellation(error)) {
                setError.accept(errorFormatter.apply(error));
            }
        }));
        return request;
    }

    CompletableFuture<ModInstallationResult> install(ModInstallationPlan plan) {
        operations.begin("正在下载并事务安装…", true);
        CompletableFuture<ModInstallationResult> request = queue(plan);
        operations.track(request);
        request.whenComplete((result, error) -> Platform.runLater(() -> {
            operations.finish();
            if (error == null) {
                setOperation.accept(result.updated() ? "模组更新完成" : "模组安装完成");
                refreshInstalled.run();
            } else if (!isCancellation(error)) {
                setError.accept(errorFormatter.apply(error));
            }
        }));
        return request;
    }

    private CompletableFuture<ModInstallationResult> queue(ModInstallationPlan plan) {
        if (taskCenter == null) {
            return installationService.install(plan, progress -> operations.updateProgress(
                    progress.overallDownloaded(), progress.overallTotal(), progress.fileName()));
        }
        DownloadTaskCenter.TaskHandle<ModInstallationResult> task = taskCenter.submit(
                "Mod installation", () -> context -> {
                    CompletableFuture<ModInstallationResult> inner = installationService.install(plan, progress -> {
                        context.updateStatus("Downloading " + progress.fileName());
                        context.updateProgress(progress.overallDownloaded(), progress.overallTotal());
                        operations.updateProgress(progress.overallDownloaded(), progress.overallTotal(),
                                progress.fileName());
                    });
                    context.registerCancellation(() -> inner.cancel(true));
                    return inner.join();
                });
        operations.trackDownload(task);
        return task.completion();
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
