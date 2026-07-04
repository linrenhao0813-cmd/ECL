package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.MicrosoftAuth;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
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
    private static final String MC_CHINESE_WIKI_VERSION_URL_PREFIX = "https://zh.minecraft.wiki/w/";
    private static final int MAX_CAPTURED_GAME_LOG_CHARS = 80000;
    private static final int DEFAULT_MAX_MEMORY_MB = 2048;
    private static final double WINDOW_WIDTH = 1366;
    private static final double WINDOW_HEIGHT = 768;
    private static final double NAV_WIDTH = 188;
    private static final double LAUNCH_WIDTH = 700;
    private static final double UTILITY_WIDTH = 330;
    private static final String ICON_GRASS_BLOCK = "/icons/ui/grass-block.png";
    private static final String ICON_STONE_BLOCK = "/icons/ui/stone-block.png";
    private static final String ICON_WOOD_BLOCK = "/icons/ui/wood-block.png";
    private static final String ICON_LAMP_BLOCK = "/icons/ui/lamp-block.png";
    private static final String ICON_MEMORY_BLOCK = "/icons/ui/memory-block.png";
    private static final String ICON_HOME = "/icons/ui/home.png";
    private static final String ICON_MODRINTH = "/icons/ui/modrinth.png";
    private static final String ICON_GEAR = "/icons/ui/gear.png";
    private static final String ICON_LOG = "/icons/ui/log.png";
    private static final String ICON_FOLDER = "/icons/ui/folder.png";
    private static final String ICON_JAVA = "/icons/ui/java.png";
    private static final String ICON_DOWNLOAD = "/icons/ui/download.png";
    private static final String ICON_CHECK = "/icons/ui/check.png";
    private static final String ICON_SIGNAL = "/icons/ui/signal.png";

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
    private ProgressBar downloadProgress;
    private Label statusLabel;
    private Label detailLabel;
    private Button launchBtn;
    private Button refreshBtn;
    private Button settingsBtn;
    private Button microsoftLoginBtn;
    private Button selectedVersionWikiButton;
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
    private Label topAuthBadgeLabel;
    private Label topVersionBadgeLabel;
    private Label topMemoryBadgeLabel;
    private Label selectedVersionTitleLabel;
    private HBox workspacePane;
    private List<ContentTarget> contentTargets;
    private ContentTarget selectedContentTarget;

    private String javaPath;
    private File gameDir;
    private String extraJvmArgs;
    private double windowDragOffsetX;
    private double windowDragOffsetY;
    private final Map<ProgressBar, Timeline> progressAnimations = new HashMap<>();
    private final Map<AppView, Button> navButtons = new HashMap<>();
    private AppView activeView = AppView.HOME;

    private enum AppView {
        HOME(ICON_HOME, "⌂", "首页"),
        VERSIONS(ICON_STONE_BLOCK, "□", "版本"),
        MODRINTH(ICON_MODRINTH, "◎", "Modrinth"),
        SETTINGS(ICON_GEAR, "⚙", "设置"),
        LOGS(ICON_LOG, "▤", "日志");

        private final String iconResource;
        private final String fallbackIcon;
        private final String label;

        AppView(String iconResource, String fallbackIcon, String label) {
            this.iconResource = iconResource;
            this.fallbackIcon = fallbackIcon;
            this.label = label;
        }
    }

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

        primaryStage.initStyle(StageStyle.UNDECORATED);

        BorderPane root = createRoot();
        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        primaryStage.setTitle("ECL Launcher");
        applyWindowIcon(primaryStage);
        primaryStage.setMinWidth(1180);
        primaryStage.setMinHeight(660);
        primaryStage.setScene(scene);
        primaryStage.show();
        primaryStage.centerOnScreen();

        updateAuthFields();
        updateRuntimeSummary();
        setStatus("就绪", "首次运行会自动拉取版本清单，未下载的版本会在启动前补齐资源。");
        refreshVersions();
    }

    private BorderPane createRoot() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setPadding(Insets.EMPTY);

        VBox topStack = new VBox(createWindowTitleBar(), createHeader());
        root.setTop(topStack);

        workspacePane = new HBox(16);
        workspacePane.getStyleClass().add("main-body");
        workspacePane.setFillHeight(false);
        workspacePane.getChildren().add(createNavigationRail());
        renderActiveView();
        root.setCenter(createWheelScrollPane(workspacePane));
        BorderPane.setMargin(root.getCenter(), new Insets(0, 16, 0, 16));
        root.setBottom(createFooterBar());
        BorderPane.setMargin(root.getBottom(), new Insets(0, 16, 0, 16));
        return root;
    }

    private HBox createWindowTitleBar() {
        HBox titleBar = new HBox(12);
        titleBar.getStyleClass().add("window-title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);

        Label closeDot = createTrafficDot("traffic-red");
        Label minDot = createTrafficDot("traffic-yellow");
        Label zoomDot = createTrafficDot("traffic-green");

        Label title = new Label("ECL Launcher");
        title.getStyleClass().add("window-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimizeButton = createWindowButton("—", () -> primaryStage.setIconified(true));
        Button maximizeButton = createWindowButton("□", () -> primaryStage.setMaximized(!primaryStage.isMaximized()));
        Button closeButton = createWindowButton("×", () -> primaryStage.close());
        closeButton.getStyleClass().add("window-close-button");

        titleBar.getChildren().addAll(closeDot, minDot, zoomDot, title, spacer, minimizeButton, maximizeButton, closeButton);
        titleBar.setOnMousePressed(event -> {
            windowDragOffsetX = event.getSceneX();
            windowDragOffsetY = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            if (!primaryStage.isMaximized()) {
                primaryStage.setX(event.getScreenX() - windowDragOffsetX);
                primaryStage.setY(event.getScreenY() - windowDragOffsetY);
            }
        });
        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                primaryStage.setMaximized(!primaryStage.isMaximized());
            }
        });
        return titleBar;
    }

    private Label createTrafficDot(String styleClass) {
        Label dot = new Label();
        dot.getStyleClass().addAll("traffic-dot", styleClass);
        return dot;
    }

    private Button createWindowButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("window-button");
        button.setOnAction(e -> action.run());
        return button;
    }

    private HBox createHeader() {
        HBox header = new HBox(16);
        header.getStyleClass().add("hero-header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox brand = new VBox(4);
        brand.setMinWidth(390);
        brand.setPrefWidth(390);
        Label title = new Label("ECL Launcher");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("轻量 Minecraft 启动器");
        subtitle.getStyleClass().add("app-subtitle");
        brand.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        spacer.setMinWidth(40);
        spacer.setPrefWidth(40);
        spacer.setMaxWidth(40);

        topAuthBadgeLabel = createValueLabel("Steve");
        topVersionBadgeLabel = createValueLabel("未选择");
        runtimeBadgeLabel = createValueLabel("检查中");
        topMemoryBadgeLabel = createValueLabel("自动");

        HBox stats = new HBox(10,
                createStatusCard(ICON_GRASS_BLOCK, "账号", topAuthBadgeLabel, 170),
                createStatusCard(ICON_GRASS_BLOCK, "版本", topVersionBadgeLabel, 230),
                createStatusCard(ICON_JAVA, "Java", runtimeBadgeLabel, 150),
                createStatusCard(ICON_MEMORY_BLOCK, "内存", topMemoryBadgeLabel, 185)
        );
        stats.setAlignment(Pos.CENTER_RIGHT);

        header.getChildren().addAll(brand, spacer, stats);
        return header;
    }

    private HBox createStatusCard(String iconResource, String title, Label valueLabel, double width) {
        Node icon = createIconNode(iconResource, "■", 42, "stat-icon");

        VBox text = new VBox(4);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("stat-title");
        valueLabel.getStyleClass().add("stat-value");
        text.getChildren().addAll(titleLabel, valueLabel);

        HBox card = new HBox(14, icon, text);
        card.getStyleClass().add("stat-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(width);
        card.setPrefWidth(width);
        card.setMaxWidth(width);
        return card;
    }

    private HBox createFooterBar() {
        Label status = new Label("镜像源可用  ·  资源校验完成");
        status.getStyleClass().add("footer-status");
        HBox statusGroup = new HBox(10, createIconNode(ICON_CHECK, "✓", 32, "footer-icon"), status);
        statusGroup.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Node signal = createIconNode(ICON_SIGNAL, "▂▅█", 35, "footer-signal");
        HBox footer = new HBox(12, statusGroup, spacer, signal);
        footer.getStyleClass().add("footer-bar");
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    private VBox createNavigationRail() {
        VBox rail = new VBox(22);
        rail.getStyleClass().add("nav-rail");
        rail.setPrefWidth(NAV_WIDTH);
        rail.setMinWidth(NAV_WIDTH);
        rail.setMaxWidth(NAV_WIDTH);

        navButtons.clear();
        for (AppView view : AppView.values()) {
            rail.getChildren().add(createNavButton(view));
        }
        return rail;
    }

    private Button createNavButton(AppView view) {
        Button button = new Button(view.label);
        button.setGraphic(createIconNode(view.iconResource, view.fallbackIcon, 40, "nav-icon"));
        button.setGraphicTextGap(14);
        button.getStyleClass().add("nav-button");
        if (view == activeView) {
            button.getStyleClass().add("nav-button-selected");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> setActiveView(view));
        navButtons.put(view, button);
        return button;
    }

    private void setActiveView(AppView view) {
        if (view == null || workspacePane == null) {
            return;
        }
        activeView = view;
        renderActiveView();
        updateNavSelection();
        if (authTypeCombo != null && authSummaryLabel != null) {
            updateAuthFields();
        } else {
            updateRuntimeSummary();
        }
        if (view == AppView.VERSIONS) {
            setStatus("版本管理", "可以刷新版本列表，或回到首页选择具体启动版本。");
        } else if (view == AppView.MODRINTH) {
            setStatus("Modrinth 内容库", "选择模组、光影、材质包或整合包后搜索下载。");
        } else if (view == AppView.SETTINGS) {
            setStatus("设置", "Java 路径、游戏目录和 JVM 参数在高级设置中修改。");
        } else if (view == AppView.LOGS) {
            setStatus("日志与诊断", "游戏异常退出后可在这里打开崩溃报告和相关目录。");
        }
    }

    private void updateNavSelection() {
        for (Map.Entry<AppView, Button> entry : navButtons.entrySet()) {
            Button button = entry.getValue();
            button.getStyleClass().remove("nav-button-selected");
            if (entry.getKey() == activeView) {
                button.getStyleClass().add("nav-button-selected");
            }
        }
    }

    private void renderActiveView() {
        if (workspacePane == null) {
            return;
        }
        while (workspacePane.getChildren().size() > 1) {
            workspacePane.getChildren().remove(1);
        }

        switch (activeView) {
            case HOME -> addMainContent(createLaunchPane(), createUtilityColumn());
            case VERSIONS -> addMainContent(createVersionsPage(), createUtilityColumn());
            case MODRINTH -> addMainContent(createModrinthPage(), createUtilityColumn());
            case SETTINGS -> addMainContent(createSettingsPage(), createUtilityColumn());
            case LOGS -> addMainContent(createLogsPage(), createUtilityColumn());
        }
    }

    private void addMainContent(Node primary, Node secondary) {
        boolean fixedWidth = primary.getStyleClass().contains("launch-pane");
        if (primary instanceof Region region && !fixedWidth) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        HBox.setHgrow(primary, fixedWidth ? Priority.NEVER : Priority.ALWAYS);
        workspacePane.getChildren().add(primary);
        if (secondary != null) {
            HBox.setHgrow(secondary, Priority.NEVER);
            workspacePane.getChildren().add(secondary);
        }
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

    private VBox createUtilityColumn() {
        VBox pane = new VBox(8);
        pane.getStyleClass().add("utility-column");
        pane.setPrefWidth(UTILITY_WIDTH);
        pane.setMinWidth(UTILITY_WIDTH);
        pane.setMaxWidth(UTILITY_WIDTH);

        statusLabel = new Label("等待任务");
        statusLabel.getStyleClass().add("status-title");
        detailLabel = new Label();
        detailLabel.getStyleClass().add("status-detail");
        detailLabel.setWrapText(true);
        detailLabel.setText("没有正在进行的下载");

        downloadProgress = new ProgressBar(0);
        downloadProgress.getStyleClass().add("download-progress");
        downloadProgress.setMaxWidth(Double.MAX_VALUE);
        downloadProgress.setVisible(true);

        VBox statusCard = createSurface(
                "下载队列",
                null,
                statusLabel,
                detailLabel,
                downloadProgress
        );

        javaSummaryLabel = createValueLabel();
        gameDirSummaryLabel = createValueLabel();
        versionSummaryLabel = createValueLabel();
        memorySummaryLabel = createValueLabel();
        jvmArgsSummaryLabel = createValueLabel();

        VBox diagnosticCard = createDiagnosticPane();

        pane.getChildren().addAll(statusCard, createSidebarModrinthPane(), diagnosticCard);
        return pane;
    }

    private VBox createDiagnosticPane() {
        HBox okRow = createDiagnosticRow(ICON_CHECK, "✓", "状态正常", "");
        HBox crashRow = createDiagnosticRow(ICON_LOG, "▤", "崩溃报告", String.valueOf(countCrashReports()));

        VBox rows = new VBox(0, okRow, crashRow);
        rows.getStyleClass().add("sidebar-list");

        return createSurface(
                "诊断",
                null,
                rows
        );
    }

    private HBox createDiagnosticRow(String iconResource, String fallbackIcon, String text, String value) {
        Node icon = createIconNode(iconResource, fallbackIcon, 38, "sidebar-icon");
        Label title = new Label(text);
        title.getStyleClass().add("sidebar-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("sidebar-value");
        HBox row = new HBox(12, icon, title, spacer, valueLabel);
        row.getStyleClass().add("sidebar-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox createSidebarModrinthPane() {
        VBox rows = new VBox(0);
        rows.getStyleClass().add("sidebar-list");
        rows.getChildren().addAll(
                createSidebarModrinthRow(contentTargets.get(0), "精选模组推荐"),
                createSidebarModrinthRow(contentTargets.get(1), "热门光影推荐"),
                createSidebarModrinthRow(contentTargets.get(2), "优质材质包推荐")
        );
        return createSurface("Modrinth 推荐", null, rows);
    }

    private HBox createSidebarModrinthRow(ContentTarget target, String subtitle) {
        Node icon = createIconNode(iconForContentTarget(target), target.initial, 44, "sidebar-icon");
        VBox text = new VBox(2);
        Label title = new Label(target.title);
        title.getStyleClass().add("sidebar-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("sidebar-subtitle");
        text.getChildren().addAll(title, subtitleLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label arrow = new Label("›");
        arrow.getStyleClass().add("sidebar-arrow");
        HBox row = new HBox(12, icon, text, spacer, arrow);
        row.getStyleClass().add("sidebar-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> showContentDownloadDialog(target));
        return row;
    }

    private int countCrashReports() {
        File crashDir = new File(getActiveGameDir(), "crash-reports");
        File[] reports = crashDir.listFiles((dir, name) -> name.endsWith(".txt"));
        return reports == null ? 0 : reports.length;
    }

    private VBox createLaunchPane() {
        VBox pane = new VBox(14);
        pane.getStyleClass().add("launch-pane");
        pane.setMinWidth(LAUNCH_WIDTH);
        pane.setPrefWidth(LAUNCH_WIDTH);
        pane.setMaxWidth(LAUNCH_WIDTH);
        HBox.setHgrow(pane, Priority.NEVER);

        StackPane launchCard = new StackPane();
        launchCard.getStyleClass().add("launch-surface");

        VBox content = new VBox(16);
        content.getStyleClass().add("launch-content");
        content.setAlignment(Pos.TOP_CENTER);
        content.getChildren().addAll(createLaunchHero(), createForm(), createActionBar());

        launchCard.getChildren().add(content);
        launchCard.getChildren().addAll(
                createLaunchCorner("launch-corner-tl", Pos.TOP_LEFT),
                createLaunchCorner("launch-corner-tr", Pos.TOP_RIGHT),
                createLaunchCorner("launch-corner-bl", Pos.BOTTOM_LEFT),
                createLaunchCorner("launch-corner-br", Pos.BOTTOM_RIGHT)
        );
        pane.getChildren().add(launchCard);
        return pane;
    }

    private VBox createLaunchHero() {
        selectedVersionTitleLabel = new Label("选择 Minecraft 版本");
        selectedVersionTitleLabel.getStyleClass().add("launch-version-title");

        Label metaText = new Label("已选择的版本");
        metaText.getStyleClass().add("focus-badge-text");
        HBox meta = new HBox(10, createIconNode(ICON_GRASS_BLOCK, "■", 34, "focus-badge-icon"), metaText);
        meta.getStyleClass().add("focus-badge");
        meta.setAlignment(Pos.CENTER);

        VBox hero = new VBox(14, selectedVersionTitleLabel, meta);
        hero.getStyleClass().add("launch-hero");
        hero.setAlignment(Pos.CENTER);
        return hero;
    }

    private Region createLaunchCorner(String styleClass, Pos alignment) {
        Region corner = new Region();
        corner.getStyleClass().addAll("launch-corner", styleClass);
        StackPane.setAlignment(corner, alignment);
        return corner;
    }

    private VBox createVersionsPage() {
        VBox page = createMainPage();

        Button refreshVersionsButton = createActionButton("刷新版本列表", "secondary-button", this::refreshVersions);
        Button chooseVersionButton = createActionButton("回首页选择版本", "primary-button", () -> setActiveView(AppView.HOME));
        Button openVersionsDirButton = createActionButton("打开版本目录", "ghost-button",
                () -> openLocalFolder(ECLConfig.getVersionsDir(), "版本目录"));

        HBox actions = new HBox(10, refreshVersionsButton, chooseVersionButton, openVersionsDirButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox versionCard = createSurface(
                "版本管理",
                "刷新 Mojang 版本清单，或回到首页选择要启动的版本",
                createInfoRow("当前筛选", createStaticValueLabel(getSelectedVersionCategory().getLabel())),
                createInfoRow("当前版本", createStaticValueLabel(getSelectedVersion() == null ? "未选择" : getSelectedVersion())),
                createInfoRow("本地目录", createStaticValueLabel(ECLConfig.getVersionsDir().getAbsolutePath())),
                actions
        );

        page.getChildren().add(versionCard);
        return page;
    }

    private VBox createModrinthPage() {
        VBox page = createMainPage();

        Label hint = createBodyText("选择内容类型后会按当前 Minecraft 版本筛选兼容项目。没有输入关键词时，会加载 Modrinth 官网下载量排序列表。");
        VBox intro = createSurface(
                "Modrinth 内容库",
                "模组、光影、材质包和整合包",
                createInfoRow("目标版本", createStaticValueLabel(getSelectedVersion() == null ? "先在首页选择版本" : getSelectedVersion())),
                hint
        );

        page.getChildren().addAll(intro, createContentPane());
        return page;
    }

    private VBox createSettingsPage() {
        VBox page = createMainPage();

        Button advancedButton = createActionButton("高级设置", "primary-button", this::showSettingsDialog);
        Button dataDirButton = createActionButton("打开数据目录", "secondary-button",
                () -> openLocalFolder(ECLConfig.getBaseDir(), "数据目录"));
        Button gameDirButton = createActionButton("打开游戏目录", "ghost-button",
                () -> openLocalFolder(getActiveGameDir(), "游戏目录"));

        HBox actions = new HBox(10, advancedButton, dataDirButton, gameDirButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox settingsCard = createSurface(
                "设置",
                "Java、游戏目录和 JVM 参数",
                createInfoRow("Java", createStaticValueLabel(javaPath == null || javaPath.isBlank() ? "未配置" : abbreviate(javaPath, 72))),
                createInfoRow("游戏目录", createStaticValueLabel(abbreviate(getActiveGameDir().getAbsolutePath(), 72))),
                createInfoRow("JVM 参数", createStaticValueLabel(extraJvmArgs == null || extraJvmArgs.isBlank() ? "未设置" : abbreviate(extraJvmArgs, 72))),
                actions
        );

        page.getChildren().add(settingsCard);
        return page;
    }

    private VBox createLogsPage() {
        VBox page = createMainPage();

        File crashDir = new File(getActiveGameDir(), "crash-reports");
        File logsDir = new File(getActiveGameDir(), "logs");
        Button crashButton = createActionButton("打开崩溃报告", "primary-button",
                () -> openLocalFolder(crashDir, "崩溃报告目录"));
        Button logsButton = createActionButton("打开日志目录", "secondary-button",
                () -> openLocalFolder(logsDir, "日志目录"));
        Button modsButton = createActionButton("打开 mods", "ghost-button",
                () -> openLocalFolder(resolveModsDir(getSelectedVersion()), "模组目录"));

        HBox actions = new HBox(10, crashButton, logsButton, modsButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox logsCard = createSurface(
                "日志与诊断",
                "查看崩溃报告和游戏运行日志",
                createInfoRow("诊断状态", createStaticValueLabel("状态正常")),
                createInfoRow("崩溃报告", createStaticValueLabel(countCrashReports() + " 个")),
                createInfoRow("游戏目录", createStaticValueLabel(abbreviate(getActiveGameDir().getAbsolutePath(), 72))),
                actions
        );

        page.getChildren().add(logsCard);
        return page;
    }

    private VBox createMainPage() {
        VBox page = new VBox(14);
        page.getStyleClass().add("launch-pane");
        page.setMinWidth(LAUNCH_WIDTH);
        page.setPrefWidth(LAUNCH_WIDTH);
        page.setMaxWidth(LAUNCH_WIDTH);
        HBox.setHgrow(page, Priority.NEVER);
        return page;
    }

    private Button createActionButton(String text, String styleClass, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", styleClass);
        button.setOnAction(e -> action.run());
        return button;
    }

    private Label createStaticValueLabel(String text) {
        Label label = createValueLabel();
        label.setText(text);
        return label;
    }

    private Label createBodyText(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status-detail");
        label.setWrapText(true);
        return label;
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
            GridPane.setConstraints(row, 0, i);
            GridPane.setHgrow(row, Priority.ALWAYS);
            if (row instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
        }
        return createSurface("Modrinth 推荐", "下载后自动导入对应目录", contentRows);
    }

    private HBox createContentRow(ContentTarget target) {
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
        downloadBtn.setText("搜索");
        downloadBtn.setTooltip(new Tooltip("在启动器内搜索、下载并导入" + target.title));
        downloadBtn.setOnAction(e -> showContentDownloadDialog(target));

        Button folderBtn = new Button("目录");
        folderBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        folderBtn.setTooltip(new Tooltip("打开本地" + target.title + "目录"));
        folderBtn.setOnAction(e -> openLocalFolder(target.folderSupplier.get(), target.title + "目录"));

        HBox actions = new HBox(6, downloadBtn, folderBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(10, icon, textBox, actions);
        row.getStyleClass().add("content-row");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBox, Priority.ALWAYS);
        return row;
    }

    private GridPane createForm() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("launch-form");
        grid.setHgap(10);
        grid.setVgap(12);

        String previousVersion = versionCombo == null ? settingsManager.getString("selectedVersion", "") : versionCombo.getValue();
        VersionManager.VersionCategory previousCategory = versionTypeCombo == null || versionTypeCombo.getValue() == null
                ? parseVersionCategory(settingsManager.getString("versionCategory2", VersionManager.VersionCategory.FEATURED.name()))
                : versionTypeCombo.getValue();
        String previousAuthType = authTypeCombo == null ? normalizeAuthType(settingsManager.getString("authType", AUTH_OFFLINE)) : normalizeAuthType(authTypeCombo.getValue());
        String previousUsername = usernameField == null ? settingsManager.getString("username", settingsManager.getString("microsoftProfileName", "Steve")) : usernameField.getText();
        if (previousUsername == null || previousUsername.isBlank()) {
            previousUsername = "Steve";
        }

        authTypeCombo = new ComboBox<>();
        authTypeCombo.getItems().addAll(AUTH_OFFLINE, AUTH_MICROSOFT, AUTH_YGGDRASIL);
        authTypeCombo.setValue(previousAuthType);
        authTypeCombo.setOnAction(e -> updateAuthFields());
        applyFieldStyle(authTypeCombo);

        yggdrasilServerField = new TextField(settingsManager.getString("yggdrasilServer", "https://littleskin.cn/api/yggdrasil/"));
        yggdrasilServerField.setPromptText("输入 Yggdrasil 认证地址");
        applyFieldStyle(yggdrasilServerField);

        usernameField = new TextField(previousUsername);
        usernameField.setPromptText("输入玩家名称");
        applyFieldStyle(usernameField);
        usernameField.textProperty().addListener((obs, oldValue, newValue) -> updateRuntimeSummary());

        passwordField = new PasswordField();
        passwordField.setPromptText("外置登录时需要");
        applyFieldStyle(passwordField);

        authSummaryLabel = createValueLabel();
        authHintLabel = new Label();
        authHintLabel.getStyleClass().add("status-detail");
        authHintLabel.setWrapText(true);

        versionCombo = new ComboBox<>();
        versionCombo.setPromptText("选择游戏版本");
        versionCombo.setVisibleRowCount(14);
        applyFieldStyle(versionCombo);
        versionCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.isBlank()) {
                settingsManager.setString("selectedVersion", newValue);
                settingsManager.save();
            }
            updateRuntimeSummary();
            updateSelectedVersionWikiButton();
        });

        versionTypeCombo = new ComboBox<>();
        versionTypeCombo.getItems().addAll(VersionManager.VersionCategory.values());
        versionTypeCombo.setValue(previousCategory);
        versionTypeCombo.setPrefWidth(176);
        versionTypeCombo.setTooltip(new Tooltip("默认显示正式版、预览版/快照和愚人节版，也可以只看某一类"));
        versionTypeCombo.setOnAction(e -> {
            settingsManager.setString("versionCategory2", getSelectedVersionCategory().name());
            settingsManager.save();
            refreshVersions();
        });
        applyFieldStyle(versionTypeCombo);

        selectedVersionWikiButton = createSelectedVersionWikiButton();
        restoreVersionComboItems(previousVersion);
        updateSelectedVersionWikiButton();

        TextField gameDirField = new TextField(abbreviate(getActiveGameDir().getAbsolutePath(), 72));
        gameDirField.setEditable(false);
        applyFieldStyle(gameDirField);

        TextField jvmField = new TextField(extraJvmArgs == null || extraJvmArgs.isBlank() ? "自动内存" : extraJvmArgs);
        jvmField.setEditable(false);
        applyFieldStyle(jvmField);

        Button folderButton = createIconActionButton(ICON_FOLDER, "▣", "打开游戏目录",
                () -> openLocalFolder(getActiveGameDir(), "游戏目录"));

        Button jvmButton = createIconActionButton(ICON_GEAR, "⚙", "高级设置", this::showSettingsDialog);

        HBox gameDirBox = new HBox(10, gameDirField, folderButton);
        HBox.setHgrow(gameDirField, Priority.ALWAYS);
        HBox jvmBox = new HBox(10, jvmField, jvmButton);
        HBox.setHgrow(jvmField, Priority.ALWAYS);
        authTypeCombo.setPrefWidth(220);
        microsoftLoginBtn = new Button("正版登录");
        microsoftLoginBtn.getStyleClass().addAll("app-button", "secondary-button", "compact-button");
        microsoftLoginBtn.setTooltip(new Tooltip("登录 Microsoft 正版 Minecraft Java 版账号"));
        microsoftLoginBtn.setOnAction(e -> loginMicrosoftAccount());
        HBox authBox = new HBox(10, authTypeCombo, usernameField, microsoftLoginBtn);
        authBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(usernameField, Priority.ALWAYS);
        VBox authHelpBox = new VBox(4, authSummaryLabel, authHintLabel);
        HBox versionBox = new HBox(10, versionTypeCombo, versionCombo, selectedVersionWikiButton);
        versionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(versionCombo, Priority.ALWAYS);
        versionCombo.valueProperty().addListener((obs, oldValue, newValue) ->
                gameDirField.setText(abbreviate(getActiveGameDir().getAbsolutePath(), 72)));

        int row = 0;
        serverLabel = new Label("外置服务器:");
        passwordLabel = new Label("密码:");

        grid.add(new Label("游戏版本"), 0, row);
        grid.add(versionBox, 1, row++);

        grid.add(new Label("账号模式"), 0, row);
        grid.add(authBox, 1, row++);

        grid.add(serverLabel, 0, row);
        grid.add(yggdrasilServerField, 1, row++);

        grid.add(passwordLabel, 0, row);
        grid.add(passwordField, 1, row++);

        grid.add(new Label("登录状态"), 0, row);
        grid.add(authHelpBox, 1, row++);

        grid.add(new Label("游戏目录"), 0, row);
        grid.add(gameDirBox, 1, row++);

        grid.add(new Label("JVM 参数"), 0, row);
        grid.add(jvmBox, 1, row);

        for (Node node : grid.getChildren()) {
            if (node instanceof Label label) {
                label.getStyleClass().add("field-label");
            }
        }

        return grid;
    }

    private void restoreVersionComboItems(String preferredVersion) {
        if (versionCombo == null || versionTypeCombo == null || versionManager == null) {
            return;
        }
        try {
            List<String> versions = versionManager.getVersions(getSelectedVersionCategory());
            versionCombo.getItems().setAll(versions);
            if (preferredVersion != null && versions.contains(preferredVersion)) {
                versionCombo.getSelectionModel().select(preferredVersion);
            } else if (!versions.isEmpty()) {
                versionCombo.getSelectionModel().select(0);
            }
        } catch (Exception ignored) {
            // Version refresh will surface network/cache failures through the status area.
        }
    }

    private Button createSelectedVersionWikiButton() {
        Button button = new Button("更新介绍");
        button.getStyleClass().addAll("app-button", "wiki-link-button");
        button.setTooltip(new Tooltip("打开 mc 中文 Wiki 的当前版本更新介绍"));
        button.setOnAction(e -> openMinecraftWikiVersionPage(getSelectedVersion()));
        return button;
    }

    private void updateSelectedVersionWikiButton() {
        if (selectedVersionWikiButton == null) {
            return;
        }
        String version = getSelectedVersion();
        boolean supported = isWikiSupportedVersion(version);
        boolean comboBusy = versionCombo != null && versionCombo.isDisabled();
        selectedVersionWikiButton.setDisable(comboBusy || !supported);
        selectedVersionWikiButton.setTooltip(new Tooltip(supported
                ? "打开 mc 中文 Wiki 的 " + version + " 更新介绍"
                : "正式版和快照版可打开 mc 中文 Wiki 更新介绍"));
    }

    private HBox createActionBar() {
        Label playIcon = new Label("▶");
        playIcon.getStyleClass().add("launch-play-icon");
        launchBtn = new Button("启动游戏");
        launchBtn.setGraphic(playIcon);
        launchBtn.setGraphicTextGap(22);
        launchBtn.getStyleClass().addAll("app-button", "launch-button");
        launchBtn.setDefaultButton(true);
        launchBtn.setOnAction(e -> launchGame());

        refreshBtn = new Button("刷新版本");
        refreshBtn.getStyleClass().addAll("app-button", "secondary-button");
        refreshBtn.setOnAction(e -> refreshVersions());
        setFieldVisible(refreshBtn, false);

        settingsBtn = new Button("高级设置");
        settingsBtn.getStyleClass().addAll("app-button", "ghost-button");
        settingsBtn.setOnAction(e -> showSettingsDialog());
        setFieldVisible(settingsBtn, false);

        HBox buttonBar = new HBox(12, launchBtn, refreshBtn, settingsBtn);
        buttonBar.setAlignment(Pos.CENTER);
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
            usernameField.setPromptText("授权后自动读取正版玩家名");
            authSummaryLabel.setText("微软正版登录");
            authHintLabel.setText("启动时会打开浏览器并显示设备码，授权成功后自动读取 Minecraft Java 版档案。 ");
        } else if (yggdrasil) {
            usernameField.setPromptText("输入外置登录用户名或邮箱");
            authSummaryLabel.setText("外置登录 / Yggdrasil");
            authHintLabel.setText("兼容 LittleSkin、Blessing Skin 和其他 authlib-injector 服务端。 ");
        } else {
            usernameField.setPromptText("输入玩家名称");
            authSummaryLabel.setText("离线登录");
            authHintLabel.setText("会为当前用户名生成本地 UUID，适合单机和快速调试。 ");
        }

        updateRuntimeSummary();
    }

    private void updateRuntimeSummary() {
        setSummaryText(javaSummaryLabel, javaPath, 64);
        setSummaryText(gameDirSummaryLabel, getActiveGameDir().getAbsolutePath(), 68);

        int count = versionCombo == null ? 0 : versionCombo.getItems().size();
        String selectedVersion = versionCombo == null ? null : versionCombo.getValue();
        String categoryLabel = getSelectedVersionCategory().getLabel();
        String versionSummary = count == 0 ? "等待拉取" + categoryLabel + "列表" : count + " 个" + categoryLabel + (selectedVersion == null ? "" : "，当前 " + selectedVersion);
        versionSummaryLabel.setText(versionSummary);
        if (selectedVersionTitleLabel != null) {
            selectedVersionTitleLabel.setText(selectedVersion == null || selectedVersion.isBlank() ? "选择 Minecraft 版本" : selectedVersion);
        }
        if (topVersionBadgeLabel != null) {
            topVersionBadgeLabel.setText(selectedVersion == null || selectedVersion.isBlank() ? "未选择" : abbreviate(selectedVersion, 16));
        }
        if (topAuthBadgeLabel != null) {
            topAuthBadgeLabel.setText(getAuthDisplayName());
        }

        if (memorySummaryLabel != null) {
            memorySummaryLabel.setText("自动");
        }
        jvmArgsSummaryLabel.setText(extraJvmArgs == null || extraJvmArgs.isBlank() ? "未设置" : abbreviate(extraJvmArgs, 68));
        jvmArgsSummaryLabel.setTooltip(extraJvmArgs == null || extraJvmArgs.isBlank() ? null : new Tooltip(extraJvmArgs));


        if (JavaRuntimeUtil.isUsableJavaPath(javaPath)) {
            runtimeBadgeLabel.setText(String.valueOf(Runtime.version().feature()));
        } else {
            runtimeBadgeLabel.setText("未配置");
        }
    }

    private String getAuthDisplayName() {
        String authType = authTypeCombo == null ? AUTH_OFFLINE : authTypeCombo.getValue();
        if (AUTH_MICROSOFT.equals(authType)) {
            return "Microsoft";
        }
        if (AUTH_YGGDRASIL.equals(authType)) {
            return "Yggdrasil";
        }
        String username = usernameField == null ? "Steve" : usernameField.getText();
        if (username == null || username.isBlank()) {
            username = "Steve";
        }
        return abbreviate(username.trim(), 14);
    }

    private String getAuthBadgeText() {
        String authType = authTypeCombo == null ? AUTH_OFFLINE : authTypeCombo.getValue();
        if (AUTH_MICROSOFT.equals(authType)) {
            return "账号 Microsoft";
        }
        if (AUTH_YGGDRASIL.equals(authType)) {
            return "账号 Yggdrasil";
        }
        String username = usernameField == null ? "Player" : usernameField.getText();
        if (username == null || username.isBlank()) {
            username = "Player";
        }
        return "账号 " + abbreviate(username.trim(), 14);
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
        updateSelectedVersionWikiButton();
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
                    updateSelectedVersionWikiButton();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("获取版本列表失败", cleanMessage(e));
                    refreshBtn.setDisable(false);
                    versionCombo.setDisable(false);
                    versionTypeCombo.setDisable(false);
                    updateRuntimeSummary();
                    updateSelectedVersionWikiButton();
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

    private String normalizeAuthType(String value) {
        if (AUTH_MICROSOFT.equals(value) || AUTH_YGGDRASIL.equals(value) || AUTH_OFFLINE.equals(value)) {
            return value;
        }
        return AUTH_OFFLINE;
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

        settingsManager.setString("javaPath", javaPath);
        settingsManager.setString("gameDir", gameDir.getAbsolutePath());
        settingsManager.setString("jvmArgs", extraJvmArgs == null ? "" : extraJvmArgs);
        settingsManager.setString("selectedVersion", selectedVersion);
        settingsManager.setString("authType", authTypeCombo.getValue());
        settingsManager.setString("username", usernameField.getText().trim());
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

        setControlsBusy(true);
        stopProgressAnimation(downloadProgress, true);
        setStatus("正在启动游戏...", "准备认证、拼接类路径并拉起客户端进程。 ");

        runAsync("ecl-launch-game", () -> {
            File launchDir = resolveVersionGameDir(version);
            try {
                ensureVersionGameDirs(version);
                AuthProvider auth = buildAuthProvider(authType, server, username, password);
                gameLauncher.setAuth(auth);
                gameLauncher.setVersion(version);
                gameLauncher.setMaxMemory(DEFAULT_MAX_MEMORY_MB);
                gameLauncher.setGameDir(launchDir);
                gameLauncher.setJvmArgs(extraJvmArgs == null ? "" : extraJvmArgs);
                gameLauncher.setJavaPath(javaPath);
                long launchStartedAt = System.currentTimeMillis();
                Process process = gameLauncher.launch();
                monitorGameProcess(process, version, launchDir, launchStartedAt);

                Platform.runLater(() -> {
                    setStatus("游戏已启动", version + " 正在运行，实例目录: " + launchDir.getAbsolutePath());
                    setControlsBusy(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, launchDir);
                    setStatus("启动失败", report.getTitle());
                    showGameErrorDialog(report);
                    setControlsBusy(false);
                });
            }
        });
    }

    private void monitorGameProcess(Process process, String version, File launchDir, long launchStartedAt) {
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

                CrashAnalyzer.Report report = CrashAnalyzer.analyzeGameExit(version, exitCode, output.toString(), launchDir, launchStartedAt);
                Platform.runLater(() -> {
                    setStatus("游戏异常退出", report.getTitle());
                    showGameErrorDialog(report);
                });
            } catch (Exception e) {
                CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, launchDir);
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

    private void loginMicrosoftAccount() {
        authTypeCombo.setValue(AUTH_MICROSOFT);
        updateAuthFields();
        setControlsBusy(true);
        setStatus("微软正版登录", "正在准备 Microsoft 设备码登录。");

        runAsync("ecl-login-microsoft", () -> {
            try {
                MicrosoftAuth microsoftAuth = authenticateMicrosoftAccount();
                Platform.runLater(() -> {
                    usernameField.setText(microsoftAuth.getUsername());
                    setStatus("微软正版登录成功", "已登录 " + microsoftAuth.getUsername() + "，现在可以直接启动游戏。");
                    updateRuntimeSummary();
                    setControlsBusy(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("微软正版登录失败", cleanMessage(e));
                    setControlsBusy(false);
                });
            }
        });
    }

    private MicrosoftAuth authenticateMicrosoftAccount() {
        MicrosoftAuth microsoftAuth = new MicrosoftAuth(settingsManager.getString("microsoftRefreshToken", ""), new MicrosoftAuth.LoginListener() {
            @Override
            public void onDeviceCode(MicrosoftAuth.DeviceCode deviceCode) {
                Platform.runLater(() -> {
                    boolean copied = copyMicrosoftDeviceCodeToClipboard(deviceCode.getUserCode());
                    setStatus("微软正版登录", copied
                            ? "登录代码已自动复制: " + deviceCode.getUserCode() + "。浏览器打开后直接粘贴完成授权。"
                            : "无法自动复制登录代码，请手动复制 " + deviceCode.getUserCode() + " 完成授权。");
                    showMicrosoftDeviceCodeDialog(deviceCode);
                    openMicrosoftVerificationPage(deviceCode);
                });
            }

            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> setStatus("微软正版登录", message));
            }
        });
        microsoftAuth.login();
        String refreshToken = microsoftAuth.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            settingsManager.setString("microsoftRefreshToken", refreshToken);
        }
        settingsManager.setString("authType", AUTH_MICROSOFT);
        settingsManager.setString("microsoftProfileName", microsoftAuth.getUsername());
        settingsManager.setString("username", microsoftAuth.getUsername());
        settingsManager.save();
        return microsoftAuth;
    }

    private void showMicrosoftDeviceCodeDialog(MicrosoftAuth.DeviceCode deviceCode) {
        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("微软正版登录");

        Label title = new Label("微软正版登录");
        title.getStyleClass().add("section-title");
        Label message = createBodyText("登录代码已自动复制。浏览器打开后直接粘贴代码完成授权，授权成功后窗口可以关闭。");

        TextField codeField = new TextField(deviceCode.getUserCode());
        codeField.setEditable(false);
        codeField.setFocusTraversable(true);
        applyFieldStyle(codeField);

        TextField urlField = new TextField(deviceCode.getVerificationUri());
        urlField.setEditable(false);
        applyFieldStyle(urlField);

        Button openButton = createActionButton("打开浏览器", "primary-button", () -> openMicrosoftVerificationPage(deviceCode));
        Button copyButton = createActionButton("复制代码", "secondary-button", () -> copyMicrosoftDeviceCode(deviceCode.getUserCode()));
        Button closeButton = createActionButton("关闭", "ghost-button", dialog::close);
        HBox actions = new HBox(10, openButton, copyButton, closeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12,
                title,
                message,
                createInfoRow("登录代码", createStaticValueLabel(deviceCode.getUserCode())),
                codeField,
                createInfoRow("验证地址", createStaticValueLabel(deviceCode.getVerificationUri())),
                urlField,
                actions
        );
        root.getStyleClass().add("surface");
        root.setPadding(new Insets(18));

        Scene scene = new Scene(root, 520, 330);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        dialog.show();
        codeField.requestFocus();
        codeField.selectAll();
    }

    private void openMicrosoftVerificationPage(MicrosoftAuth.DeviceCode deviceCode) {
        try {
            getHostServices().showDocument(deviceCode.getVerificationUri());
            setStatus("微软正版登录", "登录代码已自动复制: " + deviceCode.getUserCode() + "。浏览器打开后直接粘贴完成授权。");
        } catch (Exception e) {
            setStatus("无法打开微软登录页面", cleanMessage(e) + "；请手动打开 " + deviceCode.getVerificationUri());
        }
    }

    private void copyMicrosoftDeviceCode(String userCode) {
        if (copyMicrosoftDeviceCodeToClipboard(userCode)) {
            setStatus("已复制微软登录代码", userCode);
        } else {
            setStatus("复制微软登录代码失败", "请手动选择并复制 " + userCode);
        }
    }

    private boolean copyMicrosoftDeviceCodeToClipboard(String userCode) {
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(userCode);
            Clipboard.getSystemClipboard().setContent(content);
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
            MicrosoftAuth microsoftAuth = authenticateMicrosoftAccount();
            Platform.runLater(() -> {
                usernameField.setText(microsoftAuth.getUsername());
                updateRuntimeSummary();
            });
            return microsoftAuth;
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
        if (microsoftLoginBtn != null) {
            microsoftLoginBtn.setDisable(busy);
        }
        versionCombo.setDisable(busy);
        versionTypeCombo.setDisable(busy);
        updateSelectedVersionWikiButton();
        authTypeCombo.setDisable(busy);
        usernameField.setDisable(busy || AUTH_MICROSOFT.equals(authTypeCombo.getValue()));
        yggdrasilServerField.setDisable(busy);
        passwordField.setDisable(busy);
    }

    private void showContentDownloadDialog(ContentTarget target) {
        String gameVersion = getSelectedVersion();
        if (gameVersion == null || gameVersion.isBlank()) {
            setStatus("请选择游戏版本", "下载" + target.title + "前先选择目标 Minecraft 版本。");
            return;
        }

        File importDir = target.folderSupplier.get();
        try {
            ensureDirectory(importDir);
        } catch (IOException e) {
            setStatus("无法创建" + target.title + "目录", cleanMessage(e));
            return;
        }

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

        Button searchBtn = new Button("搜索/刷新");
        searchBtn.getStyleClass().addAll("app-button", "secondary-button");

        HBox searchBar = new HBox(10, searchField, loaderCombo, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        loaderCombo.setPrefWidth(132);

        ListView<ModrinthDownloader.Project> resultList = new ListView<>();
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPrefHeight(220);

        Label descriptionLabel = new Label("正在加载 Modrinth 官网下载列表...");
        descriptionLabel.getStyleClass().add("status-detail");
        descriptionLabel.setWrapText(true);

        Label targetLabel = new Label("目标版本: " + gameVersion + "    列表来源: Modrinth 官网下载量排序    导入目录: " + importDir.getAbsolutePath());
        targetLabel.getStyleClass().add("footer-text");
        targetLabel.setWrapText(true);

        ProgressBar modProgress = new ProgressBar(0);
        modProgress.getStyleClass().add("download-progress");
        modProgress.setMaxWidth(Double.MAX_VALUE);
        modProgress.setVisible(false);
        modProgress.managedProperty().bind(modProgress.visibleProperty());

        Label dialogStatus = new Label("正在加载 Modrinth 官网列表");
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
            descriptionLabel.setText(selected == null ? "点击搜索结果会在这里显示简介" : formatProjectDescription(selected));
        });

        searchBtn.setOnAction(e -> searchModrinthContent(target, searchField, loaderCombo, resultList, dialogStatus, searchBtn, importBtn, gameVersion));
        searchField.setOnAction(e -> searchBtn.fire());
        loaderCombo.setOnAction(e -> {
            if (searchField.getText() == null || searchField.getText().trim().isBlank()) {
                searchModrinthContent(target, searchField, loaderCombo, resultList, dialogStatus, searchBtn, importBtn, gameVersion);
            }
        });

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
        searchModrinthContent(target, searchField, loaderCombo, resultList, dialogStatus, searchBtn, importBtn, gameVersion);
    }

    private void searchModrinthContent(ContentTarget target, TextField searchField, ComboBox<String> loaderCombo,
                                       ListView<ModrinthDownloader.Project> resultList, Label dialogStatus,
                                       Button searchBtn, Button importBtn, String gameVersion) {
        String query = searchField.getText();
        String loader = target.usesLoader() ? loaderCombo.getValue() : null;
        String loaderLabel = loader == null ? "" : " / " + loader;
        boolean officialList = query == null || query.trim().isBlank();

        searchBtn.setDisable(true);
        importBtn.setDisable(true);
        resultList.getItems().clear();
        dialogStatus.setText(officialList
                ? "正在加载 Modrinth 官网" + target.title + "下载列表..."
                : "正在搜索 " + gameVersion + loaderLabel + " 的兼容" + target.title + "...");
        setStatus(officialList ? "正在加载官网列表" : "正在搜索" + target.title,
                officialList ? "Modrinth " + target.title + " · 下载量排序" : query.trim());

        runAsync("ecl-search-modrinth-" + target.projectType, () -> {
            try {
                List<ModrinthDownloader.Project> projects = officialList
                        ? modrinthDownloader.listOfficialProjects(gameVersion, target.projectType, loader, 24)
                        : modrinthDownloader.searchProjects(query, gameVersion, target.projectType, loader, 24);
                Platform.runLater(() -> {
                    resultList.getItems().setAll(projects);
                    if (!projects.isEmpty()) {
                        resultList.getSelectionModel().select(0);
                    }
                    dialogStatus.setText(projects.isEmpty()
                            ? "没有找到兼容 " + gameVersion + loaderLabel + " 的" + target.title + "。"
                            : (officialList ? "已加载 Modrinth 官网列表 " : "找到 ") + projects.size() + " 个结果，选择一个后下载。");
                    setStatus(officialList ? "官网列表已加载" : target.title + "搜索完成",
                            projects.isEmpty() ? "没有找到匹配结果。" : projects.size() + " 个兼容结果。");
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

    private String formatProjectDescription(ModrinthDownloader.Project project) {
        String description = project.getDescription() == null || project.getDescription().isBlank()
                ? "该项目没有提供简介。"
                : project.getDescription();
        return project.getTitle()
                + "\n下载量: " + formatCount(project.getDownloads())
                + "    关注: " + formatCount(project.getFollows())
                + "\n\n" + description;
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

    private File getConfiguredGameRootDir() {
        return gameDir == null ? ECLConfig.getGameDir() : gameDir;
    }

    private File getActiveGameDir() {
        return resolveVersionGameDir(getSelectedVersion());
    }

    private File resolveVersionGameDir(String gameVersion) {
        File rootDir = getConfiguredGameRootDir();
        if (gameVersion == null || gameVersion.isBlank()) {
            return rootDir;
        }
        return new File(new File(rootDir, "instances"), sanitizeVersionDirectoryName(gameVersion));
    }

    private void ensureVersionGameDirs(String gameVersion) throws IOException {
        File instanceDir = resolveVersionGameDir(gameVersion);
        ensureDirectory(instanceDir);
        ensureDirectory(new File(instanceDir, "mods"));
        ensureDirectory(new File(instanceDir, "shaderpacks"));
        ensureDirectory(new File(instanceDir, "resourcepacks"));
        ensureDirectory(new File(instanceDir, "saves"));
        ensureDirectory(new File(instanceDir, "logs"));
    }

    private void ensureDirectory(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
    }

    private String sanitizeVersionDirectoryName(String version) {
        String sanitized = version.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return sanitized.isBlank() ? "unknown-version" : sanitized;
    }

    private File resolveModsDir(String gameVersion) {
        return new File(resolveVersionGameDir(gameVersion), "mods");
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
        scrollPane.setMinHeight(0);
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

    private void openMinecraftWikiVersionPage(String version) {
        if (version == null || version.isBlank()) {
            setStatus("未选择版本", "请先选择一个正式版或快照版。");
            return;
        }
        if (!isWikiSupportedVersion(version)) {
            setStatus("当前版本暂无 Wiki 入口", "仅正式版和快照版提供 mc 中文 Wiki 更新介绍按钮。");
            return;
        }

        String url = buildMinecraftWikiVersionUrl(version);
        try {
            getHostServices().showDocument(url);
            setStatus("已打开版本介绍", version + " 的 mc 中文 Wiki 更新介绍已在浏览器中打开。");
        } catch (Exception e) {
            setStatus("无法打开版本介绍", cleanMessage(e));
        }
    }

    private boolean isWikiSupportedVersion(String version) {
        return versionManager != null && versionManager.isReleaseOrSnapshot(version);
    }

    private String buildMinecraftWikiVersionUrl(String version) {
        String pageName = "Java版" + version;
        return MC_CHINESE_WIKI_VERSION_URL_PREFIX
                + URLEncoder.encode(pageName, StandardCharsets.UTF_8).replace("+", "%20");
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

    private Label createValueLabel(String text) {
        Label label = createValueLabel();
        label.setText(text);
        return label;
    }

    private void applyFieldStyle(Control control) {
        control.getStyleClass().add("field-control");
        control.setMaxWidth(Double.MAX_VALUE);
    }

    private Button createIconActionButton(String iconResource, String fallbackIcon, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(createIconNode(iconResource, fallbackIcon, 34, "icon-button-image"));
        button.getStyleClass().addAll("app-button", "icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> action.run());
        return button;
    }

    private Node createIconNode(String resourcePath, String fallbackText, double size, String styleClass) {
        URL iconUrl = resourcePath == null ? null : getClass().getResource(resourcePath);
        if (iconUrl != null) {
            ImageView icon = new ImageView(new Image(iconUrl.toExternalForm()));
            icon.setFitWidth(size);
            icon.setFitHeight(size);
            icon.setPreserveRatio(true);
            icon.getStyleClass().add(styleClass);
            return icon;
        }
        Label fallback = new Label(fallbackText == null ? "" : fallbackText);
        fallback.getStyleClass().add(styleClass);
        return fallback;
    }

    private String iconForContentTarget(ContentTarget target) {
        if (target == null || target.projectType == null) {
            return ICON_GRASS_BLOCK;
        }
        return switch (target.projectType) {
            case "shader" -> ICON_LAMP_BLOCK;
            case "resourcepack" -> ICON_WOOD_BLOCK;
            case "modpack" -> ICON_STONE_BLOCK;
            default -> ICON_GRASS_BLOCK;
        };
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

    private String formatCount(long value) {
        if (value >= 100000000) {
            return String.format("%.1f 亿", value / 100000000.0);
        }
        if (value >= 10000) {
            return String.format("%.1f 万", value / 10000.0);
        }
        return Long.toString(value);
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
