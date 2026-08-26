package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.server.ServerBrowserView;
import com.ecl.util.Messages;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.File;

import static com.ecl.util.TextUtil.abbreviate;

/** Builds the small, self-contained launcher pages that do not own business workflows. */
final class LauncherPageFactory {
    private final LauncherUI ui;

    LauncherPageFactory(LauncherUI ui) {
        this.ui = ui;
    }

    DownloadTasksPage createDownloadTasksPage() {
        return new DownloadTasksPage(ui.downloadTaskCenter, ui.settingsManager);
    }

    WorldSavesPage createWorldSavesPage() {
        return new WorldSavesPage((LauncherUI) ui);
    }

    VBox createVersionsPage() {
        VBox page = ui.createMainPage();

        Button refreshVersionsButton = ui.createActionButton(
                Messages.get("button.refresh"), "primary-button", () -> ui.versionActions.refreshVersions());
        Button installLoaderButton = ui.createActionButton(
                Messages.get("loader.install"), "primary-button", ui::showLoaderInstallDialog);
        Button reinstallButton = ui.createActionButton(
                Messages.get("version.reinstall"), "secondary-button",
                () -> ui.versionActions.reinstallSelectedVersion());
        Button deleteButton = ui.createActionButton(
                Messages.get("version.delete"), "secondary-button",
                () -> ui.versionActions.deleteSelectedVersion());
        Button chooseVersionButton = ui.createActionButton(
                Messages.get("button.back"), "secondary-button",
                () -> ui.setActiveView(AppView.HOME));
        Button openVersionsDirButton = ui.createActionButton(
                Messages.get("button.openDir"), "ghost-button",
                () -> ui.openLocalFolder(ECLConfig.getVersionsDir(), "版本目录"));
        Button backupManagerButton = ui.createActionButton(
                Messages.get("backup.manage"), "ghost-button", ui::showBackupManagerDialog);
        Button desktopShortcutButton = ui.createActionButton(
                Messages.get("shortcut.desktop"), "ghost-button",
                () -> ui.createInstanceShortcut(false));
        Button startMenuShortcutButton = ui.createActionButton(
                Messages.get("shortcut.startMenu"), "ghost-button",
                () -> ui.createInstanceShortcut(true));

        HBox actions = new HBox(10, refreshVersionsButton, installLoaderButton, reinstallButton,
                deleteButton, chooseVersionButton, openVersionsDirButton, backupManagerButton,
                desktopShortcutButton, startMenuShortcutButton);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox versionCard = ui.createSurface(
                Messages.get("version.page.title"),
                null,
                ui.createInfoRow(Messages.get("label.currentFilter"),
                        ui.createStaticValueLabel(ui.versionActions.getSelectedVersionCategory().getLabel())),
                ui.createInfoRow(Messages.get("label.currentVersion"), ui.createStaticValueLabel(
                        ui.getSelectedVersion() == null
                                ? Messages.get("label.notSelected") : ui.getSelectedVersion())),
                ui.createInfoRow(Messages.get("info.localDir"),
                        ui.createStaticValueLabel(ECLConfig.getVersionsDir().getAbsolutePath())),
                actions
        );

        page.getChildren().add(versionCard);
        return page;
    }

    VBox createServersPage() {
        VBox page = ui.createMainPage();

        Label pageTitle = new Label(Messages.get("nav.servers"));
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label(Messages.get("server.page.subtitle"));
        pageSubtitle.getStyleClass().add("page-subtitle");
        VBox pageHeading = new VBox(6, pageTitle, pageSubtitle);
        pageHeading.getStyleClass().add("content-library-heading");

        ui.activeServerBrowserView = new ServerBrowserView(
                message -> ui.setStatus(Messages.get("nav.servers"), message), ui::setQuickServer);
        ui.activeServerBrowserView.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(ui.activeServerBrowserView, javafx.scene.layout.Priority.ALWAYS);

        page.getChildren().addAll(pageHeading, ui.activeServerBrowserView);
        return page;
    }

    VBox createSettingsPage() {
        VBox page = ui.createMainPage();

        ComboBox<String> languageBox = new ComboBox<>();
        languageBox.getItems().addAll("zh-CN", "zh-TW", "en");
        languageBox.setValue(Messages.locale().toLanguageTag());
        ui.configureLocalizedCombo(languageBox, ui::languageDisplayName);
        languageBox.setOnAction(event -> ui.switchLanguage(languageBox.getValue()));

        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll("DARK", "LIGHT");
        themeBox.setValue(ui.normalizeTheme(ui.settingsManager.get(ECLConfig.KEY_THEME)));
        ui.configureLocalizedCombo(themeBox, ui::themeDisplayName);
        themeBox.setOnAction(event -> {
            String theme = ui.normalizeTheme(themeBox.getValue());
            ui.settingsManager.set(ECLConfig.KEY_THEME, theme);
            ui.settingsManager.save();
            ui.applyTheme(theme);
        });

        Button advancedButton = ui.createActionButton(
                Messages.get("settings.advanced"), "primary-button", ui::showSettingsDialog);
        Button dataDirButton = ui.createActionButton(
                Messages.get("settings.openData"), "secondary-button",
                () -> ui.openLocalFolder(ECLConfig.getBaseDir(), Messages.get("settings.openData")));
        Button gameDirButton = ui.createActionButton(
                Messages.get("settings.openGame"), "ghost-button",
                () -> ui.openLocalFolder(ui.getActiveGameDir(), Messages.get("settings.openGame")));
        Button wizardButton = ui.createActionButton(
                Messages.get("wizard.title"), "ghost-button", ui::showFirstRunWizard);

        HBox actions = new HBox(10, advancedButton, dataDirButton, gameDirButton, wizardButton);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox settingsCard = ui.createSurface(
                "// " + Messages.get("settings.system"),
                Messages.get("settings.subtitle"),
                ui.createControlRow(Messages.get("settings.language"), languageBox),
                ui.createControlRow(Messages.get("settings.theme"), themeBox),
                ui.createInfoRow("Java", ui.createStaticValueLabel(
                        ui.javaPath == null || ui.javaPath.isBlank() ? "-" : abbreviate(ui.javaPath, 72))),
                actions
        );
        page.getChildren().add(settingsCard);
        return page;
    }

    VBox createLogsPage() {
        VBox page = ui.createMainPage();

        File crashDir = new File(ui.getActiveGameDir(), "crash-reports");
        File logsDir = new File(ui.getActiveGameDir(), "logs");
        Button crashButton = ui.createActionButton(
                Messages.get("logs.openCrash"), "primary-button",
                () -> ui.openLocalFolder(crashDir, "崩溃报告目录"));
        Button logsButton = ui.createActionButton(
                Messages.get("logs.openLogs"), "secondary-button",
                () -> ui.openLocalFolder(logsDir, "日志目录"));
        Button modsButton = ui.createActionButton(
                Messages.get("logs.openMods"), "ghost-button",
                () -> ui.openLocalFolder(ui.resolveModsDir(ui.getSelectedVersion()), "模组目录"));
        Button clearConsoleButton = ui.createActionButton(Messages.get("logs.clearConsole"),
                "ghost-button", () -> {
                    ui.liveGameLog.clear();
                    if (ui.liveConsoleArea != null) {
                        ui.liveConsoleArea.clear();
                    }
                });
        Button diagnosticButton = ui.createActionButton(
                Messages.get("diagnostic.export"), "secondary-button", ui::exportDiagnosticBundle);

        HBox actions = new HBox(10, crashButton, logsButton, modsButton,
                clearConsoleButton, diagnosticButton);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        ui.liveConsoleArea = new TextArea(ui.liveGameLog.toString());
        ui.liveConsoleArea.setEditable(false);
        ui.liveConsoleArea.setWrapText(false);
        ui.liveConsoleArea.setPrefRowCount(18);
        ui.liveConsoleArea.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");

        VBox logsCard = ui.createSurface(
                Messages.get("logs.title"),
                Messages.get("logs.subtitle"),
                ui.createInfoRow(Messages.get("label.diagStatus"),
                        ui.createStaticValueLabel(Messages.get("info.normal"))),
                ui.createInfoRow(Messages.get("label.crashReports"),
                        ui.createStaticValueLabel(Messages.format("crash.count", ui.countCrashReports()))),
                ui.createInfoRow(Messages.get("info.gameDir"),
                        ui.createStaticValueLabel(abbreviate(ui.getActiveGameDir().getAbsolutePath(), 72))),
                actions,
                ui.liveConsoleArea
        );

        page.getChildren().add(logsCard);
        return page;
    }
}
