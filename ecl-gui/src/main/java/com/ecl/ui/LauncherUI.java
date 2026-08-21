package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MicrosoftAccountStore;
import com.ecl.auth.MinecraftSkinService;
import com.ecl.auth.OfflineSkinStore;
import com.ecl.backup.WorldBackupService;
import com.ecl.config.SettingsManager;
import com.ecl.diagnostic.DiagnosticBundleService;
import com.ecl.download.DownloadService;
import com.ecl.download.ContentDownloader;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.ModrinthDownloader;
import com.ecl.download.ServerJarDownloader;
import com.ecl.desktop.DesktopShortcutService;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import com.ecl.game.PlaytimeTracker;
import com.ecl.launch.Launcher;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.modrinth.pack.ModpackUpdate;
import com.ecl.modrinth.pack.ModpackUpdateService;
import com.ecl.modrinth.provider.ContentSource;
import com.ecl.modrinth.ui.ChineseDescriptionService;
import com.ecl.modrinth.ui.ModBrowserView;
import com.ecl.modrinth.ui.RemoteImageLoader;
import com.ecl.server.ServerBrowserView;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.Messages;
import com.ecl.util.TextUtil;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ecl.util.TextUtil.abbreviate;
import static com.ecl.util.TextUtil.formatCount;

public class LauncherUI extends javafx.application.Application {
    static final Logger LOGGER = LoggerFactory.getLogger(LauncherUI.class);
    static final String AUTH_OFFLINE = "OFFLINE";
    static final String AUTH_MICROSOFT = "MICROSOFT";
    static final String AUTH_YGGDRASIL = "YGGDRASIL";
    static final String MC_CHINESE_WIKI_VERSION_URL_PREFIX = "https://zh.minecraft.wiki/w/";
    private static final double WINDOW_WIDTH = 1440;
    private static final double WINDOW_HEIGHT = 900;
    private static final double NAV_WIDTH = 200;
    private static final double LAUNCH_WIDTH = 1180;
    private static final double UTILITY_WIDTH = 360;
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
    private static final String ICON_CHECK = "/icons/ui/check.png";
    private static final String ICON_SIGNAL = "/icons/ui/signal.png";

    VersionManager versionManager;
    DownloadService downloader;
    DownloadTaskCenter downloadTaskCenter;
    private ModrinthDownloader modrinthDownloader;
    private ServerJarDownloader serverJarDownloader;
    ModLoaderInstaller modLoaderInstaller;
    private MrpackInstaller mrpackInstaller;
    WorldBackupService worldBackupService;
    MicrosoftAccountStore microsoftAccountStore;
    MinecraftSkinService minecraftSkinService;
    Launcher gameLauncher;
    SettingsManager settingsManager;
    MainController controller;
    Stage primaryStage;
    private FirstRunWizard firstRunWizard;
    MicrosoftAccountCoordinator microsoftAccounts;
    SkinCoordinator skins;
    GameLaunchCoordinator gameLaunch;
    VersionActions versionActions;

    ComboBox<String> versionCombo;
    String lastContentVersion;
    ComboBox<VersionManager.VersionCategory> versionTypeCombo;
    ComboBox<LoaderChoice> loaderChoiceCombo;
    Button installSelectedLoaderButton;
    boolean syncingLoaderChoice;
    TextField usernameField;
    PasswordField passwordField;
    ProgressBar downloadProgress;
    private Label statusLabel;
    Label detailLabel;
    Button launchBtn;
    Button refreshBtn;
    Button settingsBtn;
    Button microsoftLoginBtn;
    Button microsoftAddAccountBtn;
    Button skinUploadBtn;
    Button homeSkinUploadButton;
    Button offlineSkinRemoveBtn;
    ComboBox<MicrosoftAccountStore.Account> microsoftAccountCombo;
    volatile MicrosoftAccountStore.Account selectedMicrosoftAccount;
    volatile boolean lastMicrosoftAccountPersisted = true;
    Button selectedVersionWikiButton;
    ComboBox<String> authTypeCombo;
    TextField yggdrasilServerField;
    Label serverLabel;
    Label passwordLabel;

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
    private Label selectedRuntimeMetaLabel;
    private Label launchReadinessLabel;
    private Label homeAccountNameLabel;
    private Label homeAccountTypeLabel;
    private Label homeAccountAvatarLabel;
    private Label homeEnvironmentStatusLabel;
    private Label topTaskLabel;
    Label playtimeTotalLabel;
    Label playtimeRecentLabel;
    Label playtimeLaunchCountLabel;
    private DownloadTasksPage downloadTasksPage;
    private TitledPane instanceSettingsPane;
    private VBox homePage;
    private HBox workspacePane;
    private List<ContentTarget> contentTargets;

    String javaPath;
    File gameDir;
    String extraJvmArgs;
    int maxMemoryMb;
    int gameWidth;
    int gameHeight;
    boolean gameFullscreen;
    String quickServer;
    boolean closeAfterLaunch;
    int processorCount;
    boolean showGameConsole;
    boolean backupOnLaunch;
    int backupKeepCount;
    boolean backupIncludeMods;
    final LauncherLogBuffer liveGameLog =
            new LauncherLogBuffer(ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS);
    final PlaytimeTracker playtimeTracker = new PlaytimeTracker();
    TextArea liveConsoleArea;
    final StringBuilder pendingConsoleText = new StringBuilder();
    final AtomicBoolean consoleFlushScheduled = new AtomicBoolean();
    final AtomicBoolean applicationStopping = new AtomicBoolean();
    volatile Process activeGameProcess;
    volatile String activeGameVersion;
    private double windowDragOffsetX;
    private double windowDragOffsetY;
    private final Map<ProgressBar, Timeline> progressAnimations = new HashMap<>();
    private final Map<AppView, Button> navButtons = new HashMap<>();
    private VBox navButtonColumn;
    private Animation contentTransition;
    private ModBrowserView activeModBrowserView;
    private ServerBrowserView activeServerBrowserView;
    private AppView activeView = AppView.HOME;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        controller = new MainController();
        settingsManager = controller.settings();
        Messages.setLocale(Locale.forLanguageTag(settingsManager.get(ECLConfig.KEY_LANGUAGE)));
        versionManager = controller.versions();
        downloader = controller.gameDownloader();
        downloadTaskCenter = controller.downloadTasks();
        downloadTaskCenter.addListener(this::onDownloadTasksChanged);
        applyRequestedInstanceArgument();
        modrinthDownloader = controller.modrinthDownloader();
        serverJarDownloader = new ServerJarDownloader(versionManager);
        modLoaderInstaller = new ModLoaderInstaller();
        mrpackInstaller = new MrpackInstaller();
        worldBackupService = new WorldBackupService();
        microsoftAccountStore = new MicrosoftAccountStore();
        minecraftSkinService = new MinecraftSkinService();
        gameLauncher = controller.gameLauncher();
        microsoftAccounts = new MicrosoftAccountCoordinator(this);
        skins = new SkinCoordinator(this);
        gameLaunch = new GameLaunchCoordinator(this);
        versionActions = new VersionActions(this);

        javaPath = settingsManager.get(ECLConfig.KEY_JAVA_PATH);
        gameDir = resolveConfiguredGameRootDir(new File(
                settingsManager.get(ECLConfig.KEY_GAME_DIR)));
        extraJvmArgs = settingsManager.get(ECLConfig.KEY_JVM_ARGS);
        Integer storedMemory = settingsManager.get(ECLConfig.KEY_MAX_MEMORY_MB);
        maxMemoryMb = storedMemory == null ? ECLConfig.AUTO_MEMORY_MB : storedMemory;
        if (maxMemoryMb < ECLConfig.AUTO_MEMORY_MB
                || (maxMemoryMb > ECLConfig.AUTO_MEMORY_MB && maxMemoryMb < ECLConfig.MIN_GAME_MEMORY_MB)
                || maxMemoryMb > ECLConfig.MAX_GAME_MEMORY_MB) {
            maxMemoryMb = ECLConfig.AUTO_MEMORY_MB;
        }

        gameWidth = settingsManager.get(ECLConfig.KEY_GAME_WIDTH);
        gameHeight = settingsManager.get(ECLConfig.KEY_GAME_HEIGHT);
        gameFullscreen = settingsManager.get(ECLConfig.KEY_GAME_FULLSCREEN);
        quickServer = settingsManager.get(ECLConfig.KEY_QUICK_SERVER);
        closeAfterLaunch = settingsManager.get(ECLConfig.KEY_CLOSE_AFTER_LAUNCH);
        processorCount = settingsManager.get(ECLConfig.KEY_PROCESSOR_COUNT);
        showGameConsole = settingsManager.get(ECLConfig.KEY_SHOW_GAME_CONSOLE);
        backupOnLaunch = settingsManager.get(ECLConfig.KEY_BACKUP_ON_LAUNCH);
        backupKeepCount = Math.max(1, Math.min(100,
                settingsManager.get(ECLConfig.KEY_BACKUP_KEEP_COUNT)));
        backupIncludeMods = settingsManager.get(ECLConfig.KEY_BACKUP_INCLUDE_MODS);
        contentTargets = createContentTargets();

        primaryStage.initStyle(StageStyle.UNDECORATED);

        Pane root = createRoot();
        root.getStyleClass().add("scene-root");
        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        primaryStage.setTitle(Messages.get("app.title"));
        applyWindowIcon(primaryStage);
        primaryStage.setMinWidth(1180);
        primaryStage.setMinHeight(720);
        primaryStage.setScene(scene);
        applyTheme(settingsManager.get(ECLConfig.KEY_THEME));
        primaryStage.show();
        primaryStage.centerOnScreen();
        root.setFocusTraversable(true);
        Platform.runLater(root::requestFocus);

        updateAuthFields();
        updateRuntimeSummary();
        setStatus(Messages.get("status.ready"), Messages.get("status.ready.detail"));
        if (!Boolean.getBoolean("ecl.snapshot")) {
            versionActions.refreshVersions();
            if (!settingsManager.get(ECLConfig.KEY_FIRST_RUN_COMPLETED)) {
                Platform.runLater(this::showFirstRunWizard);
            }
        }
    }

    private void applyRequestedInstanceArgument() {
        List<String> arguments = getParameters() == null ? List.of() : getParameters().getRaw();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String value = null;
            if (argument.startsWith("--instance=")) {
                value = argument.substring("--instance=".length());
            } else if ((argument.equals("--instance") || argument.equals("--version"))
                    && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            }
            if (value != null && !value.isBlank()) {
                settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, value.trim());
                return;
            }
        }
    }

    private void onDownloadTasksChanged(List<DownloadTaskCenter.TaskSnapshot> tasks) {
        Runnable update = () -> {
            long active = tasks.stream().filter(task -> task.status() == DownloadTaskCenter.Status.QUEUED
                    || task.status() == DownloadTaskCenter.Status.RUNNING
                    || task.status() == DownloadTaskCenter.Status.CANCELLING).count();
            long failed = tasks.stream().filter(task -> task.status() == DownloadTaskCenter.Status.FAILED).count();
            if (topTaskLabel != null) {
                topTaskLabel.setText(active == 0 && failed == 0
                        ? Messages.get("download.none")
                        : Messages.format("download.summary.count", active, failed));
                topTaskLabel.setTooltip(new Tooltip(Messages.get("download.tooltip.open")));
            }
            if (downloadTasksPage != null) {
                downloadTasksPage.updateTasks(tasks);
            }
        };
        if (Platform.isFxApplicationThread()) update.run();
        else Platform.runLater(update);
    }

    @Override
    public void stop() {
        applicationStopping.set(true);
        stopAllProgressAnimations();
        closeActiveModBrowserView();
        closeActiveServerBrowserView();
        if (contentTransition != null) {
            contentTransition.stop();
        }
        if (controller != null) {
            controller.close();
        }
    }

    private void showFirstRunWizard() {
        if (firstRunWizard == null) {
            firstRunWizard = new FirstRunWizard(settingsManager, this::switchLanguage,
                    this::languageDisplayName, this::applyThemeToScene);
        }
        firstRunWizard.show(primaryStage);
    }

    private void exportDiagnosticBundle() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("diagnostic.export"));
        chooser.setInitialFileName("ecl-diagnostics.zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        File selected = chooser.showSaveDialog(primaryStage);
        if (selected == null) return;
        try {
            Path exported = new DiagnosticBundleService().export(
                    selected.toPath(), ECLConfig.getBaseDir().toPath(), getActiveGameDir().toPath());
            setStatus(Messages.get("diagnostic.export"), exported.toString());
        } catch (IOException error) {
            setStatus(Messages.get("diagnostic.export"), cleanMessage(error));
        }
    }

    private Pane createRoot() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setPadding(Insets.EMPTY);

        root.setTop(createWindowTitleBar());

        workspacePane = new HBox(24);
        workspacePane.getStyleClass().add("main-body");
        workspacePane.setAlignment(Pos.TOP_CENTER);
        workspacePane.setFillHeight(true);
        renderActiveView();
        root.setCenter(createWheelScrollPane(workspacePane));
        BorderPane.setMargin(root.getCenter(), Insets.EMPTY);
        root.setBottom(createFooterBar());
        BorderPane.setMargin(root.getBottom(), Insets.EMPTY);
        installModDropTarget(root);

        return root;
    }

    private void installModDropTarget(Pane root) {
        root.addEventHandler(DragEvent.DRAG_OVER, event -> {
            if (hasModFiles(event.getDragboard())) {
                event.acceptTransferModes(TransferMode.COPY);
                root.getStyleClass().add("drop-target-active");
            }
            event.consume();
        });
        root.addEventHandler(DragEvent.DRAG_EXITED, event -> {
            root.getStyleClass().remove("drop-target-active");
            event.consume();
        });
        root.addEventHandler(DragEvent.DRAG_DROPPED, event -> {
            root.getStyleClass().remove("drop-target-active");
            List<Path> files = event.getDragboard().hasFiles()
                    ? event.getDragboard().getFiles().stream()
                    .map(File::toPath)
                    .filter(this::isModJar)
                    .toList() : List.of();
            event.setDropCompleted(!files.isEmpty());
            event.consume();
            if (!files.isEmpty()) {
                importDroppedMods(files);
            } else {
                setStatus("Mod 导入", "请拖入 .jar 模组文件");
            }
        });
    }

    private boolean hasModFiles(javafx.scene.input.Dragboard board) {
        return board != null && board.hasFiles()
                && board.getFiles().stream().anyMatch(file -> isModJar(file.toPath()));
    }

    private boolean isModJar(Path file) {
        return file != null && Files.isRegularFile(file)
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private void importDroppedMods(List<Path> files) {
        String profileId = getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            setStatus("Mod 导入失败", "请先选择一个 Fabric、Quilt、Forge 或 NeoForge 实例");
            return;
        }
        try {
            ModInstanceContext instance = VersionProfileModInstanceContext.load(
                    profileId,
                    ECLConfig.getVersionsDir().toPath(),
                    getConfiguredGameRootDir().toPath(),
                    resolveVersionGameDir(profileId).toPath());
            if (!instance.loader().supportsMods()) {
                setStatus("Mod 导入失败", "当前实例不是支持模组的加载器实例");
                return;
            }
            CompletableFuture<List<String>> imported = CompletableFuture.completedFuture(new java.util.ArrayList<>());
            for (Path file : files) {
                imported = imported.thenCompose(names ->
                        controller.modManagementService().importLocalJar(instance, file)
                                .thenApply(mod -> {
                                    names.add(file.getFileName().toString());
                                    return names;
                                }));
            }
            imported.whenComplete((names, error) -> Platform.runLater(() -> {
                if (error != null) {
                    setStatus("Mod 导入失败", cleanMessage(error));
                } else {
                    setStatus("Mod 导入完成", "已导入 " + names.size()
                            + " 个模组到 " + instance.modsDirectory());
                    if (activeModBrowserView != null) {
                        activeModBrowserView.refreshInstalledMods();
                    }
                }
            }));
        } catch (Exception error) {
            setStatus("Mod 导入失败", cleanMessage(error));
        }
    }

    private HBox createWindowTitleBar() {
        HBox titleBar = new HBox(24);
        titleBar.getStyleClass().add("window-title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("ECL");
        title.getStyleClass().addAll("window-title", "brand-label");

        HBox navigation = new HBox(4);
        navigation.getStyleClass().add("global-nav");
        navigation.setAlignment(Pos.CENTER);
        navButtons.clear();
        for (AppView view : AppView.values()) {
            navigation.getChildren().add(createNavButton(view));
        }

        Region leftSpacer = new Region();
        Region rightSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        topTaskLabel = new Label(Messages.get("download.none"));
        topTaskLabel.getStyleClass().add("task-chip");
        topTaskLabel.setOnMouseClicked(event -> setActiveView(AppView.DOWNLOADS));
        topTaskLabel.setCursor(javafx.scene.Cursor.HAND);

        topAuthBadgeLabel = createValueLabel("Steve");
        topAuthBadgeLabel.getStyleClass().add("account-chip");

        topVersionBadgeLabel = createValueLabel("未选择");
        runtimeBadgeLabel = createValueLabel("检查中");
        topMemoryBadgeLabel = createValueLabel("自动");

        Button minimizeButton = createWindowButton("—", () -> primaryStage.setIconified(true));
        Button maximizeButton = createWindowButton("□", () -> primaryStage.setMaximized(!primaryStage.isMaximized()));
        Button closeButton = createWindowButton("×", () -> primaryStage.close());
        closeButton.getStyleClass().add("window-close-button");

        HBox windowControls = new HBox(4, minimizeButton, maximizeButton, closeButton);
        windowControls.getStyleClass().add("window-controls");
        windowControls.setAlignment(Pos.CENTER_RIGHT);

        titleBar.getChildren().addAll(
                title,
                leftSpacer,
                navigation,
                rightSpacer,
                topTaskLabel,
                topAuthBadgeLabel,
                windowControls
        );
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
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    private HBox createHeader() {
        HBox header = new HBox(16);
        header.getStyleClass().add("hero-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(Messages.get("app.launcherTitle"));
        title.getStyleClass().add("app-title");
        Label subtitle = new Label(Messages.format("app.subtitleVersion", Messages.get("app.version")));
        subtitle.getStyleClass().add("app-subtitle");

        topAuthBadgeLabel = createValueLabel("Steve");
        topVersionBadgeLabel = createValueLabel(Messages.get("label.notSelected"));
        runtimeBadgeLabel = createValueLabel(Messages.get("status.checking"));
        topMemoryBadgeLabel = createValueLabel(Messages.get("label.auto"));

        header.getChildren().addAll(title, subtitle);
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
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label version = new Label("ECL " + Messages.get("app.version"));
        version.getStyleClass().add("footer-info");
        Label state = new Label(Messages.get("footer.ready"));
        state.getStyleClass().add("footer-info");

        HBox footer = new HBox(8, version, spacer, state);
        footer.getStyleClass().add("footer-bar");
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    private VBox createNavigationRail() {
        VBox rail = new VBox();
        rail.getStyleClass().add("nav-rail");
        rail.setPrefWidth(NAV_WIDTH);
        rail.setMinWidth(NAV_WIDTH);
        rail.setMaxWidth(NAV_WIDTH);

        Label header = new Label(Messages.get("nav.command"));
        header.getStyleClass().add("nav-rail-header");
        header.setMaxWidth(Double.MAX_VALUE);

        navButtonColumn = new VBox();
        navButtonColumn.getStyleClass().add("nav-button-column");
        navButtonColumn.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(navButtonColumn, Priority.ALWAYS);

        navButtons.clear();
        for (AppView view : AppView.values()) {
            navButtonColumn.getChildren().add(createNavButton(view));
        }

        VBox footer = createNavRailFooter();

        rail.getChildren().addAll(header, navButtonColumn, footer);
        Platform.runLater(() -> updateNavSelection(false));
        return rail;
    }

    private VBox createNavRailFooter() {
        VBox box = new VBox(8);
        box.getStyleClass().add("nav-rail-footer");

        Label cpuLabel = new Label(Messages.get("telemetry.cpu"));
        cpuLabel.getStyleClass().add("telemetry-label");
        ProgressBar cpuBar = new ProgressBar(0.12);
        cpuBar.setMaxWidth(Double.MAX_VALUE);
        Label cpuValue = new Label("12%");
        cpuValue.getStyleClass().add("telemetry-value");
        HBox.setHgrow(cpuBar, Priority.ALWAYS);
        HBox cpuRow = new HBox(8, cpuLabel, cpuBar, cpuValue);
        cpuRow.setAlignment(Pos.CENTER_LEFT);

        Label ramLabel = new Label(Messages.get("telemetry.memory"));
        ramLabel.getStyleClass().add("telemetry-label");
        ProgressBar ramBar = new ProgressBar(0.42);
        ramBar.setMaxWidth(Double.MAX_VALUE);
        Label ramValue = new Label("4.2G");
        ramValue.getStyleClass().add("telemetry-value");
        HBox.setHgrow(ramBar, Priority.ALWAYS);
        HBox ramRow = new HBox(8, ramLabel, ramBar, ramValue);
        ramRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(cpuRow, ramRow);
        return box;
    }

    private void showNavCandidate(AppView candidate) {
        for (Map.Entry<AppView, Button> entry : navButtons.entrySet()) {
            Button button = entry.getValue();
            button.getStyleClass().remove("nav-button-selected");
            if (entry.getKey() == candidate) {
                button.getStyleClass().add("nav-button-selected");
            }
        }
    }

    private Button createNavButton(AppView view) {
        Button button = new Button(navTitleFor(view));
        button.getStyleClass().add("nav-button");
        if (view == activeView) {
            button.getStyleClass().add("nav-button-selected");
        }
        button.setOnAction(e -> setActiveView(view));
        navButtons.put(view, button);
        return button;
    }

    private String formatNavTitle(AppView view) {
        return String.format("%02d ▸ %s", view.ordinal() + 1, navTitleFor(view));
    }

    private String navTitleFor(AppView view) {
        return switch (view) {
            case HOME -> Messages.get("nav.short.home");
            case VERSIONS -> Messages.get("nav.short.versions");
            case DOWNLOADS -> Messages.get("nav.short.downloads");
            case MODRINTH -> Messages.get("nav.short.modrinth");
            case SERVERS -> Messages.get("nav.short.servers");
            case LOGS -> Messages.get("nav.short.logs");
            case SETTINGS -> Messages.get("nav.short.settings");
        };
    }

    private String navSubtitleFor(AppView view) {
        return switch (view) {
            case HOME -> Messages.get("nav.subtitle.home");
            case VERSIONS -> Messages.get("nav.subtitle.versions");
            case DOWNLOADS -> Messages.get("nav.subtitle.downloads");
            case MODRINTH -> Messages.get("nav.subtitle.modrinth");
            case SERVERS -> Messages.get("nav.subtitle.servers");
            case SETTINGS -> Messages.get("nav.subtitle.settings");
            case LOGS -> Messages.get("nav.subtitle.logs");
        };
    }

    void setActiveView(AppView view) {
        if (view == null || workspacePane == null) {
            return;
        }
        if (view == activeView) {
            return;
        }
        int slideDirection = Integer.compare(view.ordinal(), activeView.ordinal());
        if (view != AppView.HOME && passwordField != null) {
            passwordField.clear();
        }
        activeView = view;
        renderActiveView(slideDirection);
        showNavCandidate(view);
        if (authTypeCombo != null && authSummaryLabel != null) {
            updateAuthFields();
        } else {
            updateRuntimeSummary();
        }
    }

    private void updateNavSelection(boolean animate) {
        showNavCandidate(activeView);
    }

    void renderActiveView() {
        renderActiveView(0);
    }

    void renderActiveView(int slideDirection) {
        if (workspacePane == null) {
            return;
        }
        closeActiveModBrowserView();
        closeActiveServerBrowserView();
        workspacePane.getChildren().clear();

        switch (activeView) {
            case HOME -> addMainContent(getOrCreateHomePage(), null);
            case VERSIONS -> addMainContent(createVersionsPage(), null);
            case DOWNLOADS -> addMainContent(createDownloadTasksPage(), null);
            case MODRINTH -> addMainContent(createModrinthPage(), null);
            case SERVERS -> addMainContent(createServersPage(), null);
            case SETTINGS -> addMainContent(createSettingsPage(), null);
            case LOGS -> addMainContent(createLogsPage(), null);
        }
        if (slideDirection != 0) {
            playContentTransition(slideDirection);
        }
    }

    private void playContentTransition(int direction) {
        if (Boolean.getBoolean("ecl.reduceMotion") || workspacePane.getChildren().isEmpty()) {
            return;
        }
        if (contentTransition != null) {
            contentTransition.stop();
        }

        double offset = 26 * direction;
        Timeline transition = new Timeline();
        for (int i = 0; i < workspacePane.getChildren().size(); i++) {
            Node node = workspacePane.getChildren().get(i);
            double delay = i * 45.0;
            node.setOpacity(0);
            node.setTranslateX(offset);
            transition.getKeyFrames().addAll(
                    new KeyFrame(Duration.millis(delay),
                            new KeyValue(node.opacityProperty(), 0),
                            new KeyValue(node.translateXProperty(), offset)),
                    new KeyFrame(Duration.millis(300 + delay),
                            new KeyValue(node.opacityProperty(), 1, Interpolator.EASE_OUT),
                            new KeyValue(node.translateXProperty(), 0,
                                    Interpolator.SPLINE(0.16, 0.86, 0.24, 1.0)))
            );
        }
        contentTransition = transition;
        contentTransition.play();
    }

    private void addMainContent(Node primary, Node secondary) {
        HBox.setHgrow(primary, Priority.ALWAYS);
        workspacePane.getChildren().add(primary);
        if (secondary != null) {
            HBox.setHgrow(secondary, Priority.NEVER);
            workspacePane.getChildren().add(secondary);
        }
    }

    private List<ContentTarget> createContentTargets() {
        return ContentTargetFactory.create(
                this::resolveModsDir, this::resolveVersionGameDir,
                this::getConfiguredGameRootDir);
    }

    private VBox createUtilityColumn() {
        VBox pane = new VBox(8);
        pane.getStyleClass().add("utility-column");
        pane.setPrefWidth(UTILITY_WIDTH);
        pane.setMinWidth(UTILITY_WIDTH);
        pane.setMaxWidth(UTILITY_WIDTH);

        statusLabel = new Label(Messages.get("home.noTasks"));
        statusLabel.getStyleClass().add("status-title");
        detailLabel = new Label();
        detailLabel.getStyleClass().add("status-detail");
        detailLabel.setWrapText(true);
        detailLabel.setText(Messages.get("progress.idle"));

        downloadProgress = new ProgressBar(0);
        downloadProgress.getStyleClass().add("download-progress");
        downloadProgress.setMaxWidth(Double.MAX_VALUE);
        downloadProgress.setVisible(true);

        VBox statusCard = createSurface(
                Messages.get("download.center.title"),
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
        HBox okRow = createDiagnosticRow(ICON_CHECK, "✓", Messages.get("diagnostic.status"), "");
        HBox crashRow = createDiagnosticRow(ICON_LOG, "▤", Messages.get("label.crashReports"), String.valueOf(countCrashReports()));

        VBox rows = new VBox(0, okRow, crashRow);
        rows.getStyleClass().add("sidebar-list");

        return createSurface(
                Messages.get("diagnostic.title"),
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
                createSidebarModrinthRow(contentTargets.get(0), Messages.get("content.sidebar.mods")),
                createSidebarModrinthRow(contentTargets.get(1), Messages.get("content.sidebar.shaders")),
                createSidebarModrinthRow(contentTargets.get(2), Messages.get("content.sidebar.resourcepacks"))
        );
        return createSurface(Messages.get("content.recommended.title"), null, rows);
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

    private VBox getOrCreateHomePage() {
        if (homePage == null) {
            homePage = createLaunchPane();
        }
        return homePage;
    }

    private VBox createLaunchPane() {
        VBox pane = new VBox(24);
        pane.getStyleClass().add("launch-pane");
        pane.setPrefWidth(LAUNCH_WIDTH);
        pane.setMaxWidth(LAUNCH_WIDTH);
        pane.setFillWidth(true);
        HBox.setHgrow(pane, Priority.ALWAYS);

        Label pageTitle = new Label(Messages.get("home.title"));
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label(Messages.get("home.subtitle"));
        pageSubtitle.getStyleClass().add("page-subtitle");
        VBox pageHeading = new VBox(6, pageTitle, pageSubtitle);
        pageHeading.getStyleClass().add("page-heading");
        pageHeading.setAlignment(Pos.CENTER);

        HBox hero = createLaunchHero();
        GridPane launchForm = createForm();
        HBox summaryCards = createHomeSummaryCards();

        instanceSettingsPane = new TitledPane(Messages.get("home.instanceAccount"), launchForm);
        instanceSettingsPane.getStyleClass().add("instance-settings");
        instanceSettingsPane.setExpanded(false);
        instanceSettingsPane.setAnimated(false);
        instanceSettingsPane.setMaxWidth(Double.MAX_VALUE);

        pane.getChildren().addAll(pageHeading, hero, summaryCards, instanceSettingsPane);
        return pane;
    }

    private HBox createLaunchHero() {
        Label eyebrow = new Label(Messages.get("home.currentInstance"));
        eyebrow.getStyleClass().add("launch-eyebrow");

        selectedVersionTitleLabel = new Label(Messages.get("home.selectVersion"));
        selectedVersionTitleLabel.getStyleClass().add("launch-version-big");

        selectedRuntimeMetaLabel = new Label(Messages.format("home.runtimeMeta",
                Runtime.version().feature(), gameLaunch.getMemoryDisplayText()));
        selectedRuntimeMetaLabel.getStyleClass().add("launch-version-meta");

        launchReadinessLabel = new Label(Messages.get("home.autoCheck"));
        launchReadinessLabel.getStyleClass().add("ready-pill");

        VBox details = new VBox(16,
                eyebrow,
                selectedVersionTitleLabel,
                selectedRuntimeMetaLabel,
                launchReadinessLabel,
                createActionBar());
        details.getStyleClass().add("launch-details");
        details.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(details, Priority.ALWAYS);

        StackPane artwork = createHeroArtwork();
        HBox hero = new HBox(48, details, artwork);
        hero.getStyleClass().addAll("launch-surface", "launch-hero");
        hero.setAlignment(Pos.CENTER);
        return hero;
    }

    private StackPane createHeroArtwork() {
        Region glow = new Region();
        glow.getStyleClass().add("orb-glow");
        Region outerRing = new Region();
        outerRing.getStyleClass().add("orb-ring-outer");
        Region innerRing = new Region();
        innerRing.getStyleClass().add("orb-ring-inner");
        Region orb = new Region();
        orb.getStyleClass().add("orb-core");
        Label play = new Label("▶");
        play.getStyleClass().add("orb-play");

        StackPane artwork = new StackPane(glow, outerRing, innerRing, orb, play);
        artwork.getStyleClass().add("hero-artwork");
        artwork.setAlignment(Pos.CENTER);
        return artwork;
    }

    private HBox createHomeSummaryCards() {
        VBox accountCard = new VBox(14);
        accountCard.getStyleClass().addAll("home-card", "account-card");
        Label accountLabel = new Label(Messages.get("home.account"));
        accountLabel.getStyleClass().add("card-kicker");
        homeAccountAvatarLabel = new Label("S");
        homeAccountAvatarLabel.getStyleClass().add("account-avatar");
        homeAccountNameLabel = new Label(getAuthDisplayName());
        homeAccountNameLabel.getStyleClass().add("card-title");
        homeAccountTypeLabel = new Label(Messages.get("auth.offline"));
        homeAccountTypeLabel.getStyleClass().add("card-subtitle");
        VBox accountText = new VBox(3, homeAccountNameLabel, homeAccountTypeLabel);
        HBox accountProfile = new HBox(14, homeAccountAvatarLabel, accountText);
        accountProfile.setAlignment(Pos.CENTER_LEFT);
        Region accountSpacer = new Region();
        VBox.setVgrow(accountSpacer, Priority.ALWAYS);
        Button manageAccount = createLinkButton(Messages.get("home.manageAccount"), () -> expandInstanceSettings(authTypeCombo));
        homeSkinUploadButton = createLinkButton(Messages.get("home.uploadSkin"), () -> skins.chooseAndUploadSkin());
        Region accountActionSpacer = new Region();
        HBox.setHgrow(accountActionSpacer, Priority.ALWAYS);
        HBox accountActions = new HBox(8, manageAccount, accountActionSpacer, homeSkinUploadButton);
        accountActions.setAlignment(Pos.CENTER_LEFT);
        accountCard.getChildren().addAll(accountLabel, accountProfile, accountSpacer, accountActions);

        VBox environmentCard = new VBox(12);
        environmentCard.getStyleClass().addAll("home-card", "environment-card");
        Label environmentLabel = new Label(Messages.get("home.environment"));
        environmentLabel.getStyleClass().add("card-kicker");
        homeEnvironmentStatusLabel = new Label(Messages.get("home.environmentStatus"));
        homeEnvironmentStatusLabel.getStyleClass().add("card-title");
        Label environmentCheck = new Label("✓");
        environmentCheck.getStyleClass().add("environment-check");
        Region environmentTitleSpacer = new Region();
        HBox.setHgrow(environmentTitleSpacer, Priority.ALWAYS);
        HBox environmentTitle = new HBox(
                homeEnvironmentStatusLabel,
                environmentTitleSpacer,
                environmentCheck);
        environmentTitle.setAlignment(Pos.CENTER_LEFT);
        javaSummaryLabel = createValueLabel(Messages.format("home.javaSummary", Runtime.version().feature()));
        memorySummaryLabel = createValueLabel(gameLaunch.getMemoryDisplayText());
        versionSummaryLabel = createValueLabel(Messages.get("home.versionPending"));
        environmentCard.getChildren().addAll(
                environmentLabel,
                environmentTitle,
                createSummaryRow(Messages.get("info.java"), javaSummaryLabel),
                createSummaryRow(Messages.get("home.memory"), memorySummaryLabel),
                createSummaryRow(Messages.get("home.instance"), versionSummaryLabel));

        VBox taskCard = new VBox(12);
        taskCard.getStyleClass().addAll("home-card", "task-card");
        Label taskLabel = new Label(Messages.get("home.currentActivity"));
        taskLabel.getStyleClass().add("card-kicker");
        statusLabel = new Label(Messages.get("home.noTasks"));
        statusLabel.getStyleClass().add("card-title");
        detailLabel = new Label(Messages.get("home.taskDetail"));
        detailLabel.getStyleClass().add("card-subtitle");
        detailLabel.setWrapText(true);
        downloadProgress = new ProgressBar(0);
        downloadProgress.getStyleClass().add("download-progress");
        downloadProgress.setMaxWidth(Double.MAX_VALUE);
        Region taskSpacer = new Region();
        VBox.setVgrow(taskSpacer, Priority.ALWAYS);
        Button viewTasks = createLinkButton(Messages.get("home.viewTasks"), () -> setActiveView(AppView.DOWNLOADS));
        taskCard.getChildren().addAll(
                taskLabel,
                statusLabel,
                detailLabel,
                downloadProgress,
                taskSpacer,
                viewTasks);

        VBox playtimeCard = new VBox(12);
        playtimeCard.getStyleClass().addAll("home-card", "playtime-card");
        Label playtimeTitle = new Label(Messages.get("playtime.title"));
        playtimeTitle.getStyleClass().add("card-kicker");
        playtimeTotalLabel = createValueLabel(Messages.get("label.notSelected"));
        playtimeRecentLabel = createValueLabel(Messages.get("playtime.never"));
        playtimeLaunchCountLabel = createValueLabel("0");
        playtimeCard.getChildren().addAll(
                playtimeTitle,
                createSummaryRow(Messages.get("playtime.total"), playtimeTotalLabel),
                createSummaryRow(Messages.get("playtime.lastLaunch"), playtimeRecentLabel),
                createSummaryRow(Messages.get("playtime.launches"), playtimeLaunchCountLabel),
                createLinkButton(Messages.get("shortcut.createLink"),
                        () -> setActiveView(AppView.VERSIONS)));

        double preferredCardWidth = (LAUNCH_WIDTH - 72) / 4.0;
        for (VBox card : List.of(accountCard, environmentCard, taskCard, playtimeCard)) {
            card.setMinWidth(0);
            card.setPrefWidth(preferredCardWidth);
            card.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(card, Priority.ALWAYS);
        }

        HBox cards = new HBox(24, accountCard, environmentCard, taskCard, playtimeCard);
        cards.getStyleClass().add("home-summary");
        cards.setFillHeight(true);
        return cards;
    }

    private HBox createSummaryRow(String key, Label value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("summary-key");
        value.getStyleClass().add("summary-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, keyLabel, spacer, value);
        row.getStyleClass().add("summary-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button createLinkButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", "link-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private void expandInstanceSettings(Control focusTarget) {
        if (instanceSettingsPane == null) {
            return;
        }
        instanceSettingsPane.setExpanded(true);
        if (focusTarget != null) {
            Platform.runLater(focusTarget::requestFocus);
        }
    }

    private Region createCornerBracket(String cornerStyle) {
        Region corner = new Region();
        corner.getStyleClass().add("corner-bracket");
        if (cornerStyle != null && !cornerStyle.isBlank()) {
            corner.getStyleClass().add(cornerStyle);
        }
        corner.setMinSize(14, 14);
        corner.setPrefSize(14, 14);
        corner.setMaxSize(14, 14);
        return corner;
    }

    private Region createLaunchCorner(String styleClass, Pos alignment) {
        Region corner = new Region();
        corner.getStyleClass().addAll("launch-corner", styleClass);
        StackPane.setAlignment(corner, alignment);
        return corner;
    }

    private VBox createDownloadTasksPage() {
        downloadTasksPage = new DownloadTasksPage(downloadTaskCenter, settingsManager);
        return downloadTasksPage;
    }

    private VBox createVersionsPage() {
        VBox page = createMainPage();

        Button refreshVersionsButton = createActionButton(Messages.get("button.refresh"), "primary-button", () -> versionActions.refreshVersions());
        Button installLoaderButton = createActionButton(Messages.get("loader.install"), "primary-button",
                this::showLoaderInstallDialog);
        Button reinstallButton = createActionButton(Messages.get("version.reinstall"), "secondary-button",
                () -> versionActions.reinstallSelectedVersion());
        Button deleteButton = createActionButton(Messages.get("version.delete"), "secondary-button",
                () -> versionActions.deleteSelectedVersion());
        Button chooseVersionButton = createActionButton(Messages.get("button.back"), "secondary-button", () -> setActiveView(AppView.HOME));
        Button openVersionsDirButton = createActionButton(Messages.get("button.openDir"), "ghost-button",
                () -> openLocalFolder(ECLConfig.getVersionsDir(), "版本目录"));
        Button backupManagerButton = createActionButton(Messages.get("backup.manage"), "ghost-button",
                this::showBackupManagerDialog);
        Button desktopShortcutButton = createActionButton(Messages.get("shortcut.desktop"), "ghost-button",
                () -> createInstanceShortcut(false));
        Button startMenuShortcutButton = createActionButton(Messages.get("shortcut.startMenu"), "ghost-button",
                () -> createInstanceShortcut(true));

        HBox actions = new HBox(10, refreshVersionsButton, installLoaderButton, reinstallButton,
                deleteButton, chooseVersionButton, openVersionsDirButton, backupManagerButton,
                desktopShortcutButton, startMenuShortcutButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox versionCard = createSurface(
                Messages.get("version.page.title"),
                null,
                createInfoRow(Messages.get("label.currentFilter"), createStaticValueLabel(versionActions.getSelectedVersionCategory().getLabel())),
                createInfoRow(Messages.get("label.currentVersion"), createStaticValueLabel(getSelectedVersion() == null ? Messages.get("label.notSelected") : getSelectedVersion())),
                createInfoRow(Messages.get("info.localDir"), createStaticValueLabel(ECLConfig.getVersionsDir().getAbsolutePath())),
                actions
        );

        page.getChildren().add(versionCard);
        return page;
    }

    private void createInstanceShortcut(boolean startMenu) {
        String profileId = getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            setStatus(Messages.get("shortcut.error.title"), Messages.get("shortcut.error.selectInstance"));
            return;
        }
        Path executable = resolveLauncherExecutable();
        if (executable == null) {
            setStatus(Messages.get("shortcut.error.title"), Messages.get("shortcut.error.packagedExe"));
            return;
        }
        try {
            DesktopShortcutService shortcuts = new DesktopShortcutService();
            String name = "ECL - " + profileId;
            Path created = startMenu
                    ? shortcuts.createStartMenuShortcut(executable, name, List.of("--instance", profileId))
                    : shortcuts.createDesktopShortcut(executable, name, List.of("--instance", profileId));
            setStatus(Messages.get("shortcut.created"), created.toString());
        } catch (IOException error) {
            setStatus(Messages.get("shortcut.failed"), cleanMessage(error));
        }
    }

    private Path resolveLauncherExecutable() {
        return LauncherExecutableResolver.resolveCurrent(LauncherUI.class);
    }

    static Path resolveLauncherExecutableCandidate(String configured, String runningCommand,
                                                    Path workingDirectory, Path codeSource) {
        return LauncherExecutableResolver.resolveCandidate(
                configured, runningCommand, workingDirectory, codeSource);
    }

    private void showBackupManagerDialog() {
        new BackupManagerDialog(this).show();
    }

    String resolveBackupSourceVersion(String profileId) {
        try {
            return versionManager.resolveMinecraftVersionId(profileId);
        } catch (IOException error) {
            LOGGER.debug("Cannot resolve Minecraft version for backup {}", profileId, error);
            return profileId;
        }
    }

    private void showLoaderInstallDialog() {
        new LoaderInstallDialog(this).show();
    }

    private VBox createModrinthPage() {
        VBox page = createMainPage();

        Label pageTitle = new Label(Messages.get("content.page.title"));
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label(Messages.get("content.page.subtitle"));
        pageSubtitle.getStyleClass().add("page-subtitle");
        VBox pageHeading = new VBox(6, pageTitle, pageSubtitle);
        pageHeading.getStyleClass().add("content-library-heading");

        VBox navigation = new VBox(8);
        navigation.getStyleClass().add("content-library-nav");
        navigation.setPrefWidth(210);
        navigation.setMinWidth(210);
        navigation.setMaxWidth(210);

        Label navigationTitle = new Label(Messages.get("content.category.title"));
        navigationTitle.getStyleClass().add("content-library-nav-title");
        Label navigationHint = new Label(Messages.get("content.category.hint"));
        navigationHint.getStyleClass().add("content-library-nav-hint");
        navigation.getChildren().addAll(navigationTitle, navigationHint);

        StackPane content = new StackPane();
        content.getStyleClass().add("content-library-content");
        content.setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);

        List<Button> categoryButtons = new java.util.ArrayList<>();
        for (ContentTarget target : contentTargets) {
            Button categoryButton = createContentLibraryNavButton(target);
            categoryButtons.add(categoryButton);
            navigation.getChildren().add(categoryButton);
            categoryButton.setOnAction(event -> {
                categoryButtons.forEach(button ->
                        button.getStyleClass().remove("content-library-nav-item-active"));
                categoryButton.getStyleClass().add("content-library-nav-item-active");
                closeActiveModBrowserView();
                Node selectedContent = switch (target.projectType) {
                    case "mod" -> createModLibraryContent();
                    case "server" -> createServerJarLibraryContent();
                    default -> createContentLibraryBrowser(target);
                };
                content.getChildren().setAll(selectedContent);
            });
        }
        Button packUpdatesButton = createPackUpdatesNavButton();
        navigation.getChildren().add(packUpdatesButton);
        packUpdatesButton.setOnAction(event -> {
            categoryButtons.forEach(button ->
                    button.getStyleClass().remove("content-library-nav-item-active"));
            packUpdatesButton.getStyleClass().add("content-library-nav-item-active");
            closeActiveModBrowserView();
            content.getChildren().setAll(createPackUpdatesContent());
        });

        HBox library = new HBox(18, navigation, content);
        library.getStyleClass().add("content-library-layout");
        library.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);
        page.getChildren().addAll(pageHeading, library);

        if (!categoryButtons.isEmpty()) {
            categoryButtons.getFirst().fire();
        }
        return page;
    }

    private Button createContentLibraryNavButton(ContentTarget target) {
        Label icon = new Label(target.initial);
        icon.getStyleClass().add("content-library-nav-icon");
        Label title = new Label(target.title);
        title.getStyleClass().add("content-library-nav-item-title");
        Label detail = new Label(switch (target.projectType) {
            case "mod" -> Messages.get("content.detail.mods");
            case "shader" -> Messages.get("content.detail.shaders");
            case "resourcepack" -> Messages.get("content.detail.resourcepacks");
            case "modpack" -> Messages.get("content.detail.modpacks");
            case "server" -> Messages.get("content.detail.server");
            default -> target.subtitle;
        });
        detail.getStyleClass().add("content-library-nav-item-detail");
        VBox labels = new VBox(2, title, detail);
        HBox row = new HBox(10, icon, labels);
        row.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.setGraphic(row);
        button.getStyleClass().add("content-library-nav-item");
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private Button createPackUpdatesNavButton() {
        Label icon = new Label("↻");
        icon.getStyleClass().add("content-library-nav-icon");
        Label title = new Label("整合包更新");
        title.getStyleClass().add("content-library-nav-item-title");
        Label detail = new Label("检查已安装整合包的新版本");
        detail.getStyleClass().add("content-library-nav-item-detail");
        HBox row = new HBox(10, icon, new VBox(2, title, detail));
        row.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button();
        button.setGraphic(row);
        button.getStyleClass().add("content-library-nav-item");
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private Node createPackUpdatesContent() {
        VBox page = new VBox(14);
        page.getStyleClass().add("content-library-content");

        Label title = new Label("整合包更新");
        title.getStyleClass().add("content-library-section-title");
        Label hint = new Label("只检查已通过 Modrinth 安装并记录来源的整合包，更新会保留存档等实例文件。");
        hint.getStyleClass().add("status-detail");
        hint.setWrapText(true);

        ListView<ModpackUpdate> list = new ListView<>();
        list.getStyleClass().add("mod-result-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(new Label("尚未发现可更新的整合包。"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ModpackUpdate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(item.instance().name());
                name.getStyleClass().add("mod-item-title");
                Label detail = new Label(item.instance().currentVersion() + "  →  "
                        + item.availableVersion().versionNumber() + "   ·   "
                        + item.instance().minecraftVersion()
                        + (item.instance().loader().isBlank() ? "" : " / " + item.instance().loader()));
                detail.getStyleClass().add("status-detail");
                detail.setWrapText(true);
                setGraphic(new VBox(3, name, detail));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Label status = createBodyText("点击“检查更新”扫描已安装整合包。");
        Button[] controls = new Button[3];
        controls[0] = createActionButton("检查更新", "secondary-button", () ->
                checkPackUpdates(list, status, controls[0], controls[1], controls[2]));
        controls[1] = createActionButton("更新选中", "primary-button", () ->
                applyPackUpdates(list.getSelectionModel().getSelectedItems(), list, status,
                        controls[0], controls[1], controls[2]));
        controls[2] = createActionButton("一键更新全部", "primary-button", () ->
                applyPackUpdates(List.copyOf(list.getItems()), list, status,
                        controls[0], controls[1], controls[2]));
        Button check = controls[0];
        Button updateSelected = controls[1];
        Button updateAll = controls[2];
        updateSelected.setDisable(true);
        updateAll.setDisable(true);
        list.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ModpackUpdate>) change ->
                        updateSelected.setDisable(list.getSelectionModel().getSelectedItems().isEmpty()));
        HBox actions = new HBox(8, check, updateSelected, updateAll);
        actions.setAlignment(Pos.CENTER_RIGHT);
        page.getChildren().addAll(createSurface("整合包更新检测", null, title, hint, list, status, actions));

        Platform.runLater(() -> checkPackUpdates(list, status, check, updateSelected, updateAll));
        return page;
    }

    private void checkPackUpdates(ListView<ModpackUpdate> list, Label status,
                                  Button check, Button updateSelected, Button updateAll) {
        setPackUpdateControls(true, check, updateSelected, updateAll);
        status.setText("正在检查整合包更新...");
        ModpackUpdateService service = controller.modpackUpdateService();
        service.checkUpdates(getConfiguredGameRootDir().toPath(), controller.preferredModReleaseChannel())
                .whenComplete((updates, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        list.getItems().clear();
                        status.setText("检查失败: " + cleanMessage(error));
                    } else {
                        list.getItems().setAll(updates);
                        status.setText(updates.isEmpty()
                                ? "所有已记录来源的整合包均为最新版本。"
                                : "发现 " + updates.size() + " 个整合包可更新。");
                    }
                    setPackUpdateControls(false, check, updateSelected, updateAll);
                    updateAll.setDisable(list.getItems().isEmpty());
                    updateSelected.setDisable(list.getSelectionModel().getSelectedItems().isEmpty());
                }));
    }

    private void applyPackUpdates(List<ModpackUpdate> updates, ListView<ModpackUpdate> list,
                                  Label status, Button check, Button updateSelected, Button updateAll) {
        if (updates == null || updates.isEmpty()) return;
        setPackUpdateControls(true, check, updateSelected, updateAll);
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (ModpackUpdate update : updates) {
            chain = chain.thenCompose(count -> controller.modpackUpdateService()
                    .applyUpdate(update, getConfiguredGameRootDir().toPath(), new MrpackInstaller.Listener() {
                        @Override
                        public void onStatus(String message) {
                            Platform.runLater(() -> status.setText(message));
                        }

                        @Override
                        public void onProgress(long downloaded, long total) {
                            if (total > 0) {
                                Platform.runLater(() -> status.setText("正在更新 "
                                        + update.instance().name() + " · "
                                        + formatPackBytes(downloaded) + " / " + formatPackBytes(total)));
                            }
                        }
                    }).thenApply(result -> count + 1));
        }
        chain.whenComplete((count, error) -> Platform.runLater(() -> {
            setPackUpdateControls(false, check, updateSelected, updateAll);
            if (error != null) {
                status.setText("批量更新中断: " + cleanMessage(error));
            } else {
                status.setText("已完成 " + count + " 个整合包更新。");
                list.getItems().removeAll(updates);
                updateAll.setDisable(list.getItems().isEmpty());
            }
            updateSelected.setDisable(list.getSelectionModel().getSelectedItems().isEmpty());
        }));
    }

    private void setPackUpdateControls(boolean busy, Button check, Button updateSelected,
                                       Button updateAll) {
        check.setDisable(busy);
        updateSelected.setDisable(busy);
        updateAll.setDisable(busy);
    }

    private static String formatPackBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private Node createModLibraryContent() {
        String selectedVersion = getSelectedVersion();
        if (selectedVersion == null || selectedVersion.isBlank()) {
            Button choose = createActionButton(Messages.get("content.chooseInstance"), "primary-button",
                    () -> setActiveView(AppView.HOME));
            return createSurface(Messages.get("content.mods.title"), Messages.get("content.noInstance"),
                    createBodyText(Messages.get("content.mods.requirement")),
                    choose);
        }
        Path selectedMetadata;
        try {
            selectedMetadata = com.ecl.util.FileUtil.safeVersionJson(
                    ECLConfig.getVersionsDir(), selectedVersion).toPath();
        } catch (IOException error) {
            return createSurface(Messages.get("content.openFailed"), selectedVersion,
                    createBodyText(Messages.format("content.openFailed.detail", error.getMessage())));
        }
        if (!Files.isRegularFile(selectedMetadata)) {
            try {
                String minecraftVersion = versionManager.resolveMinecraftVersionId(selectedVersion);
                return createContentLibraryLoaderPrompt(selectedVersion, minecraftVersion);
            } catch (IOException ignored) {
                // Fall through to the detailed error state below.
            }
        }
        try {
            ModInstanceContext instance = VersionProfileModInstanceContext.load(
                    selectedVersion,
                    ECLConfig.getVersionsDir().toPath(),
                    getConfiguredGameRootDir().toPath(),
                    resolveVersionGameDir(selectedVersion).toPath());
            if (!instance.loader().supportsMods()) {
                return createLoaderSelectionPage(selectedVersion, instance.minecraftVersion());
            }
            activeModBrowserView = new ModBrowserView(
                    controller,
                    instance,
                    message -> Platform.runLater(() -> setStatus("模组中心", message)));
            activeModBrowserView.setMaxWidth(Double.MAX_VALUE);
            return activeModBrowserView;
        } catch (Exception e) {
            LOGGER.warn("Cannot open mod browser for version {}", selectedVersion, e);
            return createSurface(Messages.get("content.openFailed"), selectedVersion,
                    createBodyText(Messages.format("content.openFailed.detail", e.getMessage())));
        }
    }

    private VBox createContentLibraryLoaderPrompt(String profileId, String minecraftVersion) {
        Button install = createActionButton(Messages.get("content.loader.install"), "primary-button",
                this::showLoaderInstallDialog);
        return createSurface(Messages.get("content.mods.title"), versionManager.getVersionDisplayName(profileId),
                createBodyText(Messages.format("content.loader.prompt", minecraftVersion)),
                install);
    }

    private Node createServerJarLibraryContent() {
        Label eyebrow = new Label(Messages.get("server.library.eyebrow"));
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label(Messages.get("server.library.title"));
        title.getStyleClass().add("content-library-section-title");
        Label description = new Label(Messages.get("server.library.description"));
        description.getStyleClass().add("status-detail");
        description.setWrapText(true);
        VBox heading = new VBox(4, eyebrow, title, description);

        ComboBox<VersionManager.VersionCategory> categoryCombo = new ComboBox<>();
        categoryCombo.getItems().addAll(VersionManager.VersionCategory.values());
        categoryCombo.setValue(VersionManager.VersionCategory.ALL);
        categoryCombo.setPrefWidth(176);
        categoryCombo.setCellFactory(list -> createServerVersionCategoryCell());
        categoryCombo.setButtonCell(createServerVersionCategoryCell());
        applyFieldStyle(categoryCombo);

        ComboBox<String> serverVersionCombo = new ComboBox<>();
        serverVersionCombo.setVisibleRowCount(18);
        serverVersionCombo.setMaxWidth(Double.MAX_VALUE);
        serverVersionCombo.setCellFactory(list -> createPlainVersionCell());
        serverVersionCombo.setButtonCell(createPlainVersionCell());
        applyFieldStyle(serverVersionCombo);
        HBox.setHgrow(serverVersionCombo, Priority.ALWAYS);

        Button refreshButton = createActionButton(Messages.get("server.download.refresh"),
                "secondary-button", () -> { });
        HBox versionRow = new HBox(8, categoryCombo, serverVersionCombo, refreshButton);

        Label artifactInfo = new Label(Messages.get("server.download.artifactPrompt"));
        artifactInfo.getStyleClass().add("content-library-description");
        artifactInfo.setWrapText(true);

        Label channelsLabel = new Label(Messages.get("server.download.channelsPending"));
        channelsLabel.getStyleClass().add("content-library-target");
        channelsLabel.setWrapText(true);

        AtomicReference<File> directory = new AtomicReference<>(
                new File(getConfiguredGameRootDir(), "server-downloads"));
        Label targetLabel = new Label();
        targetLabel.getStyleClass().add("content-library-target");
        targetLabel.setWrapText(true);

        Label status = new Label(Messages.get("server.download.selectVersion"));
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);

        ProgressBar progress = new ProgressBar(0);
        progress.getStyleClass().add("download-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());

        Button downloadButton = createActionButton(Messages.get("server.download.action.download"),
                "primary-button", () -> { });
        downloadButton.setDisable(true);
        Button chooseFolderButton = createActionButton(Messages.get("server.download.action.changeFolder"),
                "secondary-button", () -> { });
        Button openFolderButton = createActionButton(Messages.get("server.download.action.openFolder"),
                "secondary-button", () -> { });
        HBox actions = new HBox(8, downloadButton, chooseFolderButton, openFolderButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Label eulaNotice = new Label(Messages.get("server.download.eula"));
        eulaNotice.getStyleClass().add("status-detail");
        eulaNotice.setWrapText(true);

        AtomicReference<ServerJarDownloader.ServerArtifact> artifact = new AtomicReference<>();
        AtomicLong metadataGeneration = new AtomicLong();
        AtomicLong downloadGeneration = new AtomicLong();

        Runnable updateTarget = () -> {
            String version = serverVersionCombo.getValue();
            String fileName = ServerJarDownloader.suggestedFileName(version);
            targetLabel.setText(Messages.format("server.download.target",
                    new File(directory.get(), fileName).getAbsolutePath()));
        };

        Runnable loadArtifact = () -> loadServerJarArtifact(
                serverVersionCombo.getValue(), artifact, artifactInfo, channelsLabel, status,
                progress, downloadButton, metadataGeneration, updateTarget);

        Runnable updateVersions = () -> {
            String previous = serverVersionCombo.getValue();
            String currentMinecraftVersion = selectedMinecraftVersionForServerDownload();
            List<String> versions = new java.util.ArrayList<>(versionManager.getVersions(
                    categoryCombo.getValue() == null
                            ? VersionManager.VersionCategory.ALL : categoryCombo.getValue()));
            if (!currentMinecraftVersion.isBlank() && !versions.contains(currentMinecraftVersion)) {
                versions.addFirst(currentMinecraftVersion);
            }
            serverVersionCombo.getItems().setAll(versions);
            if (previous != null && versions.contains(previous)) {
                serverVersionCombo.setValue(previous);
            } else if (!currentMinecraftVersion.isBlank() && versions.contains(currentMinecraftVersion)) {
                serverVersionCombo.setValue(currentMinecraftVersion);
            } else if (!versions.isEmpty()) {
                serverVersionCombo.setValue(versions.getFirst());
            } else {
                artifact.set(null);
                artifactInfo.setText(Messages.get("server.download.listEmpty"));
                channelsLabel.setText(Messages.get("server.download.channelsWaitingVersions"));
                status.setText(Messages.get("server.download.noVersions"));
                downloadButton.setDisable(true);
                updateTarget.run();
            }
            if (serverVersionCombo.getValue() != null) loadArtifact.run();
        };

        serverVersionCombo.setOnAction(event -> {
            updateTarget.run();
            loadArtifact.run();
        });
        categoryCombo.setOnAction(event -> updateVersions.run());

        refreshButton.setOnAction(event -> {
            refreshButton.setDisable(true);
            categoryCombo.setDisable(true);
            serverVersionCombo.setDisable(true);
            status.setText(Messages.get("server.download.refreshingVersions"));
            runAsync("ecl-refresh-server-versions", () -> {
                try {
                    versionManager.refresh();
                    Platform.runLater(() -> {
                        updateVersions.run();
                        refreshButton.setDisable(false);
                        categoryCombo.setDisable(false);
                        serverVersionCombo.setDisable(false);
                        status.setText(Messages.get("server.download.versionsUpdated"));
                    });
                } catch (Exception error) {
                    Platform.runLater(() -> {
                        refreshButton.setDisable(false);
                        categoryCombo.setDisable(false);
                        serverVersionCombo.setDisable(false);
                        status.setText(Messages.format(
                                "server.download.versionsFailed", cleanMessage(error)));
                    });
                }
            });
        });

        chooseFolderButton.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(Messages.get("server.download.chooseDirectory"));
            File current = directory.get();
            if (current.isDirectory()) chooser.setInitialDirectory(current);
            File selected = chooser.showDialog(primaryStage);
            if (selected != null) {
                directory.set(selected);
                updateTarget.run();
                status.setText(Messages.get("server.download.directoryChanged"));
            }
        });
        openFolderButton.setOnAction(event -> {
            try {
                ensureDirectory(directory.get());
                openLocalFolder(directory.get(), Messages.get("server.download.directoryName"));
            } catch (IOException error) {
                status.setText(Messages.format(
                        "server.download.openDirectoryFailed", cleanMessage(error)));
            }
        });

        downloadButton.setOnAction(event -> {
            ServerJarDownloader.ServerArtifact selectedArtifact = artifact.get();
            if (selectedArtifact == null) return;
            File target = new File(directory.get(),
                    ServerJarDownloader.suggestedFileName(selectedArtifact.versionId()));
            try {
                ensureDirectory(directory.get());
            } catch (IOException error) {
                status.setText(Messages.format(
                        "server.download.createDirectoryFailed", cleanMessage(error)));
                return;
            }
            long generation = downloadGeneration.incrementAndGet();
            downloadButton.setDisable(true);
            chooseFolderButton.setDisable(true);
            serverVersionCombo.setDisable(true);
            categoryCombo.setDisable(true);
            refreshButton.setDisable(true);
            progress.setProgress(0);
            progress.setVisible(true);
            status.setText(Messages.format(
                    "server.download.preparing", selectedArtifact.versionId()));
            setStatus(Messages.get("server.download.statusTitle"),
                    selectedArtifact.versionId() + " · " + target.getName());

            DownloadTaskCenter.TaskHandle<Void> serverTask = downloadTaskCenter.submit(
                    "Server JAR " + selectedArtifact.versionId(), context -> {
                try {
                    serverJarDownloader.download(selectedArtifact, target,
                            createServerDownloadListener(status, progress, generation, downloadGeneration, context));
                    if (context.isCancelled()) return null;
                    Platform.runLater(() -> {
                        if (generation != downloadGeneration.get() || context.isCancelled()) return;
                        progress.setProgress(1);
                        status.setText(Messages.format(
                                "server.download.completedVerified", target.getAbsolutePath()));
                        setStatus(Messages.get("server.download.completedTitle"), target.getAbsolutePath());
                        setServerDownloadControlsBusy(false, downloadButton, chooseFolderButton,
                                serverVersionCombo, categoryCombo, refreshButton);
                    });
                } catch (Exception error) {
                    boolean cancelled = context.isCancelled() || isCancellation(error);
                    Platform.runLater(() -> {
                        if (generation != downloadGeneration.get()) return;
                        if (cancelled) {
                            status.setText(Messages.get("download.status.cancelled"));
                            setStatus(Messages.get("download.status.cancelled"), "");
                        } else {
                            status.setText(Messages.format("download.status.failed", cleanMessage(error)));
                            setStatus(Messages.get("status.downloadFailed"), cleanMessage(error));
                        }
                        setServerDownloadControlsBusy(false, downloadButton, chooseFolderButton,
                                serverVersionCombo, categoryCombo, refreshButton);
                    });
                    throw error;
                }
                return null;
            });
        });

        updateVersions.run();
        updateTarget.run();

        VBox browser = new VBox(12, heading, versionRow, artifactInfo, channelsLabel,
                targetLabel, status, progress, eulaNotice, actions);
        browser.getStyleClass().addAll("surface", "content-library-browser");
        browser.setFillWidth(true);
        return browser;
    }

    private ListCell<String> createPlainVersionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        };
    }

    private ListCell<VersionManager.VersionCategory> createServerVersionCategoryCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(VersionManager.VersionCategory item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item) {
                    case FEATURED -> Messages.get("server.download.category.featured");
                    case RELEASE -> Messages.get("version.release");
                    case PREVIEW -> Messages.get("version.preview");
                    case APRIL_FOOLS -> Messages.get("version.aprilFools");
                    case ALL -> Messages.get("version.all");
                });
            }
        };
    }

    private String selectedMinecraftVersionForServerDownload() {
        String profile = getSelectedVersion();
        if (profile == null || profile.isBlank()) return "";
        try {
            return versionManager.resolveMinecraftVersionId(profile);
        } catch (IOException error) {
            return profile;
        }
    }

    private void loadServerJarArtifact(
            String version,
            AtomicReference<ServerJarDownloader.ServerArtifact> artifact,
            Label artifactInfo,
            Label channelsLabel,
            Label status,
            ProgressBar progress,
            Button downloadButton,
            AtomicLong metadataGeneration,
            Runnable updateTarget
    ) {
        long generation = metadataGeneration.incrementAndGet();
        artifact.set(null);
        downloadButton.setDisable(true);
        progress.setVisible(false);
        if (version == null || version.isBlank()) return;
        artifactInfo.setText(Messages.format("server.download.metadataReading", version));
        channelsLabel.setText(Messages.get("server.download.channelsResolving"));
        status.setText(Messages.get("server.download.manifestQuery"));

        runAsync("ecl-resolve-server-" + version, () -> {
            try {
                ServerJarDownloader.ServerArtifact resolved = serverJarDownloader.resolve(version,
                        new ServerJarDownloader.Listener() {
                            @Override
                            public void onSource(String sourceName, String candidateUrl, boolean mirror) {
                                Platform.runLater(() -> {
                                    if (generation == metadataGeneration.get()) {
                                        status.setText(Messages.format(
                                                "server.download.metadataSource", sourceName));
                                    }
                                });
                            }

                            @Override
                            public void onSourceFailure(String candidateUrl, IOException error) {
                                Platform.runLater(() -> {
                                    if (generation == metadataGeneration.get()) {
                                        status.setText(Messages.get(
                                                "server.download.metadataSourceFailed"));
                                    }
                                });
                            }
                        });
                Platform.runLater(() -> {
                    if (generation != metadataGeneration.get()) return;
                    artifact.set(resolved);
                    String size = resolved.size() < 0
                            ? Messages.get("server.download.sizeUnknown") : formatBytes(resolved.size());
                    String sha1 = resolved.sha1() == null || resolved.sha1().isBlank()
                            ? Messages.get("server.download.shaUnavailable") : resolved.sha1();
                    artifactInfo.setText(Messages.format("server.download.artifactDetails",
                            resolved.versionId(), size, sha1));
                    channelsLabel.setText(Messages.format("server.download.channels",
                            resolved.channels().stream()
                            .map(ServerJarDownloader.DownloadChannel::name)
                            .distinct()
                            .collect(java.util.stream.Collectors.joining(" → "))));
                    status.setText(Messages.get("server.download.available"));
                    downloadButton.setDisable(false);
                    updateTarget.run();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (generation != metadataGeneration.get()) return;
                    artifactInfo.setText(Messages.format("server.download.noArtifact", version));
                    channelsLabel.setText(Messages.get("server.download.channelsUnavailable"));
                    status.setText(cleanMessage(error));
                });
            }
        });
    }

    private ServerJarDownloader.Listener createServerDownloadListener(
            Label status,
            ProgressBar progress,
            long generation,
            AtomicLong downloadGeneration,
            DownloadTaskCenter.TaskContext taskContext
    ) {
        return new ServerJarDownloader.Listener() {
            @Override
            public void onStart(long total) {
                taskContext.updateProgress(0, total);
                Platform.runLater(() -> {
                    if (generation == downloadGeneration.get()) progress.setProgress(0);
                });
            }

            @Override
            public void onProgress(long downloaded, long total) {
                taskContext.updateProgress(downloaded, total);
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) return;
                    progress.setProgress(total > 0 ? (double) downloaded / total : -1);
                    status.setText(total > 0
                            ? Messages.format("server.download.progressKnown",
                                    formatBytes(downloaded), formatBytes(total))
                            : Messages.format("server.download.progressUnknown",
                                    formatBytes(downloaded)));
                });
            }

            @Override
            public void onSource(String sourceName, String candidateUrl, boolean mirror) {
                Platform.runLater(() -> {
                    if (generation == downloadGeneration.get()) {
                        status.setText(Messages.format("server.download.usingSource", sourceName));
                    }
                });
            }

            @Override
            public void onSourceFailure(String candidateUrl, IOException error) {
                Platform.runLater(() -> {
                    if (generation == downloadGeneration.get()) {
                        status.setText(Messages.get("server.download.sourceFailed"));
                    }
                });
            }
        };
    }

    private void setServerDownloadControlsBusy(
            boolean busy,
            Button downloadButton,
            Button chooseFolderButton,
            ComboBox<String> serverVersionCombo,
            ComboBox<VersionManager.VersionCategory> categoryCombo,
            Button refreshButton
    ) {
        downloadButton.setDisable(busy);
        chooseFolderButton.setDisable(busy);
        serverVersionCombo.setDisable(busy);
        categoryCombo.setDisable(busy);
        refreshButton.setDisable(busy);
    }

    private Node createContentLibraryBrowser(ContentTarget target) {
        List<String> profileIds = availableContentProfiles(target);
        if (profileIds.isEmpty()) {
            Button choose = createActionButton("返回首页选择实例", "primary-button",
                    () -> setActiveView(AppView.HOME));
            return createSurface(target.title, "还没有可用的 Minecraft 实例",
                    createBodyText("请先安装或选择一个游戏版本，下载后会自动导入该实例的 "
                            + ("shader".equals(target.projectType) ? "shaderpacks" : "resourcepacks") + " 目录。"),
                    choose);
        }

        String activeProfile = getSelectedVersion();
        String initialProfile = profileIds.contains(activeProfile) ? activeProfile : profileIds.getFirst();
        ContentInstance initialInstance = resolveContentInstance(initialProfile);

        Label eyebrow = new Label("MODRINTH + CURSEFORGE / "
                + target.projectType.toUpperCase(Locale.ROOT));
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label(target.title);
        title.getStyleClass().add("content-library-section-title");
        Label description = new Label("modpack".equals(target.projectType)
                ? "选择兼容整合包，安装为独立实例后立即启动"
                : "搜索、选择兼容版本并直接安装到当前实例");
        description.getStyleClass().add("status-detail");
        VBox heading = new VBox(4, eyebrow, title, description);

        ComboBox<String> targetProfileCombo = new ComboBox<>();
        targetProfileCombo.getItems().setAll(profileIds);
        targetProfileCombo.setValue(initialProfile);
        targetProfileCombo.setCellFactory(list -> createVersionCell());
        targetProfileCombo.setButtonCell(createVersionCell());
        targetProfileCombo.setVisibleRowCount(14);
        applyFieldStyle(targetProfileCombo);
        targetProfileCombo.setMaxWidth(Double.MAX_VALUE);

        TextField searchField = new TextField();
        searchField.setPromptText(target.searchHint);
        applyFieldStyle(searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button searchButton = createActionButton("搜索", "primary-button", () -> { });
        ComboBox<ContentSource> sourceCombo = createContentSourceCombo();
        HBox searchBar = new HBox(8, sourceCombo, searchField, searchButton);

        ListView<ModrinthDownloader.Project> resultList = new ListView<>();
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPrefHeight(330);
        resultList.setPlaceholder(new Label("没有找到兼容内容"));
        resultList.setCellFactory(list -> createContentProjectCell(target));

        Label projectDescription = new Label("选择一个项目查看简介和兼容版本");
        projectDescription.getStyleClass().add("content-library-description");
        projectDescription.setWrapText(true);
        projectDescription.setMinHeight(86);

        ComboBox<ModrinthDownloader.ProjectVersion> versionComboBox = new ComboBox<>();
        versionComboBox.setPromptText("选择具体版本");
        versionComboBox.setDisable(true);
        versionComboBox.setMaxWidth(Double.MAX_VALUE);
        applyFieldStyle(versionComboBox);

        Label targetLabel = new Label();
        targetLabel.getStyleClass().add("content-library-target");
        targetLabel.setWrapText(true);
        updateContentTargetLabel(target, initialInstance, targetLabel);

        Label status = new Label("正在加载 Modrinth 热门" + target.title + "…");
        status.getStyleClass().add("status-detail");
        status.setWrapText(true);
        ProgressBar progress = new ProgressBar(0);
        progress.getStyleClass().add("download-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());

        Button downloadButton = createActionButton("modpack".equals(target.projectType)
                ? "安装并启动" : "下载并安装", "primary-button", () -> { });
        downloadButton.setDisable(true);
        Button folderButton = createActionButton("打开安装目录", "secondary-button", () -> {
            ContentInstance instance = resolveContentInstance(targetProfileCombo.getValue());
            File directory = target.folderResolver.apply(instance.profileId());
            try {
                ensureDirectory(directory);
                openLocalFolder(directory, target.title + "目录");
            } catch (IOException error) {
                status.setText("无法创建目录: " + cleanMessage(error));
            }
        });
        HBox actions = new HBox(8, downloadButton, folderButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        AtomicLong searchGeneration = new AtomicLong();
        AtomicLong versionGeneration = new AtomicLong();
        AtomicLong downloadGeneration = new AtomicLong();
        AtomicLong descriptionGeneration = new AtomicLong();

        resultList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            long selectedDescriptionGeneration = descriptionGeneration.incrementAndGet();
            versionGeneration.incrementAndGet();
            versionComboBox.getItems().clear();
            versionComboBox.setDisable(selected == null);
            downloadButton.setDisable(true);
            projectDescription.setText(selected == null
                    ? "选择一个项目查看简介和兼容版本"
                    : "正在翻译中文简介…");
            if (selected != null) {
                setTranslatedProjectDescription(selected, projectDescription,
                        descriptionGeneration, selectedDescriptionGeneration);
                loadProjectVersions(sourceCombo.getValue(), target, selected,
                        resolveContentInstance(targetProfileCombo.getValue()), versionComboBox,
                        status, downloadButton, versionGeneration);
            }
        });
        versionComboBox.valueProperty().addListener((observable, oldValue, selected) ->
                downloadButton.setDisable(selected == null
                        || resultList.getSelectionModel().getSelectedItem() == null));

        Runnable search = () -> searchModrinthContent(sourceCombo.getValue(), target,
                resolveContentInstance(targetProfileCombo.getValue()), searchField, resultList,
                status, searchButton, downloadButton, searchGeneration);
        searchButton.setOnAction(event -> search.run());
        searchField.setOnAction(event -> search.run());
        sourceCombo.setOnAction(event -> {
            versionGeneration.incrementAndGet();
            versionComboBox.getItems().clear();
            versionComboBox.setDisable(true);
            resultList.getItems().clear();
            downloadButton.setDisable(true);
            search.run();
        });
        targetProfileCombo.setOnAction(event -> {
            ContentInstance instance = resolveContentInstance(targetProfileCombo.getValue());
            updateContentTargetLabel(target, instance, targetLabel);
            versionGeneration.incrementAndGet();
            versionComboBox.getItems().clear();
            versionComboBox.setDisable(true);
            resultList.getItems().clear();
            downloadButton.setDisable(true);
            search.run();
        });
        downloadButton.setOnAction(event -> {
            ContentInstance instance = resolveContentInstance(targetProfileCombo.getValue());
            File directory = target.folderResolver.apply(instance.profileId());
            try {
                ensureDirectory(directory);
            } catch (IOException error) {
                status.setText("无法创建目录: " + cleanMessage(error));
                return;
            }
            downloadSelectedContent(sourceCombo.getValue(), target,
                    resultList.getSelectionModel().getSelectedItem(),
                    versionComboBox.getValue(), instance, directory, status, progress,
                    searchButton, downloadButton, targetProfileCombo, downloadGeneration);
        });

        VBox browser = new VBox(12, heading, targetProfileCombo, searchBar, resultList,
                projectDescription, versionComboBox, targetLabel, status, progress, actions);
        browser.getStyleClass().addAll("surface", "content-library-browser");
        browser.setFillWidth(true);
        search.run();
        return browser;
    }

    private record ContentInstance(
            String profileId,
            String minecraftVersion,
            String loader,
            File gameDirectory
    ) {
    }

    private void closeActiveModBrowserView() {
        if (activeModBrowserView != null) {
            activeModBrowserView.close();
            activeModBrowserView = null;
        }
    }

    private void closeActiveServerBrowserView() {
        if (activeServerBrowserView != null) {
            activeServerBrowserView.close();
            activeServerBrowserView = null;
        }
    }

    private VBox createServersPage() {
        VBox page = createMainPage();

        Label pageTitle = new Label(Messages.get("nav.servers"));
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label(Messages.get("server.page.subtitle"));
        pageSubtitle.getStyleClass().add("page-subtitle");
        VBox pageHeading = new VBox(6, pageTitle, pageSubtitle);
        pageHeading.getStyleClass().add("content-library-heading");

        activeServerBrowserView = new ServerBrowserView(
                message -> setStatus(Messages.get("nav.servers"), message), this::setQuickServer);
        activeServerBrowserView.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(activeServerBrowserView, Priority.ALWAYS);

        page.getChildren().addAll(pageHeading, activeServerBrowserView);
        return page;
    }

    /** 将地址写入直连服务器配置并持久化，供服务器浏览页“设为直连”调用。 */
    private void setQuickServer(String address) {
        quickServer = address == null ? "" : address.trim();
        settingsManager.set(ECLConfig.KEY_QUICK_SERVER, quickServer);
        settingsManager.save();
        setStatus("已设为直连服务器", quickServer.isBlank()
                ? "已清空直连服务器地址"
                : "下次启动将直接连接 " + quickServer);
    }

    private VBox createSettingsPage() {
        VBox page = createMainPage();

        ComboBox<String> languageBox = new ComboBox<>();
        languageBox.getItems().addAll("zh-CN", "zh-TW", "en");
        languageBox.setValue(Messages.locale().toLanguageTag());
        configureLocalizedCombo(languageBox, this::languageDisplayName);
        languageBox.setOnAction(event -> switchLanguage(languageBox.getValue()));

        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll("DARK", "LIGHT");
        themeBox.setValue(normalizeTheme(settingsManager.get(ECLConfig.KEY_THEME)));
        configureLocalizedCombo(themeBox, this::themeDisplayName);
        themeBox.setOnAction(event -> {
            String theme = normalizeTheme(themeBox.getValue());
            settingsManager.set(ECLConfig.KEY_THEME, theme);
            settingsManager.save();
            applyTheme(theme);
        });

        Button advancedButton = createActionButton(Messages.get("settings.advanced"),
                "primary-button", this::showSettingsDialog);
        Button dataDirButton = createActionButton(Messages.get("settings.openData"), "secondary-button",
                () -> openLocalFolder(ECLConfig.getBaseDir(), Messages.get("settings.openData")));
        Button gameDirButton = createActionButton(Messages.get("settings.openGame"), "ghost-button",
                () -> openLocalFolder(getActiveGameDir(), Messages.get("settings.openGame")));
        Button wizardButton = createActionButton(Messages.get("wizard.title"), "ghost-button",
                this::showFirstRunWizard);

        HBox actions = new HBox(10, advancedButton, dataDirButton, gameDirButton, wizardButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox settingsCard = createSurface(
                "// " + Messages.get("settings.system"),
                Messages.get("settings.subtitle"),
                createControlRow(Messages.get("settings.language"), languageBox),
                createControlRow(Messages.get("settings.theme"), themeBox),
                createInfoRow("Java", createStaticValueLabel(
                        javaPath == null || javaPath.isBlank() ? "-" : abbreviate(javaPath, 72))),
                actions
        );
        page.getChildren().add(settingsCard);
        return page;
    }

    @SuppressWarnings("unused")
    private VBox createLegacySettingsPage() {
        VBox page = createMainPage();

        Button advancedButton = createActionButton("高级设置", "primary-button", this::showSettingsDialog);
        Button dataDirButton = createActionButton("打开数据目录", "secondary-button",
                () -> openLocalFolder(ECLConfig.getBaseDir(), "数据目录"));
        Button gameDirButton = createActionButton("打开游戏目录", "ghost-button",
                () -> openLocalFolder(getActiveGameDir(), "游戏目录"));

        HBox actions = new HBox(10, advancedButton, dataDirButton, gameDirButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox settingsCard = createSurface(
                "// 系统设置",
                "Java / 游戏目录 / JVM 参数",
                createInfoRow("Java", createStaticValueLabel(javaPath == null || javaPath.isBlank() ? "未设置" : abbreviate(javaPath, 72))),
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
        Button crashButton = createActionButton(Messages.get("logs.openCrash"), "primary-button",
                () -> openLocalFolder(crashDir, "崩溃报告目录"));
        Button logsButton = createActionButton(Messages.get("logs.openLogs"), "secondary-button",
                () -> openLocalFolder(logsDir, "日志目录"));
        Button modsButton = createActionButton(Messages.get("logs.openMods"), "ghost-button",
                () -> openLocalFolder(resolveModsDir(getSelectedVersion()), "模组目录"));
        Button clearConsoleButton = createActionButton(Messages.get("logs.clearConsole"), "ghost-button", () -> {
            liveGameLog.clear();
            if (liveConsoleArea != null) liveConsoleArea.clear();
        });
        Button diagnosticButton = createActionButton(Messages.get("diagnostic.export"),
                "secondary-button", this::exportDiagnosticBundle);

        HBox actions = new HBox(10, crashButton, logsButton, modsButton, clearConsoleButton, diagnosticButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        liveConsoleArea = new TextArea(liveGameLog.toString());
        liveConsoleArea.setEditable(false);
        liveConsoleArea.setWrapText(false);
        liveConsoleArea.setPrefRowCount(18);
        liveConsoleArea.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");

        VBox logsCard = createSurface(
                Messages.get("logs.title"),
                Messages.get("logs.subtitle"),
                createInfoRow(Messages.get("label.diagStatus"), createStaticValueLabel(Messages.get("info.normal"))),
                createInfoRow(Messages.get("label.crashReports"), createStaticValueLabel(Messages.format("crash.count", countCrashReports()))),
                createInfoRow(Messages.get("info.gameDir"), createStaticValueLabel(abbreviate(getActiveGameDir().getAbsolutePath(), 72))),
                actions,
                liveConsoleArea
        );

        page.getChildren().add(logsCard);
        return page;
    }

    private VBox createMainPage() {
        VBox page = new VBox(18);
        page.getStyleClass().add("launch-pane");
        page.setPrefWidth(LAUNCH_WIDTH);
        page.setMaxWidth(LAUNCH_WIDTH);
        HBox.setHgrow(page, Priority.ALWAYS);
        return page;
    }

    Button createActionButton(String text, String styleClass, Runnable action) {
        return LauncherUiFactory.actionButton(text, styleClass, action);
    }

    Label createStaticValueLabel(String text) {
        return LauncherUiFactory.valueLabel(text);
    }

    Label createBodyText(String text) {
        return LauncherUiFactory.bodyText(text);
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
        return createSurface(Messages.get("content.recommended.title"),
                Messages.get("content.recommended.subtitle"), contentRows);
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
        folderBtn.setOnAction(e -> openLocalFolder(target.folderResolver.apply(getSelectedVersion()), target.title + "目录"));

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

        String previousVersion = versionCombo == null ? settingsManager.get(ECLConfig.KEY_SELECTED_VERSION) : versionCombo.getValue();
        VersionManager.VersionCategory previousCategory = versionTypeCombo == null || versionTypeCombo.getValue() == null
                ? versionActions.parseVersionCategory(settingsManager.get(ECLConfig.KEY_VERSION_CATEGORY))
                : versionTypeCombo.getValue();
        String previousAuthType = authTypeCombo == null ? normalizeAuthType(settingsManager.get(ECLConfig.KEY_AUTH_TYPE)) : normalizeAuthType(authTypeCombo.getValue());
        String previousUsername = usernameField == null
                ? settingsManager.get(ECLConfig.KEY_USERNAME)
                : usernameField.getText();
        if (previousUsername == null || previousUsername.isBlank()) {
            previousUsername = "Steve";
        }

        authTypeCombo = new ComboBox<>();
        authTypeCombo.getItems().addAll(AUTH_OFFLINE, AUTH_MICROSOFT, AUTH_YGGDRASIL);
        authTypeCombo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : authDisplayName(item));
            }
        });
        authTypeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : authDisplayName(item));
            }
        });
        authTypeCombo.setValue(previousAuthType);
        authTypeCombo.setOnAction(e -> updateAuthFields());
        applyFieldStyle(authTypeCombo);

        yggdrasilServerField = new TextField(settingsManager.get(ECLConfig.KEY_YGGDRASIL_SERVER));
        yggdrasilServerField.setPromptText("输入 Yggdrasil 认证地址");
        applyFieldStyle(yggdrasilServerField);

        usernameField = new TextField(previousUsername);
        usernameField.setPromptText("输入玩家名称");
        applyFieldStyle(usernameField);
        usernameField.textProperty().addListener((obs, oldValue, newValue) -> {
            updateRuntimeSummary();
            updateOfflineSkinControls();
        });

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
        versionCombo.setCellFactory(list -> createVersionCell());
        versionCombo.setButtonCell(createVersionCell());
        applyFieldStyle(versionCombo);
        versionCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.isBlank()) {
                settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, newValue);
                if (!settingsManager.save()) {
                    setStatus("设置保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
                }
            }
            updateRuntimeSummary();
            versionActions.updateSelectedVersionWikiButton();
            syncLoaderChoiceFromProfile(newValue);
        });

        versionTypeCombo = new ComboBox<>();
        versionTypeCombo.getItems().addAll(VersionManager.VersionCategory.values());
        versionTypeCombo.setValue(previousCategory);
        versionTypeCombo.setPrefWidth(176);
        versionTypeCombo.setTooltip(new Tooltip("默认显示正式版、预览版/快照和愚人节版，也可以只看某一类"));
        versionTypeCombo.setOnAction(e -> {
            settingsManager.set(ECLConfig.KEY_VERSION_CATEGORY, versionActions.getSelectedVersionCategory().name());
            if (!settingsManager.save()) {
                setStatus("设置保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
                return;
            }
                versionActions.refreshVersions();
        });
        applyFieldStyle(versionTypeCombo);

        loaderChoiceCombo = new ComboBox<>();
        loaderChoiceCombo.getItems().setAll(LoaderChoice.values());
        loaderChoiceCombo.setValue(LoaderChoice.VANILLA);
        loaderChoiceCombo.setPrefWidth(160);
        loaderChoiceCombo.setTooltip(new Tooltip(
                "选择 Fabric、Quilt、Forge 或 NeoForge 后，可直接安装对应实例"));
        loaderChoiceCombo.setOnAction(event -> handleLoaderChoiceChanged());
        applyFieldStyle(loaderChoiceCombo);
        installSelectedLoaderButton = new Button("当前为原版");
        installSelectedLoaderButton.getStyleClass().addAll(
                "app-button", "secondary-button", "compact-button");
        installSelectedLoaderButton.setDisable(true);
        installSelectedLoaderButton.setOnAction(event -> installSelectedLoader(null));

        selectedVersionWikiButton = createSelectedVersionWikiButton();
        versionActions.restoreVersionComboItems(previousVersion);
        versionActions.updateSelectedVersionWikiButton();

        TextField gameDirField = new TextField(abbreviate(getActiveGameDir().getAbsolutePath(), 72));
        gameDirField.setEditable(false);
        applyFieldStyle(gameDirField);

        TextField jvmField = new TextField(extraJvmArgs == null || extraJvmArgs.isBlank()
                ? "未设置（内存: " + gameLaunch.getMemoryDisplayText() + "）"
                : extraJvmArgs);
        jvmField.setEditable(false);
        applyFieldStyle(jvmField);

        Button folderButton = createIconActionButton(ICON_FOLDER, "▣", "打开游戏目录",
                () -> openLocalFolder(getActiveGameDir(), "游戏目录"));

        Button jvmButton = createIconActionButton(ICON_GEAR, "⚙", "高级设置", this::showSettingsDialog);

        HBox gameDirBox = new HBox(10, gameDirField, folderButton);
        HBox.setHgrow(gameDirField, Priority.ALWAYS);
        HBox jvmBox = new HBox(10, jvmField, jvmButton);
        HBox.setHgrow(jvmField, Priority.ALWAYS);
        authTypeCombo.setPrefWidth(200);
        microsoftLoginBtn = new Button("正版登录");
        microsoftLoginBtn.getStyleClass().addAll("app-button", "secondary-button", "compact-button");
        microsoftLoginBtn.setTooltip(new Tooltip("登录 Microsoft 正版 Minecraft Java 版账号"));
        microsoftLoginBtn.setOnAction(e -> microsoftAccounts.loginMicrosoftAccount());
        microsoftAddAccountBtn = new Button("添加账号");
        microsoftAddAccountBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        microsoftAddAccountBtn.setTooltip(new Tooltip("使用设备码添加另一个 Microsoft 账号"));
        microsoftAddAccountBtn.setOnAction(e -> microsoftAccounts.addMicrosoftAccount());
        skinUploadBtn = new Button("上传皮肤");
        skinUploadBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        skinUploadBtn.setTooltip(new Tooltip("上传 PNG 皮肤到当前 Minecraft Java 正版账号"));
        skinUploadBtn.setOnAction(e -> skins.chooseAndUploadSkin());
        offlineSkinRemoveBtn = new Button("清除皮肤");
        offlineSkinRemoveBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        offlineSkinRemoveBtn.setTooltip(new Tooltip("移除当前离线账号已导入的本地皮肤"));
        offlineSkinRemoveBtn.setOnAction(e -> skins.removeOfflineSkin());
        setFieldVisible(offlineSkinRemoveBtn, false);
        microsoftAccountCombo = new ComboBox<>();
        microsoftAccountCombo.setPromptText("选择已保存账号");
        microsoftAccountCombo.getItems().setAll(microsoftAccountStore.list());
        String selectedAccountUuid = settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_UUID);
        microsoftAccountCombo.getItems().stream()
                .filter(account -> account.uuid().equalsIgnoreCase(selectedAccountUuid))
                .findFirst()
                .ifPresentOrElse(microsoftAccountCombo::setValue, () -> {
                    if (!microsoftAccountCombo.getItems().isEmpty()) {
                        microsoftAccountCombo.getSelectionModel().selectFirst();
                    }
                });
        microsoftAccountCombo.valueProperty().addListener((obs, oldValue, account) -> {
            selectedMicrosoftAccount = account;
            if (account != null) {
                usernameField.setText(account.username());
                settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_UUID, account.uuid());
                settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_NAME, account.username());
                settingsManager.save();
                updateRuntimeSummary();
            }
        });
        selectedMicrosoftAccount = microsoftAccountCombo.getValue();
        applyFieldStyle(microsoftAccountCombo);
        HBox authBox = new HBox(10, authTypeCombo, usernameField, microsoftAccountCombo,
                microsoftLoginBtn, microsoftAddAccountBtn, skinUploadBtn, offlineSkinRemoveBtn);
        authBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(usernameField, Priority.ALWAYS);
        HBox.setHgrow(microsoftAccountCombo, Priority.ALWAYS);
        VBox authHelpBox = new VBox(4, authSummaryLabel, authHintLabel);
        HBox versionBox = new HBox(10, versionTypeCombo, versionCombo, selectedVersionWikiButton);
        versionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(versionCombo, Priority.ALWAYS);
        Label loaderHint = new Label("安装后会自动切换到独立模组实例");
        loaderHint.getStyleClass().add("status-detail");
        HBox loaderBox = new HBox(10, loaderChoiceCombo, installSelectedLoaderButton, loaderHint);
        loaderBox.setAlignment(Pos.CENTER_LEFT);
        versionCombo.valueProperty().addListener((obs, oldValue, newValue) ->
                gameDirField.setText(abbreviate(getActiveGameDir().getAbsolutePath(), 72)));

        int row = 0;
        serverLabel = new Label("外置服务器");
        serverLabel.getStyleClass().add("field-label");
        passwordLabel = new Label("密码");
        passwordLabel.getStyleClass().add("field-label");

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

        grid.add(serverLabel, 0, row);
        grid.add(yggdrasilServerField, 1, row++);

        grid.add(passwordLabel, 0, row);
        grid.add(passwordField, 1, row++);

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

    private VBox createLoaderSelectionPage(String profileId, String minecraftVersion) {
        VBox page = createMainPage();
        VBox guidance = new VBox(12,
                createBodyText("为 Minecraft " + minecraftVersion
                        + " 选择加载器，安装完成后会自动进入对应的独立模组实例。"),
                createLoaderQuickActions(profileId));
        page.getChildren().add(createSurface(
                "// 当前版本没有模组加载器",
                versionManager.getVersionDisplayName(profileId),
                guidance
        ));
        return page;
    }

    private HBox createLoaderQuickActions(String profileId) {
        Button fabric = createActionButton("安装 Fabric", "primary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.FABRIC,
                        this::renderActiveView));
        Button quilt = createActionButton("安装 Quilt", "secondary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.QUILT,
                        this::renderActiveView));
        Button forge = createActionButton("安装 Forge", "secondary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.FORGE,
                        this::renderActiveView));
        Button neoForge = createActionButton("安装 NeoForge", "secondary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.NEOFORGE,
                        this::renderActiveView));
        HBox actions = new HBox(10, fabric, quilt, forge, neoForge);
        actions.setAlignment(Pos.CENTER_LEFT);
        return actions;
    }

    private void syncLoaderChoiceFromProfile(String profileId) {
        if (loaderChoiceCombo == null || syncingLoaderChoice) {
            return;
        }
        LoaderChoice detected = loaderChoiceForProfile(profileId);
        syncingLoaderChoice = true;
        try {
            loaderChoiceCombo.setValue(detected);
        } finally {
            syncingLoaderChoice = false;
        }
        updateLoaderControls();
    }

    LoaderChoice loaderChoiceForProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return LoaderChoice.VANILLA;
        }
        return versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .map(profile -> loaderChoiceForId(profile.loader()))
                .findFirst()
                .orElse(LoaderChoice.VANILLA);
    }

    private static LoaderChoice loaderChoiceForId(String loaderId) {
        if (loaderId == null || loaderId.isBlank()) {
            return LoaderChoice.VANILLA;
        }
        for (LoaderChoice choice : LoaderChoice.values()) {
            if (!choice.vanilla() && choice.loader.id().equalsIgnoreCase(loaderId)) {
                return choice;
            }
        }
        return LoaderChoice.VANILLA;
    }

    private void handleLoaderChoiceChanged() {
        if (syncingLoaderChoice || loaderChoiceCombo == null || versionCombo == null) {
            return;
        }
        LoaderChoice requested = loaderChoiceCombo.getValue();
        String selectedProfile = versionCombo.getValue();
        if (requested == null || selectedProfile == null || selectedProfile.isBlank()) {
            updateLoaderControls();
            return;
        }
        String minecraftVersion;
        try {
            minecraftVersion = versionManager.resolveMinecraftVersionId(selectedProfile);
        } catch (IOException error) {
            setStatus("无法识别 Minecraft 版本", cleanMessage(error));
            updateLoaderControls();
            return;
        }
        if (requested.vanilla()) {
            if (!versionCombo.getItems().contains(minecraftVersion)) {
                versionCombo.getItems().add(0, minecraftVersion);
            }
            versionCombo.setValue(minecraftVersion);
            updateLoaderControls();
            return;
        }
        versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.minecraftVersion().equals(minecraftVersion))
                .filter(profile -> profile.loader().equalsIgnoreCase(requested.loader.id()))
                .findFirst()
                .ifPresent(profile -> versionCombo.setValue(profile.profileId()));
        updateLoaderControls();
    }

    void updateLoaderControls() {
        if (loaderChoiceCombo == null) {
            return;
        }
        LoaderChoice requested = loaderChoiceCombo.getValue();
        String selectedProfile = versionCombo == null ? null : versionCombo.getValue();
        LoaderChoice installed = loaderChoiceForProfile(selectedProfile);
        boolean requiresInstall = requested != null && !requested.vanilla() && requested != installed;
        if (installSelectedLoaderButton != null) {
            installSelectedLoaderButton.setDisable(selectedProfile == null || selectedProfile.isBlank()
                    || !requiresInstall);
            if (requested == null || requested.vanilla()) {
                installSelectedLoaderButton.setText("当前为原版");
            } else if (requiresInstall) {
                installSelectedLoaderButton.setText("安装 " + requested.displayName);
            } else {
                installSelectedLoaderButton.setText(requested.displayName + " 已安装");
            }
        }
        if (launchBtn != null) {
            launchBtn.setText(requiresInstall ? "安装并启动" : "启动游戏");
        }
    }

    void installSelectedLoader(Runnable afterSuccess) {
        LoaderChoice requested = loaderChoiceCombo == null ? null : loaderChoiceCombo.getValue();
        String selectedProfile = versionCombo == null ? null : versionCombo.getValue();
        if (requested == null || requested.vanilla()) {
            setStatus("请选择模组加载器", "可选择 Fabric、Quilt、Forge 或 NeoForge。");
            return;
        }
        installLoaderForProfile(selectedProfile, requested.loader, afterSuccess);
    }

    private void installLoaderForProfile(String selectedProfile, ModLoaderInstaller.Loader loader,
                                         Runnable afterSuccess) {
        if (selectedProfile == null || selectedProfile.isBlank()) {
            setStatus("请选择 Minecraft 版本", "安装加载器前需要先选择游戏版本。");
            return;
        }
        String minecraftVersion;
        try {
            minecraftVersion = versionManager.resolveMinecraftVersionId(selectedProfile);
        } catch (IOException error) {
            setStatus("无法识别 Minecraft 版本", cleanMessage(error));
            return;
        }
        setControlsBusy(true);
        startProgressAnimation(downloadProgress);
        setStatus("正在安装加载器", loader.displayName() + " / Minecraft " + minecraftVersion);
        DownloadTaskCenter.TaskHandle<Void> loaderTask = downloadTaskCenter.submit(
                "Loader " + loader.displayName(), context -> {
            try {
                ModLoaderInstaller.InstallResult result = modLoaderInstaller.install(
                        minecraftVersion, loader, "", new ModLoaderInstaller.Listener() {
                            @Override
                            public void onStatus(String message) {
                                context.updateStatus(message);
                                Platform.runLater(() -> setStatus("正在安装加载器", message));
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                context.updateProgress(downloaded, total);
                                Platform.runLater(() -> updateProgress(downloadProgress, downloaded, total));
                            }
                        });
                gameRepository().applyDefaultIsolationSettingForNewInstance(result.profileId());
                Platform.runLater(() -> {
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    versionActions.restoreVersionComboItems(result.profileId());
                    versionCombo.setValue(result.profileId());
                    syncLoaderChoiceFromProfile(result.profileId());
                    setStatus("加载器安装完成", result.loader().displayName() + " "
                            + result.loaderVersion() + " / Minecraft " + result.minecraftVersion());
                    if (afterSuccess != null) {
                        afterSuccess.run();
                    } else if (activeView != AppView.HOME) {
                        renderActiveView();
                    }
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    updateLoaderControls();
                    setStatus("加载器安装失败", cleanMessage(error));
                });
                throw error;
            }
            return null;
        });
    }

    private ListCell<String> createVersionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : versionManager.getVersionDisplayName(item));
            }
        };
    }

    Button createSelectedVersionWikiButton() {
        Button button = new Button("更新说明");
        button.getStyleClass().addAll("app-button", "wiki-link-button");
        button.setTooltip(new Tooltip("打开 mc 中文 Wiki 的当前版本更新介绍"));
        button.setOnAction(e -> versionActions.openMinecraftWikiVersionPage(getSelectedVersion()));
        return button;
    }

    void updateSelectedVersionWikiButton() {
        if (selectedVersionWikiButton == null) {
            return;
        }
        String version = getSelectedVersion();
        boolean supported = versionActions.isWikiSupportedVersion(version);
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
        launchBtn.setGraphicTextGap(10);
        launchBtn.getStyleClass().addAll("app-button", "launch-button");
        launchBtn.setDefaultButton(true);
        launchBtn.setOnAction(e -> gameLaunch.launchGame());
        updateLoaderControls();

        Button switchInstanceButton = createLinkButton(
                "选择版本 / 加载器  ›",
                () -> expandInstanceSettings(versionCombo));

        refreshBtn = new Button("刷新版本");
        refreshBtn.getStyleClass().addAll("app-button", "secondary-button");
        refreshBtn.setOnAction(e -> versionActions.refreshVersions());
        setFieldVisible(refreshBtn, false);

        settingsBtn = new Button("高级设置");
        settingsBtn.getStyleClass().addAll("app-button", "ghost-button");
        settingsBtn.setOnAction(e -> showSettingsDialog());
        setFieldVisible(settingsBtn, false);

        HBox buttonBar = new HBox(14, launchBtn, switchInstanceButton, refreshBtn, settingsBtn);
        buttonBar.getStyleClass().add("launch-actions");
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        return buttonBar;
    }

    void updateAuthFields() {
        String authType = authTypeCombo.getValue();
        boolean microsoft = AUTH_MICROSOFT.equals(authType);
        boolean yggdrasil = AUTH_YGGDRASIL.equals(authType);
        boolean offline = AUTH_OFFLINE.equals(authType);

        // 切换到非 Yggdrasil 认证方式时清除密码
        if (!yggdrasil) {
            passwordField.clear();
        }

        usernameField.setDisable(microsoft);
        setFieldVisible(usernameField, !microsoft);
        setFieldVisible(microsoftAccountCombo, microsoft);
        setFieldVisible(microsoftLoginBtn, microsoft);
        setFieldVisible(microsoftAddAccountBtn, microsoft);
        setFieldVisible(skinUploadBtn, microsoft || offline);
        setFieldVisible(homeSkinUploadButton, microsoft || offline);
        setFieldVisible(serverLabel, yggdrasil);
        setFieldVisible(yggdrasilServerField, yggdrasil);
        setFieldVisible(passwordLabel, yggdrasil);
        setFieldVisible(passwordField, yggdrasil);

        if (microsoft) {
            usernameField.setPromptText("授权后自动读取正版玩家名");
            authSummaryLabel.setText("微软正版登录");
            authHintLabel.setText("会优先静默恢复已保存的登录状态；仅在缓存和刷新令牌失效时显示设备码。 ");
            skinUploadBtn.setText("上传皮肤");
            skinUploadBtn.setTooltip(new Tooltip("上传 PNG 皮肤到当前 Minecraft Java 正版账号"));
            homeSkinUploadButton.setText("上传皮肤  ›");
            homeSkinUploadButton.setTooltip(new Tooltip("上传 PNG 皮肤到当前 Minecraft Java 正版账号"));
        } else if (yggdrasil) {
            usernameField.setPromptText("输入外置登录用户名或邮箱");
            authSummaryLabel.setText("外置登录 / Yggdrasil");
            authHintLabel.setText("密码按“服务器 + 账号”加密保存；留空会复用完全匹配的凭据。 ");
        } else {
            usernameField.setPromptText("输入玩家名称");
            authSummaryLabel.setText("离线登录");
            authHintLabel.setText("会为当前用户名生成本地 UUID，适合单机和快速调试。 ");
            skinUploadBtn.setText("导入皮肤");
            skinUploadBtn.setTooltip(new Tooltip("为离线账号导入本地 PNG 皮肤，启动游戏时自动注入，无需正版账号"));
            homeSkinUploadButton.setText("导入皮肤  ›");
            homeSkinUploadButton.setTooltip(new Tooltip("为离线账号导入本地 PNG 皮肤，启动游戏时自动注入，无需正版账号"));
        }

        updateOfflineSkinControls();
        updateRuntimeSummary();
    }

    void updateOfflineSkinControls() {
        if (offlineSkinRemoveBtn == null) {
            return;
        }
        boolean offline = AUTH_OFFLINE.equals(authTypeCombo.getValue());
        setFieldVisible(offlineSkinRemoveBtn, offline && offlineSkinExists());
        offlineSkinRemoveBtn.setDisable(false);
    }

    boolean offlineSkinExists() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (username.isBlank()) {
            return false;
        }
        return new OfflineSkinStore()
                .find(OfflineSkinStore.identityForOffline(username))
                .isPresent();
    }

    void updateRuntimeSummary() {
        String launcherJava = "启动器 Java " + Runtime.version().feature();
        if (javaSummaryLabel != null) {
            javaSummaryLabel.setText(launcherJava);
            javaSummaryLabel.setTooltip(javaPath == null || javaPath.isBlank()
                    ? null
                    : new Tooltip(javaPath));
        }
        setSummaryText(gameDirSummaryLabel, getActiveGameDir().getAbsolutePath(), 68);

        String selectedVersion = versionCombo == null ? null : versionCombo.getValue();
        String versionDisplay = selectedVersion == null || selectedVersion.isBlank()
                ? Messages.get("home.versionPending")
                : versionManager.getVersionDisplayName(selectedVersion);
        if (versionSummaryLabel != null) {
            versionSummaryLabel.setText(abbreviate(versionDisplay, 26));
            versionSummaryLabel.setTooltip(selectedVersion == null ? null : new Tooltip(selectedVersion));
        }
        if (selectedVersionTitleLabel != null) {
            selectedVersionTitleLabel.setText(selectedVersion == null || selectedVersion.isBlank()
                    ? Messages.get("home.selectVersion")
                    : versionDisplay);
        }
        String memoryText = gameLaunch.getMemoryDisplayText();
        if (selectedRuntimeMetaLabel != null) {
            selectedRuntimeMetaLabel.setText(Messages.format("home.runtimeMeta", launcherJava, memoryText));
        }
        if (topVersionBadgeLabel != null) {
            topVersionBadgeLabel.setText(selectedVersion == null || selectedVersion.isBlank()
                    ? Messages.get("label.notSelected")
                    : abbreviate(versionDisplay, 16));
        }
        String accountName = getAuthDisplayName();
        if (topAuthBadgeLabel != null) {
            topAuthBadgeLabel.setText(accountName);
        }
        if (homeAccountNameLabel != null) {
            homeAccountNameLabel.setText(accountName);
        }
        if (homeAccountTypeLabel != null) {
            homeAccountTypeLabel.setText(getAuthModeLabel());
        }
        if (homeAccountAvatarLabel != null) {
            homeAccountAvatarLabel.setText(accountName.isBlank()
                    ? "E"
                    : accountName.substring(0, 1).toUpperCase(Locale.ROOT));
        }
        if (memorySummaryLabel != null) {
            memorySummaryLabel.setText(memoryText);
        }
        if (topMemoryBadgeLabel != null) {
            topMemoryBadgeLabel.setText(memoryText);
        }
        if (jvmArgsSummaryLabel != null) {
            jvmArgsSummaryLabel.setText(extraJvmArgs == null || extraJvmArgs.isBlank()
                    ? Messages.get("label.notSet") : abbreviate(extraJvmArgs, 68));
            jvmArgsSummaryLabel.setTooltip(extraJvmArgs == null || extraJvmArgs.isBlank() ? null : new Tooltip(extraJvmArgs));
        }

        boolean configuredJava = JavaRuntimeUtil.isUsableJavaPath(javaPath);
        if (runtimeBadgeLabel != null) {
            runtimeBadgeLabel.setText(configuredJava
                    ? String.valueOf(Runtime.version().feature())
                    : Messages.get("label.auto"));
        }
        if (homeEnvironmentStatusLabel != null) {
            homeEnvironmentStatusLabel.setText(configuredJava
                    ? Messages.get("home.envConfigured") : Messages.get("home.envAutoPrepare"));
        }

        String readiness = Messages.get("home.readiness.pendingInstance");
        if (gameLaunch.isGameProcessRunning()) {
            readiness = Messages.get("home.readiness.running");
        } else if (selectedVersion != null && !selectedVersion.isBlank()) {
            readiness = versionManager.isVersionDownloaded(selectedVersion)
                    ? Messages.get("home.readiness.installed")
                    : Messages.get("home.readiness.prepareFirst");
        }
        if (launchReadinessLabel != null) {
            launchReadinessLabel.setText("●  " + readiness);
        }
        gameLaunch.updatePlaytimeSummary();
    }

    private String getAuthDisplayName() {
        String authType = authTypeCombo == null ? AUTH_OFFLINE : authTypeCombo.getValue();
        if (AUTH_MICROSOFT.equals(authType)) {
            if (selectedMicrosoftAccount != null
                    && selectedMicrosoftAccount.username() != null
                    && !selectedMicrosoftAccount.username().isBlank()) {
                return abbreviate(selectedMicrosoftAccount.username().trim(), 18);
            }
        }
        String username = usernameField == null ? "Steve" : usernameField.getText();
        if (username == null || username.isBlank()) {
            username = AUTH_MICROSOFT.equals(authType) ? "Microsoft" : "Steve";
        }
        return abbreviate(username.trim(), 18);
    }

    private String getAuthModeLabel() {
        String authType = authTypeCombo == null ? AUTH_OFFLINE : authTypeCombo.getValue();
        if (AUTH_MICROSOFT.equals(authType)) {
            return "Microsoft 账号";
        }
        if (AUTH_YGGDRASIL.equals(authType)) {
            return "外置登录";
        }
        return "离线登录";
    }

    private void setSummaryText(Label label, String value, int maxLength) {
        if (label == null) {
            return;
        }
        String display = (value == null || value.isBlank()) ? "未设置" : abbreviate(value, maxLength);
        label.setText(display);
        label.setTooltip((value == null || value.isBlank()) ? null : new Tooltip(value));
    }

    void setStatus(String title, String detail) {
        String safeTitle = title == null || title.isBlank() ? "暂无任务" : title.trim();
        String safeDetail = detail == null || detail.isBlank() ? "" : detail.trim();
        if (statusLabel != null) {
            statusLabel.setText(safeTitle);
        }
        if (detailLabel != null) {
            detailLabel.setText(safeDetail);
        }
        if (topTaskLabel != null && !downloadTaskChipHasAttention()) {
            topTaskLabel.setText(abbreviate(safeTitle, 12));
            topTaskLabel.setTooltip(safeDetail.isBlank() ? null : new Tooltip(safeDetail));
        }
    }

    private boolean downloadTaskChipHasAttention() {
        return downloadTaskCenter != null && downloadTaskCenter.snapshots().stream()
                .anyMatch(task -> task.status() == DownloadTaskCenter.Status.QUEUED
                        || task.status() == DownloadTaskCenter.Status.RUNNING
                        || task.status() == DownloadTaskCenter.Status.CANCELLING
                        || task.status() == DownloadTaskCenter.Status.FAILED);
    }

    void startProgressAnimation(ProgressBar progressBar) {
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

    void updateProgress(ProgressBar progressBar, long downloaded, long total) {
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

    void stopProgressAnimation(ProgressBar progressBar, boolean hide) {
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

    private void stopAllProgressAnimations() {
        for (ProgressBar progressBar : List.copyOf(progressAnimations.keySet())) {
            stopProgressAnimation(progressBar, false);
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

    private String normalizeAuthType(String value) {
        if (AUTH_MICROSOFT.equals(value) || AUTH_YGGDRASIL.equals(value) || AUTH_OFFLINE.equals(value)) {
            return value;
        }
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.contains("microsoft")) return AUTH_MICROSOFT;
            if (normalized.contains("yggdrasil")) return AUTH_YGGDRASIL;
            if (normalized.contains("offline") || normalized.contains("离线") || normalized.contains("離線")) {
                return AUTH_OFFLINE;
            }
        }
        return AUTH_OFFLINE;
    }

    private String authDisplayName(String type) {
        return switch (normalizeAuthType(type)) {
            case AUTH_MICROSOFT -> Messages.get("auth.microsoft");
            case AUTH_YGGDRASIL -> Messages.get("auth.yggdrasil");
            default -> Messages.get("auth.offline");
        };
    }

    /**
     * Offline account path: pick a PNG, confirm the model, and copy it into the launcher data
     * directory. The skin is injected at launch time through the built-in Yggdrasil skin service,
     * so it works in single player and on offline-mode servers without any mods or premium login.
     */

    void setControlsBusy(boolean busy) {
        launchBtn.setDisable(busy);
        refreshBtn.setDisable(busy);
        settingsBtn.setDisable(busy);
        if (microsoftLoginBtn != null) {
            microsoftLoginBtn.setDisable(busy);
        }
        if (microsoftAddAccountBtn != null) {
            microsoftAddAccountBtn.setDisable(busy);
        }
        if (skinUploadBtn != null) {
            skinUploadBtn.setDisable(busy);
        }
        if (homeSkinUploadButton != null) {
            homeSkinUploadButton.setDisable(busy);
        }
        if (offlineSkinRemoveBtn != null) {
            offlineSkinRemoveBtn.setDisable(busy);
        }
        if (microsoftAccountCombo != null) {
            microsoftAccountCombo.setDisable(busy);
        }
        versionCombo.setDisable(busy);
        versionTypeCombo.setDisable(busy);
        if (loaderChoiceCombo != null) {
            loaderChoiceCombo.setDisable(busy);
        }
        if (installSelectedLoaderButton != null) {
            installSelectedLoaderButton.setDisable(busy);
        }
        versionActions.updateSelectedVersionWikiButton();
        authTypeCombo.setDisable(busy);
        usernameField.setDisable(busy || AUTH_MICROSOFT.equals(authTypeCombo.getValue()));
        yggdrasilServerField.setDisable(busy);
        passwordField.setDisable(busy);
        if (!busy) {
            updateLoaderControls();
        }
    }

    private void showContentDownloadDialog(ContentTarget target) {
        List<String> profileIds = availableContentProfiles(target);
        if (profileIds.isEmpty()) {
            String detail = target.usesLoader()
                    ? "请选择并安装 Fabric、Forge、NeoForge 或 Quilt 实例。"
                    : "请先在启动器中加载可用的 Minecraft 版本。";
            setStatus("没有可用目标实例", detail);
            if (target.usesLoader()) {
                showLoaderInstallDialog();
            }
            return;
        }
        String activeProfile = getSelectedVersion();
        String initialProfile = profileIds.contains(activeProfile) ? activeProfile : profileIds.getFirst();
        ContentInstance initialInstance = resolveContentInstance(initialProfile);

        Stage dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("下载 " + target.title + " - "
                + versionManager.getVersionDisplayName(initialInstance.profileId()));
        applyWindowIcon(dialog);

        ComboBox<String> targetProfileCombo = new ComboBox<>();
        targetProfileCombo.getItems().setAll(profileIds);
        targetProfileCombo.setValue(initialProfile);
        targetProfileCombo.setCellFactory(list -> createVersionCell());
        targetProfileCombo.setButtonCell(createVersionCell());
        targetProfileCombo.setVisibleRowCount(14);
        applyFieldStyle(targetProfileCombo);

        TextField searchField = new TextField();
        searchField.setPromptText(target.searchHint);
        applyFieldStyle(searchField);

        ComboBox<String> loaderCombo = new ComboBox<>();
        if (target.usesLoader()) {
            loaderCombo.getItems().setAll(initialInstance.loader());
            loaderCombo.setValue(initialInstance.loader());
            loaderCombo.setDisable(true);
        }
        applyFieldStyle(loaderCombo);
        setFieldVisible(loaderCombo, target.usesLoader());

        Button searchBtn = new Button("搜索");
        searchBtn.getStyleClass().addAll("app-button", "secondary-button");

        HBox targetBar = new HBox(10, targetProfileCombo, loaderCombo);
        HBox.setHgrow(targetProfileCombo, Priority.ALWAYS);
        loaderCombo.setPrefWidth(132);

        ComboBox<ContentSource> sourceCombo = createContentSourceCombo();
        HBox searchBar = new HBox(10, sourceCombo, searchField, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        ListView<ModrinthDownloader.Project> resultList = new ListView<>();
        resultList.getStyleClass().add("mod-result-list");
        resultList.setPrefHeight(220);
        resultList.setCellFactory(list -> createContentProjectCell(target));

        ComboBox<ModrinthDownloader.ProjectVersion> projectVersionCombo = new ComboBox<>();
        projectVersionCombo.setPromptText("选择具体版本");
        projectVersionCombo.setDisable(true);
        applyFieldStyle(projectVersionCombo);

        Label descriptionLabel = new Label("正在加载 Modrinth 下载列表...");
        descriptionLabel.getStyleClass().add("status-detail");
        descriptionLabel.setWrapText(true);

        Label targetLabel = new Label();
        targetLabel.getStyleClass().add("footer-text");
        targetLabel.setWrapText(true);
        updateContentTargetLabel(target, initialInstance, targetLabel);

        ProgressBar modProgress = new ProgressBar(0);
        modProgress.getStyleClass().add("download-progress");
        modProgress.setMaxWidth(Double.MAX_VALUE);
        modProgress.setVisible(false);
        modProgress.managedProperty().bind(modProgress.visibleProperty());
        AtomicLong searchGeneration = new AtomicLong();
        AtomicLong versionGeneration = new AtomicLong();
        AtomicLong downloadGeneration = new AtomicLong();
        AtomicLong descriptionGeneration = new AtomicLong();
        dialog.setOnHidden(e -> {
            searchGeneration.incrementAndGet();
            versionGeneration.incrementAndGet();
            downloadGeneration.incrementAndGet();
            descriptionGeneration.incrementAndGet();
            stopProgressAnimation(modProgress, true);
        });

        Label dialogStatus = new Label("正在加载 Modrinth 列表");
        dialogStatus.getStyleClass().add("status-detail");
        dialogStatus.setWrapText(true);

        Button importBtn = new Button("modpack".equals(target.projectType) ? "安装并启动" : "导入");
        importBtn.getStyleClass().addAll("app-button", "primary-button");
        importBtn.setDisable(true);

        Button folderBtn = new Button("打开实例目录");
        folderBtn.getStyleClass().addAll("app-button", "secondary-button");
        folderBtn.setOnAction(e -> {
            ContentInstance selectedInstance = resolveContentInstance(targetProfileCombo.getValue());
            File importDir = target.folderResolver.apply(selectedInstance.profileId());
            try {
                ensureDirectory(importDir);
                openLocalFolder(importDir, target.title + "目录");
            } catch (IOException error) {
                dialogStatus.setText("无法创建目录: " + cleanMessage(error));
            }
        });

        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().addAll("app-button", "ghost-button");
        closeBtn.setOnAction(e -> dialog.close());

        resultList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            long selectedDescriptionGeneration = descriptionGeneration.incrementAndGet();
            versionGeneration.incrementAndGet();
            importBtn.setDisable(true);
            projectVersionCombo.getItems().clear();
            projectVersionCombo.setDisable(selected == null);
            descriptionLabel.setText(selected == null ? "选择一个结果查看简介" : "正在翻译中文简介…");
            if (selected != null) {
                setTranslatedProjectDescription(selected, descriptionLabel,
                        descriptionGeneration, selectedDescriptionGeneration);
                ContentInstance selectedInstance = resolveContentInstance(targetProfileCombo.getValue());
                loadProjectVersions(
                        sourceCombo.getValue(), target,
                        selected,
                        selectedInstance,
                        projectVersionCombo,
                        dialogStatus,
                        importBtn,
                        versionGeneration);
            }
        });
        projectVersionCombo.valueProperty().addListener((obs, oldValue, selectedVersion) ->
                importBtn.setDisable(
                        selectedVersion == null
                                || resultList.getSelectionModel().getSelectedItem() == null));

        searchBtn.setOnAction(e -> searchModrinthContent(
                sourceCombo.getValue(), target,
                resolveContentInstance(targetProfileCombo.getValue()),
                searchField,
                resultList,
                dialogStatus,
                searchBtn,
                importBtn,
                searchGeneration));
        searchField.setOnAction(e -> searchBtn.fire());
        sourceCombo.setOnAction(e -> {
            versionGeneration.incrementAndGet();
            projectVersionCombo.getItems().clear();
            projectVersionCombo.setDisable(true);
            resultList.getItems().clear();
            importBtn.setDisable(true);
            searchBtn.fire();
        });
        targetProfileCombo.setOnAction(e -> {
            ContentInstance selectedInstance = resolveContentInstance(targetProfileCombo.getValue());
            if (target.usesLoader()) {
                loaderCombo.getItems().setAll(selectedInstance.loader());
                loaderCombo.setValue(selectedInstance.loader());
            }
            updateContentTargetLabel(target, selectedInstance, targetLabel);
            versionGeneration.incrementAndGet();
            projectVersionCombo.getItems().clear();
            projectVersionCombo.setDisable(true);
            resultList.getItems().clear();
            importBtn.setDisable(true);
            descriptionLabel.setText("正在加载所选实例的兼容内容...");
            searchModrinthContent(
                    sourceCombo.getValue(), target, selectedInstance, searchField, resultList, dialogStatus,
                    searchBtn, importBtn, searchGeneration);
        });

        importBtn.setOnAction(e -> {
            ContentInstance selectedInstance = resolveContentInstance(targetProfileCombo.getValue());
            File importDir = target.folderResolver.apply(selectedInstance.profileId());
            try {
                ensureDirectory(importDir);
            } catch (IOException error) {
                dialogStatus.setText("无法创建目录: " + cleanMessage(error));
                return;
            }
            downloadSelectedContent(
                    sourceCombo.getValue(), target,
                    resultList.getSelectionModel().getSelectedItem(),
                    projectVersionCombo.getValue(),
                    selectedInstance,
                    importDir,
                    dialogStatus,
                    modProgress,
                    searchBtn,
                    importBtn,
                    targetProfileCombo,
                    downloadGeneration);
        });

        HBox actions = new HBox(10, importBtn, folderBtn, closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox dialogRoot = new VBox(14,
                createSurface("下载 " + target.title,
                        "先选择目标实例；加载器由实例锁定，下载完成后只导入该实例",
                        targetBar, searchBar, targetLabel),
                createSurface("结果与简介",
                        "选择项目后还需要选择一个与目标实例兼容的具体版本",
                        resultList, descriptionLabel, projectVersionCombo),
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
        applyThemeToScene(scene, settingsManager.get(ECLConfig.KEY_THEME));
        dialog.show();
        searchModrinthContent(
                sourceCombo.getValue(), target, initialInstance, searchField, resultList, dialogStatus,
                searchBtn, importBtn, searchGeneration);
    }

    private List<String> availableContentProfiles(ContentTarget target) {
        if (target.usesLoader()) {
            List<String> allowedLoaders = List.of(target.loaders);
            return versionManager.getLocalVersionProfiles().stream()
                    .filter(profile -> allowedLoaders.contains(profile.loader()))
                    .map(VersionManager.LocalVersionProfile::profileId)
                    .distinct()
                    .toList();
        }
        if (versionCombo == null) {
            return List.of();
        }
        return versionCombo.getItems().stream()
                .filter(profileId -> profileId != null && !profileId.isBlank())
                .distinct()
                .toList();
    }

    private ContentInstance resolveContentInstance(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("目标实例不能为空");
        }
        String loader = null;
        for (VersionManager.LocalVersionProfile profile : versionManager.getLocalVersionProfiles()) {
            if (profile.profileId().equals(profileId)) {
                loader = profile.loader();
                break;
            }
        }
        try {
            return new ContentInstance(
                    profileId,
                    versionManager.resolveMinecraftVersionId(profileId),
                    loader,
                    resolveVersionGameDir(profileId));
        } catch (IOException error) {
            throw new IllegalArgumentException("无法解析目标实例 " + profileId, error);
        }
    }

    private void updateContentTargetLabel(
            ContentTarget target,
            ContentInstance instance,
            Label targetLabel
    ) {
        File importDir = target.folderResolver.apply(instance.profileId());
        String loaderLabel = instance.loader() == null || instance.loader().isBlank()
                ? "原版/通用" : loaderDisplayName(instance.loader());
        targetLabel.setText("目标实例: " + versionManager.getVersionDisplayName(instance.profileId())
                + "    Minecraft: " + instance.minecraftVersion()
                + "    加载器: " + loaderLabel
                + "    导入目录: " + importDir.getAbsolutePath());
    }

    private void loadProjectVersions(
            ContentSource source,
            ContentTarget target,
            ModrinthDownloader.Project project,
            ContentInstance instance,
            ComboBox<ModrinthDownloader.ProjectVersion> projectVersionCombo,
            Label dialogStatus,
            Button importBtn,
            AtomicLong versionGeneration
    ) {
        long generation = versionGeneration.incrementAndGet();
        String loader = target.usesLoader() ? instance.loader() : null;
        projectVersionCombo.setDisable(true);
        importBtn.setDisable(true);
        dialogStatus.setText("正在加载 " + project.getTitle() + " 的兼容版本...");

        runAsync("ecl-load-" + source.id() + "-versions", () -> {
            try {
                List<ModrinthDownloader.ProjectVersion> versions =
                        controller.contentDownloader(source).listProjectVersions(
                                project, instance.minecraftVersion(), loader).stream()
                                .filter(version -> controller.preferredModReleaseChannel()
                                        .allows(version.versionType()))
                                .toList();
                Platform.runLater(() -> {
                    if (generation != versionGeneration.get()) {
                        return;
                    }
                    projectVersionCombo.getItems().setAll(versions);
                    projectVersionCombo.setDisable(versions.isEmpty());
                    if (versions.isEmpty()) {
                        dialogStatus.setText("当前发布通道没有兼容 "
                                + instance.minecraftVersion() + " / "
                                + loaderDisplayName(loader) + " 的版本");
                        importBtn.setDisable(true);
                        return;
                    }
                    projectVersionCombo.getSelectionModel().selectFirst();
                    dialogStatus.setText("已加载 " + versions.size() + " 个兼容版本，请确认后导入");
                    importBtn.setDisable(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (generation != versionGeneration.get()) {
                        return;
                    }
                    projectVersionCombo.getItems().clear();
                    projectVersionCombo.setDisable(true);
                    importBtn.setDisable(true);
                    dialogStatus.setText("版本加载失败: " + cleanMessage(error));
                });
            }
        });
    }

    private void searchModrinthContent(ContentSource source, ContentTarget target,
                                       ContentInstance instance, TextField searchField,
                                       ListView<ModrinthDownloader.Project> resultList, Label dialogStatus,
                                       Button searchBtn, Button importBtn, AtomicLong searchGeneration) {
        long generation = searchGeneration.incrementAndGet();
        String query = searchField.getText();
        String gameVersion = instance.minecraftVersion();
        String loader = target.usesLoader() ? instance.loader() : null;
        String loaderLabel = loader == null ? "" : " / " + loader;
        String sourceName = source == ContentSource.CURSEFORGE ? "CurseForge" : "Modrinth";
        boolean officialList = query == null || query.trim().isBlank();

        searchBtn.setDisable(true);
        importBtn.setDisable(true);
        resultList.getItems().clear();
        dialogStatus.setText(officialList
                ? "正在加载 " + sourceName + " " + target.title + "下载列表..."
                : "正在搜索 " + gameVersion + loaderLabel + " 的兼容" + target.title + "...");
        setStatus(officialList ? "正在加载官网列表" : "正在搜索" + target.title,
                officialList ? sourceName + " " + target.title + " · 下载量排序" : query.trim());

        runAsync("ecl-search-" + source.id() + "-" + target.projectType, () -> {
            try {
                ContentDownloader contentDownloader = controller.contentDownloader(source);
                List<ModrinthDownloader.Project> projects = officialList
                        ? contentDownloader.listOfficialProjects(
                                gameVersion, target.projectType, loader, 24)
                        : contentDownloader.searchProjects(
                                query, gameVersion, target.projectType, loader, 24);
                Platform.runLater(() -> {
                    if (generation != searchGeneration.get()) {
                        return;
                    }
                    resultList.getItems().setAll(projects);
                    RemoteImageLoader.prefetch(projects.stream()
                            .map(ModrinthDownloader.Project::getIconUrl)
                            .filter(java.util.Objects::nonNull)
                            .map(this::safeUri)
                            .filter(java.util.Objects::nonNull)
                            .toList());
                    if (!projects.isEmpty()) {
                        resultList.getSelectionModel().select(0);
                    }
                    dialogStatus.setText(projects.isEmpty()
                            ? "没有找到兼容 " + gameVersion + loaderLabel + " 的" + target.title + "。"
                            : (officialList ? "已加载 " + sourceName + " 列表 " : "找到 ")
                                    + projects.size() + " 个结果，选择一个后下载。");
                    setStatus(officialList ? "官网列表已加载" : target.title + "搜索完成",
                            projects.isEmpty() ? "没有找到匹配结果。" : projects.size() + " 个兼容结果。");
                    searchBtn.setDisable(false);
                    importBtn.setDisable(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (generation != searchGeneration.get()) {
                        return;
                    }
                    String message = cleanMessage(e);
                    dialogStatus.setText("搜索失败: " + message);
                    setStatus(target.title + "搜索失败", message);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(true);
                });
            }
        });
    }

    private ListCell<ModrinthDownloader.Project> createContentProjectCell(ContentTarget target) {
        return new ListCell<>() {
            @Override
            protected void updateItem(ModrinthDownloader.Project project, boolean empty) {
                super.updateItem(project, empty);
                setText(null);
                if (empty || project == null) {
                    setGraphic(null);
                    return;
                }
                ImageView cover = new ImageView(RemoteImageLoader.loadingPlaceholder());
                cover.setFitWidth(54);
                cover.setFitHeight(54);
                cover.setPreserveRatio(true);
                URI iconUri = safeUri(project.getIconUrl());
                if (iconUri == null) {
                    cover.setImage(RemoteImageLoader.brokenPlaceholder());
                } else {
                    RemoteImageLoader.load(iconUri).thenAccept(image -> Platform.runLater(() -> {
                        if (getItem() == project) cover.setImage(image);
                    }));
                }
                Label title = new Label(project.getTitle());
                title.getStyleClass().add("mod-item-title");
                Label summary = new Label("modpack".equals(target.projectType)
                        ? "正在翻译简介…" : project.getDescription());
                summary.getStyleClass().add("content-project-summary");
                summary.setWrapText(true);
                summary.setMaxWidth(620);
                if ("modpack".equals(target.projectType)) {
                    ChineseDescriptionService.translate(project.getDescription()).thenAccept(translated ->
                            Platform.runLater(() -> {
                                if (getItem() == project) {
                                    summary.setText(translated == null || translated.isBlank()
                                            ? project.getDescription() : translated);
                                }
                            }));
                }
                String author = project.getAuthor() == null || project.getAuthor().isBlank()
                        ? "Modrinth" : project.getAuthor();
                Label meta = new Label(author + " · 下载 " + formatCount(project.getDownloads()));
                meta.getStyleClass().add("mod-item-meta");
                VBox labels = new VBox(3, title, summary, meta);
                HBox row = new HBox(12, cover, labels);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        };
    }

    private ComboBox<ContentSource> createContentSourceCombo() {
        ComboBox<ContentSource> combo = new ComboBox<>();
        combo.getItems().setAll(ContentSource.MODRINTH, ContentSource.CURSEFORGE);
        combo.setValue(ContentSource.MODRINTH);
        combo.setPrefWidth(132);
        combo.setCellFactory(list -> contentSourceCell());
        combo.setButtonCell(contentSourceCell());
        applyFieldStyle(combo);
        return combo;
    }

    private static ListCell<ContentSource> contentSourceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ContentSource item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item) {
                    case MODRINTH -> "Modrinth";
                    case CURSEFORGE -> "CurseForge";
                });
            }
        };
    }

    private URI safeUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void setTranslatedProjectDescription(
            ModrinthDownloader.Project project,
            Label descriptionLabel,
            AtomicLong descriptionGeneration,
            long expectedGeneration
    ) {
        ChineseDescriptionService.translate(project.getDescription()).thenAccept(translated ->
                Platform.runLater(() -> {
                    if (descriptionLabel.getScene() == null
                            || descriptionGeneration.get() != expectedGeneration) return;
                    String description = translated == null || translated.isBlank()
                            ? project.getDescription() : translated;
                    descriptionLabel.setText(project.getTitle()
                            + "\n下载量: " + formatCount(project.getDownloads())
                            + "    关注: " + formatCount(project.getFollows())
                            + "\n\n" + description);
                }));
    }

    private void downloadSelectedContent(
            ContentSource source,
            ContentTarget target,
            ModrinthDownloader.Project project,
            ModrinthDownloader.ProjectVersion selectedVersion,
            ContentInstance instance,
            File importDir,
            Label dialogStatus,
            ProgressBar modProgress,
            Button searchBtn,
            Button importBtn,
            ComboBox<String> targetProfileCombo,
            AtomicLong downloadGeneration
    ) {
        if (project == null || selectedVersion == null) {
            dialogStatus.setText("请先选择一个" + target.title + "及其具体版本。");
            return;
        }

        long generation = downloadGeneration.incrementAndGet();
        String loader = target.usesLoader() ? instance.loader() : null;
        String gameVersion = instance.minecraftVersion();
        setControlsBusy(true);
        searchBtn.setDisable(true);
        importBtn.setDisable(true);
        targetProfileCombo.setDisable(true);
        modProgress.setProgress(0);
        downloadProgress.setProgress(0);
        startProgressAnimation(modProgress);
        startProgressAnimation(downloadProgress);
        String loaderLabel = loader == null ? "" : " / " + loader;
        setStatus("正在下载" + target.title,
                project.getTitle() + " " + selectedVersion.versionNumber()
                        + " -> " + gameVersion + loaderLabel);

        DownloadTaskCenter.TaskHandle<Void> contentTask = downloadTaskCenter.submit(
                "Content " + project.getTitle(), context -> {
            if (generation != downloadGeneration.get()) {
                // The download dialog was closed; do not start the transfer or touch the UI.
                return null;
            }
            try {
                ModrinthDownloader.DownloadResult result = controller.contentDownloader(source).downloadVersion(
                        project,
                        selectedVersion,
                        gameVersion,
                        loader,
                        importDir,
                        target.downloadDependencies,
                        new ModrinthDownloader.DownloadListener() {
                            @Override
                            public void onStatus(String message) {
                                context.updateStatus(message);
                                Platform.runLater(() -> {
                                    if (generation != downloadGeneration.get()) {
                                        return;
                                    }
                                    dialogStatus.setText(message);
                                    setStatus("正在导入" + target.title, message);
                                });
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                context.updateProgress(downloaded, total);
                                Platform.runLater(() -> {
                                    if (generation != downloadGeneration.get()) {
                                        return;
                                    }
                                    updateProgress(modProgress, downloaded, total);
                                    updateProgress(downloadProgress, downloaded, total);
                                });
                            }
                        },
                        target.allowedExtensions
                );
                MrpackInstaller.InstallResult packResult = null;
                if ("modpack".equals(target.projectType)) {
                    if (result.getMainFile() == null) {
                        throw new IOException("整合包下载完成，但没有找到安装文件");
                    }
                    File installArchive = result.getMainFile();
                    boolean converted = source == ContentSource.CURSEFORGE;
                    if (converted) {
                        dialogStatus.setText("正在解析 CurseForge 整合包清单...");
                        installArchive = controller.curseForgeDownloader()
                                .convertModpackToMrpack(result.getMainFile());
                    }
                    try {
                        packResult = mrpackInstaller.install(
                                installArchive,
                                getConfiguredGameRootDir(),
                                project.getTitle(),
                                source == ContentSource.MODRINTH ? project.getProjectId() : "",
                                source == ContentSource.MODRINTH ? selectedVersion.versionId() : "",
                                new MrpackInstaller.Listener() {
                                @Override
                                public void onStatus(String message) {
                                    context.updateStatus(message);
                                    Platform.runLater(() -> {
                                        if (generation != downloadGeneration.get()) {
                                            return;
                                        }
                                        dialogStatus.setText(message);
                                        setStatus("正在安装整合包", message);
                                    });
                                }

                                @Override
                                public void onProgress(long downloaded, long total) {
                                    context.updateProgress(downloaded, total);
                                    Platform.runLater(() -> {
                                        if (generation != downloadGeneration.get()) {
                                            return;
                                        }
                                        updateProgress(modProgress, downloaded, total);
                                        updateProgress(downloadProgress, downloaded, total);
                                    });
                                }
                                });
                    } finally {
                        if (converted) Files.deleteIfExists(installArchive.toPath());
                    }
                    gameRepository().applyDefaultIsolationSettingForNewInstance(packResult.profileId());
                }

                MrpackInstaller.InstallResult completedPack = packResult;
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) {
                        return;
                    }
                    modProgress.setProgress(1);
                    downloadProgress.setProgress(1);
                    stopProgressAnimation(modProgress, false);
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(false);
                    targetProfileCombo.setDisable(false);
                    String mainFile = result.getMainFile() == null ? project.getTitle() : result.getMainFile().getName();
                    String detail = completedPack == null
                            ? "已导入 " + result.getFiles().size() + " 个文件到: " + importDir.getAbsolutePath()
                            : "已安装为独立可启动实例 " + completedPack.profileId()
                                    + "，文件目录: " + completedPack.instanceDirectory();
                    dialogStatus.setText(mainFile + " 导入完成。 " + detail);
                    setStatus(target.title + "导入完成", detail);
                    if (completedPack != null) {
                        versionActions.restoreVersionComboItems(completedPack.profileId());
                        versionActions.syncLaunchVersionToContent(completedPack.profileId());
                        dialogStatus.setText(mainFile + " 安装完成，正在启动整合包…");
                        setStatus("整合包安装完成", "正在启动 " + completedPack.name());
                        Platform.runLater(() -> gameLaunch.launchGame());
                    } else {
                        versionActions.syncLaunchVersionToContent(instance.profileId());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (generation != downloadGeneration.get()) {
                        return;
                    }
                    String message = cleanMessage(e);
                    stopProgressAnimation(modProgress, true);
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    searchBtn.setDisable(false);
                    importBtn.setDisable(false);
                    targetProfileCombo.setDisable(false);
                    dialogStatus.setText("下载失败: " + message);
                    setStatus(target.title + "下载失败", message);
                });
                throw e;
            }
            return null;
        });
    }

    String getSelectedVersion() {
        return versionCombo == null ? null : versionCombo.getValue();
    }

    /**
     * Content is downloaded into the instance directory of {@code contentVersion}. To keep the
     * launched game directory identical to the download target (so mods / shaderpacks /
     * resourcepacks are actually loaded), the launch selection is realigned to that version after
     * a successful import. Only selects the value when it is already offered by the combo.
     */

    File getConfiguredGameRootDir() {
        return gameDir == null ? ECLConfig.getGameDir() : gameDir;
    }

    /**
     * Normalizes a configured root directory into the single source of truth used for every
     * content path (mods / shaderpacks / resourcepacks / saves). The legacy {@code <base>/game}
     * location is folded back into the standard game directory so download targets and the launched
     * game directory can never diverge because of an outdated saved path.
     */
    File resolveConfiguredGameRootDir(File candidate) {
        if (candidate == null || (candidate.getPath().isBlank())) {
            return ECLConfig.getGameDir();
        }
        if (isSamePath(candidate, ECLConfig.getLegacyGameDir())) {
            File defaultGameDir = ECLConfig.getGameDir();
            settingsManager.set(ECLConfig.KEY_GAME_DIR, defaultGameDir.getAbsolutePath());
            settingsManager.save();
            return defaultGameDir;
        }
        return candidate;
    }

    private boolean isSamePath(File first, File second) {
        if (first == null || second == null) {
            return false;
        }
        String firstPath = first.getAbsoluteFile().toPath().normalize().toString();
        String secondPath = second.getAbsoluteFile().toPath().normalize().toString();
        return firstPath.equalsIgnoreCase(secondPath);
    }

    private File getActiveGameDir() {
        return resolveVersionGameDir(getSelectedVersion());
    }

    File resolveVersionGameDir(String gameVersion) {
        File rootDir = getConfiguredGameRootDir();
        if (gameVersion == null || gameVersion.isBlank()) {
            return rootDir;
        }
        try {
            return gameRepository().runDirectory(gameVersion).toFile();
        } catch (IOException error) {
            LOGGER.warn("Cannot resolve run directory for {}; using isolated fallback", gameVersion, error);
            return resolveVersionInstanceRoot(gameVersion);
        }
    }

    File resolveVersionInstanceRoot(String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return getConfiguredGameRootDir();
        }
        return gameRepository().instanceRoot(sanitizeVersionDirectoryName(gameVersion)).toFile();
    }

    DefaultGameRepository gameRepository() {
        return new DefaultGameRepository(ECLConfig.getVersionsDir().toPath(),
                getConfiguredGameRootDir().toPath(), DefaultIsolationType.parse(
                        settingsManager.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
    }

    void ensureDirectory(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
    }

    private String sanitizeVersionDirectoryName(String version) {
        String sanitized = TextUtil.replaceInvalidFilenameChars(version.trim());
        return sanitized.isBlank() ? "unknown-version" : sanitized;
    }

    private static String loaderDisplayName(String loader) {
        if (loader == null) {
            return "原版";
        }
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> loader;
        };
    }

    File resolveModsDir(String gameVersion) {
        return new File(resolveVersionGameDir(gameVersion), "mods");
    }

    private void showSettingsDialog() {
        new SettingsDialog(this).show();
    }

    int parseMemorySetting(String value) {
        if (value == null || value.isBlank() || "自动".equals(value.trim())) {
            return ECLConfig.AUTO_MEMORY_MB;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < ECLConfig.MIN_GAME_MEMORY_MB || parsed > ECLConfig.MAX_GAME_MEMORY_MB) {
                throw new IllegalArgumentException("请输入 " + ECLConfig.MIN_GAME_MEMORY_MB + " 到 "
                        + ECLConfig.MAX_GAME_MEMORY_MB + " 之间的 MB 数值，或留空使用自动分配。");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("请输入整数 MB 数值，或留空使用自动分配。", e);
        }
    }

    int parseRangedInt(String value, String label, int min, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(label + "必须在 " + min + " 到 " + max + " 之间。");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + "必须是整数。", error);
        }
    }

    ScrollPane createWheelScrollPane(Node content) {
        return LauncherUiFactory.wheelScrollPane(content);
    }

    private double clamp(double value, double min, double max) {
        return LauncherUiFactory.clamp(value, min, max);
    }

    void openLocalFolder(File folder, String label) {
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
            } catch (URISyntaxException uriError) {
                LOGGER.warn("Failed to open local folder {}", folder, uriError);
                setStatus("无法打开" + label, cleanMessage(e));
            }
        }
    }

    void openExternalUrl(String url) {
        getHostServices().showDocument(url);
    }

    void applyWindowIcon(Stage stage) {
        URL icon = getClass().getResource("/icons/ecl-icon.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon.toExternalForm()));
        }
    }

    File prepareChooserDir(String rawPath) {
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

    void runAsync(String threadName, Runnable action) {
        controller.runAsync(threadName, action);
    }

    private void setFieldVisible(Node node, boolean visible) {
        LauncherUiFactory.setVisible(node, visible);
    }

    VBox createSurface(String title, String subtitle, Node... content) {
        return LauncherUiFactory.surface(title, subtitle, content);
    }

    HBox createInfoRow(String key, Label valueLabel) {
        return LauncherUiFactory.infoRow(key, valueLabel);
    }

    private HBox createControlRow(String key, Node control) {
        return LauncherUiFactory.controlRow(key, control);
    }

    private void configureLocalizedCombo(ComboBox<String> combo, Function<String, String> displayName) {
        LauncherUiFactory.configureLocalizedCombo(combo, displayName);
    }

    private String languageDisplayName(String tag) {
        return LauncherThemeManager.languageDisplayName(tag);
    }

    private String themeDisplayName(String theme) {
        return LauncherThemeManager.themeDisplayName(theme);
    }

    private String normalizeTheme(String theme) {
        return LauncherThemeManager.normalize(theme);
    }

    private void switchLanguage(String languageTag) {
        if (languageTag == null) return;
        Messages.setLocale(Locale.forLanguageTag(languageTag));
        settingsManager.set(ECLConfig.KEY_LANGUAGE, languageTag);
        settingsManager.save();
        primaryStage.setTitle(Messages.get("app.title"));
        navButtons.forEach((view, button) -> button.setText(navTitleFor(view)));
        if (authTypeCombo != null) authTypeCombo.requestLayout();
        homePage = null;
        contentTargets = createContentTargets();
        renderActiveView();
    }

    private void applyTheme(String requestedTheme) {
        LauncherThemeManager.applyToAllWindows(primaryStage, requestedTheme);
    }

    void applyThemeToScene(Scene scene, String requestedTheme) {
        LauncherThemeManager.applyToScene(scene, requestedTheme);
    }

    private Label createValueLabel() {
        return LauncherUiFactory.valueLabel();
    }

    private Label createValueLabel(String text) {
        return LauncherUiFactory.valueLabel(text);
    }

    void applyFieldStyle(Control control) {
        LauncherUiFactory.applyFieldStyle(control);
    }

    private Button createIconActionButton(String iconResource, String fallbackIcon, String tooltip, Runnable action) {
        return LauncherUiFactory.iconActionButton(LauncherUI.class,
                iconResource, fallbackIcon, tooltip, action);
    }

    private Node createIconNode(String resourcePath, String fallbackText, double size, String styleClass) {
        return LauncherUiFactory.iconNode(
                LauncherUI.class, resourcePath, fallbackText, size, styleClass);
    }

    private String iconForContentTarget(ContentTarget target) {
        if (target == null || target.projectType == null) {
            return ICON_GRASS_BLOCK;
        }
        return switch (target.projectType) {
            case "shader" -> ICON_LAMP_BLOCK;
            case "resourcepack" -> ICON_WOOD_BLOCK;
            case "modpack" -> ICON_STONE_BLOCK;
            case "server" -> ICON_SIGNAL;
            default -> ICON_GRASS_BLOCK;
        };
    }

    String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    boolean isCancellation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof CancellationException || cursor instanceof InterruptedException) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    String cleanMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
