package com.ecl.ui;

import com.ecl.util.Messages;
import com.ecl.modrinth.model.ContentVersion;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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

/** Installation choices and action shown after selecting a Minecraft version. */
final class InstanceInstallPage extends VBox {
    private final LauncherUI ui;
    private final String minecraftVersion;
    private final Function<LoaderChoice, CompletableFuture<List<String>>> versionLookup;
    private final Supplier<CompletableFuture<List<ContentVersion>>> fabricApiLookup;
    private final ToggleGroup choices = new ToggleGroup();
    private final ToggleGroup modes = new ToggleGroup();
    private final ToggleButton clientMode = new ToggleButton(GuiMessages.get("instance.install.mode.client"));
    private final ToggleButton serverMode = new ToggleButton(GuiMessages.get("instance.install.mode.server"));
    private final Label serverDestination = new Label();
    private final Label serverNotice = new Label(GuiMessages.get("instance.install.server.notice"));
    private final VBox serverSection = new VBox(8, serverDestination, serverNotice);
    private final Label choiceHint = new Label();
    private final Label subtitle = new Label();
    private final Label choiceSubtitle = new Label();
    private final Label status = new Label();
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
        subtitle.getStyleClass().add("section-subtitle");
        choiceSubtitle.getStyleClass().add("section-subtitle");
        choiceSubtitle.setWrapText(true);
        subtitle.setWrapText(true);
        VBox heading = new VBox(5, eyebrow, title, subtitle);
        HBox header = new HBox(14, backButton, heading);
        header.setAlignment(Pos.CENTER_LEFT);

        clientMode.setId("instance-install-client");
        serverMode.setId("instance-install-server");
        clientMode.setToggleGroup(modes);
        serverMode.setToggleGroup(modes);
        modes.selectToggle(clientMode);
        clientMode.getStyleClass().addAll("instance-install-choice", "instance-install-choice-title");
        serverMode.getStyleClass().addAll("instance-install-choice", "instance-install-choice-title");
        HBox modeRow = new HBox(12, clientMode, serverMode);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        serverDestination.setId("instance-server-destination");
        serverDestination.setText(GuiMessages.get("instance.install.server.destination",
                ui.getServerDownloadDir().getAbsolutePath()));
        serverDestination.setWrapText(true);
        serverNotice.setWrapText(true);
        serverDestination.getStyleClass().add("status-detail");
        serverNotice.getStyleClass().add("status-detail");

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

        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        status.managedProperty().bind(status.visibleProperty());
        installButton = ui.createActionButton(Messages.get("instance.install.action"),
                "primary-button", this::startInstall);
        installButton.setId("instance-install-action");
        Region modeSpacer = new Region();
        HBox.setHgrow(modeSpacer, Priority.ALWAYS);
        modeRow.getChildren().addAll(modeSpacer, installButton);
        loaderVersions.valueProperty().addListener((obs, oldValue, newValue) -> updateInstallAvailability());
        fabricApiVersions.valueProperty().addListener((obs, oldValue, newValue) -> updateInstallAvailability());
        choices.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            updateChoiceHint();
            loadLoaderVersions();
            loadFabricApiVersions();
        });
        modes.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            updateChoiceHint();
            loadFabricApiVersions();
        });
        updateChoiceHint();
        loadLoaderVersions();
        loadFabricApiVersions();
        getChildren().addAll(header,
                ui.createSurface(Messages.get("instance.install.choice.title"),
                        null, choiceSubtitle, modeRow, serverSection,
                        choiceList, choiceHint, versionSection, fabricApiSection, status));
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
        if (isServer() && (choice == LoaderChoice.VANILLA || choice == LoaderChoice.FABRIC)) {
            return GuiMessages.get(choice.vanilla() ? "instance.install.server.vanilla" : "instance.install.server.fabric");
        }
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
        subtitle.setText(isServer() ? GuiMessages.get("instance.install.server.subtitle") : Messages.get("instance.install.subtitle"));
        choiceSubtitle.setText(isServer() ? GuiMessages.get("instance.install.server.choiceSubtitle")
                : Messages.get("instance.install.choice.subtitle"));
        choices.getToggles().forEach(toggle -> {
            ToggleButton button = (ToggleButton) toggle;
            Label detail = (Label) ((VBox) button.getGraphic()).getChildren().get(1);
            detail.setText(choiceDetail((LoaderChoice) button.getUserData()));
        });
        serverSection.setVisible(isServer());
        serverSection.setManaged(isServer());
        choiceHint.setText(isServer() ? GuiMessages.get("instance.install.server.selected", choice.displayName)
                : choice == LoaderChoice.FABRIC ? Messages.get("instance.install.fabricApi.notice")
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
        boolean fabric = selectedChoice() == LoaderChoice.FABRIC && !isServer();
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

    private boolean isServer() {
        return modes.getSelectedToggle() == serverMode;
    }

    private boolean missingVersion() {
        return modes.getSelectedToggle() == null
                || (!selectedChoice().vanilla() && !loaderVersions.getItems().contains(loaderVersions.getValue()))
                || (!isServer() && selectedChoice() == LoaderChoice.FABRIC
                && !fabricApiVersions.getItems().contains(fabricApiVersions.getValue()));
    }

    private void updateInstallAvailability() {
        installButton.setDisable(busy || missingVersion());
    }

    private void startInstall() {
        LoaderChoice choice = selectedChoice();
        String loaderVersion = loaderVersions.getValue();
        if (busy || missingVersion()) return;
        boolean server = isServer();
        InstanceInstallWorkflow.Listener listener = new InstanceInstallWorkflow.Listener() {
                    @Override
                    public void onStarted() {
                        setBusy(true);
                        status.setText("");
                    }

                    @Override
                    public void onStatus(String message) { }

                    @Override
                    public void onProgress(long downloaded, long total) { }

                    @Override
                    public void onComplete(String profileId) {
                        status.setText(server ? GuiMessages.get("instance.install.server.complete", profileId)
                                : Messages.format("instance.install.complete", profileId));
                        installButton.setText(Messages.get("instance.install.done"));
                        backButton.setDisable(false);
                    }

                    @Override
                    public void onFailure(String message) {
                        status.setText(Messages.format("instance.install.failed", message));
                        setBusy(false);
                    }
                };
        if (server) {
            new ServerInstallWorkflow(ui).install(minecraftVersion, choice, loaderVersion,
                    List.copyOf(loaderVersions.getItems()), listener);
        } else {
            new InstanceInstallWorkflow(ui).install(minecraftVersion, choice, loaderVersion, fabricApiVersions.getValue(), listener);
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        backButton.setDisable(busy);
        clientMode.setDisable(busy);
        serverMode.setDisable(busy);
        updateInstallAvailability();
        loaderVersions.setDisable(busy || loaderVersions.getItems().isEmpty());
        refreshVersions.setDisable(busy);
        fabricApiVersions.setDisable(busy || fabricApiVersions.getItems().isEmpty());
        refreshFabricApi.setDisable(busy);
        choices.getToggles().forEach(toggle -> ((ToggleButton) toggle).setDisable(busy));
    }
}
