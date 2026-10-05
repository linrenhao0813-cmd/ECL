package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.net.URL;

/** Edits global game behavior without writing instance profiles or new-instance defaults. */
final class SettingsDialog {
    private final LauncherUI ui;

    SettingsDialog(LauncherUI ui) {
        this.ui = ui;
    }

    void show() {
        Scene ownerScene = ui.primaryStage.getScene();
        javafx.scene.Node focusReturnTarget = ownerScene == null ? null : ownerScene.getFocusOwner();
        Stage dialog = new Stage();
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.initOwner(ui.primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(Messages.get("settings.advanced"));
        ui.applyWindowIcon(dialog);

        GlobalGameSettingsPane form = new GlobalGameSettingsPane(ui, false);
        form.setOnSaved(() -> {
            ui.renderActiveView();
            dialog.close();
        });
        VBox dialogRoot = new VBox(form);
        dialogRoot.getStyleClass().add("root-pane");
        dialogRoot.setPadding(new Insets(24));
        finishDialog(dialog, dialogRoot, form.statusLabel(), form.saveButton(), focusReturnTarget);
    }

    private void finishDialog(Stage dialog, VBox dialogRoot, Label status, Button saveBtn,
                              javafx.scene.Node focusReturnTarget) {
        Button cancelBtn = new Button(Messages.get("button.cancel"));
        cancelBtn.getStyleClass().addAll("app-button", "ghost-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(12, saveBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        dialogRoot.getChildren().addAll(status, buttonBar);

        LauncherWindowChrome chrome = new LauncherWindowChrome(dialog);
        Label title = new Label(Messages.get("settings.advanced"));
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleBar = new HBox(12, title, spacer, chrome.createControls());
        titleBar.getStyleClass().addAll("window-title-bar", "settings-dialog-title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);
        chrome.installDragBehavior(titleBar);
        BorderPane frame = new BorderPane(ui.createWheelScrollPane(dialogRoot));
        frame.setTop(titleBar);
        Scene scene = new Scene(frame, 760, 650);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) dialog.close();
        });
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        ui.applyThemeToScene(scene);
        dialog.setOnHidden(event -> javafx.application.Platform.runLater(() -> {
            javafx.scene.Node target = focusReturnTarget != null && focusReturnTarget.getScene() != null
                    ? focusReturnTarget : ui.primaryStage.getScene().lookup("#settings-language");
            if (target != null) target.requestFocus();
        }));
        dialog.show();
    }

}
