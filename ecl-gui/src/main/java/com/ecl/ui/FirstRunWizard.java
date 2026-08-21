package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingsManager;
import com.ecl.util.Messages;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URL;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns the first-run onboarding window and completion state. */
final class FirstRunWizard {
    private final SettingsManager settings;
    private final Consumer<String> languageSwitcher;
    private final Function<String, String> languageDisplayName;
    private final BiConsumer<Scene, String> themeApplier;
    private Stage stage;

    FirstRunWizard(SettingsManager settings, Consumer<String> languageSwitcher,
                   Function<String, String> languageDisplayName,
                   BiConsumer<Scene, String> themeApplier) {
        this.settings = settings;
        this.languageSwitcher = languageSwitcher;
        this.languageDisplayName = languageDisplayName;
        this.themeApplier = themeApplier;
    }

    void show(Stage owner) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }
        List<String> steps = List.of(
                "wizard.welcome", "wizard.java", "wizard.account", "wizard.version");
        int[] currentStep = {0};
        Stage wizard = new Stage(StageStyle.DECORATED);
        stage = wizard;
        wizard.setTitle(Messages.get("wizard.title"));
        wizard.initOwner(owner);
        wizard.initModality(Modality.WINDOW_MODAL);

        Label progress = new Label();
        progress.getStyleClass().add("section-kicker");
        Label body = new Label();
        body.getStyleClass().add("section-title");
        body.setWrapText(true);
        ComboBox<String> languageBox = new ComboBox<>();
        languageBox.getItems().addAll("zh-CN", "zh-TW", "en");
        languageBox.setValue(Messages.locale().toLanguageTag());
        LauncherUiFactory.configureLocalizedCombo(languageBox, languageDisplayName);
        Button back = actionButton(Messages.get("wizard.back"), "ghost-button");
        Button skip = actionButton(Messages.get("wizard.skip"), "ghost-button");
        Button next = actionButton(Messages.get("wizard.next"), "primary-button");
        Runnable update = () -> {
            progress.setText((currentStep[0] + 1) + " / " + steps.size());
            body.setText(Messages.get(steps.get(currentStep[0])));
            back.setText(Messages.get("wizard.back"));
            skip.setText(Messages.get("wizard.skip"));
            next.setText(Messages.get(currentStep[0] == steps.size() - 1
                    ? "wizard.finish" : "wizard.next"));
            back.setDisable(currentStep[0] == 0);
        };
        languageBox.setOnAction(event -> {
            languageSwitcher.accept(languageBox.getValue());
            wizard.setTitle(Messages.get("wizard.title"));
            update.run();
        });
        back.setOnAction(event -> {
            if (currentStep[0] > 0) {
                currentStep[0]--;
            }
            update.run();
        });
        skip.setOnAction(event -> finish(wizard));
        next.setOnAction(event -> {
            if (currentStep[0] == steps.size() - 1) {
                finish(wizard);
            } else {
                currentStep[0]++;
                update.run();
            }
        });
        Region spacer = new Region();
        HBox actions = new HBox(10, skip, spacer, back, next);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(22, progress, body,
                controlRow(Messages.get("settings.language"), languageBox), actions);
        root.getStyleClass().addAll("scene-root", "wizard-root");
        root.setPadding(new Insets(32));
        Scene scene = new Scene(root, 620, 330);
        URL stylesheet = FirstRunWizard.class.getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        wizard.setScene(scene);
        themeApplier.accept(scene, settings.get(ECLConfig.KEY_THEME));
        wizard.setOnCloseRequest(event -> finish(wizard));
        update.run();
        wizard.show();
    }

    private void finish(Stage wizard) {
        settings.set(ECLConfig.KEY_FIRST_RUN_COMPLETED, true);
        settings.save();
        wizard.setOnCloseRequest(null);
        wizard.close();
        stage = null;
    }

    private static Button actionButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", styleClass);
        return button;
    }

    private static HBox controlRow(String labelText, javafx.scene.Node control) {
        return LauncherUiFactory.controlRow(labelText, control);
    }

}
