package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.launcher.VersionManager;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;


import static com.ecl.util.TextUtil.abbreviate;

/** Owns the launcher start form, account controls, and loader-install interaction. */
final class LauncherLaunchForm {
    private final LauncherUI ui;
    private final LauncherAuthController auth;
    private final LauncherLoaderWorkflow loader;

    LauncherLaunchForm(LauncherUI ui) {
        this.ui = ui;
        this.auth = new LauncherAuthController(ui);
        this.loader = new LauncherLoaderWorkflow(ui);
    }

    GridPane createForm() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("launch-form");
        grid.setHgap(10);
        grid.setVgap(12);

        String previousVersion = ui.versionCombo == null
                ? ui.settingsManager.get(ECLConfig.KEY_SELECTED_VERSION) : ui.versionCombo.getValue();
        String previousAuthType = ui.authTypeCombo == null
                ? auth.normalizeAuthType(ui.settingsManager.get(ECLConfig.KEY_AUTH_TYPE))
                : auth.normalizeAuthType(ui.authTypeCombo.getValue());
        String previousUsername = ui.usernameField == null
                ? ui.settingsManager.get(ECLConfig.KEY_USERNAME)
                : ui.usernameField.getText();
        if (previousUsername == null || previousUsername.isBlank()) {
            previousUsername = "Steve";
        }

        ui.authTypeCombo = new ComboBox<>();
        ui.authTypeCombo.getItems().addAll(LauncherUI.AUTH_OFFLINE, LauncherUI.AUTH_MICROSOFT, LauncherUI.AUTH_YGGDRASIL);
        ui.authTypeCombo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : auth.authDisplayName(item));
            }
        });
        ui.authTypeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : auth.authDisplayName(item));
            }
        });
        ui.authTypeCombo.setValue(previousAuthType);
        ui.authTypeCombo.setOnAction(e -> auth.updateAuthFields());
        ui.applyFieldStyle(ui.authTypeCombo);

        ui.yggdrasilServerField = new TextField(ui.settingsManager.get(ECLConfig.KEY_YGGDRASIL_SERVER));
        ui.yggdrasilServerField.setPromptText("输入 Yggdrasil 认证地址");
        ui.applyFieldStyle(ui.yggdrasilServerField);

        ui.usernameField = new TextField(previousUsername);
        ui.usernameField.setPromptText("输入玩家名称");
        ui.applyFieldStyle(ui.usernameField);
        ui.usernameField.textProperty().addListener((obs, oldValue, newValue) -> {
            ui.updateRuntimeSummary();
            auth.updateOfflineSkinControls();
        });

        ui.passwordField = new PasswordField();
        ui.passwordField.setPromptText("外置登录时需要");
        ui.applyFieldStyle(ui.passwordField);

        ui.authSummaryLabel = ui.createValueLabel();
        ui.authHintLabel = new Label();
        ui.authHintLabel.getStyleClass().add("status-detail");
        ui.authHintLabel.setWrapText(true);

        ui.versionCombo = new ComboBox<>();
        ui.versionCombo.setPromptText("选择游戏版本");
        ui.versionCombo.setVisibleRowCount(14);
        ui.versionCombo.setCellFactory(list -> createVersionCell());
        ui.versionCombo.setButtonCell(createVersionCell());
        ui.applyFieldStyle(ui.versionCombo);
        ui.versionCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.isBlank()) {
                ui.settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, newValue);
                if (!ui.settingsManager.save()) {
                    ui.setStatus("设置保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
                }
            }
            ui.updateRuntimeSummary();
            ui.versionActions.updateSelectedVersionWikiButton();
            loader.syncLoaderChoiceFromProfile(newValue);
        });

        ui.versionTypeCombo = new ComboBox<>();
        ui.versionTypeCombo.getItems().addAll(VersionManager.VersionCategory.values());
        ui.versionTypeCombo.setValue(VersionManager.VersionCategory.FEATURED);
        ui.versionTypeCombo.setPrefWidth(176);
        ui.versionTypeCombo.setTooltip(new Tooltip("默认显示正式版、预览版/快照和愚人节版，也可以只看某一类"));
        ui.versionTypeCombo.setOnAction(e -> {
            ui.settingsManager.set(ECLConfig.KEY_VERSION_CATEGORY, ui.versionActions.getSelectedVersionCategory().name());
            if (!ui.settingsManager.save()) {
                ui.setStatus("设置保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
                return;
            }
            ui.versionActions.refreshVersions();
        });
        ui.applyFieldStyle(ui.versionTypeCombo);

        ui.loaderChoiceCombo = new ComboBox<>();
        ui.loaderChoiceCombo.getItems().setAll(LoaderChoice.values());
        ui.loaderChoiceCombo.setValue(LoaderChoice.VANILLA);
        ui.loaderChoiceCombo.setPrefWidth(160);
        ui.loaderChoiceCombo.setTooltip(new Tooltip(
                "选择 Fabric、Quilt、Forge 或 NeoForge 后，可直接安装对应实例"));
        ui.loaderChoiceCombo.setOnAction(event -> loader.handleLoaderChoiceChanged());
        ui.applyFieldStyle(ui.loaderChoiceCombo);
        ui.installSelectedLoaderButton = new Button("当前为原版");
        ui.installSelectedLoaderButton.getStyleClass().addAll(
                "app-button", "secondary-button", "compact-button");
        ui.installSelectedLoaderButton.setDisable(true);
        ui.installSelectedLoaderButton.setOnAction(event -> loader.installSelectedLoader(null));

        ui.selectedVersionWikiButton = createSelectedVersionWikiButton();
        ui.versionActions.restoreVersionComboItems(previousVersion);
        ui.versionActions.updateSelectedVersionWikiButton();

        TextField gameDirField = new TextField(abbreviate(ui.getActiveGameDir().getAbsolutePath(), 72));
        gameDirField.setEditable(false);
        ui.applyFieldStyle(gameDirField);

        TextField jvmField = new TextField(ui.extraJvmArgs == null || ui.extraJvmArgs.isBlank()
                ? "未设置（内存: " + ui.gameLaunch.getMemoryDisplayText() + "）"
                : ui.extraJvmArgs);
        jvmField.setEditable(false);
        ui.applyFieldStyle(jvmField);

        Button folderButton = LauncherUiFactory.iconActionButton(LauncherUI.class,
                "/icons/ui/folder.png", "▣", "打开游戏目录",
                () -> ui.openLocalFolder(ui.getActiveGameDir(), "游戏目录"));

        Button jvmButton = LauncherUiFactory.iconActionButton(LauncherUI.class,
                "/icons/ui/gear.png", "⚙", "高级设置", ui::showSettingsDialog);

        HBox gameDirBox = new HBox(10, gameDirField, folderButton);
        HBox.setHgrow(gameDirField, Priority.ALWAYS);
        HBox jvmBox = new HBox(10, jvmField, jvmButton);
        HBox.setHgrow(jvmField, Priority.ALWAYS);
        ui.authTypeCombo.setPrefWidth(200);
        ui.microsoftLoginBtn = new Button("正版登录");
        ui.microsoftLoginBtn.getStyleClass().addAll("app-button", "secondary-button", "compact-button");
        ui.microsoftLoginBtn.setTooltip(new Tooltip("登录 Microsoft 正版 Minecraft Java 版账号"));
        ui.microsoftLoginBtn.setOnAction(e -> ui.microsoftAccounts.loginMicrosoftAccount());
        ui.microsoftAddAccountBtn = new Button("添加账号");
        ui.microsoftAddAccountBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        ui.microsoftAddAccountBtn.setTooltip(new Tooltip("使用设备码添加另一个 Microsoft 账号"));
        ui.microsoftAddAccountBtn.setOnAction(e -> ui.microsoftAccounts.addMicrosoftAccount());
        ui.skinUploadBtn = new Button("上传皮肤");
        ui.skinUploadBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        ui.skinUploadBtn.setTooltip(new Tooltip("上传 PNG 皮肤到当前 Minecraft Java 正版账号"));
        ui.skinUploadBtn.setOnAction(e -> ui.skins.chooseAndUploadSkin());
        ui.offlineSkinRemoveBtn = new Button("清除皮肤");
        ui.offlineSkinRemoveBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        ui.offlineSkinRemoveBtn.setTooltip(new Tooltip("移除当前离线账号已导入的本地皮肤"));
        ui.offlineSkinRemoveBtn.setOnAction(e -> ui.skins.removeOfflineSkin());
        LauncherUiFactory.setVisible(ui.offlineSkinRemoveBtn, false);
        ui.microsoftAccountCombo = new ComboBox<>();
        ui.microsoftAccountCombo.setPromptText("选择已保存账号");
        ui.microsoftAccountCombo.getItems().setAll(ui.microsoftAccountStore.list());
        String selectedAccountUuid = ui.settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_UUID);
        ui.microsoftAccountCombo.getItems().stream()
                .filter(account -> account.uuid().equalsIgnoreCase(selectedAccountUuid))
                .findFirst()
                .ifPresentOrElse(ui.microsoftAccountCombo::setValue, () -> {
                    if (!ui.microsoftAccountCombo.getItems().isEmpty()) {
                        ui.microsoftAccountCombo.getSelectionModel().selectFirst();
                    }
                });
        ui.microsoftAccountCombo.valueProperty().addListener((obs, oldValue, account) -> {
            ui.selectedMicrosoftAccount = account;
            if (account != null) {
                ui.usernameField.setText(account.username());
                ui.settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_UUID, account.uuid());
                ui.settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_NAME, account.username());
                ui.settingsManager.save();
                ui.updateRuntimeSummary();
            }
        });
        ui.selectedMicrosoftAccount = ui.microsoftAccountCombo.getValue();
        ui.applyFieldStyle(ui.microsoftAccountCombo);
        HBox authBox = new HBox(10, ui.authTypeCombo, ui.usernameField, ui.microsoftAccountCombo,
                ui.microsoftLoginBtn, ui.microsoftAddAccountBtn, ui.skinUploadBtn, ui.offlineSkinRemoveBtn);
        authBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(ui.usernameField, Priority.ALWAYS);
        HBox.setHgrow(ui.microsoftAccountCombo, Priority.ALWAYS);
        VBox authHelpBox = new VBox(4, ui.authSummaryLabel, ui.authHintLabel);
        HBox versionBox = new HBox(10, ui.versionCombo, ui.selectedVersionWikiButton);
        versionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(ui.versionCombo, Priority.ALWAYS);
        Label loaderHint = new Label("安装后会自动切换到独立模组实例");
        loaderHint.getStyleClass().add("status-detail");
        HBox loaderBox = new HBox(10, ui.loaderChoiceCombo, ui.installSelectedLoaderButton, loaderHint);
        loaderBox.setAlignment(Pos.CENTER_LEFT);
        ui.versionCombo.valueProperty().addListener((obs, oldValue, newValue) ->
                gameDirField.setText(abbreviate(ui.getActiveGameDir().getAbsolutePath(), 72)));

        int row = 0;
        ui.serverLabel = new Label("外置服务器");
        ui.serverLabel.getStyleClass().add("field-label");
        ui.passwordLabel = new Label("密码");
        ui.passwordLabel.getStyleClass().add("field-label");

        Label gameVersionLabel = new Label("游戏版本");
        gameVersionLabel.getStyleClass().add("field-label");
        grid.add(gameVersionLabel, 0, row);
        grid.add(versionBox, 1, row++);

        Label modLoaderLabel = new Label("模组加载器");
        modLoaderLabel.getStyleClass().add("field-label");
        grid.add(modLoaderLabel, 0, row);
        grid.add(loaderBox, 1, row++);

        Label accountModeLabel = new Label("账号模式");
        accountModeLabel.getStyleClass().add("field-label");
        grid.add(accountModeLabel, 0, row);
        grid.add(authBox, 1, row++);

        grid.add(ui.serverLabel, 0, row);
        grid.add(ui.yggdrasilServerField, 1, row++);

        grid.add(ui.passwordLabel, 0, row);
        grid.add(ui.passwordField, 1, row++);

        Label loginStatusLabel = new Label("登录状态");
        loginStatusLabel.getStyleClass().add("field-label");
        grid.add(loginStatusLabel, 0, row);
        grid.add(authHelpBox, 1, row++);

        Label gameDirLabel = new Label("游戏目录");
        gameDirLabel.getStyleClass().add("field-label");
        grid.add(gameDirLabel, 0, row);
        grid.add(gameDirBox, 1, row++);

        Label jvmParamsLabel = new Label("JVM 参数");
        jvmParamsLabel.getStyleClass().add("field-label");
        grid.add(jvmParamsLabel, 0, row);
        grid.add(jvmBox, 1, row);

        return grid;
    }

    VBox createLoaderSelectionPage(String profileId, String minecraftVersion) {
        VBox page = ui.createMainPage();
        VBox guidance = new VBox(12,
                ui.createBodyText("为 Minecraft " + minecraftVersion
                        + " 选择加载器，安装完成后会自动进入对应的独立模组实例。"),
                loader.createLoaderQuickActions(profileId));
        page.getChildren().add(ui.createSurface(
                "// 当前版本没有模组加载器",
                ui.versionManager.getVersionDisplayName(profileId),
                guidance));
        return page;
    }

    /**
     * Offline account path: pick a PNG, confirm the model, and copy it into the launcher data
     * directory. The skin is injected at launch time through the built-in Yggdrasil skin service,
     * so it works in single player and on offline-mode servers without any mods or premium login.
     */

    ListCell<String> createVersionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : ui.versionManager.getVersionDisplayName(item));
            }
        };
    }

    Button createSelectedVersionWikiButton() {
        Button button = new Button("更新说明");
        button.getStyleClass().addAll("app-button", "wiki-link-button");
        button.setTooltip(new Tooltip("打开 mc 中文 Wiki 的当前版本更新介绍"));
        button.setOnAction(e -> ui.versionActions.openMinecraftWikiVersionPage(ui.getSelectedVersion()));
        return button;
    }

    void updateSelectedVersionWikiButton() {
        if (ui.selectedVersionWikiButton == null) {
            return;
        }
        String version = ui.getSelectedVersion();
        boolean supported = ui.versionActions.isWikiSupportedVersion(version);
        boolean comboBusy = ui.versionCombo != null && ui.versionCombo.isDisabled();
        ui.selectedVersionWikiButton.setDisable(comboBusy || !supported);
        ui.selectedVersionWikiButton.setTooltip(new Tooltip(supported
                ? "打开 mc 中文 Wiki 的 " + version + " 更新介绍"
                : "正式版和快照版可打开 mc 中文 Wiki 更新介绍"));
    }

    HBox createActionBar() {
        Label playIcon = new Label("▶");
        playIcon.getStyleClass().add("launch-play-icon");
        ui.launchBtn = new Button("启动游戏");
        ui.launchBtn.setGraphic(playIcon);
        ui.launchBtn.setGraphicTextGap(10);
        ui.launchBtn.getStyleClass().addAll("app-button", "launch-button");
        ui.launchBtn.setDefaultButton(true);
        ui.launchBtn.setOnAction(e -> ui.gameLaunch.launchGame());
        loader.updateLoaderControls();

        Button switchInstanceButton = ui.createLinkButton(
                "选择版本 / 加载器  ›",
                () -> ui.openInstanceSettings(false));

        ui.refreshBtn = new Button("刷新版本");
        ui.refreshBtn.getStyleClass().addAll("app-button", "secondary-button");
        ui.refreshBtn.setOnAction(e -> ui.versionActions.refreshVersions());
        LauncherUiFactory.setVisible(ui.refreshBtn, false);

        ui.settingsBtn = new Button("高级设置");
        ui.settingsBtn.getStyleClass().addAll("app-button", "ghost-button");
        ui.settingsBtn.setOnAction(e -> ui.showSettingsDialog());
        LauncherUiFactory.setVisible(ui.settingsBtn, false);

        HBox buttonBar = new HBox(14, ui.launchBtn, switchInstanceButton, ui.refreshBtn, ui.settingsBtn);
        buttonBar.getStyleClass().add("launch-actions");
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        return buttonBar;
    }

    void setControlsBusy(boolean busy) {
        ui.launchBtn.setDisable(busy);
        ui.refreshBtn.setDisable(busy);
        ui.settingsBtn.setDisable(busy);
        if (ui.microsoftLoginBtn != null) {
            ui.microsoftLoginBtn.setDisable(busy);
        }
        if (ui.microsoftAddAccountBtn != null) {
            ui.microsoftAddAccountBtn.setDisable(busy);
        }
        if (ui.skinUploadBtn != null) {
            ui.skinUploadBtn.setDisable(busy);
        }
        if (ui.homeSkinUploadButton != null) {
            ui.homeSkinUploadButton.setDisable(busy);
        }
        if (ui.offlineSkinRemoveBtn != null) {
            ui.offlineSkinRemoveBtn.setDisable(busy);
        }
        if (ui.microsoftAccountCombo != null) {
            ui.microsoftAccountCombo.setDisable(busy);
        }
        ui.versionCombo.setDisable(busy);
        ui.versionTypeCombo.setDisable(busy);
        if (ui.loaderChoiceCombo != null) {
            ui.loaderChoiceCombo.setDisable(busy);
        }
        if (ui.installSelectedLoaderButton != null) {
            ui.installSelectedLoaderButton.setDisable(busy);
        }
        ui.versionActions.updateSelectedVersionWikiButton();
        ui.authTypeCombo.setDisable(busy);
        ui.usernameField.setDisable(busy || LauncherUI.AUTH_MICROSOFT.equals(ui.authTypeCombo.getValue()));
        ui.yggdrasilServerField.setDisable(busy);
        ui.passwordField.setDisable(busy);
        if (!busy) {
            loader.updateLoaderControls();
        }
    }

    // Delegates kept for LauncherUIView wrappers
    LoaderChoice loaderChoiceForProfile(String profileId) {
        return loader.loaderChoiceForProfile(profileId);
    }

    void updateLoaderControls() {
        loader.updateLoaderControls();
    }

    void syncLoaderChoiceFromProfile(String profileId) {
        loader.syncLoaderChoiceFromProfile(profileId);
    }

    void installSelectedLoader(Runnable afterSuccess) {
        loader.installSelectedLoader(afterSuccess);
    }

    void updateAuthFields() {
        auth.updateAuthFields();
    }

    void updateOfflineSkinControls() {
        auth.updateOfflineSkinControls();
    }

    boolean offlineSkinExists() {
        return auth.offlineSkinExists();
    }

    String getAuthDisplayName() {
        return auth.getAuthDisplayName();
    }
}
