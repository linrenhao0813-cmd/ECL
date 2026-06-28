package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineAuth;
import com.ecl.auth.YggdrasilAuth;
import com.ecl.config.SettingsManager;
import com.ecl.download.GameDownloader;
import com.ecl.download.ModrinthDownloader;
import com.ecl.launcher.CrashAnalyzer;
import com.ecl.launcher.GameLauncher;
import com.ecl.launcher.VersionManager;
import com.ecl.util.JavaRuntimeUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class LauncherUI extends javafx.application.Application {
    private static final String AUTH_OFFLINE = "离线登录";
    private static final String AUTH_MICROSOFT = "微软登录 (Microsoft)";
    private static final String AUTH_YGGDRASIL = "外置登录 (Yggdrasil)";
    private static final String MODRINTH_DISCOVER_URL = "https://modrinth.com/discover/";
    private static final int MAX_CAPTURED_GAME_LOG_CHARS = 80000;

    private VersionManager versionManager;
    private GameDownloader downloader;
    private ModrinthDownloader modrinthDownloader;
    private GameLauncher gameLauncher;
    private SettingsManager settingsManager;
    private Stage primaryStage;

    private ComboBox<String> versionCombo;
    private ComboBox<VersionManager.VersionCategory> versionTypeCombo;
    private TextField usernameField;
    private PasswordField passwordField;
    private Slider memorySlider;
    private Label memoryLabel;
    private ProgressBar downloadProgress;
    private Label statusLabel;
    private Label detailLabel;
    private Button launchBtn;
    private Button refreshBtn;
    private Button settingsBtn;
    private ComboBox<String> authTypeCombo;
    private TextField yggdrasilServerField;
    private Label serverLabel;
    private Label passwordLabel;

    private Label authSummaryLabel;
    private Label authHintLabel;
    private Label javaSummaryLabel;
    private Label gameDirSummaryLabel;
    private Label versionSummaryLabel;
    private Label memorySummaryLabel;
    private Label jvmArgsSummaryLabel;
    private Label runtimeBadgeLabel;
    private List<ContentTarget> contentTargets;
    private ContentTarget selectedContentTarget;

    private String javaPath;
    private File gameDir;
    private String extraJvmArgs;
    private final Map<ProgressBar, Timeline> progressAnimations = new HashMap<>();

    private static class ContentTarget {
        private final String title;
        private final String subtitle;
        private final String initial;
        private final String projectType;
        private final String defaultLoader;
        private final String[] loaders;
        private final String[] allowedExtensions;
        private final boolean downloadDependencies;
        private final String searchHint;
        private final Supplier<File> folderSupplier;

        private ContentTarget(String title, String subtitle, String initial, String projectType,
                              String defaultLoader, String[] loaders, String[] allowedExtensions,
                              boolean downloadDependencies, String searchHint, Supplier<File> folderSupplier) {
            this.title = title;
            this.subtitle = subtitle;
            this.initial = initial;
            this.projectType = projectType;
            this.defaultLoader = defaultLoader;
            this.loaders = loaders;
            this.allowedExtensions = allowedExtensions;
            this.downloadDependencies = downloadDependencies;
            this.searchHint = searchHint;
            this.folderSupplier = folderSupplier;
        }

        private boolean usesLoader() {
            return loaders != null && loaders.length > 0;
        }
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        ECLConfig.ensureDirs();

        settingsManager = new SettingsManager();
        settingsManager.load();

        versionManager = new VersionManager();
        downloader = new GameDownloader();
        modrinthDownloader = new ModrinthDownloader();
        gameLauncher = new GameLauncher();

        javaPath = JavaRuntimeUtil.resolveJavaExecutable(settingsManager.getString("javaPath", ""));
        gameDir = new File(settingsManager.getString("gameDir", ECLConfig.getGameDir().getAbsolutePath()));
        extraJvmArgs = settingsManager.getString("jvmArgs", "");
        contentTargets = createContentTargets();

        BorderPane root = createRoot();
        Scene scene = new Scene(root, 1020, 660);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        primaryStage.setTitle("ECL - Minecraft Launcher");
        applyWindowIcon(primaryStage);
        primaryStage.setMinWidth(920);
        primaryStage.setMinHeight(620);
        primaryStage.setScene(scene);
        primaryStage.show();

        updateAuthFields();
        updateRuntimeSummary();
        setStatus("就绪", "首次运行会自动拉取版本清单，未下载的版本会在启动前补齐资源。");
        refreshVersions();
    }

    private BorderPane createRoot() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setPadding(new Insets(18));

        root.setTop(createHeader());
        BorderPane.setMargin(root.getTop(), new Insets(0, 0, 18, 0));

        HBox body = new HBox(16, createOverviewPane(), createLaunchPane());
        body.getStyleClass().add("main-body");
        body.setFillHeight(false);
        root.setCenter(createWheelScrollPane(body));
        return root;
    }

    private HBox createHeader() {
        HBox header = new HBox(14);
        header.getStyleClass().add("header-bar");
        header.setAlignment(Pos.CENTER_LEFT);

        Label logo = new Label("E");
        logo.getStyleClass().add("logo-tile");

        VBox titleBox = new VBox(2);
        Label title = new Label(ECLConfig.LAUNCHER_NAME);
        title.getStyleClass().add("header-title");
        Label subtitle = new Label("轻量 Minecraft 启动器");
        subtitle.getStyleClass().add("header-subtitle");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        runtimeBadgeLabel = createBadge("Java 运行时检查中", "badge");
        Label versionBadge = createBadge("v" + ECLConfig.LAUNCHER_VERSION, "badge-neutral");

        header.getChildren().addAll(logo, titleBox, spacer, runtimeBadgeLabel, versionBadge);
        return header;
    }

    private List<ContentTarget> createContentTargets() {
        return List.of(
                new ContentTarget(
                        "模组", "Fabric / Forge / NeoForge / Quilt", "M", "mod",
                        "fabric", new String[]{"fabric", "forge", "neoforge", "quilt"}, new String[]{".jar"},
                        true, "搜索模组名称，例如 sodium、journeymap", () -> resolveModsDir(getSelectedVersion())),
                new ContentTarget(
                        "光影包", "Iris / OptiFine shaderpacks", "S", "shader",
                        null, new String[0], new String[]{".zip"},
                        false, "搜索光影名称，例如 complementary、bsl", () -> new File(getActiveGameDir(), "shaderpacks")),
                new ContentTarget(
                        "材质包", "resourcepacks 目录资源包", "R", "resourcepack",
                        null, new String[0], new String[]{".zip"},
                        false, "搜索材质包名称，例如 fresh animations、faithful", () -> new File(getActiveGameDir(), "resourcepacks")),
                new ContentTarget(
                        "整合包", "完整玩法包与客户端预设", "P", "modpack",
                        "fabric", new String[]{"fabric", "forge", "neoforge", "quilt"}, new String[]{".mrpack"},
                        false, "搜索整合包名称，例如 fabulously optimized", () -> new File(ECLConfig.getBaseDir(), "modpacks"))
        );
    }

    private VBox createOverviewPane() {
        VBox pane = new VBox(14);
        pane.setPrefWidth(315);
        pane.setMaxWidth(335);

        statusLabel = new Label("就绪");
        statusLabel.getStyleClass().add("status-title");
        detailLabel = new Label();
        detailLabel.getStyleClass().add("status-detail");
        detailLabel.setWrapText(true);

        downloadProgress = new ProgressBar(0);
        downloadProgress.getStyleClass().add("download-progress");
        downloadProgress.setMaxWidth(Double.MAX_VALUE);
        downloadProgress.setVisible(false);
        downloadProgress.managedProperty().bind(downloadProgress.visibleProperty());

        VBox statusCard = createSurface(
                "当前状态",
                "下载、认证和启动进度会显示在这里",
                statusLabel,
                detailLabel,
                downloadProgress
        );

        javaSummaryLabel = createValueLabel();
        gameDirSummaryLabel = createValueLabel();
        versionSummaryLabel = createValueLabel();
        memorySummaryLabel = createValueLabel();
        jvmArgsSummaryLabel = createValueLabel();

        VBox runtimeCard = createSurface(
                "运行环境",
                "启动器会根据设置同步更新这些信息",
                createInfoRow("Java 运行时", javaSummaryLabel),
                createInfoRow("游戏目录", gameDirSummaryLabel),
                createInfoRow("可用版本", versionSummaryLabel),
                createInfoRow("内存分配", memorySummaryLabel),
                createInfoRow("额外参数", jvmArgsSummaryLabel)
        );

        authSummaryLabel = createValueLabel();
        authHintLabel = new Label();
        authHintLabel.getStyleClass().add("status-detail");
        authHintLabel.setWrapText(true);

        VBox authCard = createSurface(
                "账号与模式",
                "离线模式可直接进入游戏，外置登录适配第三方认证服务器",
                createInfoRow("当前方式", authSummaryLabel),
                authHintLabel
        );

        Label footer = new Label("数据目录: " + ECLConfig.getBaseDir().getAbsolutePath());
        footer.getStyleClass().add("footer-text");

        pane.getChildren().addAll(statusCard, runtimeCard, authCard, footer);
        return pane;
    }

    private VBox createLaunchPane() {
        VBox pane = new VBox(14);
        pane.getStyleClass().add("launch-pane");
        HBox.setHgrow(pane, Priority.ALWAYS);
        VBox launchCard = createSurface("启动设置", "选择版本、登录方式与内存分配", createForm(), createActionBar());
        launchCard.getStyleClass().add("launch-surface");
        pane.getChildren().add(launchCard);
        pane.getChildren().add(createContentPane());
        return pane;
    }

    private VBox createContentPane() {
        GridPane contentRows = new GridPane();
        contentRows.getStyleClass().add("content-grid");
        contentRows.setHgap(10);
        contentRows.setVgap(10);
        for (ContentTarget target : contentTargets) {
            contentRows.getChildren().add(createContentRow(target));
        }
        for (int i = 0; i < contentRows.getChildren().size(); i++) {
            Node row = contentRows.getChildren().get(i);
            GridPane.setConstraints(row, i % 2, i / 2);
            GridPane.setHgrow(row, Priority.ALWAYS);
            if (row instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
        }
        return createSurface("内容下载", "下载后可直接打开本地目录放入对应文件", contentRows);
    }

    private VBox createContentRow(ContentTarget target) {
        Label icon = new Label(target.initial);
        icon.getStyleClass().add("content-icon");

        VBox textBox = new VBox(3);
        Label titleLabel = new Label(target.title);
        titleLabel.getStyleClass().add("content-title");
        Label subtitleLabel = new Label(target.subtitle);
        subtitleLabel.getStyleClass().add("content-subtitle");
        subtitleLabel.setWrapText(true);
        textBox.getChildren().addAll(titleLabel, subtitleLabel);

        Button downloadBtn = new Button("下载");
        downloadBtn.getStyleClass().addAll("app-button", "secondary-button", "compact-button");
        downloadBtn.setText("内置下载");
        downloadBtn.setTooltip(new Tooltip("在启动器内搜索、下载并导入" + target.title));
        downloadBtn.setOnAction(e -> showContentDownloadDialog(target));

        Button folderBtn = new Button("目录");
        folderBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        folderBtn.setTooltip(new Tooltip("打开本地" + target.title + "目录"));
        folderBtn.setOnAction(e -> openLocalFolder(target.folderSupplier.get(), target.title + "目录"));

        HBox actions = new HBox(8, downloadBtn, folderBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox row = new VBox(10, icon, textBox, actions);
        row.getStyleClass().add("content-row");
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private GridPane createForm() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);

        int savedMem = settingsManager.getInt("maxMemory", 2048);

        authTypeCombo = new ComboBox<>();
        authTypeCombo.getItems().addAll(AUTH_OFFLINE, AUTH_MICROSOFT, AUTH_YGGDRASIL);
        authTypeCombo.setValue(AUTH_OFFLINE);
        authTypeCombo.setOnAction(e -> updateAuthFields());
        applyFieldStyle(authTypeCombo);

        yggdrasilServerField = new TextField(settingsManager.getString("yggdrasilServer", "https://littleskin.cn/api/yggdrasil/"));
        yggdrasilServerField.setPromptText("输入 Yggdrasil 认证地址");
        applyFieldStyle(yggdrasilServerField);

        usernameField = new TextField("Player");
        usernameField.setPromptText("输入玩家名称");
        applyFieldStyle(usernameField);

        passwordField = new PasswordField();
        passwordField.setPromptText("外置登录时需要");
        applyFieldStyle(passwordField);

        versionCombo = new ComboBox<>();
        versionCombo.setPromptText("选择游戏版本");
        versionCombo.setVisibleRowCount(14);
        applyFieldStyle(versionCombo);

        versionTypeCombo = new ComboBox<>();
        versionTypeCombo.getItems().addAll(VersionManager.VersionCategory.values());
        versionTypeCombo.setValue(parseVersionCategory(settingsManager.getString("versionCategory2", VersionManager.VersionCategory.FEATURED.name())));
        versionTypeCombo.setPrefWidth(176);
        versionTypeCombo.setTooltip(new Tooltip("默认显示正式版、预览版/快照和愚人节版，也可以只看某一类"));
        versionTypeCombo.setOnAction(e -> {
            settingsManager.setString("versionCategory2", getSelectedVersionCategory().name());
            settingsManager.save();
            refreshVersions();
        });
        applyFieldStyle(versionTypeCombo);

        memorySlider = new Slider(512, 16384, savedMem);
        memorySlider.setMajorTickUnit(2048);
        memorySlider.setMinorTickCount(3);
        memorySlider.setBlockIncrement(512);
        memorySlider.setShowTickLabels(true);
        memorySlider.setShowTickMarks(true);
        memorySlider.setSnapToTicks(true);
        memorySlider.getStyleClass().add("memory-slider");
        memorySlider.valueProperty().addListener((obs, oldVal, newVal) -> updateMemoryDisplay(newVal.doubleValue()));

        memoryLabel = createBadge(savedMem + " MB", "badge-neutral");
        memoryLabel.setMinWidth(92);
        updateMemoryDisplay(savedMem);

        HBox memoryBox = new HBox(12, memorySlider, memoryLabel);
        memoryBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(memorySlider, Priority.ALWAYS);

        HBox versionBox = new HBox(10, versionTypeCombo, versionCombo);
        versionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(versionCombo, Priority.ALWAYS);

        int row = 0;
        grid.add(new Label("登录方式:"), 0, row);
        grid.add(authTypeCombo, 1, row++);

        serverLabel = new Label("外置服务器:");
        grid.add(serverLabel, 0, row);
        grid.add(yggdrasilServerField, 1, row++);

        grid.add(new Label("用户名:"), 0, row);
        grid.add(usernameField, 1, row++);

        passwordLabel = new Label("密码:");
        grid.add(passwordLabel, 0, row);
        grid.add(passwordField, 1, row++);

        grid.add(new Label("游戏版本:"), 0, row);
        grid.add(versionBox, 1, row++);

        grid.add(new Label("最大内存:"), 0, row);
        grid.add(memoryBox, 1, row);

        for (Node node : grid.getChildren()) {
            if (node instanceof Label label) {
                label.getStyleClass().add("field-label");
            }
        }

        return grid;
    }

    private HBox createActionBar() {
        launchBtn = new Button("启动游戏");
        launchBtn.getStyleClass().addAll("app-button", "primary-button");
        launchBtn.setDefaultButton(true);
        launchBtn.setOnAction(e -> launchGame());

        refreshBtn = new Button("刷新版本");
        refreshBtn.getStyleClass().addAll("app-button", "secondary-button");
        refreshBtn.setOnAction(e -> refreshVersions());

        settingsBtn = new Button("高级设置");
        settingsBtn.getStyleClass().addAll("app-button", "ghost-button");
        settingsBtn.setOnAction(e -> showSettingsDialog());

        HBox buttonBar = new HBox(12, launchBtn, refreshBtn, settingsBtn);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        return buttonBar;
    }

    private void updateAuthFields() {
        String authType = authTypeCombo.getValue();
        boolean microsoft = AUTH_MICROSOFT.equals(authType);
        boolean yggdrasil = AUTH_YGGDRASIL.equals(authType);

        usernameField.setDisable(microsoft);
        setFieldVisible(serverLabel, yggdrasil);
        setFieldVisible(yggdrasilServerField, yggdrasil);
        setFieldVisible(passwordLabel, yggdrasil);
        setFieldVisible(passwordField, yggdrasil);

        if (microsoft) {
            authSummaryLabel.setText("微软登录预留入口");
            authHintLabel.setText("当前版本还没有接入 Microsoft OAuth 流程，先使用离线登录或外置登录。 ");
        } else if (yggdrasil) {
            authSummaryLabel.setText("外置登录 / Yggdrasil");
            authHintLabel.setText("兼容 LittleSkin、Blessing Skin 和其他 authlib-injector 服务端。 ");
        } else {
            authSummaryLabel.setText("离线登录");
            authHintLabel.setText("会为当前用户名生成本地 UUID，适合单机和快速调试。 ");
        }

        updateRuntimeSummary();
    }

    private void updateRuntimeSummary() {
        setSummaryText(javaSummaryLabel, javaPath, 64);
        setSummaryText(gameDirSummaryLabel, gameDir == null ? ECLConfig.getGameDir().getAbsolutePath() : gameDir.getAbsolutePath(), 68);

        int count = versionCombo == null ? 0 : versionCombo.getItems().size();
        String selectedVersion = versionCombo == null ? null : versionCombo.getValue();
        String categoryLabel = getSelectedVersionCategory().getLabel();
        String versionSummary = count == 0 ? "等待拉取" + categoryLabel + "列表" : count + " 个" + categoryLabel + (selectedVersion == null ? "" : "，当前 " + selectedVersion);
        versionSummaryLabel.setText(versionSummary);

        if (memorySummaryLabel != null) {
            memorySummaryLabel.setText(memoryLabel == null ? "2048 MB" : memoryLabel.getText());
        }
        jvmArgsSummaryLabel.setText(extraJvmArgs == null || extraJvmArgs.isBlank() ? "未设置" : abbreviate(extraJvmArgs, 68));
        jvmArgsSummaryLabel.setTooltip(extraJvmArgs == null || extraJvmArgs.isBlank() ? null : new Tooltip(extraJvmArgs));


        if (JavaRuntimeUtil.isUsableJavaPath(javaPath)) {
            runtimeBadgeLabel.setText("Java 已就绪");
        } else {
            runtimeBadgeLabel.setText("未发现可用 Java");
        }
    }

    private void setSummaryText(Label label, String value, int maxLength) {
        String display = (value == null || value.isBlank()) ? "未设置" : abbreviate(value, maxLength);
        label.setText(display);
        label.setTooltip((value == null || value.isBlank()) ? null : new Tooltip(value));
    }

    private void setStatus(String title, String detail) {
        statusLabel.setText(title);
        detailLabel.setText(detail == null || detail.isBlank() ? "" : detail.trim());
    }

    private void startProgressAnimation(ProgressBar progressBar) {
        if (progressBar == null) {
            return;
        }

        stopProgressAnimation(progressBar, false);
        progressBar.setVisible(true);
        progressBar.getProperties().put("pulse-step", 0);

        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(260), e -> advanceProgressPulse(progressBar)));
        timeline.setCycleCount(Timeline.INDEFINITE);
        progressAnimations.put(progressBar, timeline);
        timeline.play();
        advanceProgressPulse(progressBar);
    }

    private void updateProgress(ProgressBar progressBar, long downloaded, long total) {
        if (progressBar == null) {
            return;
        }

        progressBar.setVisible(true);
        if (total > 0) {
            progressBar.setProgress(clamp((double) downloaded / total, 0, 1));
        } else {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
    }

    private void stopProgressAnimation(ProgressBar progressBar, boolean hide) {
        if (progressBar == null) {
            return;
        }

        Timeline timeline = progressAnimations.remove(progressBar);
        if (timeline != null) {
            timeline.stop();
        }
        progressBar.getStyleClass().removeAll("progress-pulse-a", "progress-pulse-b", "progress-pulse-c");
        progressBar.getProperties().remove("pulse-step");
        if (hide) {
            progressBar.setVisible(false);
        }
    }

    private void advanceProgressPulse(ProgressBar progressBar) {
        progressBar.getStyleClass().removeAll("progress-pulse-a", "progress-pulse-b", "progress-pulse-c");
        int step = ((Number) progressBar.getProperties().getOrDefault("pulse-step", 0)).intValue();
        progressBar.getStyleClass().add(switch (step) {
            case 0 -> "progress-pulse-a";
            case 1 -> "progress-pulse-b";
            default -> "progress-pulse-c";
        });
        progressBar.getProperties().put("pulse-step", (step + 1) % 3);
    }

    private void refreshVersions() {
        VersionManager.VersionCategory category = getSelectedVersionCategory();
        String categoryLabel = category.getLabel();
        refreshBtn.setDisable(true);
        versionCombo.setDisable(true);
        versionTypeCombo.setDisable(true);
        setStatus("正在获取版本列表...", "正在加载 " + categoryLabel + "，失败时会回退到本地缓存。 ");

        runAsync("ecl-refresh-versions", () -> {
            try {
                versionManager.refresh();
                List<String> versions = versionManager.getVersions(category);
                Platform.runLater(() -> {
                    String current = versionCombo.getValue();
                    versionCombo.getItems().setAll(versions);
                    if (current != null && versions.contains(current)) {
                        versionCombo.getSelectionModel().select(current);
                    } else if (!versions.isEmpty()) {
                        versionCombo.getSelectionModel().select(0);
                    }
                    setStatus("版本列表已更新", versions.isEmpty() ? "没有发现可用的" + categoryLabel + "。" : "已载入 " + versions.size() + " 个" + categoryLabel + "。 ");
                    refreshBtn.setDisable(false);
                    versionCombo.setDisable(false);
                    versionTypeCombo.setDisable(false);
                    updateRuntimeSummary();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("获取版本列表失败", cleanMessage(e));
                    refreshBtn.setDisable(false);
                    versionCombo.setDisable(false);
                    versionTypeCombo.setDisable(false);
                    updateRuntimeSummary();
                });
            }
        });
    }

    private VersionManager.VersionCategory getSelectedVersionCategory() {
        if (versionTypeCombo == null || versionTypeCombo.getValue() == null) {
            return VersionManager.VersionCategory.FEATURED;
        }
        return versionTypeCombo.getValue();
    }

    private VersionManager.VersionCategory parseVersionCategory(String value) {
        try {
            return VersionManager.VersionCategory.valueOf(value);
        } catch (Exception ignored) {
            return VersionManager.VersionCategory.FEATURED;
        }
    }

    private void launchGame() {
        String selectedVersion = versionCombo.getValue();
        if (selectedVersion == null || selectedVersion.isBlank()) {
            setStatus("请选择游戏版本", "先刷新并选择一个可启动的 Minecraft 版本。 ");
            return;
        }

        String configuredJavaPath = javaPath == null ? "" : javaPath.trim();
        if (!configuredJavaPath.isBlank() && !JavaRuntimeUtil.isUsableJavaPath(configuredJavaPath)) {
            setStatus("Java 路径无效", "高级设置里的 Java 路径不可用，请重新选择 java.exe 或 JDK 根目录。 ");
            return;
        }

        if (configuredJavaPath.isBlank()) {
            javaPath = JavaRuntimeUtil.detectSystemJavaExecutable();
        }
        if (!JavaRuntimeUtil.isUsableJavaPath(javaPath)) {
            setStatus("未找到可用 Java", "请在高级设置里指定可执行的 Java 17+ 运行时。 ");
            return;
        }

        int memory = (int) Math.round(memorySlider.getValue());
        settingsManager.setInt("maxMemory", memory);
        settingsManager.setString("javaPath", javaPath);
        settingsManager.setString("gameDir", gameDir.getAbsolutePath());
        settingsManager.setString("jvmArgs", extraJvmArgs == null ? "" : extraJvmArgs);
        if (AUTH_YGGDRASIL.equals(authTypeCombo.getValue())) {
            settingsManager.setString("yggdrasilServer", yggdrasilServerField.getText().trim());
        }
        settingsManager.save();
        updateRuntimeSummary();

        if (!versionManager.isVersionDownloaded(selectedVersion)) {
            downloadAndLaunch(selectedVersion);
        } else {
            startGame(selectedVersion);
        }
    }

    private void downloadAndLaunch(String version) {
        String url = versionManager.getVersionUrl(version);
        if (url == null || url.isBlank()) {
            setStatus("找不到版本下载地址", "请先刷新版本列表，或者检查本地缓存是否完整。 ");
            return;
        }

        setControlsBusy(true);
        downloadProgress.setProgress(0);
        startProgressAnimation(downloadProgress);
        setStatus("正在准备下载", version + " 首次启动需要补齐客户端、依赖库和资源文件。 ");

        downloader.setListener(new GameDownloader.DownloadListener() {
            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> setStatus("下载中", message));
            }

            @Override
            public void onProgress(long downloaded, long total) {
                Platform.runLater(() -> {
                    updateProgress(downloadProgress, downloaded, total);
                    detailLabel.setText("当前进度: " + formatBytes(downloaded) + (total > 0 ? " / " + formatBytes(total) : ""));
                });
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> {
                    setStatus("下载失败", message);
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                });
            }

            @Override
            public void onComplete() {
                Platform.runLater(() -> {
                    downloadProgress.setProgress(1);
                    stopProgressAnimation(downloadProgress, true);
                    setStatus("下载完成", version + " 的必需文件已经就绪，准备启动游戏。 ");
                    startGame(version);
                });
            }
        });

        downloader.downloadVersion(version, url);
    }

    private void startGame(String version) {
        String authType = authTypeCombo.getValue();
        String server = yggdrasilServerField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        int memory = (int) Math.round(memorySlider.getValue());

        setControlsBusy(true);
        stopProgressAnimation(downloadProgress, true);
        setStatus("正在启动游戏...", "准备认证、拼接类路径并拉起客户端进程。 ");

        runAsync("ecl-launch-game", () -> {
            try {
                AuthProvider auth = buildAuthProvider(authType, server, username, password);
                gameLauncher.setAuth(auth);
                gameLauncher.setVersion(version);
                gameLauncher.setMaxMemory(memory);
                gameLauncher.setGameDir(gameDir);
                gameLauncher.setJvmArgs(extraJvmArgs == null ? "" : extraJvmArgs);
                gameLauncher.setJavaPath(javaPath);
                long launchStartedAt = System.currentTimeMillis();
                Process process = gameLauncher.launch();
                monitorGameProcess(process, version, launchStartedAt);

                Platform.runLater(() -> {
                    setStatus("游戏已启动", version + " 正在运行。若异常退出，启动器会自动分析错误。 ");
                    setControlsBusy(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, getActiveGameDir());
                    setStatus("启动失败", report.getTitle());
                    showGameErrorDialog(report);
                    setControlsBusy(false);
                });
            }
        });
    }

    private void monitorGameProcess(Process process, String version, long launchStartedAt) {
        runAsync("ecl-monitor-game-" + version, () -> {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendCapturedLog(output, line);
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    Platform.runLater(() -> setStatus("游戏已正常退出", version + " 退出码 0。"));
                    return;
                }

                CrashAnalyzer.Report report = CrashAnalyzer.analyzeGameExit(version, exitCode, output.toString(), getActiveGameDir(), launchStartedAt);
                Platform.runLater(() -> {
                    setStatus("游戏异常退出", report.getTitle());
                    showGameErrorDialog(report);
                });
            } catch (Exception e) {
                CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, getActiveGameDir());
                Platform.runLater(() -> {
                    setStatus("错误分析失败", report.getTitle());
                    showGameErrorDialog(report);
                });
            }
        });
    }

    private void appendCapturedLog(StringBuilder output, String line) {
        output.append(line).append(System.lineSeparator());
        if (output.length() > MAX_CAPTURED_GAME_LOG_CHARS) {
            output.delete(0, output.length() - MAX_CAPTURED_GAME_LOG_CHARS);
        }
    }

    private void showGameErrorDialog(CrashAnalyzer.Report report) {
        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("启动错误诊断");
        applyWindowIcon(dialog);

        Label title = new Label(report.getTitle());
        title.getStyleClass().add("status-title");
        title.setWrapText(true);

        Label explanation = new Label(report.getExplanation());
        explanation.getStyleClass().add("status-detail");
        explanation.setWrapText(true);

        Label suggestions = new Label(toBulletText(report.getSuggestions()));
        suggestions.getStyleClass().add("diagnostic-text");
        suggestions.setWrapText(true);

        TextArea evidenceArea = new TextArea(toBulletText(report.getEvidence()));
        evidenceArea.getStyleClass().add("diagnostic-log");
        evidenceArea.setEditable(false);
        evidenceArea.setWrapText(true);
        evidenceArea.setPrefRowCount(10);

        Button openCrashDirBtn = new Button("打开崩溃报告");
        openCrashDirBtn.getStyleClass().addAll("app-button", "secondary-button");
        openCrashDirBtn.setDisable(report.getCrashReportFile() == null);
        openCrashDirBtn.setOnAction(e -> {
            File crashFile = report.getCrashReportFile();
            if (crashFile != null && crashFile.getParentFile() != null) {
                openLocalFolder(crashFile.getParentFile(), "崩溃报告目录");
            }
        });

        Button openModsBtn = new Button("打开 mods");
        openModsBtn.getStyleClass().addAll("app-button", "secondary-button");
        openModsBtn.setOnAction(e -> openLocalFolder(resolveModsDir(getSelectedVersion()), "模组目录"));

        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().addAll("app-button", "ghost-button");
        closeBtn.setOnAction(e -> dialog.close());

        HBox actions = new HBox(10, openCrashDirBtn, openModsBtn, closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14,
                createSurface("中文解释", null, title, explanation),
                createSurface("修复建议", null, suggestions),
                createSurface("关键日志", "下面是启动器从英文报错中提取的关键行", evidenceArea),
                actions
        );
        root.getStyleClass().add("root-pane");
        root.setPadding(new Insets(18));

        Scene scene = new Scene(createWheelScrollPane(root), 760, 620);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.show();
    }

    private String toBulletText(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "未提取到关键日志。";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                sb.append("- ").append(item.trim()).append(System.lineSeparator());
            }
        }
        return sb.toString().trim();
    }

    private AuthProvider buildAuthProvider(String authType, String server, String username, String password) {
        if (AUTH_MICROSOFT.equals(authType)) {
            throw new UnsupportedOperationException("暂不支持微软登录，请先使用离线登录或外置登录。");
        }

        if (AUTH_YGGDRASIL.equals(authType)) {
            if (server.isBlank() || username.isBlank() || password.isBlank()) {
                throw new IllegalArgumentException("请填写完整的外置登录信息。");
            }
            YggdrasilAuth yggdrasilAuth = new YggdrasilAuth(server);
            yggdrasilAuth.setCredentials(username, password);
            yggdrasilAuth.login();
            return yggdrasilAuth;
        }

        String offlineName = username.isBlank() ? "Player" : username;
        return new OfflineAuth(offlineName);
    }
    private void setControlsBusy(boolean busy) {
        launchBtn.setDisable(busy);
        refreshBtn.setDisable(busy);
        settingsBtn.setDisable(busy);
        versionCombo.setDisable(busy);
        versionTypeCombo.setDisable(busy);
        authTypeCombo.setDisable(busy);
        usernameField.setDisable(busy || AUTH_MICROSOFT.equals(authTypeCombo.getValue()));
        yggdrasilServerField.setDisable(busy);
        passwordField.setDisable(busy);
        memorySlider.setDisable(busy);
    }

    private void showContentDownloadDialog(ContentTarget target) {
        String gameVersion = getSelectedVersion();
        if (gameVersion == null || gameVersion.isBlank()) {
            setStatus("请选择游戏版本", "下载" + target.title + "前先选择目标 Minecraft 版本。");
            return;
        }

        File importDir = target.folderSupplier.get();

        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("下载" + target.title + " - " + gameVersion);
        applyWindowIcon(dialog);

        TextField searchField = new TextField();
        searchField.setPromptText(target.searchHint);
        applyFieldStyle(searchField);

        ComboBox<String> loaderCombo = new ComboBox<>();
        if (target.usesLoader()) {
            loaderCombo.getItems().addAll(target.loaders);
            loaderCombo.setValue(target.defaultLoader);
        }
        applyFieldStyle(loaderCombo);
        setFieldVisible(loaderCombo, target.usesLoader());

        Button searchBtn = new Button("搜索");
        searchBtn.getStyleClass().addAll("app-button", "secondary-button");

        HBox searchBar = new HBox(10, searchField, loaderCombo, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        loaderCombo.setPrefWidth(132);

        ListView<ModrinthDownloader.Project> resultList = new ListView<>();
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPrefHeight(220);

        Label descriptionLabel = new Label("点击搜索结果会在这里显示简介");
        descriptionLabel.getStyleClass().add("status-detail");
        descriptionLabel.setWrapText(true);

        Label targetLabel = new Label("目标版本: " + gameVersion + "    导入目录: " + importDir.getAbsolutePath());
        targetLabel.getStyleClass().add("footer-text");
        targetLabel.setWrapText(true);

        ProgressBar modProgress = new ProgressBar(0);
        modProgress.getStyleClass().add("download-progress");
        modProgress.setMaxWidth(Double.MAX_VALUE);
        modProgress.setVisible(false);
        modProgress.managedProperty().bind(modProgress.visibleProperty());

        Label dialogStatus = new Label("输入关键词后搜索 Modrinth");
        dialogStatus.getStyleClass().add("status-detail");
        dialogStatus.setWrapText(true);

        Button importBtn = new Button("下载并导入");
        importBtn.getStyleClass().addAll("app-button", "primary-button");
        importBtn.setDisable(true);

        Button folderBtn = new Button("打开目录");
        folderBtn.getStyleClass().addAll("app-button", "secondary-button");
        folderBtn.setOnAction(e -> openLocalFolder(importDir, target.title + "目录"));

        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().addAll("app-button", "ghost-button");
        closeBtn.setOnAction(e -> dialog.close());

        resultList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            importBtn.setDisable(selected == null);
            descriptionLabel.setText(selected == null ? "点击搜索结果会在这里显示简介" : selected.getDescription());
        });

        searchBtn.setOnAction(e -> searchModrinthContent(target, searchField, loaderCombo, resultList, dialogStatus, searchBtn, importBtn, gameVersion));
        searchField.setOnAction(e -> searchBtn.fire());

        importBtn.setOnAction(e -> downloadSelectedContent(
                target,
                resultList.getSelectionModel().getSelectedItem(),
                target.usesLoader() ? loaderCombo.getValue() : null,
                gameVersion,
                importDir,
                dialogStatus,
                modProgress,
                searchBtn,
                importBtn
        ));

        HBox actions = new HBox(10, importBtn, folderBtn, closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox dialogRoot = new VBox(14,
                createSurface(target.title + "下载", "按当前游戏版本筛选兼容文件，下载完成后自动导入对应目录", searchBar, targetLabel),
                createSurface("搜索结果与简介", "选择一条结果即可查看简介", resultList, descriptionLabel),
                createSurface("导入进度", null, dialogStatus, modProgress, actions)
        );
        dialogRoot.getStyleClass().add("root-pane");
        dialogRoot.setPadding(new Insets(18));

        Scene scene = new Scene(createWheelScrollPane(dialogRoot), 780, 650);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.show();
    }

    private void searchModrinthContent(ContentTarget target, TextField searchField, ComboBox<String> loaderCombo,
                                       ListView<ModrinthDownloader.Project> resultList, Label dialogStatus,
                                       Button searchBtn, Button importBtn, String gameVersion) {
        String query = searchField.getText();
        String loader = target.usesLoader() ? loaderCombo.getValue() : null;
        String loaderLabel = loader == null ? "" : " / " + loader;

        searchBtn.setDisable(true);
        importBtn.setDisable(true);
        resultList.getItems().clear();
        dialogStatus.setText("正在搜索 " + gameVersion + loaderLabel + " 的兼容" + target.title + "...");
        setStatus("正在搜索" + target.title, query == null || query.isBlank() ? "等待关键词" : query.trim());

        runAsync("ecl-search-modrinth-" + target.projectType, () -> {
            try {
                List<ModrinthDownloader.Project> projects = modrinthDownloader.searchProjects(query, gameVersion, target.projectType, loader, 12);
                Platform.runLater(() -> {
                    resultList.getItems().setAll(projects);
                    if (!projects.isEmpty()) {
                        resultList.getSelectionModel().select(0);
                    }
                    dialogStatus.setText(projects.isEmpty()
                            ? "没有找到兼容 " + gameVersion + loaderLabel + " 的" + target.title + "。"
                            : "找到 " + projects.size() + " 个结果，选择一个后下载。");
                    setStatus(target.title + "搜索完成", projects.isEmpty() ? "没有找到匹配结果。" : "找到 " + projects.size() + " 个兼容结果。");
                    searchBtn.setDisable(false);
                    importBtn.setDisable(resultList.getSelectionModel().getSelectedItem() == null);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String message = cleanMessage(e);
                    dialogStatus.setText("搜索失败: " + message);
                    setStatus(target.title + "搜索失败", message);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(true);
                });
            }
        });
    }

    private void downloadSelectedContent(ContentTarget target, ModrinthDownloader.Project project, String loader,
                                         String gameVersion, File importDir, Label dialogStatus, ProgressBar modProgress,
                                         Button searchBtn, Button importBtn) {
        if (project == null) {
            dialogStatus.setText("请先选择一个" + target.title + "。");
            return;
        }

        setControlsBusy(true);
        searchBtn.setDisable(true);
        importBtn.setDisable(true);
        modProgress.setProgress(0);
        downloadProgress.setProgress(0);
        startProgressAnimation(modProgress);
        startProgressAnimation(downloadProgress);
        String loaderLabel = loader == null ? "" : " / " + loader;
        setStatus("正在下载" + target.title, project.getTitle() + " -> " + gameVersion + loaderLabel);

        runAsync("ecl-download-modrinth-" + target.projectType, () -> {
            try {
                ModrinthDownloader.DownloadResult result = modrinthDownloader.downloadLatest(
                        project,
                        gameVersion,
                        loader,
                        importDir,
                        target.downloadDependencies,
                        new ModrinthDownloader.DownloadListener() {
                            @Override
                            public void onStatus(String message) {
                                Platform.runLater(() -> {
                                    dialogStatus.setText(message);
                                    setStatus("正在导入" + target.title, message);
                                });
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                Platform.runLater(() -> {
                                    updateProgress(modProgress, downloaded, total);
                                    updateProgress(downloadProgress, downloaded, total);
                                });
                            }
                        },
                        target.allowedExtensions
                );

                Platform.runLater(() -> {
                    modProgress.setProgress(1);
                    downloadProgress.setProgress(1);
                    stopProgressAnimation(modProgress, false);
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(false);
                    String mainFile = result.getMainFile() == null ? project.getTitle() : result.getMainFile().getName();
                    String detail = "已导入 " + result.getFiles().size() + " 个文件到: " + importDir.getAbsolutePath();
                    dialogStatus.setText(mainFile + " 导入完成。 " + detail);
                    setStatus(target.title + "导入完成", detail);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String message = cleanMessage(e);
                    stopProgressAnimation(modProgress, true);
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(false);
                    dialogStatus.setText("下载失败: " + message);
                    setStatus(target.title + "下载失败", message);
                });
            }
        });
    }

    private String getSelectedVersion() {
        return versionCombo == null ? null : versionCombo.getValue();
    }

    private File getActiveGameDir() {
        return gameDir == null ? ECLConfig.getGameDir() : gameDir;
    }

    private File resolveModsDir(String gameVersion) {
        return new File(getActiveGameDir(), "mods");
    }

    private void showSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("高级设置");
        applyWindowIcon(dialog);

        TextField javaField = new TextField(javaPath);
        javaField.setPromptText("选择 java.exe 或 JDK 根目录");
        applyFieldStyle(javaField);

        Button detectBtn = new Button("自动检测");
        detectBtn.getStyleClass().addAll("app-button", "secondary-button");
        detectBtn.setOnAction(e -> javaField.setText(JavaRuntimeUtil.detectSystemJavaExecutable()));

        Button javaBrowseBtn = new Button("选择 Java");
        javaBrowseBtn.getStyleClass().addAll("app-button", "secondary-button");
        javaBrowseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 Java 可执行文件");
            File initial = prepareChooserDir(javaField.getText());
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java 可执行文件", "java.exe", "*.exe"));
            }
            File selected = chooser.showOpenDialog(dialog);
            if (selected != null) {
                javaField.setText(selected.getAbsolutePath());
            }
        });

        HBox javaBox = new HBox(10, javaField, detectBtn, javaBrowseBtn);
        HBox.setHgrow(javaField, Priority.ALWAYS);

        TextField dirField = new TextField(gameDir.getAbsolutePath());
        dirField.setPromptText("输入游戏目录");
        applyFieldStyle(dirField);

        Button dirBrowseBtn = new Button("选择目录");
        dirBrowseBtn.getStyleClass().addAll("app-button", "secondary-button");
        dirBrowseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择游戏目录");
            File initial = prepareChooserDir(dirField.getText());
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

        TextField jvmField = new TextField(extraJvmArgs);
        jvmField.setPromptText("例如: -XX:+UseG1GC -Dfile.encoding=UTF-8");
        applyFieldStyle(jvmField);

        VBox dialogRoot = new VBox(18,
                createSurface("Java 路径", "可填写 java.exe，也可以直接指向 JDK 根目录", javaBox),
                createSurface("游戏目录", "启动器会在这个目录下运行游戏进程", dirBox),
                createSurface("额外 JVM 参数", "这些参数会附加到默认启动参数之后", jvmField)
        );
        dialogRoot.getStyleClass().add("root-pane");
        dialogRoot.setPadding(new Insets(24));

        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().addAll("app-button", "primary-button");
        saveBtn.setOnAction(e -> {
            String configuredJava = javaField.getText().trim();
            if (!configuredJava.isBlank() && !JavaRuntimeUtil.isUsableJavaPath(configuredJava)) {
                setStatus("Java 路径无效", "请选择 java.exe 或 JDK 根目录后再保存。 ");
                return;
            }

            String configuredGameDir = dirField.getText().trim();
            if (configuredGameDir.isBlank()) {
                configuredGameDir = ECLConfig.getGameDir().getAbsolutePath();
            }

            javaPath = configuredJava.isBlank() ? JavaRuntimeUtil.detectSystemJavaExecutable() : JavaRuntimeUtil.resolveJavaExecutable(configuredJava);
            gameDir = new File(configuredGameDir);
            gameDir.mkdirs();
            extraJvmArgs = jvmField.getText().trim();

            settingsManager.setString("javaPath", javaPath);
            settingsManager.setString("gameDir", gameDir.getAbsolutePath());
            settingsManager.setString("jvmArgs", extraJvmArgs);
            settingsManager.save();

            updateRuntimeSummary();
            setStatus("设置已保存", "新的 Java 路径、游戏目录和 JVM 参数已经生效。 ");
            dialog.close();
        });

        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().addAll("app-button", "ghost-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(12, saveBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        dialogRoot.getChildren().add(buttonBar);

        Scene scene = new Scene(createWheelScrollPane(dialogRoot), 760, 500);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.show();
    }

    private ScrollPane createWheelScrollPane(Node content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.getStyleClass().add("main-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);
        scrollPane.setFocusTraversable(false);
        scrollPane.addEventFilter(ScrollEvent.SCROLL, event -> scrollByWheel(scrollPane, event));
        return scrollPane;
    }

    private void scrollByWheel(ScrollPane scrollPane, ScrollEvent event) {
        double deltaY = event.getDeltaY();
        if (deltaY == 0 || scrollPane.getContent() == null) {
            return;
        }

        double contentHeight = scrollPane.getContent().getBoundsInLocal().getHeight();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double scrollableHeight = contentHeight - viewportHeight;
        if (scrollableHeight <= 0) {
            return;
        }

        double nextValue = clamp(scrollPane.getVvalue() - (deltaY / scrollableHeight), 0, 1);
        if (nextValue != scrollPane.getVvalue()) {
            scrollPane.setVvalue(nextValue);
            event.consume();
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void openExternalUrl(String url, String label) {
        try {
            getHostServices().showDocument(url);
            setStatus("已打开" + label, "浏览器会显示可下载内容，下载后放入对应本地目录即可。");
        } catch (Exception e) {
            setStatus("无法打开" + label, cleanMessage(e));
        }
    }

    private void openLocalFolder(File folder, String label) {
        try {
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("无法创建目录: " + folder.getAbsolutePath());
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(folder);
            } else {
                getHostServices().showDocument(folder.toURI().toString());
            }
            setStatus("已打开" + label, folder.getAbsolutePath());
        } catch (Exception e) {
            try {
                getHostServices().showDocument(new URI("file", "", folder.getAbsolutePath().replace('\\', '/'), null).toString());
            } catch (URISyntaxException ignored) {
                setStatus("无法打开" + label, cleanMessage(e));
            }
        }
    }

    private void applyWindowIcon(Stage stage) {
        URL icon = getClass().getResource("/icons/ecl-icon.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon.toExternalForm()));
        }
    }

    private File prepareChooserDir(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }

        File file = new File(rawPath.trim());
        if (file.isDirectory()) {
            return file.exists() ? file : null;
        }
        File parent = file.getParentFile();
        return parent != null && parent.exists() ? parent : null;
    }

    private void runAsync(String threadName, Runnable action) {
        Thread thread = new Thread(action, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private void updateMemoryDisplay(double value) {
        int memoryMb = (int) Math.round(value / 512.0) * 512;
        String text = memoryMb + " MB";
        if (memoryLabel != null) {
            memoryLabel.setText(text);
        }
        if (memorySummaryLabel != null) {
            memorySummaryLabel.setText(text);
        }
    }

    private void setFieldVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private VBox createSurface(String title, String subtitle, Node... content) {
        VBox box = new VBox(12);
        box.getStyleClass().add("surface");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("section-title");
        box.getChildren().add(titleLabel);

        if (subtitle != null && !subtitle.isBlank()) {
            Label subtitleLabel = new Label(subtitle);
            subtitleLabel.getStyleClass().add("section-subtitle");
            subtitleLabel.setWrapText(true);
            box.getChildren().add(subtitleLabel);
        }

        box.getChildren().addAll(content);
        return box;
    }

    private HBox createInfoRow(String key, Label valueLabel) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("info-key");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, keyLabel, spacer, valueLabel);
        row.getStyleClass().add("info-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label createValueLabel() {
        Label label = new Label();
        label.getStyleClass().add("info-value");
        label.setWrapText(true);
        return label;
    }

    private void applyFieldStyle(Control control) {
        control.getStyleClass().add("field-control");
        control.setMaxWidth(Double.MAX_VALUE);
    }

    private Label createBadge(String text, String styleClass) {
        Label badge = new Label(text);
        badge.getStyleClass().addAll("badge", styleClass);
        return badge;
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        int head = Math.max(8, maxLength / 2 - 2);
        int tail = Math.max(8, maxLength - head - 3);
        return text.substring(0, head) + "..." + text.substring(text.length() - tail);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    private String cleanMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
