package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Persistent instance context bar shown above every page. It owns the launch-target selector so the
 * current instance stays visible and switchable regardless of the active page.
 */
final class LauncherInstanceBar {
    private final LauncherUI ui;
    private Label caption;

    LauncherInstanceBar(LauncherUI ui) {
        this.ui = ui;
    }

    HBox create() {
        HBox bar = new HBox(12);
        bar.setId("instance-bar");
        bar.getStyleClass().add("instance-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        caption = new Label(Messages.get("instanceBar.current"));
        caption.getStyleClass().add("instance-bar-label");

        ui.versionCombo = new ComboBox<>();
        ui.versionCombo.setId("instance-selector");
        ui.versionCombo.setPromptText(Messages.get("home.selectVersion"));
        ui.versionCombo.setVisibleRowCount(14);
        ui.versionCombo.setCellFactory(list -> ui.createVersionCell());
        ui.versionCombo.setButtonCell(ui.createVersionCell());
        ui.applyFieldStyle(ui.versionCombo);
        ui.versionCombo.getStyleClass().add("instance-bar-selector");
        ui.versionCombo.setPrefWidth(280);
        ui.versionCombo.setMaxWidth(380);
        ui.versionCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            // The selector is only a view over the shared instance state.
            ui.instanceSelection.setLaunchTarget(newValue);
            ui.updateRuntimeSummary();
            ui.versionActions.updateSelectedVersionWikiButton();
            ui.syncLoaderChoiceFromProfile(newValue);
        });
        ui.instanceSelection.launchTargetProperty().addListener((obs, oldValue, newValue) -> {
            String target = newValue == null || newValue.isBlank() ? null : newValue;
            if (!java.util.Objects.equals(ui.versionCombo.getValue(), target)) {
                ui.versionCombo.setValue(target);
            }
        });

        ui.selectedVersionWikiButton = ui.createSelectedVersionWikiButton();

        ui.instanceMetaLabel = new Label(Messages.get("label.notSelected"));
        ui.instanceMetaLabel.setId("instance-bar-meta");
        ui.instanceMetaLabel.getStyleClass().add("instance-bar-badge");
        ui.instanceMetaLabel.setMaxWidth(320);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(
                caption,
                ui.versionCombo,
                ui.selectedVersionWikiButton,
                ui.instanceMetaLabel,
                spacer);
        return bar;
    }

    /** Reapplies locale-dependent text without recreating the selector or losing its selection. */
    void refreshTexts() {
        if (caption == null) {
            return;
        }
        caption.setText(Messages.get("instanceBar.current"));
        ui.versionCombo.setPromptText(Messages.get("home.selectVersion"));
        if (ui.selectedVersionWikiButton != null) {
            ui.selectedVersionWikiButton.setText(Messages.get("instanceBar.wiki"));
        }
        ui.updateRuntimeSummary();
    }
}
