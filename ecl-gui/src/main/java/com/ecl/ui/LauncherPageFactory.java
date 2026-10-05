package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.config.SettingKey;
import com.ecl.config.SettingsManager;
import com.ecl.game.DefaultIsolationType;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.server.ServerBrowserView;
import com.ecl.util.HttpUtil;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.JvmArgumentPolicy;
import com.ecl.util.Messages;
import com.ecl.util.TextUtil;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Builds the small, self-contained launcher pages that do not own business workflows. */
final class LauncherPageFactory {
    private final LauncherUI ui;
    private SettingsPageGuard settingsGuard;
    private String requestedInstance;
    private Function<Tab, SettingsPageGuard.Choice> settingsChoiceProvider = this::showSettingsChoice;

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
        ui.activeServerManagementPage = new ServerManagementPage(ui);
        ui.compactLayoutConsumer = ui.activeServerManagementPage::setCompact;
        TabPane tabs = new TabPane(
                new Tab(GuiMessages.get("server.local.publicTab"), ui.activeServerBrowserView),
                new Tab(GuiMessages.get("server.local.managedTab"), ui.activeServerManagementPage));
        tabs.setId("servers-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("mod-tabs");
        VBox.setVgrow(tabs, javafx.scene.layout.Priority.ALWAYS);
        page.getChildren().addAll(pageHeading, tabs);
        return page;
    }

    /**
     * Settings are split by save scope so a value never lands somewhere the user did not expect:
     * general and downloads are global, defaults only apply to instances without a profile, and the
     * instance settings edit only the instance selected within their own workspace.
     */
    VBox createSettingsPage() {
        VBox page = ui.createMainPage();

        Label pageTitle = new Label(Messages.get("nav.settings"));
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label(Messages.get("settings.subtitle"));
        pageSubtitle.getStyleClass().add("page-subtitle");
        VBox pageHeading = new VBox(6, pageTitle, pageSubtitle);
        pageHeading.getStyleClass().add("content-library-heading");

        Tab general = new Tab(Messages.get("settings.tab.general"));
        Tab downloads = new Tab(Messages.get("settings.tab.downloads"));
        Tab defaults = new Tab(Messages.get("settings.tab.defaults"));
        Tab accounts = new Tab(GuiMessages.get("accounts.title"), new AccountManagementPage(ui));
        Tab about = new Tab(Messages.get("settings.tab.about"), createAboutSettingsPage());

        TabPane tabs = new TabPane(general, downloads, defaults, accounts, about);
        tabs.setId("settings-tabs");
        tabs.getStyleClass().add("mod-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        SettingsPageGuard guard = new SettingsPageGuard(tabs, tab -> settingsChoiceProvider.apply(tab));
        settingsGuard = guard;
        general.setContent(buildGeneralSettingsTab(general, guard));
        downloads.setContent(buildDownloadSettingsTab(downloads, guard));
        defaults.setContent(buildDefaultSettingsTab(defaults, guard));
        // "Discard" simply rebuilds the tab, which restores every field from the saved settings.
        guard.onDiscard(downloads, () -> downloads.setContent(buildDownloadSettingsTab(downloads, guard)));
        guard.onDiscard(defaults, () -> defaults.setContent(buildDefaultSettingsTab(defaults, guard)));
        guard.onDiscard(general, () -> general.setContent(buildGeneralSettingsTab(general, guard)));

        tabs.getSelectionModel().select(ui.accountSettingsSelected ? accounts : general);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            ui.accountSettingsSelected = tabs.getSelectionModel().getSelectedItem() == accounts;
        });
        page.getChildren().addAll(pageHeading, tabs);
        return page;
    }

    InstalledInstancesPage createInstalledInstancesPage() {
        if (requestedInstance != null) {
            ui.instanceSelection.setViewedInstance(requestedInstance);
            requestedInstance = null;
        }
        InstalledInstancesPage page = new InstalledInstancesPage(ui);
        SettingsPageGuard guard = page.createGuard(tab -> settingsChoiceProvider.apply(tab));
        settingsGuard = guard;
        page.guardChanges(guard::confirmDeparture, () -> settingsGuard == guard);
        return page;
    }

    void openInstanceSettings(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            ui.setActiveView(AppView.VERSIONS);
            return;
        }
        if (!confirmSettingsDeparture()) return;
        requestedInstance = instanceId;
        if (ui.activeView == AppView.VERSIONS) {
            ui.renderActiveView();
        } else {
            ui.setActiveView(AppView.VERSIONS);
        }
    }

    /** Confirms a destructive page departure before navigation or rebuilding the workspace. */
    boolean confirmSettingsDeparture() {
        if (settingsGuard != null && !settingsGuard.confirmDeparture()) {
            return false;
        }
        settingsGuard = null;
        return true;
    }

    void setSettingsChoiceProvider(Function<Tab, SettingsPageGuard.Choice> provider) {
        settingsChoiceProvider = java.util.Objects.requireNonNull(provider);
    }

    private SettingsPageGuard.Choice showSettingsChoice(Tab tab) {
        javafx.scene.control.ButtonType save = new javafx.scene.control.ButtonType(
                Messages.get("settings.save"), javafx.scene.control.ButtonBar.ButtonData.YES);
        javafx.scene.control.ButtonType discard = new javafx.scene.control.ButtonType(
                Messages.get("settings.discard"), javafx.scene.control.ButtonBar.ButtonData.NO);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(Messages.get("settings.unsaved.title"));
        alert.setHeaderText(Messages.get("settings.unsaved.header"));
        alert.setContentText(Messages.format("settings.unsaved.body", tab.getText()));
        alert.getButtonTypes().setAll(save, discard, javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.control.ButtonType choice = alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
        return choice == save ? SettingsPageGuard.Choice.SAVE
                : choice == discard ? SettingsPageGuard.Choice.DISCARD : SettingsPageGuard.Choice.CANCEL;
    }

    private VBox buildGeneralSettingsTab(Tab tab, SettingsPageGuard guard) {
        VBox page = ui.createMainPage();
        page.getStyleClass().add("settings-general");

        ComboBox<String> languageBox = new ComboBox<>();
        languageBox.setId("settings-language");
        languageBox.getItems().addAll("zh-CN", "zh-TW", "en");
        languageBox.setValue(Messages.locale().toLanguageTag());
        ui.configureLocalizedCombo(languageBox, ui::languageDisplayName);
        languageBox.setOnAction(event -> changeSettingsLanguage(languageBox));

        GlobalGameSettingsPane global = new GlobalGameSettingsPane(ui, true);
        guard.onDirtyCheck(tab, global::hasUnsavedChanges);
        guard.onSave(tab, global::save);
        global.setOnDiscard(() -> tab.setContent(buildGeneralSettingsTab(tab, guard)));
        page.getChildren().addAll(
                ui.createSurface(Messages.get("settings.group.interface"), null,
                        ui.createControlRow(Messages.get("settings.language"), languageBox)),
                global);
        return page;
    }

    private void changeSettingsLanguage(ComboBox<String> languageBox) {
        if (java.util.Objects.equals(languageBox.getValue(), Messages.locale().toLanguageTag())) return;
        ui.switchLanguage(languageBox.getValue());
        String actual = Messages.locale().toLanguageTag();
        if (!java.util.Objects.equals(languageBox.getValue(), actual)) {
            var action = languageBox.getOnAction();
            languageBox.setOnAction(null);
            try {
                languageBox.setValue(actual);
            } finally {
                languageBox.setOnAction(action);
            }
        }
    }

    /** Download concurrency, speed limit and the content release channel. */
    private VBox buildDownloadSettingsTab(Tab tab, SettingsPageGuard guard) {
        VBox page = ui.createMainPage();
        page.getStyleClass().add("settings-form");
        page.setSpacing(14);
        Runnable markDirty = () -> guard.markDirty(tab);

        TextField concurrencyField = new TextField(Integer.toString(
                ui.settingsManager.get(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT)));
        concurrencyField.setId("settings-download-concurrency");
        concurrencyField.setPromptText(Integer.toString(ECLConfig.DOWNLOAD_THREADS));
        concurrencyField.setMaxWidth(180);
        ui.applyFieldStyle(concurrencyField);
        concurrencyField.textProperty().addListener(
                (observable, previous, value) -> markDirty.run());

        TextField rateField = new TextField(Integer.toString(
                ui.settingsManager.get(ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB)));
        rateField.setPromptText(Messages.get("settings.download.rateHint"));
        rateField.setMaxWidth(180);
        ui.applyFieldStyle(rateField);
        rateField.textProperty().addListener((observable, previous, value) -> markDirty.run());

        ComboBox<ReleaseChannel> channelBox = new ComboBox<>();
        channelBox.getItems().setAll(ReleaseChannel.values());
        channelBox.setValue(ui.controller.preferredModReleaseChannel());
        ui.configureLocalizedCombo(channelBox, LauncherPageFactory::releaseChannelName);
        channelBox.valueProperty().addListener((observable, previous, value) -> markDirty.run());

        Label status = ui.createBodyText("");
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        status.managedProperty().bind(status.visibleProperty());
        BooleanSupplier performSave = () -> {
            int concurrency;
            int rate;
            try {
                concurrency = ECLConfig.clampDownloadConcurrency(ui.parseRangedInt(
                        concurrencyField.getText(), Messages.get("settings.download.parallel"), 1,
                        ECLConfig.MAX_DOWNLOAD_CONCURRENT));
                rate = rateField.getText() == null || rateField.getText().isBlank() ? 0
                        : ui.parseRangedInt(rateField.getText(),
                        Messages.get("settings.download.rate"), 0, 1_000_000);
            } catch (IllegalArgumentException error) {
                status.setText(error.getMessage());
                return false;
            }
            boolean saved = saveFormSettings(() -> {
                ui.settingsManager.set(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT, concurrency);
                ui.settingsManager.set(ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB, rate);
                ui.settingsManager.set(ECLConfig.KEY_MOD_RELEASE_CHANNEL, channelBox.getValue().name());
            }, ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT, ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB,
                    ECLConfig.KEY_MOD_RELEASE_CHANNEL);
            if (!saved) {
                status.setText(Messages.get("status.settingsSaveFailed.detail"));
                return false;
            }
            HttpUtil.setDownloadMaxConcurrent(concurrency);
            HttpUtil.setDownloadRateLimitBytesPerSecond(rate * 1024L);
            guard.clearDirty(tab);
            status.setText(Messages.get("status.settingsSaved.detail"));
            ui.setStatus(Messages.get("status.settingsSaved"), Messages.get("settings.tab.downloads"));
            return true;
        };
        guard.onSave(tab, performSave);
        Button save = ui.createActionButton(Messages.get("settings.save"), "primary-button", () -> performSave.getAsBoolean());
        save.setId("settings-download-save");
        Button cancel = ui.createActionButton(Messages.get("settings.discard"), "ghost-button",
                () -> {
                    guard.clearDirty(tab);
                    tab.setContent(buildDownloadSettingsTab(tab, guard));
                });
        cancel.setId("settings-download-discard");
        HBox actions = new HBox(10, save, cancel);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        page.getChildren().add(ui.createSurface(
                Messages.get("settings.group.transfer"),
                Messages.get("settings.download.subtitle"),
                ui.createControlRow(Messages.get("settings.download.parallel"), concurrencyField),
                ui.createControlRow(Messages.get("settings.download.rate"), rateField)));
        page.getChildren().addAll(ui.createSurface(Messages.get("settings.group.content"), null,
                ui.createControlRow(Messages.get("settings.download.channel"), channelBox)), status, actions);
        return page;
    }

    /** Java, memory and JVM defaults used by instances without their own launch profile. */
    private VBox buildDefaultSettingsTab(Tab tab, SettingsPageGuard guard) {
        VBox page = ui.createMainPage();
        page.getStyleClass().add("settings-form");
        page.setSpacing(14);
        Runnable markDirty = () -> guard.markDirty(tab);

        TextField javaField = new TextField(ui.javaPath == null ? "" : ui.javaPath);
        javaField.setId("settings-default-java");
        javaField.setPromptText(Messages.get("instances.java.prompt"));
        javaField.setPrefWidth(320);
        ui.applyFieldStyle(javaField);
        javaField.textProperty().addListener((observable, previous, value) -> markDirty.run());
        Button detectJava = ui.createActionButton(Messages.get("settings.detect"),
                "secondary-button",
                () -> javaField.setText(JavaRuntimeUtil.detectSystemJavaExecutable()));
        Button browseJava = ui.createActionButton(Messages.get("settings.browse"),
                "secondary-button", () -> {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle(Messages.get("instances.java.prompt"));
                    File initial = ui.prepareChooserDir(javaField.getText());
                    if (initial != null) {
                        chooser.setInitialDirectory(initial);
                    }
                    chooser.getExtensionFilters().add(
                            new FileChooser.ExtensionFilter("Java", "java.exe", "*.exe"));
                    File selected = chooser.showOpenDialog(ui.primaryStage);
                    if (selected != null) {
                        javaField.setText(selected.getAbsolutePath());
                    }
                });
        HBox javaRow = new HBox(10, javaField, detectJava, browseJava);
        HBox.setHgrow(javaField, javafx.scene.layout.Priority.ALWAYS);

        TextField memoryField = new TextField(ui.maxMemoryMb == ECLConfig.AUTO_MEMORY_MB
                ? "" : Integer.toString(ui.maxMemoryMb));
        memoryField.setId("settings-default-memory");
        memoryField.setPromptText(Messages.format("home.memoryAutoHint",
                ECLConfig.calculateAutoMemoryMb()));
        memoryField.setMaxWidth(220);
        ui.applyFieldStyle(memoryField);
        memoryField.textProperty().addListener((observable, previous, value) -> markDirty.run());

        TextField jvmField = new TextField(ui.extraJvmArgs == null ? "" : ui.extraJvmArgs);
        jvmField.setId("settings-default-jvm");
        jvmField.setPromptText(Messages.get("instances.jvm.prompt"));
        jvmField.setPrefWidth(420);
        ui.applyFieldStyle(jvmField);
        jvmField.textProperty().addListener((observable, previous, value) -> markDirty.run());

        ComboBox<DefaultIsolationType> isolationBox = new ComboBox<>();
        isolationBox.setId("settings-default-isolation");
        isolationBox.getItems().setAll(DefaultIsolationType.values());
        isolationBox.setValue(DefaultIsolationType.parse(ui.settingsManager.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
        ui.configureLocalizedCombo(isolationBox, LauncherPageFactory::isolationName);
        isolationBox.valueProperty().addListener((observable, previous, value) -> markDirty.run());

        Label status = ui.createBodyText("");
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        status.managedProperty().bind(status.visibleProperty());
        BooleanSupplier performSave = () -> {
            String javaValue = javaField.getText() == null ? "" : javaField.getText().trim();
            if (!javaValue.isBlank() && !JavaRuntimeUtil.isUsableJavaPath(javaValue)) {
                status.setText(Messages.get("instances.config.javaInvalid"));
                return false;
            }
            int memoryMb;
            try {
                memoryMb = ui.parseMemorySetting(memoryField.getText());
            } catch (IllegalArgumentException error) {
                status.setText(error.getMessage());
                return false;
            }
            try {
                JvmArgumentPolicy.requireSafe(TextUtil.parseCommandLine(jvmField.getText()));
            } catch (IllegalArgumentException error) {
                status.setText(Messages.format("instances.config.jvmInvalid", error.getMessage()));
                return false;
            }
            String resolvedJava = javaValue.isBlank() ? "" : JavaRuntimeUtil.resolveJavaExecutable(javaValue);
            String jvmArgs = jvmField.getText() == null ? "" : jvmField.getText().trim();
            boolean saved = saveFormSettings(() -> {
                ui.settingsManager.set(ECLConfig.KEY_JAVA_PATH, resolvedJava);
                ui.settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, memoryMb);
                ui.settingsManager.set(ECLConfig.KEY_JVM_ARGS, jvmArgs);
                ui.settingsManager.set(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE, isolationBox.getValue().name());
            }, ECLConfig.KEY_JAVA_PATH, ECLConfig.KEY_MAX_MEMORY_MB, ECLConfig.KEY_JVM_ARGS, ECLConfig.KEY_DEFAULT_ISOLATION_TYPE);
            if (!saved) {
                status.setText(Messages.get("status.settingsSaveFailed.detail"));
                return false;
            }
            ui.javaPath = resolvedJava;
            ui.maxMemoryMb = memoryMb;
            ui.extraJvmArgs = jvmArgs;
            guard.clearDirty(tab);
            status.setText(Messages.get("instances.config.saved"));
            ui.setStatus(Messages.get("status.settingsSaved"), Messages.get("settings.tab.defaults"));
            return true;
        };
        guard.onSave(tab, performSave);
        Button save = ui.createActionButton(Messages.get("settings.save"), "primary-button", () -> performSave.getAsBoolean());
        save.setId("settings-default-save");
        Button discard = ui.createActionButton(Messages.get("settings.discard"), "ghost-button", () -> {
            guard.clearDirty(tab);
            tab.setContent(buildDefaultSettingsTab(tab, guard));
        });
        discard.setId("settings-default-discard");
        Button restore = ui.createActionButton(Messages.get("instances.config.reset"),
                "ghost-button", () -> {
                    javaField.setText("");
                    memoryField.setText("");
                    jvmField.setText("");
                    isolationBox.setValue(DefaultIsolationType.MODDED);
                    status.setText(Messages.get("settings.defaults.restored"));
                });
        HBox actions = new HBox(10, save, discard, restore);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        page.getChildren().add(ui.createSurface(
                Messages.get("settings.group.runtime"),
                Messages.get("settings.defaults.subtitle"),
                ui.createControlRow(Messages.get("label.javaPath"), javaRow),
                ui.createControlRow(Messages.get("label.maxMemory"), memoryField),
                ui.createControlRow(Messages.get("label.jvmParams"), jvmField)));
        page.getChildren().addAll(ui.createSurface(Messages.get("settings.group.instanceStorage"),
                Messages.get("settings.defaults.isolationHint"),
                ui.createControlRow(Messages.get("settings.download.isolation"), isolationBox)), status, actions);
        return page;
    }

    /** Prevents a failed save from becoming the source of a later discard or auto-save. */
    boolean saveFormSettings(Runnable update, SettingKey<?>... keys) {
        SettingsManager manager = ui.settingsManager;
        synchronized (manager) {
            List<Runnable> rollback = new ArrayList<>();
            for (SettingKey<?> key : keys) {
                rollback.add(preserveSetting(manager, key));
            }
            boolean saved = false;
            try {
                update.run();
                saved = manager.save();
                return saved;
            } finally {
                if (!saved) {
                    rollback.forEach(Runnable::run);
                }
            }
        }
    }

    private static <T> Runnable preserveSetting(SettingsManager manager, SettingKey<T> key) {
        boolean existed = manager.has(key);
        T previous = manager.get(key);
        return () -> {
            if (existed) {
                manager.set(key, previous);
            } else {
                manager.remove(key);
            }
        };
    }

    /** Version information, local folders and diagnostics entry points. */
    private VBox createAboutSettingsPage() {
        VBox page = ui.createMainPage();
        Button logsButton = ui.createActionButton(Messages.get("settings.openLogs"),
                "secondary-button", () -> ui.openLocalFolder(
                        new File(System.getProperty("user.home"), ".ecl/logs"), Messages.get("settings.openLogs")));
        logsButton.setId("settings-open-logs");
        Button wizardButton = ui.createActionButton(Messages.get("wizard.title"),
                "ghost-button", ui::showFirstRunWizard);
        Button crashButton = ui.createActionButton(Messages.get("settings.openCrash"),
                "ghost-button", () -> ui.openLocalFolder(
                        new File(ui.getActiveGameDir(), "crash-reports"),
                        Messages.get("label.crashReports")));
        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(10, 8, logsButton, crashButton, wizardButton);

        page.getChildren().add(ui.createSurface(
                Messages.get("settings.tab.about"),
                Messages.get("settings.about.subtitle"),
                ui.createInfoRow(Messages.get("settings.about.version"),
                        ui.createStaticValueLabel(Messages.get("app.version"))),
                ui.createInfoRow("Java", ui.createStaticValueLabel(System.getProperty("java.version")))));
        page.getChildren().add(ui.createSurface(Messages.get("settings.group.diagnostics"),
                Messages.get("settings.about.subtitle"), actions));
        return page;
    }

    private static String releaseChannelName(ReleaseChannel channel) {
        return Messages.get("settings.channel." + channel.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String isolationName(DefaultIsolationType type) {
        return Messages.get("settings.isolation." + type.name().toLowerCase(java.util.Locale.ROOT));
    }

}
