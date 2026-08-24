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
import com.ecl.game.PlaytimeTracker;
import com.ecl.launch.Launcher;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.modrinth.provider.ContentSource;
import com.ecl.modrinth.ui.ChineseDescriptionService;
import com.ecl.modrinth.ui.ModBrowserView;
import com.ecl.modrinth.ui.RemoteImageLoader;
import com.ecl.server.ServerBrowserView;
import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.Messages;
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
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
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
    static final double LAUNCH_WIDTH = 1180;
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
    ServerJarDownloader serverJarDownloader;
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
    Label statusLabel;
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
    Label javaSummaryLabel;
    Label gameDirSummaryLabel;
    Label versionSummaryLabel;
    Label memorySummaryLabel;
    private Label jvmArgsSummaryLabel;
    private Label runtimeBadgeLabel;
    private Label topAuthBadgeLabel;
    private Label topVersionBadgeLabel;
    private Label topMemoryBadgeLabel;
    Label selectedVersionTitleLabel;
    Label selectedRuntimeMetaLabel;
    Label launchReadinessLabel;
    Label homeAccountNameLabel;
    Label homeAccountTypeLabel;
    Label homeAccountAvatarLabel;
    Label homeEnvironmentStatusLabel;
    private Label topTaskLabel;
    Label playtimeTotalLabel;
    Label playtimeRecentLabel;
    Label playtimeLaunchCountLabel;
    private DownloadTasksPage downloadTasksPage;
    private final LauncherPageFactory pageFactory = new LauncherPageFactory(this);
    private final LauncherPathService pathService = new LauncherPathService(this);
    private final HomePageFactory homePageFactory = new HomePageFactory(this);
    private final ContentLibraryPageFactory contentLibraryPageFactory =
            new ContentLibraryPageFactory(this);
    TitledPane instanceSettingsPane;
    VBox homePage;
    private HBox workspacePane;
    List<ContentTarget> contentTargets;

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
    final Map<Process, String> activeGameProcesses = new ConcurrentHashMap<>();
    private final LauncherProgressController progressController = new LauncherProgressController();
    private final LauncherNavigationRail navigationRail = new LauncherNavigationRail(this::setActiveView);
    private Animation contentTransition;
    private ModBrowserView activeModBrowserView;
    ServerBrowserView activeServerBrowserView;
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
        gameDir = pathService.resolveConfiguredGameRootDir(new File(
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
        progressController.stopAll();
        closeActiveModBrowserView();
        closeActiveServerBrowserView();
        if (contentTransition != null) {
            contentTransition.stop();
        }
        if (controller != null) {
            controller.close();
        }
    }

    void showFirstRunWizard() {
        if (firstRunWizard == null) {
            firstRunWizard = new FirstRunWizard(settingsManager, this::switchLanguage,
                    this::languageDisplayName, this::applyThemeToScene);
        }
        firstRunWizard.show(primaryStage);
    }

    void exportDiagnosticBundle() {
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
        new ModDropImportHandler(controller, this::getSelectedVersion,
                this::getConfiguredGameRootDir, this::resolveVersionGameDir, this::setStatus,
                () -> {
                    if (activeModBrowserView != null) {
                        activeModBrowserView.refreshInstalledMods();
                    }
                }).install(root);
    }

    private HBox createWindowTitleBar() {
        HBox titleBar = new HBox(24);
        titleBar.getStyleClass().add("window-title-bar");
        titleBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("ECL");
        title.getStyleClass().addAll("window-title", "brand-label");

        HBox navigation = navigationRail.createTopNavigation(activeView);

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

        LauncherWindowChrome windowChrome = new LauncherWindowChrome(primaryStage);
        HBox windowControls = windowChrome.createControls();

        titleBar.getChildren().addAll(
                title,
                leftSpacer,
                navigation,
                rightSpacer,
                topTaskLabel,
                topAuthBadgeLabel,
                windowControls
        );
        windowChrome.installDragBehavior(titleBar);
        return titleBar;
    }

    private Label createTrafficDot(String styleClass) {
        Label dot = new Label();
        dot.getStyleClass().addAll("traffic-dot", styleClass);
        return dot;
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
        navigationRail.showSelected(view);
        if (authTypeCombo != null && authSummaryLabel != null) {
            updateAuthFields();
        } else {
            updateRuntimeSummary();
        }
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
            case HOME -> addMainContent(homePageFactory.getOrCreate(), null);
            case VERSIONS -> addMainContent(pageFactory.createVersionsPage(), null);
            case DOWNLOADS -> {
                downloadTasksPage = pageFactory.createDownloadTasksPage();
                addMainContent(downloadTasksPage, null);
            }
            case MODRINTH -> addMainContent(contentLibraryPageFactory.createPage(), null);
            case SERVERS -> addMainContent(pageFactory.createServersPage(), null);
            case SETTINGS -> addMainContent(pageFactory.createSettingsPage(), null);
            case LOGS -> addMainContent(pageFactory.createLogsPage(), null);
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

    int countCrashReports() {
        File crashDir = new File(getActiveGameDir(), "crash-reports");
        File[] reports = crashDir.listFiles((dir, name) -> name.endsWith(".txt"));
        return reports == null ? 0 : reports.length;
    }

    private VBox getOrCreateHomePage() {
        return homePageFactory.getOrCreate();
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

    void createInstanceShortcut(boolean startMenu) {
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

    void showBackupManagerDialog() {
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

    void showLoaderInstallDialog() {
        new LoaderInstallDialog(this).show();
    }

    Node createModLibraryContent() {
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

    VBox createContentLibraryLoaderPrompt(String profileId, String minecraftVersion) {
        Button install = createActionButton(Messages.get("content.loader.install"), "primary-button",
                this::showLoaderInstallDialog);
        return createSurface(Messages.get("content.mods.title"), versionManager.getVersionDisplayName(profileId),
                createBodyText(Messages.format("content.loader.prompt", minecraftVersion)),
                install);
    }

    Node createServerJarLibraryContent() {
        return new ServerJarDownloadPage(this).build();
    }

    Node createContentLibraryBrowser(ContentTarget target) {
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
        AtomicLong activeDownloadGeneration = new AtomicLong();
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
                    searchButton, downloadButton, targetProfileCombo, downloadGeneration,
                    activeDownloadGeneration);
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

    void closeActiveModBrowserView() {
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

    /** 将地址写入直连服务器配置并持久化，供服务器浏览页“设为直连”调用。 */
    void setQuickServer(String address) {
        quickServer = address == null ? "" : address.trim();
        settingsManager.set(ECLConfig.KEY_QUICK_SERVER, quickServer);
        settingsManager.save();
        setStatus("已设为直连服务器", quickServer.isBlank()
                ? "已清空直连服务器地址"
                : "下次启动将直接连接 " + quickServer);
    }

    @SuppressWarnings("unused")
    HBox createSummaryRow(String key, Label value) {
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

    Button createLinkButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", "link-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    void expandInstanceSettings(Control focusTarget) {
        if (instanceSettingsPane == null) {
            return;
        }
        instanceSettingsPane.setExpanded(true);
        if (focusTarget != null) {
            Platform.runLater(focusTarget::requestFocus);
        }
    }

    VBox createMainPage() {
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


    GridPane createForm() {
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
                "Loader " + loader.displayName(), () -> context -> {
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

    HBox createActionBar() {
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

    String getAuthDisplayName() {
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
        progressController.start(progressBar);
    }

    void updateProgress(ProgressBar progressBar, long downloaded, long total) {
        progressController.update(progressBar, downloaded, total);
    }

    void stopProgressAnimation(ProgressBar progressBar, boolean hide) {
        progressController.stop(progressBar, hide);
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

    void registerActiveGameProcess(Process process, String version) {
        if (process == null) {
            return;
        }
        activeGameProcesses.put(process, version);
        activeGameProcess = process;
        activeGameVersion = version;
    }

    void unregisterActiveGameProcess(Process process) {
        if (process == null) {
            return;
        }
        activeGameProcesses.remove(process);
        if (activeGameProcess == process) {
            Map.Entry<Process, String> replacement = activeGameProcesses.entrySet().stream()
                    .findFirst().orElse(null);
            activeGameProcess = replacement == null ? null : replacement.getKey();
            activeGameVersion = replacement == null ? null : replacement.getValue();
        }
    }

    boolean hasRunningGameProcess() {
        return activeGameProcesses.keySet().stream().anyMatch(Process::isAlive);
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
        AtomicLong activeDownloadGeneration = new AtomicLong();
        AtomicLong descriptionGeneration = new AtomicLong();
        dialog.setOnHidden(e -> {
            searchGeneration.incrementAndGet();
            versionGeneration.incrementAndGet();
            downloadGeneration.incrementAndGet();
            descriptionGeneration.incrementAndGet();
            if (activeDownloadGeneration.getAndSet(0) != 0) {
                stopProgressAnimation(modProgress, true);
                stopProgressAnimation(downloadProgress, true);
                setControlsBusy(false);
            }
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
                    downloadGeneration,
                    activeDownloadGeneration);
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
            AtomicLong downloadGeneration,
            AtomicLong activeDownloadGeneration
    ) {
        if (project == null || selectedVersion == null) {
            dialogStatus.setText("请先选择一个" + target.title + "及其具体版本。");
            return;
        }

        long generation = downloadGeneration.incrementAndGet();
        activeDownloadGeneration.set(generation);
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
                "Content " + project.getTitle(), () -> context -> {
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
                        Platform.runLater(() -> {
                            if (generation == downloadGeneration.get()) {
                                dialogStatus.setText("正在解析 CurseForge 整合包清单...");
                            }
                        });
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
                    activeDownloadGeneration.compareAndSet(generation, 0);
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
                    activeDownloadGeneration.compareAndSet(generation, 0);
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

    File getConfiguredGameRootDir() {
        return pathService.getConfiguredGameRootDir();
    }

    File resolveConfiguredGameRootDir(File candidate) {
        return pathService.resolveConfiguredGameRootDir(candidate);
    }

    File getActiveGameDir() {
        return pathService.getActiveGameDir();
    }

    File resolveVersionGameDir(String gameVersion) {
        return pathService.resolveVersionGameDir(gameVersion);
    }

    File resolveVersionInstanceRoot(String gameVersion) {
        return pathService.resolveVersionInstanceRoot(gameVersion);
    }

    DefaultGameRepository gameRepository() {
        return pathService.gameRepository();
    }

    void ensureDirectory(File dir) throws IOException {
        pathService.ensureDirectory(dir);
    }

    private static String loaderDisplayName(String loader) {
        return LauncherPathService.loaderDisplayName(loader);
    }

    File resolveModsDir(String gameVersion) {
        return pathService.resolveModsDir(gameVersion);
    }

    void showSettingsDialog() {
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

    HBox createControlRow(String key, Node control) {
        return LauncherUiFactory.controlRow(key, control);
    }

    void configureLocalizedCombo(ComboBox<String> combo, Function<String, String> displayName) {
        LauncherUiFactory.configureLocalizedCombo(combo, displayName);
    }

    String languageDisplayName(String tag) {
        return LauncherThemeManager.languageDisplayName(tag);
    }

    String themeDisplayName(String theme) {
        return LauncherThemeManager.themeDisplayName(theme);
    }

    String normalizeTheme(String theme) {
        return LauncherThemeManager.normalize(theme);
    }

    void switchLanguage(String languageTag) {
        if (languageTag == null) return;
        Messages.setLocale(Locale.forLanguageTag(languageTag));
        settingsManager.set(ECLConfig.KEY_LANGUAGE, languageTag);
        settingsManager.save();
        primaryStage.setTitle(Messages.get("app.title"));
        navigationRail.refreshTexts();
        if (authTypeCombo != null) authTypeCombo.requestLayout();
        homePage = null;
        contentTargets = createContentTargets();
        renderActiveView();
    }

    void applyTheme(String requestedTheme) {
        LauncherThemeManager.applyToAllWindows(primaryStage, requestedTheme);
    }

    void applyThemeToScene(Scene scene, String requestedTheme) {
        LauncherThemeManager.applyToScene(scene, requestedTheme);
    }

    Label createValueLabel() {
        return LauncherUiFactory.valueLabel();
    }

    Label createValueLabel(String text) {
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
