package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Inline "install a new instance" wizard hosted by the instance manager:
 * 1) pick a Minecraft version, 2) pick vanilla or a loader, 3) review the target directory,
 * 4) run the install task, 5) optionally set the new instance as the launch target.
 *
 * <p>The install reuses {@link InstanceInstallWorkflow}, so it appears in the global task panel and
 * does not silently retarget the next launch.
 */
final class InstanceInstallWizard extends VBox {
    private final LauncherUI ui;
    private final Runnable onCancel;
    private final Consumer<String> onInstalled;

    private final Label stepLabel = new Label();
    private final VBox body = new VBox(14);
    private final ToggleGroup loaderChoices = new ToggleGroup();
    private final Label targetSummary = new Label();
    private final Label progressStatus = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final VBox progressPane;

    private String minecraftVersion;
    private String installedProfileId;

    InstanceInstallWizard(LauncherUI ui, Runnable onCancel, Consumer<String> onInstalled) {
        this.ui = ui;
        this.onCancel = onCancel;
        this.onInstalled = onInstalled;
        getStyleClass().addAll("instance-details-body", "instance-install-wizard");
        setSpacing(14);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);

        stepLabel.getStyleClass().add("card-kicker");
        progressStatus.getStyleClass().add("status-detail");
        progressStatus.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("download-progress");
        progressPane = new VBox(8, progress, progressStatus);

        getChildren().addAll(stepLabel, body);
        showVersionStep();
    }

    private void setStep(int index, int total) {
        stepLabel.setText(Messages.format("instances.wizard.step", index, total));
    }

    /** Step 1: pick the Minecraft version from the shared version catalog. */
    private void showVersionStep() {
        setStep(1, 4);
        InstanceVersionCatalog catalog = new InstanceVersionCatalog(ui, this::showLoaderStep);
        Button cancel = ui.createActionButton(Messages.get("settings.cancel"), "ghost-button", onCancel);
        HBox actions = new HBox(10, cancel);
        actions.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().setAll(
                ui.createSurface(Messages.get("instances.wizard.version.title"),
                        Messages.get("instances.wizard.version.subtitle"), catalog),
                actions);
    }

    /** Step 2: pick vanilla or a loader and review where the instance will live. */
    private void showLoaderStep(String version) {
        minecraftVersion = version;
        setStep(2, 4);
        loaderChoices.getToggles().clear();
        VBox choiceList = new VBox(9);
        choiceList.getStyleClass().add("instance-install-choices");
        for (LoaderChoice choice : LoaderChoice.values()) {
            choiceList.getChildren().add(createChoice(choice));
        }
        ((ToggleButton) loaderChoices.getToggles().getFirst()).setSelected(true);

        targetSummary.getStyleClass().add("status-detail");
        targetSummary.setWrapText(true);
        loaderChoices.selectedToggleProperty().addListener(
                (observable, previous, selected) -> updateTargetSummary());

        Button back = ui.createActionButton(Messages.get("instance.install.back"),
                "ghost-button", this::showVersionStep);
        Button next = ui.createActionButton(Messages.get("instances.wizard.review"),
                "primary-button", this::showConfirmStep);
        next.setId("instance-wizard-review");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, back, spacer, next);
        actions.setAlignment(Pos.CENTER_LEFT);

        updateTargetSummary();
        body.getChildren().setAll(
                ui.createSurface(Messages.get("instance.install.choice.title"),
                        "Minecraft " + version, choiceList),
                targetSummary,
                actions);
    }

    /** Step 3: confirm the install; nothing is downloaded until the user agrees. */
    private void showConfirmStep() {
        setStep(3, 4);
        LoaderChoice choice = selectedChoice();
        VBox summary = new VBox(8,
                ui.createInfoRow(Messages.get("label.gameVersion"),
                        ui.createStaticValueLabel("Minecraft " + minecraftVersion)),
                ui.createInfoRow(Messages.get("instances.wizard.loader"),
                        ui.createStaticValueLabel(choice.displayName)),
                ui.createInfoRow(Messages.get("instances.wizard.targetDir"),
                        ui.createStaticValueLabel(targetDirectoryText(choice))),
                ui.createInfoRow(Messages.get("instances.wizard.target"),
                        ui.createStaticValueLabel(Messages.get("instances.wizard.noAutoTarget"))));

        Button back = ui.createActionButton(Messages.get("instance.install.back"),
                "ghost-button", () -> showLoaderStep(minecraftVersion));
        Button install = ui.createActionButton(Messages.get("instance.install.action"),
                "primary-button", this::startInstall);
        install.setId("instance-wizard-install");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, back, spacer, install);
        actions.setAlignment(Pos.CENTER_LEFT);

        body.getChildren().setAll(
                ui.createSurface(Messages.get("instances.wizard.confirm.title"),
                        Messages.get("instances.wizard.confirm.subtitle"), summary),
                actions);
    }

    /** Step 4: run the shared install task and report progress. */
    private void startInstall() {
        setStep(4, 4);
        LoaderChoice choice = selectedChoice();
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressStatus.setText(Messages.get("instance.install.starting"));
        body.getChildren().setAll(
                ui.createSurface(Messages.get("instance.install.progress.title"),
                        "Minecraft " + minecraftVersion + " / " + choice.displayName,
                        progressPane));
        new InstanceInstallWorkflow(ui).install(minecraftVersion, choice,
                new InstanceInstallWorkflow.Listener() {
                    @Override
                    public void onStatus(String message) {
                        progressStatus.setText(message);
                    }

                    @Override
                    public void onProgress(long downloaded, long total) {
                        progress.setProgress(total > 0 ? (double) downloaded / total
                                : ProgressBar.INDETERMINATE_PROGRESS);
                    }

                    @Override
                    public void onComplete(String profileId) {
                        installedProfileId = profileId;
                        showDoneStep(profileId);
                    }

                    @Override
                    public void onFailure(String message) {
                        progress.setProgress(0);
                        progressStatus.setText(Messages.format("instance.install.failed", message));
                        showRetryActions();
                    }
                }, false);
    }

    private void showRetryActions() {
        Button back = ui.createActionButton(Messages.get("instance.install.back"),
                "ghost-button", () -> showLoaderStep(minecraftVersion));
        Button retry = ui.createActionButton(Messages.get("tasks.action.retry"),
                "primary-button", this::showConfirmStep);
        HBox actions = new HBox(10, back, retry);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (body.getChildren().size() > 1) {
            body.getChildren().set(1, actions);
        } else {
            body.getChildren().add(actions);
        }
    }

    /** Step 5: the instance exists; offer to make it the launch target. */
    private void showDoneStep(String profileId) {
        progress.setProgress(1);
        progressStatus.setText(Messages.format("instance.install.complete", profileId));

        Button setTarget = ui.createActionButton(Messages.get("instances.setTarget"),
                "primary-button", () -> {
                    ui.setLaunchTarget(profileId);
                    ui.updateRuntimeSummary();
                    ui.setStatus(Messages.get("instances.target.switched"),
                            ui.versionManager.getVersionDisplayName(profileId));
                    finish();
                });
        setTarget.setId("instance-wizard-set-target");
        setTarget.setDisable(profileId.equals(ui.getSelectedVersion()));
        Button openDetails = ui.createActionButton(Messages.get("instances.wizard.openDetails"),
                "secondary-button", this::finish);
        Button installMore = ui.createActionButton(Messages.get("instances.installNew"),
                "ghost-button", this::showVersionStep);
        HBox actions = new HBox(10, setTarget, openDetails, installMore);
        actions.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().add(actions);
    }

    private void finish() {
        onInstalled.accept(installedProfileId);
    }

    private ToggleButton createChoice(LoaderChoice choice) {
        Label title = new Label(choice.displayName);
        title.getStyleClass().add("instance-install-choice-title");
        Label detail = new Label(choiceDetail(choice));
        detail.getStyleClass().add("instance-install-choice-detail");
        ToggleButton button = new ToggleButton();
        button.setGraphic(new VBox(3, title, detail));
        button.setUserData(choice);
        button.setToggleGroup(loaderChoices);
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

    private LoaderChoice selectedChoice() {
        if (loaderChoices.getSelectedToggle() == null
                || !(loaderChoices.getSelectedToggle().getUserData() instanceof LoaderChoice choice)) {
            return LoaderChoice.VANILLA;
        }
        return choice;
    }

    private void updateTargetSummary() {
        LoaderChoice choice = selectedChoice();
        targetSummary.setText(Messages.format(choice.vanilla()
                        ? "instances.wizard.target.shared" : "instances.wizard.target.isolated",
                targetDirectoryText(choice)));
    }

    private String targetDirectoryText(LoaderChoice choice) {
        if (choice.vanilla()) {
            return ui.getConfiguredGameRootDir().getAbsolutePath();
        }
        return new java.io.File(ui.getConfiguredGameRootDir(), "versions").getAbsolutePath()
                + java.io.File.separator + "<" + Messages.get("instances.wizard.newDir") + ">";
    }
}
