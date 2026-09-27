package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.server.ServerBrowserView;
import com.ecl.util.Messages;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import static com.ecl.util.TextUtil.abbreviate;

/** Builds the small, self-contained launcher pages that do not own business workflows. */
final class LauncherPageFactory {
    private final LauncherUI ui;

    LauncherPageFactory(LauncherUI ui) {
        this.ui = ui;
    }

    WorldSavesPage createWorldSavesPage() {
        return new WorldSavesPage((LauncherUI) ui);
    }

    VBox createVersionsPage() {
        VBox page = ui.createMainPage();
        showVersionsOverview(page);
        return page;
    }

    private void showVersionsOverview(VBox page) {
        InstanceVersionCatalog versionCatalog = new InstanceVersionCatalog(
                ui, version -> showVersionInstaller(page, version));

        VBox catalogCard = ui.createSurface(
                Messages.get("instance.catalog.title"),
                Messages.get("instance.catalog.subtitle"),
                versionCatalog);
        page.getChildren().setAll(catalogCard);
    }

    private void showVersionInstaller(VBox page, String version) {
        page.getChildren().setAll(new InstanceInstallPage(
                ui, version, () -> showVersionsOverview(page)));
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
        Tab general = new Tab(GuiMessages.get("settings.general"), createGeneralSettingsPage());
        Tab accounts = new Tab(GuiMessages.get("accounts.title"), new AccountManagementPage(ui));
        TabPane tabs = new TabPane(general, accounts);
        tabs.setId("settings-tabs");
        tabs.getStyleClass().add("mod-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(ui.accountSettingsSelected ? accounts : general);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                ui.accountSettingsSelected = selected == accounts);
        page.getChildren().add(tabs);
        return page;
    }

    private VBox createGeneralSettingsPage() {
        VBox page = ui.createMainPage();

        ComboBox<String> languageBox = new ComboBox<>();
        languageBox.getItems().addAll("zh-CN", "zh-TW", "en");
        languageBox.setValue(Messages.locale().toLanguageTag());
        ui.configureLocalizedCombo(languageBox, ui::languageDisplayName);
        languageBox.setOnAction(event -> ui.switchLanguage(languageBox.getValue()));

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
                ui.createInfoRow("Java", ui.createStaticValueLabel(
                        ui.javaPath == null || ui.javaPath.isBlank() ? "-" : abbreviate(ui.javaPath, 72))),
                actions
        );
        page.getChildren().add(settingsCard);
        return page;
    }

}
