package com.ecl.ui;

import com.ecl.util.Messages;
import com.ecl.modrinth.model.ContentVersion;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Loader-choice and progress page shown after selecting a Minecraft version. */
final class InstanceInstallPage extends VBox {
    private final LauncherUI ui;
    private final String minecraftVersion;
    private final Function<LoaderChoice, CompletableFuture<List<String>>> versionLookup;
    private final Supplier<CompletableFuture<List<ContentVersion>>> fabricApiLookup;
    private final ToggleGroup choices = new ToggleGroup();
    private final Label choiceHint = new Label();
    private final Label status = new Label(Messages.get("instance.install.ready"));
    private final ProgressBar progress = new ProgressBar(0);
    private final Button backButton;
    private final Button installButton;
    private final ComboBox<String> loaderVersions = new ComboBox<>();
    private final Label versionStatus = new Label();
    private final Button refreshVersions;
    private final VBox versionSection;
    private final ComboBox<ContentVersion> fabricApiVersions = new ComboBox<>();
    private final Label fabricApiStatus = new Label();
    private final Button refreshFabricApi;
    private final VBox fabricApiSection;
    private int fabricApiRequest;
    private int versionRequest;
    private boolean busy;

    InstanceInstallPage(LauncherUI ui, String minecraftVersion, Runnable backAction) {
        this(ui, minecraftVersion, backAction, choice -> ui.controller.supplyAsync("ecl-loader-versions", () -> {
            try {
                return ui.modLoaderInstaller.listVersions(minecraftVersion, choice.loader);
            } catch (java.io.IOException error) {
                throw new UncheckedIOException(error);
            }
        }), () -> ui.controller.supplyAsync("ecl-fabric-api-versions", () -> {
            try {
                return new InstanceInstallWorkflow(ui).listFabricApiVersions(minecraftVersion);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }));
    }

    InstanceInstallPage(LauncherUI ui, String minecraftVersion, Runnable backAction,
                        Function<LoaderChoice, CompletableFuture<List<String>>> versionLookup,
                        Supplier<CompletableFuture<List<ContentVersion>>> fabricApiLookup) {
        this.ui = ui;
        this.minecraftVersion = minecraftVersion;
        this.versionLookup = versionLookup;
        this.fabricApiLookup = fabricApiLookup;
        getStyleClass().add("instance-install-page");
        setSpacing(18);
        setMaxWidth(Double.MAX_VALUE);

        backButton = ui.createActionButton(Messages.get("instance.install.back"),
                "ghost-button", backAction);
        Label eyebrow = new Label(Messages.get("instance.install.eyebrow"));
        eyebrow.getStyleClass().add("card-kicker");
        Label title = new Label("Minecraft " + minecraftVersion);
        title.getStyleClass().add("content-library-section-title");
        Label subtitle = new Label(Messages.get("instance.install.subtitle"));
        subtitle.getStyleClass().add("section-subtitle");
        subtitle.setWrapText(true);
        VBox heading = new VBox(5, eyebrow, title, subtitle);
        HBox header = new HBox(14, backButton, heading);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox choiceList = new VBox(9);
        choiceList.getStyleClass().add("instance-install-choices");
        for (LoaderChoice choice : LoaderChoice.values()) {
            choiceList.getChildren().add(createChoice(choice));
        }
        choices.selectToggle(choices.getToggles().getFirst());
        choiceHint.getStyleClass().add("status-detail");
        choiceHint.setWrapText(true);

        loaderVersions.setId("instance-loader-version");
        loaderVersions.setPromptText(Messages.get("instance.install.loaderVersion.choose"));
        loaderVersions.setAccessibleText(Messages.get("instance.install.loaderVersion.title"));
        loaderVersions.setMaxWidth(Double.MAX_VALUE);
        ui.applyFieldStyle(loaderVersions);
        refreshVersions = ui.createActionButton(Messages.get("instance.install.loaderVersion.refresh"),
                "ghost-button", this::loadLoaderVersions);
        versionStatus.getStyleClass().add("status-detail");
        versionStatus.setWrapText(true);
        HBox versionRow = new HBox(12, loaderVersions, refreshVersions);
        HBox.setHgrow(loaderVersions, Priority.ALWAYS);
        versionSection = new VBox(8, new Label(Messages.get("instance.install.loaderVersion.title")),
                versionRow, versionStatus);

        fabricApiVersions.setId("instance-fabric-api-version");
        fabricApiVersions.setPromptText(Messages.get("instance.install.fabricApiVersion.choose"));
        fabricApiVersions.setAccessibleText(Messages.get("instance.install.fabricApiVersion.title"));
        fabricApiVersions.setMaxWidth(Double.MAX_VALUE);
        ui.applyFieldStyle(fabricApiVersions);
        refreshFabricApi = ui.createActionButton(Messages.get("instance.install.loaderVersion.refresh"),
                "ghost-button", this::loadFabricApiVersions);
        fabricApiStatus.getStyleClass().add("status-detail");
        fabricApiStatus.setWrapText(true);
        HBox apiRow = new HBox(12, fabricApiVersions, refreshFabricApi);
        HBox.setHgrow(fabricApiVersions, Priority.ALWAYS);
        fabricApiSection = new VBox(8, new Label(Messages.get("instance.install.fabricApiVersion.title")), apiRow, fabricApiStatus);

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("download-progress");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        installButton = ui.createActionButton(Messages.get("instance.install.action"),
                "primary-button", this::startInstall);
        installButton.setId("instance-install-action");
        loaderVersions.valueProperty().addListener((obs, oldValue, newValue) -> updateInstallAvailability());
        fabricApiVersions.valueProperty().addListener((obs, oldValue, newValue) -> updateInstallAvailability());
        choices.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            updateChoiceHint();
            loadLoaderVersions();
            loadFabricApiVersions();
        });
        updateChoiceHint();
        loadLoaderVersions();
        loadFabricApiVersions();
        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actions = new HBox(12, status, actionSpacer, installButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);

        getChildren().addAll(header,
                ui.createSurface(Messages.get("instance.install.choice.title"),
                        Messages.get("instance.install.choice.subtitle"), choiceList, choiceHint, versionSection, fabricApiSection),
                ui.createSurface(Messages.get("instance.install.progress.title"), null,
                        progress, actions));
    }

    private ToggleButton createChoice(LoaderChoice choice) {
        Label title = new Label(choice.displayName);
        title.getStyleClass().add("instance-install-choice-title");
        Label detail = new Label(choiceDetail(choice));
        detail.getStyleClass().add("instance-install-choice-detail");
        ToggleButton button = new ToggleButton();
        button.setGraphic(new VBox(3, title, detail));
        button.setUserData(choice);
        button.setToggleGroup(choices);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(64);
        button.setPrefHeight(64);
        button.setMaxHeight(64);
        button.getStyleClass().add("instance-install-choice");
        return button;
    }

    private String choiceDetail(LoaderChoice choice) {
        return switch (choice) {
            case VANILLA -> Messages.get("instance.install.choice.vanilla");
            case FABRIC -> Messages.get("instance.install.choice.fabric");
            case QUILT -> Messages.get("instance.install.choice.quilt");
            case FORGE -> Messages.get("instance.install.choice.forge");
            case NEOFORGE -> Messages.get("instance.install.choice.neoforge");
        };
    }

    private void updateChoiceHint() {
        LoaderChoice choice = selectedChoice();
        choiceHint.setText(choice == LoaderChoice.FABRIC
                ? Messages.get("instance.install.fabricApi.notice")
                : Messages.format("instance.install.selected", choice.displayName));
    }

    private LoaderChoice selectedChoice() {
        if (choices.getSelectedToggle() == null
                || !(choices.getSelectedToggle().getUserData() instanceof LoaderChoice choice)) {
            return LoaderChoice.VANILLA;
        }
        return choice;
    }

    private void loadLoaderVersions() {
        int request = ++versionRequest;
        LoaderChoice choice = selectedChoice();
        versionSection.setVisible(!choice.vanilla());
        versionSection.setManaged(!choice.vanilla());
        loaderVersions.getItems().clear();
        loaderVersions.setValue(null);
        loaderVersions.setDisable(true);
        refreshVersions.setDisable(true);
        updateInstallAvailability();
        if (choice.vanilla()) return;
        versionStatus.setText(Messages.get("instance.install.loaderVersion.loading"));
        versionLookup.apply(choice).whenComplete((versions, error) -> Platform.runLater(() -> {
            if (request != versionRequest) return;
            refreshVersions.setDisable(busy);
            if (error != null) {
                versionStatus.setText(Messages.format("instance.install.loaderVersion.failed", ui.cleanMessage(error)));
            } else if (versions.isEmpty()) {
                versionStatus.setText(Messages.get("instance.install.loaderVersion.empty"));
            } else {
                loaderVersions.getItems().setAll(versions);
                loaderVersions.setDisable(busy);
                versionStatus.setText(Messages.get("instance.install.loaderVersion.choose"));
            }
            updateInstallAvailability();
        }));
    }

    private void loadFabricApiVersions() {
        int request = ++fabricApiRequest;
        boolean fabric = selectedChoice() == LoaderChoice.FABRIC;
        fabricApiSection.setVisible(fabric);
        fabricApiSection.setManaged(fabric);
        fabricApiVersions.getItems().clear();
        fabricApiVersions.setValue(null);
        fabricApiVersions.setDisable(true);
        refreshFabricApi.setDisable(true);
        updateInstallAvailability();
        if (!fabric) return;
        fabricApiStatus.setText(Messages.get("instance.install.fabricApiVersion.loading"));
        fabricApiLookup.get().whenComplete((versions, error) -> Platform.runLater(() -> {
            if (request != fabricApiRequest) return;
            refreshFabricApi.setDisable(busy);
            if (error != null) {
                fabricApiStatus.setText(Messages.format("instance.install.fabricApiVersion.failed", ui.cleanMessage(error)));
            } else if (versions.isEmpty()) {
                fabricApiStatus.setText(Messages.get("instance.install.fabricApiVersion.empty"));
            } else {
                fabricApiVersions.getItems().setAll(versions);
                fabricApiVersions.setDisable(busy);
                fabricApiStatus.setText(Messages.get("instance.install.fabricApiVersion.choose"));
            }
            updateInstallAvailability();
        }));
    }

    private boolean missingVersion() {
        return (!selectedChoice().vanilla() && loaderVersions.getValue() == null)
                || (selectedChoice() == LoaderChoice.FABRIC && fabricApiVersions.getValue() == null);
    }

    private void updateInstallAvailability() {
        installButton.setDisable(busy || missingVersion());
    }

    private void startInstall() {
        LoaderChoice choice = selectedChoice();
        String loaderVersion = loaderVersions.getValue();
        if (busy || missingVersion()) return;
        new InstanceInstallWorkflow(ui).install(minecraftVersion, choice, loaderVersion, fabricApiVersions.getValue(),
                new InstanceInstallWorkflow.Listener() {
                    @Override
                    public void onStarted() {
                        setBusy(true);
                        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        status.setText(Messages.get("instance.install.starting"));
                    }

                    @Override
                    public void onStatus(String message) {
                        status.setText(message);
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        progress.setProgress(total > 0 ? (double) downloaded / total
                                : ProgressBar.INDETERMINATE_PROGRESS);
                    }

                    @Override
                    public void onComplete(String profileId) {
                        progress.setProgress(1);
                        status.setText(Messages.format("instance.install.complete", profileId));
                        installButton.setText(Messages.get("instance.install.done"));
                        backButton.setDisable(false);
                    }

                    @Override
                    public void onFailure(String message) {
                        progress.setProgress(0);
                        status.setText(Messages.format("instance.install.failed", message));
                        setBusy(false);
                    }
                });
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        backButton.setDisable(busy);
        updateInstallAvailability();
        loaderVersions.setDisable(busy || loaderVersions.getItems().isEmpty());
        refreshVersions.setDisable(busy);
        fabricApiVersions.setDisable(busy || fabricApiVersions.getItems().isEmpty());
        refreshFabricApi.setDisable(busy);
        choices.getToggles().forEach(toggle -> ((ToggleButton) toggle).setDisable(busy));
    }
}
