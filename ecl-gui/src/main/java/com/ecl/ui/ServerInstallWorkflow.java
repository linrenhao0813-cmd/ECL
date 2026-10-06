package com.ecl.ui;

import com.ecl.download.DownloadTaskCenter;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.launcher.ServerInstaller;
import com.ecl.util.FileUtil;
import com.ecl.util.Messages;
import javafx.application.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** Installs an independent server directory; never installs or selects a client instance. */
class ServerInstallWorkflow {
    private final LauncherUI ui;

    ServerInstallWorkflow(LauncherUI ui) {
        this.ui = ui;
    }

    void install(String minecraftVersion, LoaderChoice choice, String loaderVersion,
                 List<String> compatibleVersions, InstanceInstallWorkflow.Listener listener) {
        if (minecraftVersion == null || minecraftVersion.isBlank() || choice == null) {
            listener.onFailure(GuiMessages.get("instance.install.server.invalid"));
            return;
        }
        if (!choice.vanilla() && (loaderVersion == null || loaderVersion.isBlank()
                || compatibleVersions == null || !compatibleVersions.contains(loaderVersion))) {
            listener.onFailure(Messages.get("instance.install.loaderVersion.choose"));
            return;
        }
        try {
            FileUtil.requireSafeVersionId(minecraftVersion);
            if (!choice.vanilla()) FileUtil.requireSafeVersionId(loaderVersion);
        } catch (java.io.IOException error) {
            listener.onFailure(GuiMessages.get("instance.install.server.invalid"));
            return;
        }
        Path target = ui.getServerDownloadDir().toPath().toAbsolutePath().normalize()
                .resolve("server-" + UUID.randomUUID());
        if (Files.exists(target)) {
            listener.onFailure(GuiMessages.get("instance.install.server.invalid"));
            return;
        }
        String title = GuiMessages.get("instance.install.server.task", minecraftVersion, choice.displayName);
        AtomicReference<AtomicReference<Path>> attempt = new AtomicReference<>();
        ui.downloadTaskCenter.<Path>submit(title, () -> {
            AtomicReference<Path> published = new AtomicReference<>();
            attempt.set(published);
            LauncherUiFactory.runOnUi(() -> {
                ui.setControlsBusy(true);
                ui.downloadProgress.setProgress(0);
                ui.startProgressAnimation(ui.downloadProgress);
                listener.onStarted();
                String destination = GuiMessages.get("instance.install.server.destination", target);
                listener.onStatus(destination);
                ui.setStatus(GuiMessages.get("instance.install.server.taskStatus"), destination);
            });
            return context -> runInstall(context, minecraftVersion, choice, loaderVersion, target, listener, published);
        }, (directory, error) -> {
            // Completion runs before the task future resolves, so retries cannot race this cleanup.
            Throwable failure = cleanupCancelledInstallation(attempt.get().get(), error);
            Platform.runLater(() -> finish(directory, failure, listener));
        });
    }

    private void finish(Path directory, Throwable error, InstanceInstallWorkflow.Listener listener) {
        ui.stopProgressAnimation(ui.downloadProgress, error != null);
        ui.setControlsBusy(false);
        if (error != null) {
            String message = ui.cleanMessage(error);
            listener.onFailure(message);
            ui.setStatus(GuiMessages.get(ui.isCancellation(error)
                    ? "instance.install.server.cancelled" : "instance.install.server.failed"), message);
            return;
        }
        listener.onComplete(directory.toString());
        ui.setStatus(GuiMessages.get("instance.install.server.finished"),
                GuiMessages.get("instance.install.server.complete", directory));
    }

    private static Throwable cleanupCancelledInstallation(Path published, Throwable error) {
        if (error == null || published == null) return error;
        boolean interrupted = Thread.interrupted();
        try {
            FileUtil.deleteDirectory(published);
            return error;
        } catch (java.io.IOException | RuntimeException cleanupError) {
            java.io.IOException failure = new java.io.IOException(
                    GuiMessages.get("instance.install.server.cleanupFailed", published));
            failure.addSuppressed(cleanupError);
            failure.addSuppressed(error);
            return failure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private Path runInstall(DownloadTaskCenter.TaskContext context, String minecraftVersion,
                            LoaderChoice choice, String loaderVersion, Path target,
                            InstanceInstallWorkflow.Listener listener, AtomicReference<Path> published) throws java.io.IOException {
        requireActive(context);
        FileUtil.validateExistingAncestors(target.getRoot(), target);
        Files.createDirectories(target.getParent());
        requireActive(context);
        Path result = installServer(minecraftVersion, choice, loaderVersion, target, new ModLoaderInstaller.Listener() {
                    @Override
                    public void onStatus(String message) {
                        requireActive(context);
                        context.updateStatus(message);
                        Platform.runLater(() -> {
                            listener.onStatus(message);
                            ui.setStatus(GuiMessages.get("instance.install.server.taskStatus"), message);
                        });
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        requireActive(context);
                        context.updateProgress(downloaded, total);
                        Platform.runLater(() -> {
                            listener.onProgress(downloaded, total);
                            ui.updateProgress(ui.downloadProgress, downloaded, total);
                        });
                    }
                });
        published.set(result);
        requireActive(context);
        return result;
    }

    Path installServer(String minecraftVersion, LoaderChoice choice, String loaderVersion, Path target,
                       ModLoaderInstaller.Listener listener) throws java.io.IOException {
        return new ServerInstaller(ui.serverJarDownloader, ui.modLoaderInstaller).install(
                minecraftVersion, choice.loader, loaderVersion, target, listener);
    }

    private static void requireActive(DownloadTaskCenter.TaskContext context) {
        if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException(GuiMessages.get("instance.install.server.cancelled"));
        }
    }
}
