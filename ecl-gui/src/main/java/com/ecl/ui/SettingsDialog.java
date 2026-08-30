package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import com.ecl.game.InstanceGameSettings;
import com.ecl.game.InstanceGameSettingsStore;
import com.ecl.game.InstanceLaunchProfile;
import com.ecl.modrinth.model.ReleaseChannel;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.JvmArgumentPolicy;
import com.ecl.util.TextUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

/** Owns the advanced settings dialog and persists its form values. */
final class SettingsDialog {
    private final LauncherUI ui;

    SettingsDialog(LauncherUI ui) {
        this.ui = ui;
    }

    void show() {
        String selectedInstanceId = ui.getSelectedVersion();
        InstanceLaunchProfile selectedLaunchProfile = loadExistingProfile(selectedInstanceId);
        Stage dialog = new Stage();
        dialog.initOwner(ui.primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("高级设置");
        ui.applyWindowIcon(dialog);

        TextField javaField = new TextField(selectedLaunchProfile == null
                ? ui.javaPath : selectedLaunchProfile.javaPath());
        javaField.setPromptText("java.exe 或 JDK 根目录");
        ui.applyFieldStyle(javaField);

        Button detectBtn = new Button("自动检测");
        detectBtn.getStyleClass().addAll("app-button", "secondary-button");
        detectBtn.setOnAction(e -> javaField.setText(JavaRuntimeUtil.detectSystemJavaExecutable()));

        Button javaBrowseBtn = new Button("浏览");
        javaBrowseBtn.getStyleClass().addAll("app-button", "secondary-button");
        javaBrowseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 Java 可执行文件");
            File initial = ui.prepareChooserDir(javaField.getText());
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java 可执行文件", "java.exe", "*.exe"));
            File selected = chooser.showOpenDialog(dialog);
            if (selected != null) {
                javaField.setText(selected.getAbsolutePath());
            }
        });

        HBox javaBox = new HBox(10, javaField, detectBtn, javaBrowseBtn);
        HBox.setHgrow(javaField, Priority.ALWAYS);

        TextField dirField = new TextField(ui.gameDir.getAbsolutePath());
        dirField.setPromptText("输入游戏目录");
        ui.applyFieldStyle(dirField);

        Button dirBrowseBtn = new Button("浏览");
        dirBrowseBtn.getStyleClass().addAll("app-button", "secondary-button");
        dirBrowseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择游戏目录");
            File initial = ui.prepareChooserDir(dirField.getText());
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            File selected = chooser.showDialog(dialog);
            if (selected != null) {
                dirField.setText(selected.getAbsolutePath());
            }
        });

        HBox dirBox = new HBox(10, dirField, dirBrowseBtn);
        HBox.setHgrow(dirField, Priority.ALWAYS);

        ComboBox<String> isolationPolicyField = new ComboBox<>();
        isolationPolicyField.getItems().setAll("始终隔离", "仅 Mod/加载器实例隔离", "全部共享");
        isolationPolicyField.getSelectionModel().select(switch (DefaultIsolationType.parse(
                ui.settingsManager.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE))) {
            case ALWAYS -> 0;
            case MODDED -> 1;
            case NEVER -> 2;
        });
        ui.applyFieldStyle(isolationPolicyField);

        ComboBox<String> instanceDirectoryModeField = new ComboBox<>();
        instanceDirectoryModeField.getItems().setAll("跟随默认策略", "使用独立实例目录", "使用自定义目录");
        TextField customInstanceDirectoryField = new TextField();
        customInstanceDirectoryField.setPromptText("选择此实例的自定义运行目录");
        ui.applyFieldStyle(customInstanceDirectoryField);
        if (selectedInstanceId == null || selectedInstanceId.isBlank()) {
            instanceDirectoryModeField.getSelectionModel().select(0);
            instanceDirectoryModeField.setDisable(true);
            customInstanceDirectoryField.setDisable(true);
        } else {
            try {
                InstanceGameSettings currentInstanceSettings = new InstanceGameSettingsStore().load(
                        ui.gameRepository().instanceRoot(selectedInstanceId));
                if (!currentInstanceSettings.overridesRunningDirectory()) {
                    instanceDirectoryModeField.getSelectionModel().select(0);
                } else if (currentInstanceSettings.hasCustomDirectory()) {
                    instanceDirectoryModeField.getSelectionModel().select(2);
                    customInstanceDirectoryField.setText(currentInstanceSettings.runningDirectory());
                } else {
                    instanceDirectoryModeField.getSelectionModel().select(1);
                }
            } catch (IOException error) {
                ui.LOGGER.warn("Cannot load instance directory settings for {}", selectedInstanceId, error);
                instanceDirectoryModeField.getSelectionModel().select(0);
            }
        }
        customInstanceDirectoryField.setDisable(
                instanceDirectoryModeField.getSelectionModel().getSelectedIndex() != 2);
        instanceDirectoryModeField.setOnAction(event -> customInstanceDirectoryField.setDisable(
                instanceDirectoryModeField.getSelectionModel().getSelectedIndex() != 2));
        Button customInstanceBrowseButton = new Button("浏览");
        customInstanceBrowseButton.getStyleClass().addAll("app-button", "secondary-button");
        customInstanceBrowseButton.disableProperty().bind(customInstanceDirectoryField.disabledProperty());
        customInstanceBrowseButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择实例运行目录");
            File initial = ui.prepareChooserDir(customInstanceDirectoryField.getText());
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            File selected = chooser.showDialog(dialog);
            if (selected != null) {
                customInstanceDirectoryField.setText(selected.getAbsolutePath());
            }
        });
        HBox customInstanceDirectoryBox = new HBox(10,
                customInstanceDirectoryField, customInstanceBrowseButton);
        HBox.setHgrow(customInstanceDirectoryField, Priority.ALWAYS);
        VBox instanceDirectoryBox = new VBox(10,
                instanceDirectoryModeField, customInstanceDirectoryBox);

        int initialMemoryMb = selectedLaunchProfile == null
                ? ui.maxMemoryMb : selectedLaunchProfile.maxMemoryMb();
        TextField memoryField = new TextField(initialMemoryMb == ECLConfig.AUTO_MEMORY_MB
                ? "" : Integer.toString(initialMemoryMb));
        memoryField.setPromptText("自动（当前 " + ECLConfig.calculateAutoMemoryMb() + " MB）");
        ui.applyFieldStyle(memoryField);

        TextField jvmField = new TextField(selectedLaunchProfile == null
                ? ui.extraJvmArgs
                : TextUtil.formatCommandLine(selectedLaunchProfile.customJvmArguments()));
        jvmField.setPromptText("例如：-XX:+UseG1GC -Dfile.encoding=UTF-8");
        ui.applyFieldStyle(jvmField);

        TextField widthField = new TextField(Integer.toString(ui.gameWidth));
        widthField.setPromptText("宽度");
        ui.applyFieldStyle(widthField);
        TextField heightField = new TextField(Integer.toString(ui.gameHeight));
        heightField.setPromptText("高度");
        ui.applyFieldStyle(heightField);
        HBox resolutionBox = new HBox(10, widthField, new Label("×"), heightField);
        resolutionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(widthField, Priority.ALWAYS);
        HBox.setHgrow(heightField, Priority.ALWAYS);

        CheckBox fullscreenField = new CheckBox("全屏启动");
        fullscreenField.setSelected(ui.gameFullscreen);
        TextField serverField = new TextField(ui.quickServer == null ? "" : ui.quickServer);
        serverField.setPromptText("可选，例如 play.example.com:25565");
        ui.applyFieldStyle(serverField);
        TextField processorField = new TextField(ui.processorCount <= 0 ? "" : Integer.toString(ui.processorCount));
        processorField.setPromptText("留空使用全部可用核心");
        ui.applyFieldStyle(processorField);
        CheckBox closeAfterLaunchField = new CheckBox("游戏启动后隐藏启动器，退出后恢复");
        closeAfterLaunchField.setSelected(ui.closeAfterLaunch);
        CheckBox showConsoleField = new CheckBox("启动后自动打开实时控制台");
        showConsoleField.setSelected(ui.showGameConsole);
        VBox behaviorBox = new VBox(10, fullscreenField, closeAfterLaunchField, showConsoleField);

        CheckBox backupOnLaunchField = new CheckBox("每次启动游戏前自动备份存档");
        backupOnLaunchField.setSelected(ui.backupOnLaunch);
        TextField backupKeepCountField = new TextField(Integer.toString(ui.backupKeepCount));
        backupKeepCountField.setPromptText("保留份数（1-100）");
        backupKeepCountField.setMaxWidth(160);
        ui.applyFieldStyle(backupKeepCountField);
        CheckBox backupIncludeModsField = new CheckBox("自动备份同时包含 mods");
        backupIncludeModsField.setSelected(ui.backupIncludeMods);
        Label backupKeepCountLabel = new Label("最多保留");
        backupKeepCountLabel.getStyleClass().add("info-key");
        Label backupKeepCountUnit = new Label("份");
        HBox backupKeepCountBox = new HBox(10, backupKeepCountLabel,
                backupKeepCountField, backupKeepCountUnit);
        backupKeepCountBox.setAlignment(Pos.CENTER_LEFT);
        VBox backupBehaviorBox = new VBox(10, backupOnLaunchField,
                backupKeepCountBox, backupIncludeModsField);

        ComboBox<String> modReleaseChannelField = new ComboBox<>();
        modReleaseChannelField.getItems().setAll("仅正式版", "正式版和 Beta", "全部（含 Alpha）");
        modReleaseChannelField.getSelectionModel().select(switch (ui.controller.preferredModReleaseChannel()) {
            case RELEASE_ONLY -> 0;
            case RELEASE_AND_BETA -> 1;
            case ALL -> 2;
        });
        ui.applyFieldStyle(modReleaseChannelField);

        PasswordField curseForgeApiKeyField = new PasswordField();
        String storedCurseForgeKey = ui.settingsManager.getEncrypted(
                ECLConfig.KEY_CURSEFORGE_API_KEY);
        boolean unreadableCurseForgeKey =
                ui.settingsManager.consumeUnreadableEncryptedSetting() != null;
        curseForgeApiKeyField.setText(storedCurseForgeKey == null ? "" : storedCurseForgeKey);
        curseForgeApiKeyField.setPromptText("可留空，或使用 CURSEFORGE_API_KEY 环境变量");
        if (unreadableCurseForgeKey) {
            curseForgeApiKeyField.setPromptText("已保存的 API Key 解密失败，请重新输入");
        }
        ui.applyFieldStyle(curseForgeApiKeyField);

        VBox dialogRoot = new VBox(18,
                ui.createSurface("Java 路径", "指向 java.exe 或 JDK 根目录", javaBox),
                ui.createSurface("游戏目录", "Minecraft 实例根目录", dirBox),
                ui.createSurface("默认版本隔离", "推荐仅隔离带加载器的实例；整合包始终隔离",
                        isolationPolicyField),
                ui.createSurface("当前实例运行目录",
                        selectedInstanceId == null || selectedInstanceId.isBlank()
                                ? "选择一个已安装版本后可配置实例级覆盖"
                                : "当前实例：" + selectedInstanceId,
                        instanceDirectoryBox),
                ui.createSurface("最大内存", "留空使用自动分配（MB）", memoryField),
                ui.createSurface("JVM 参数", "追加到默认启动参数之后", jvmField),
                ui.createSurface("窗口分辨率", "窗口模式下的宽度和高度", resolutionBox),
                ui.createSurface("直连服务器", "启动后直接连接，可留空", serverField),
                ui.createSurface("处理器核心数", "通过 ActiveProcessorCount 限制游戏可见核心数", processorField),
                ui.createSurface("启动行为", null, behaviorBox),
                ui.createSurface("存档自动备份",
                        "备份位于 ECL 数据目录；失败只写入日志，不会阻止游戏启动",
                        backupBehaviorBox),
                ui.createSurface("Modrinth 发布通道",
                        "控制默认版本、依赖版本和更新版本的稳定性范围",
                        modReleaseChannelField),
                ui.createSurface("CurseForge API Key",
                        "用于 CurseForge 模组、光影、材质包和整合包搜索下载；保存时加密存储",
                        curseForgeApiKeyField)
        );
        dialogRoot.getStyleClass().add("root-pane");
        dialogRoot.setPadding(new Insets(24));

        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().addAll("app-button", "primary-button");
        saveBtn.setOnAction(e -> {
            String configuredJava = javaField.getText().trim();
            if (!configuredJava.isBlank() && !JavaRuntimeUtil.isUsableJavaPath(configuredJava)) {
                ui.setStatus("错误: Java 路径无效", "请选择一个可用的 java.exe 或 JDK 根目录后再保存。");
                return;
            }

            String configuredGameDir = dirField.getText().trim();
            if (configuredGameDir.isBlank()) {
                configuredGameDir = ECLConfig.getGameDir().getAbsolutePath();
            }

            int configuredMemoryMb;
            try {
                configuredMemoryMb = ui.parseMemorySetting(memoryField.getText());
            } catch (IllegalArgumentException memoryError) {
                ui.setStatus("错误: 内存格式无效", memoryError.getMessage());
                return;
            }
            List<String> configuredJvmArguments;
            try {
                configuredJvmArguments = JvmArgumentPolicy.requireSafe(
                        TextUtil.parseCommandLine(jvmField.getText()));
            } catch (IllegalArgumentException jvmError) {
                ui.setStatus("JVM 参数无效", jvmError.getMessage());
                return;
            }
            int configuredWidth;
            int configuredHeight;
            int configuredProcessors;
            int configuredBackupKeepCount;
            try {
                configuredWidth = ui.parseRangedInt(widthField.getText(), "窗口宽度", 320, 16_384);
                configuredHeight = ui.parseRangedInt(heightField.getText(), "窗口高度", 240, 16_384);
                configuredProcessors = processorField.getText().isBlank() ? 0
                        : ui.parseRangedInt(processorField.getText(), "核心数", 1,
                                Math.max(1, Runtime.getRuntime().availableProcessors()));
                configuredBackupKeepCount = ui.parseRangedInt(
                        backupKeepCountField.getText(), "备份保留份数", 1, 100);
            } catch (IllegalArgumentException valueError) {
                ui.setStatus("游戏参数无效", valueError.getMessage());
                return;
            }

            ui.javaPath = configuredJava.isBlank() ? "" : JavaRuntimeUtil.resolveJavaExecutable(configuredJava);
            ui.gameDir = ui.resolveConfiguredGameRootDir(new File(configuredGameDir));
            if (!ui.gameDir.isDirectory() && !ui.gameDir.mkdirs() && !ui.gameDir.isDirectory()) {
                ui.setStatus("游戏目录无效", "无法创建游戏目录：" + ui.gameDir);
                return;
            }
            ui.extraJvmArgs = jvmField.getText().trim();
            ui.maxMemoryMb = configuredMemoryMb;
            ui.gameWidth = configuredWidth;
            ui.gameHeight = configuredHeight;
            ui.gameFullscreen = fullscreenField.isSelected();
            ui.quickServer = serverField.getText().trim();
            ui.processorCount = configuredProcessors;
            ui.closeAfterLaunch = closeAfterLaunchField.isSelected();
            ui.showGameConsole = showConsoleField.isSelected();
            ui.backupOnLaunch = backupOnLaunchField.isSelected();
            ui.backupKeepCount = configuredBackupKeepCount;
            ui.backupIncludeMods = backupIncludeModsField.isSelected();

            DefaultIsolationType configuredIsolationType = switch (
                    isolationPolicyField.getSelectionModel().getSelectedIndex()) {
                case 0 -> DefaultIsolationType.ALWAYS;
                case 2 -> DefaultIsolationType.NEVER;
                default -> DefaultIsolationType.MODDED;
            };

            ui.settingsManager.set(ECLConfig.KEY_JAVA_PATH, ui.javaPath);
            ui.settingsManager.set(ECLConfig.KEY_GAME_DIR, ui.gameDir.getAbsolutePath());
            ui.settingsManager.set(ECLConfig.KEY_JVM_ARGS, ui.extraJvmArgs);
            ui.settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, ui.maxMemoryMb);
            ui.settingsManager.set(ECLConfig.KEY_GAME_WIDTH, ui.gameWidth);
            ui.settingsManager.set(ECLConfig.KEY_GAME_HEIGHT, ui.gameHeight);
            ui.settingsManager.set(ECLConfig.KEY_GAME_FULLSCREEN, ui.gameFullscreen);
            ui.settingsManager.set(ECLConfig.KEY_QUICK_SERVER, ui.quickServer);
            ui.settingsManager.set(ECLConfig.KEY_PROCESSOR_COUNT, ui.processorCount);
            ui.settingsManager.set(ECLConfig.KEY_CLOSE_AFTER_LAUNCH, ui.closeAfterLaunch);
            ui.settingsManager.set(ECLConfig.KEY_SHOW_GAME_CONSOLE, ui.showGameConsole);
            ui.settingsManager.set(ECLConfig.KEY_BACKUP_ON_LAUNCH, ui.backupOnLaunch);
            ui.settingsManager.set(ECLConfig.KEY_BACKUP_KEEP_COUNT, ui.backupKeepCount);
            ui.settingsManager.set(ECLConfig.KEY_BACKUP_INCLUDE_MODS, ui.backupIncludeMods);
            ui.settingsManager.set(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE,
                    configuredIsolationType.name());
            ReleaseChannel modReleaseChannel = switch (
                    modReleaseChannelField.getSelectionModel().getSelectedIndex()) {
                case 0 -> ReleaseChannel.RELEASE_ONLY;
                case 2 -> ReleaseChannel.ALL;
                default -> ReleaseChannel.RELEASE_AND_BETA;
            };
            ui.settingsManager.set(ECLConfig.KEY_MOD_RELEASE_CHANNEL, modReleaseChannel.name());
            ui.settingsManager.setEncrypted(ECLConfig.KEY_CURSEFORGE_API_KEY,
                    curseForgeApiKeyField.getText().trim());
            if (selectedInstanceId != null && !selectedInstanceId.isBlank()) {
                try {
                    DefaultGameRepository repository = ui.gameRepository();
                    switch (instanceDirectoryModeField.getSelectionModel().getSelectedIndex()) {
                        case 1 -> repository.setIsolated(selectedInstanceId);
                        case 2 -> {
                            String customDirectory = customInstanceDirectoryField.getText().trim();
                            if (customDirectory.isBlank()) {
                                ui.setStatus("实例目录无效", "选择自定义目录时必须填写路径。");
                                return;
                            }
                            repository.setCustomRunDirectory(selectedInstanceId,
                                    Path.of(customDirectory));
                        }
                        default -> repository.inheritRunDirectoryPolicy(selectedInstanceId);
                    }
                    Path instanceRoot = ui.resolveVersionInstanceRoot(selectedInstanceId).toPath();
                    InstanceLaunchProfile currentProfile = ui.controller.instanceLaunchProfiles()
                            .load(instanceRoot);
                    InstanceLaunchProfile updatedProfile = new InstanceLaunchProfile(
                            currentProfile.schemaVersion(),
                            ui.javaPath.isBlank() ? InstanceLaunchProfile.JavaMode.AUTO
                                    : InstanceLaunchProfile.JavaMode.CUSTOM,
                            ui.javaPath,
                            currentProfile.performancePreset(),
                            ui.maxMemoryMb == ECLConfig.AUTO_MEMORY_MB
                                    ? InstanceLaunchProfile.MemoryMode.AUTO
                                    : InstanceLaunchProfile.MemoryMode.CUSTOM,
                            ui.maxMemoryMb,
                            currentProfile.generatedJvmOptions(),
                            configuredJvmArguments,
                            currentProfile.autoRepair(),
                            currentProfile.backupPolicyId());
                    ui.controller.instanceLaunchProfiles().save(instanceRoot, updatedProfile);
                } catch (Exception directoryError) {
                    ui.setStatus("实例设置保存失败", directoryError.getMessage());
                    return;
                }
            }
            if (!ui.settingsManager.save()) {
                ui.setStatus("保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
                return;
            }

            ui.updateRuntimeSummary();
            ui.setStatus("设置已保存", "新的运行环境、版本隔离与 Modrinth 发布通道已经生效。");
            dialog.close();
        });

        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().addAll("app-button", "ghost-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(12, saveBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        dialogRoot.getChildren().add(buttonBar);

        Scene scene = new Scene(ui.createWheelScrollPane(dialogRoot), 760, 650);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        ui.applyThemeToScene(scene, ui.settingsManager.get(ECLConfig.KEY_THEME));
        dialog.show();
    
    }

    private InstanceLaunchProfile loadExistingProfile(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }
        Path instanceRoot = ui.resolveVersionInstanceRoot(instanceId).toPath();
        Path profileFile = ui.controller.instanceLaunchProfiles().profileFile(instanceRoot);
        if (!java.nio.file.Files.isRegularFile(profileFile)) {
            return null;
        }
        try {
            return ui.controller.instanceLaunchProfiles().load(instanceRoot);
        } catch (IOException invalidProfile) {
            LauncherUI.LOGGER.warn("Cannot load launch profile for {}", instanceId, invalidProfile);
            return null;
        }
    }
}
