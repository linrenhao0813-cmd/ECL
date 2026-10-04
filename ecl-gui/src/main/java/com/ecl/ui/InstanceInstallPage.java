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

/** Loader-choice and progress page shown after selecting a Minecraft version. */
final class InstanceInstallPage extends VBox {
    private final LauncherUI ui;
    private final String minecraftVersion;
    private final Runnable backAction;
    private final ToggleGroup choices = new ToggleGroup();
    private final Label choiceHint = new Label();
    private final Label status = new Label(Messages.get("instance.install.ready"));
    private final ProgressBar progress = new ProgressBar(0);
    private final Button backButton;
    private final Button installButton;

    InstanceInstallPage(LauncherUI ui, String minecraftVersion, Runnable backAction) {
        this.ui = ui;
        this.minecraftVersion = minecraftVersion;
        this.backAction = backAction;
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
        choices.selectedToggleProperty().addListener((obs, oldValue, newValue) -> updateChoiceHint());
        updateChoiceHint();

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("download-progress");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        installButton = ui.createActionButton(Messages.get("instance.install.action"),
                "primary-button", this::startInstall);
        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actions = new HBox(12, status, actionSpacer, installButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);

        getChildren().addAll(header,
                ui.createSurface(Messages.get("instance.install.choice.title"),
                        Messages.get("instance.install.choice.subtitle"), choiceList, choiceHint),
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

    private void startInstall() {
        LoaderChoice choice = selectedChoice();
        setBusy(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        status.setText(Messages.get("instance.install.starting"));
        new InstanceInstallWorkflow(ui).install(minecraftVersion, choice,
                new InstanceInstallWorkflow.Listener() {
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
        backButton.setDisable(busy);
        installButton.setDisable(busy);
        choices.getToggles().forEach(toggle -> ((ToggleButton) toggle).setDisable(busy));
    }
}
