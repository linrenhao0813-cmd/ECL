package com.ecl.ui;

import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.ServerJarDownloader;
import com.ecl.launcher.VersionManager;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Builds the server JAR browser and coordinates its metadata/download controls. */
final class ServerJarDownloadPage {
    private final LauncherUI ui;

    ServerJarDownloadPage(LauncherUI ui) {
        this.ui = ui;
    }

    VBox build() {
        Label eyebrow = new Label(Messages.get("server.library.eyebrow"));
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label(Messages.get("server.library.title"));
        title.getStyleClass().add("content-library-section-title");
        Label description = new Label(Messages.get("server.library.description"));
        description.getStyleClass().add("status-detail");
        description.setWrapText(true);
        VBox heading = new VBox(4, eyebrow, title, description);

        ComboBox<VersionManager.VersionCategory> categoryCombo = new ComboBox<>();
        categoryCombo.getItems().addAll(VersionManager.VersionCategory.values());
        categoryCombo.setValue(VersionManager.VersionCategory.ALL);
        categoryCombo.setPrefWidth(176);
        categoryCombo.setCellFactory(list -> createCategoryCell());
        categoryCombo.setButtonCell(createCategoryCell());
        ui.applyFieldStyle(categoryCombo);

        ComboBox<String> serverVersionCombo = new ComboBox<>();
        serverVersionCombo.setVisibleRowCount(18);
        serverVersionCombo.setMaxWidth(Double.MAX_VALUE);
        serverVersionCombo.setCellFactory(list -> createVersionCell());
        serverVersionCombo.setButtonCell(createVersionCell());
        ui.applyFieldStyle(serverVersionCombo);
        HBox.setHgrow(serverVersionCombo, Priority.ALWAYS);

        Button refreshButton = ui.createActionButton(Messages.get("server.download.refresh"),
                "secondary-button", () -> { });
        HBox versionRow = new HBox(8, categoryCombo, serverVersionCombo, refreshButton);

        Label artifactInfo = new Label(Messages.get("server.download.artifactPrompt"));
        artifactInfo.getStyleClass().add("content-library-description");
        artifactInfo.setWrapText(true);
        Label channelsLabel = new Label(Messages.get("server.download.channelsPending"));
        channelsLabel.getStyleClass().add("content-library-target");
        channelsLabel.setWrapText(true);
        AtomicReference<File> directory = new AtomicReference<>(
                new File(ui.getConfiguredGameRootDir(), "server-downloads"));
        Label targetLabel = new Label();
        targetLabel.getStyleClass().add("content-library-target");
        targetLabel.setWrapText(true);
        Label status = new Label(Messages.get("server.download.selectVersion"));
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        ProgressBar progress = new ProgressBar(0);
        progress.getStyleClass().add("download-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());

        Button downloadButton = ui.createActionButton(Messages.get("server.download.action.download"),
                "primary-button", () -> { });
        downloadButton.setDisable(true);
        Button chooseFolderButton = ui.createActionButton(
                Messages.get("server.download.action.changeFolder"), "secondary-button", () -> { });
        Button openFolderButton = ui.createActionButton(
                Messages.get("server.download.action.openFolder"), "secondary-button", () -> { });
        HBox actions = new HBox(8, downloadButton, chooseFolderButton, openFolderButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Label eulaNotice = new Label(Messages.get("server.download.eula"));
        eulaNotice.getStyleClass().add("status-detail");
        eulaNotice.setWrapText(true);

        AtomicReference<ServerJarDownloader.ServerArtifact> artifact = new AtomicReference<>();
        AtomicLong metadataGeneration = new AtomicLong();
        AtomicLong downloadGeneration = new AtomicLong();
        Runnable updateTarget = () -> {
            String version = serverVersionCombo.getValue();
            String fileName = ServerJarDownloader.suggestedFileName(version);
            targetLabel.setText(Messages.format("server.download.target",
                    new File(directory.get(), fileName).getAbsolutePath()));
        };
        Runnable loadArtifact = () -> loadArtifact(serverVersionCombo.getValue(), artifact,
                artifactInfo, channelsLabel, status, progress, downloadButton,
                metadataGeneration, updateTarget);
        Runnable updateVersions = () -> updateVersions(categoryCombo, serverVersionCombo,
                artifact, artifactInfo, channelsLabel, status, downloadButton, loadArtifact,
                updateTarget);

        serverVersionCombo.setOnAction(event -> {
            updateTarget.run();
            loadArtifact.run();
        });
        categoryCombo.setOnAction(event -> updateVersions.run());
        refreshButton.setOnAction(event -> refreshVersions(refreshButton, categoryCombo,
                serverVersionCombo, status, updateVersions));
        chooseFolderButton.setOnAction(event -> chooseFolder(directory, updateTarget, status));
        openFolderButton.setOnAction(event -> {
            try {
                ui.ensureDirectory(directory.get());
                ui.openLocalFolder(directory.get(), Messages.get("server.download.directoryName"));
            } catch (IOException error) {
                status.setText(Messages.format(
                        "server.download.openDirectoryFailed", ui.cleanMessage(error)));
            }
        });
        downloadButton.setOnAction(event -> download(artifact.get(), directory.get(), status,
                progress, downloadButton, chooseFolderButton, serverVersionCombo, categoryCombo,
                refreshButton, downloadGeneration));

        updateVersions.run();
        updateTarget.run();
        VBox browser = new VBox(12, heading, versionRow, artifactInfo, channelsLabel,
                targetLabel, status, progress, eulaNotice, actions);
        browser.getStyleClass().addAll("surface", "content-library-browser");
        browser.setFillWidth(true);
        return browser;
    }

    private void updateVersions(ComboBox<VersionManager.VersionCategory> categoryCombo,
                                ComboBox<String> serverVersionCombo,
                                AtomicReference<ServerJarDownloader.ServerArtifact> artifact,
                                Label artifactInfo, Label channelsLabel, Label status,
                                Button downloadButton, Runnable loadArtifact, Runnable updateTarget) {
        String previous = serverVersionCombo.getValue();
        String currentMinecraftVersion = selectedMinecraftVersion();
        List<String> versions = new java.util.ArrayList<>(ui.versionManager.getVersions(
                categoryCombo.getValue() == null
                        ? VersionManager.VersionCategory.ALL : categoryCombo.getValue()));
        if (!currentMinecraftVersion.isBlank() && !versions.contains(currentMinecraftVersion)) {
            versions.addFirst(currentMinecraftVersion);
        }
        serverVersionCombo.getItems().setAll(versions);
        if (previous != null && versions.contains(previous)) {
            serverVersionCombo.setValue(previous);
        } else if (!currentMinecraftVersion.isBlank() && versions.contains(currentMinecraftVersion)) {
            serverVersionCombo.setValue(currentMinecraftVersion);
        } else if (!versions.isEmpty()) {
            serverVersionCombo.setValue(versions.getFirst());
        } else {
            artifact.set(null);
            artifactInfo.setText(Messages.get("server.download.listEmpty"));
            channelsLabel.setText(Messages.get("server.download.channelsWaitingVersions"));
            status.setText(Messages.get("server.download.noVersions"));
            downloadButton.setDisable(true);
            updateTarget.run();
        }
        if (serverVersionCombo.getValue() != null) {
            loadArtifact.run();
        }
    }

    private void refreshVersions(Button refreshButton,
                                 ComboBox<VersionManager.VersionCategory> categoryCombo,
                                 ComboBox<String> serverVersionCombo, Label status,
                                 Runnable updateVersions) {
        refreshButton.setDisable(true);
        categoryCombo.setDisable(true);
        serverVersionCombo.setDisable(true);
        status.setText(Messages.get("server.download.refreshingVersions"));
        ui.runAsync("ecl-refresh-server-versions", () -> {
            try {
                ui.versionManager.refresh();
                Platform.runLater(() -> {
                    updateVersions.run();
                    refreshButton.setDisable(false);
                    categoryCombo.setDisable(false);
                    serverVersionCombo.setDisable(false);
                    status.setText(Messages.get("server.download.versionsUpdated"));
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    refreshButton.setDisable(false);
                    categoryCombo.setDisable(false);
                    serverVersionCombo.setDisable(false);
                    status.setText(Messages.format(
                            "server.download.versionsFailed", ui.cleanMessage(error)));
                });
            }
        });
    }

    private void chooseFolder(AtomicReference<File> directory, Runnable updateTarget, Label status) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.get("server.download.chooseDirectory"));
        File current = directory.get();
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File selected = chooser.showDialog(ui.primaryStage);
        if (selected != null) {
            directory.set(selected);
            updateTarget.run();
            status.setText(Messages.get("server.download.directoryChanged"));
        }
    }

    private void download(ServerJarDownloader.ServerArtifact artifact, File directory, Label status,
                          ProgressBar progress, Button downloadButton, Button chooseFolderButton,
                          ComboBox<String> serverVersionCombo,
                          ComboBox<VersionManager.VersionCategory> categoryCombo,
                          Button refreshButton, AtomicLong downloadGeneration) {
        if (artifact == null) {
            return;
        }
        File target = new File(directory, ServerJarDownloader.suggestedFileName(artifact.versionId()));
        try {
            ui.ensureDirectory(directory);
        } catch (IOException error) {
            status.setText(Messages.format(
                    "server.download.createDirectoryFailed", ui.cleanMessage(error)));
            return;
        }
        long generation = downloadGeneration.incrementAndGet();
        setControlsBusy(true, downloadButton, chooseFolderButton, serverVersionCombo,
                categoryCombo, refreshButton);
        progress.setProgress(0);
        progress.setVisible(true);
        status.setText(Messages.format("server.download.preparing", artifact.versionId()));
        ui.setStatus(Messages.get("server.download.statusTitle"),
                artifact.versionId() + " · " + target.getName());

        ui.downloadTaskCenter.submit("Server JAR " + artifact.versionId(), () -> context -> {
            try {
                ui.serverJarDownloader.download(artifact, target,
                        createDownloadListener(status, progress, generation, downloadGeneration, context));
                if (context.isCancelled()) {
                    return null;
                }
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get() || context.isCancelled()) {
                        return;
                    }
                    progress.setProgress(1);
                    status.setText(Messages.format(
                            "server.download.completedVerified", target.getAbsolutePath()));
                    ui.setStatus(Messages.get("server.download.completedTitle"), target.getAbsolutePath());
                    setControlsBusy(false, downloadButton, chooseFolderButton, serverVersionCombo,
                            categoryCombo, refreshButton);
                });
            } catch (Exception error) {
                boolean cancelled = context.isCancelled() || ui.isCancellation(error);
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) {
                        return;
                    }
                    if (cancelled) {
                        status.setText(Messages.get("download.status.cancelled"));
                        ui.setStatus(Messages.get("download.status.cancelled"), "");
                    } else {
                        status.setText(Messages.format("download.status.failed", ui.cleanMessage(error)));
                        ui.setStatus(Messages.get("status.downloadFailed"), ui.cleanMessage(error));
                    }
                    setControlsBusy(false, downloadButton, chooseFolderButton, serverVersionCombo,
                            categoryCombo, refreshButton);
                });
                throw error;
            }
            return null;
        });
    }

    private void loadArtifact(String version,
                              AtomicReference<ServerJarDownloader.ServerArtifact> artifact,
                              Label artifactInfo, Label channelsLabel, Label status,
                              ProgressBar progress, Button downloadButton,
                              AtomicLong metadataGeneration, Runnable updateTarget) {
        long generation = metadataGeneration.incrementAndGet();
        artifact.set(null);
        downloadButton.setDisable(true);
        progress.setVisible(false);
        if (version == null || version.isBlank()) {
            return;
        }
        artifactInfo.setText(Messages.format("server.download.metadataReading", version));
        channelsLabel.setText(Messages.get("server.download.channelsResolving"));
        status.setText(Messages.get("server.download.manifestQuery"));
        ui.runAsync("ecl-resolve-server-" + version, () -> {
            try {
                ServerJarDownloader.ServerArtifact resolved = ui.serverJarDownloader.resolve(version,
                        new ServerJarDownloader.Listener() {
                            @Override
                            public void onSource(String sourceName, String candidateUrl, boolean mirror) {
                                Platform.runLater(() -> {
                                    if (generation == metadataGeneration.get()) {
                                        status.setText(Messages.format(
                                                "server.download.metadataSource", sourceName));
                                    }
                                });
                            }

                            @Override
                            public void onSourceFailure(String candidateUrl, IOException error) {
                                Platform.runLater(() -> {
                                    if (generation == metadataGeneration.get()) {
                                        status.setText(Messages.get(
                                                "server.download.metadataSourceFailed"));
                                    }
                                });
                            }
                        });
                Platform.runLater(() -> {
                    if (generation != metadataGeneration.get()) {
                        return;
                    }
                    artifact.set(resolved);
                    String size = resolved.size() < 0
                            ? Messages.get("server.download.sizeUnknown") : ui.formatBytes(resolved.size());
                    String sha1 = resolved.sha1() == null || resolved.sha1().isBlank()
                            ? Messages.get("server.download.shaUnavailable") : resolved.sha1();
                    artifactInfo.setText(Messages.format("server.download.artifactDetails",
                            resolved.versionId(), size, sha1));
                    channelsLabel.setText(Messages.format("server.download.channels",
                            resolved.channels().stream()
                                    .map(ServerJarDownloader.DownloadChannel::name)
                                    .distinct()
                                    .collect(java.util.stream.Collectors.joining(" → "))));
                    status.setText(Messages.get("server.download.available"));
                    downloadButton.setDisable(false);
                    updateTarget.run();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (generation != metadataGeneration.get()) {
                        return;
                    }
                    artifactInfo.setText(Messages.format("server.download.noArtifact", version));
                    channelsLabel.setText(Messages.get("server.download.channelsUnavailable"));
                    status.setText(ui.cleanMessage(error));
                });
            }
        });
    }

    private ServerJarDownloader.Listener createDownloadListener(
            Label status, ProgressBar progress, long generation,
            AtomicLong downloadGeneration, DownloadTaskCenter.TaskContext taskContext) {
        return new ServerJarDownloader.Listener() {
            @Override
            public void onStart(long total) {
                taskContext.updateProgress(0, total);
                Platform.runLater(() -> {
                    if (generation == downloadGeneration.get()) {
                        progress.setProgress(0);
                    }
                });
            }

            @Override
            public void onProgress(long downloaded, long total) {
                taskContext.updateProgress(downloaded, total);
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) {
                        return;
                    }
                    progress.setProgress(total > 0 ? (double) downloaded / total : -1);
                    status.setText(total > 0
                            ? Messages.format("server.download.progressKnown",
                                    ui.formatBytes(downloaded), ui.formatBytes(total))
                            : Messages.format("server.download.progressUnknown",
                                    ui.formatBytes(downloaded)));
                });
            }

            @Override
            public void onSource(String sourceName, String candidateUrl, boolean mirror) {
                Platform.runLater(() -> {
                    if (generation == downloadGeneration.get()) {
                        status.setText(Messages.format("server.download.usingSource", sourceName));
                    }
                });
            }

            @Override
            public void onSourceFailure(String candidateUrl, IOException error) {
                Platform.runLater(() -> {
                    if (generation == downloadGeneration.get()) {
                        status.setText(Messages.get("server.download.sourceFailed"));
                    }
                });
            }
        };
    }

    private void setControlsBusy(boolean busy, Button downloadButton, Button chooseFolderButton,
                                 ComboBox<String> serverVersionCombo,
                                 ComboBox<VersionManager.VersionCategory> categoryCombo,
                                 Button refreshButton) {
        downloadButton.setDisable(busy);
        chooseFolderButton.setDisable(busy);
        serverVersionCombo.setDisable(busy);
        categoryCombo.setDisable(busy);
        refreshButton.setDisable(busy);
    }

    private String selectedMinecraftVersion() {
        String profile = ui.getSelectedVersion();
        if (profile == null || profile.isBlank()) {
            return "";
        }
        try {
            return ui.versionManager.resolveMinecraftVersionId(profile);
        } catch (IOException error) {
            return profile;
        }
    }

    private ListCell<String> createVersionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        };
    }

    private ListCell<VersionManager.VersionCategory> createCategoryCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(VersionManager.VersionCategory item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item) {
                    case FEATURED -> Messages.get("server.download.category.featured");
                    case RELEASE -> Messages.get("version.release");
                    case PREVIEW -> Messages.get("version.preview");
                    case APRIL_FOOLS -> Messages.get("version.aprilFools");
                    case ALL -> Messages.get("version.all");
                });
            }
        };
    }

}
