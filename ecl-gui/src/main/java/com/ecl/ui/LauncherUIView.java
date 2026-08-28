package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MicrosoftAccountStore;
import com.ecl.auth.MinecraftSkinService;
import com.ecl.backup.WorldBackupService;
import com.ecl.config.SettingsManager;
import com.ecl.diagnostic.DiagnosticBundleService;
import com.ecl.download.DownloadService;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.ServerJarDownloader;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.PlaytimeTracker;
import com.ecl.launch.Launcher;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.modrinth.ui.ModBrowserView;
import com.ecl.server.ServerBrowserView;
import com.ecl.util.Messages;
import javafx.animation.Animation;
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
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ecl.util.TextUtil.abbreviate;

class LauncherUIView extends javafx.application.Application {
    static final Logger LOGGER = LoggerFactory.getLogger(LauncherUI.class);
    static final String AUTH_OFFLINE = "OFFLINE";
    static final String AUTH_MICROSOFT = "MICROSOFT";
    static final String AUTH_YGGDRASIL = "YGGDRASIL";
    static final String MC_CHINESE_WIKI_VERSION_URL_PREFIX = "https://zh.minecraft.wiki/w/";
    private static final double WINDOW_WIDTH = 1440;
    private static final double WINDOW_HEIGHT = 900;
    static final double LAUNCH_WIDTH = 1180;

    VersionManager versionManager;
    DownloadService downloader;
    DownloadTaskCenter downloadTaskCenter;
    ServerJarDownloader serverJarDownloader;
    ModLoaderInstaller modLoaderInstaller;
    MrpackInstaller mrpackInstaller;
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

    Label authSummaryLabel;
    Label authHintLabel;
    Label javaSummaryLabel;
    Label gameDirSummaryLabel;
    Label versionSummaryLabel;
    Label memorySummaryLabel;
    Label jvmArgsSummaryLabel;
    Label runtimeBadgeLabel;
    Label topAuthBadgeLabel;
    Label topVersionBadgeLabel;
    Label topMemoryBadgeLabel;
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
    DownloadTasksPage downloadTasksPage;
    private final LauncherContentBrowser contentBrowser = new LauncherContentBrowser((LauncherUI) this);
    private final LauncherLaunchForm launchForm = new LauncherLaunchForm((LauncherUI) this);
    final LauncherPageFactory pageFactory = new LauncherPageFactory((LauncherUI) this);
    private final LauncherPathService pathService = new LauncherPathService((LauncherUI) this);
    final HomePageFactory homePageFactory = new HomePageFactory((LauncherUI) this);
    final ContentLibraryPageFactory contentLibraryPageFactory =
            new ContentLibraryPageFactory((LauncherUI) this);
    private final RuntimeSummaryPresenter runtimeSummaryPresenter =
            new RuntimeSummaryPresenter((LauncherUI) this);
    TitledPane instanceSettingsPane;
    VBox homePage;
    HBox workspacePane;
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
    final LauncherPageRouter pageRouter = new LauncherPageRouter((LauncherUI) this);
    final LauncherDesktopIntegration desktopIntegration =
            new LauncherDesktopIntegration((LauncherUI) this);
    final LauncherNavigationRail navigationRail = new LauncherNavigationRail(pageRouter::setActiveView);
    Animation contentTransition;
    private ModBrowserView activeModBrowserView;
    ServerBrowserView activeServerBrowserView;
    AppView activeView = AppView.HOME;

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
        serverJarDownloader = new ServerJarDownloader(versionManager);
        modLoaderInstaller = new ModLoaderInstaller();
        mrpackInstaller = new MrpackInstaller();
        worldBackupService = new WorldBackupService();
        microsoftAccountStore = new MicrosoftAccountStore();
        minecraftSkinService = new MinecraftSkinService();
        gameLauncher = controller.gameLauncher();
        microsoftAccounts = new MicrosoftAccountCoordinator((LauncherUI) this);
        skins = new SkinCoordinator((LauncherUI) this);
        gameLaunch = new GameLaunchCoordinator((LauncherUI) this);
        versionActions = new VersionActions((LauncherUI) this);

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
        pageRouter.setActiveView(view);
    }

    boolean isHomeViewActive() {
        return pageRouter.isHomeViewActive();
    }

    void renderActiveView() {
        pageRouter.renderActiveView();
    }

    void renderActiveView(int slideDirection) {
        pageRouter.renderActiveView(slideDirection);
    }

    private List<ContentTarget> createContentTargets() {
        return ContentTargetFactory.create(
                this::resolveModsDir, this::resolveVersionGameDir,
                this::getConfiguredGameRootDir);
    }

    int countCrashReports() {
        File crashDir = new File(getActiveGameDir(), "crash-reports");
        File[] reports = crashDir.listFiles((dir, name) -> name.endsWith(".txt"));
        return reports == null ? 0 : reports.length;
    }

    void createInstanceShortcut(boolean startMenu) {
        desktopIntegration.createInstanceShortcut(startMenu);
    }

    static Path resolveLauncherExecutableCandidate(String configured, String runningCommand,
                                                    Path workingDirectory, Path codeSource) {
        return LauncherExecutableResolver.resolveCandidate(
                configured, runningCommand, workingDirectory, codeSource);
    }

    void showBackupManagerDialog() {
        new BackupManagerDialog((LauncherUI) this).show();
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
        new LoaderInstallDialog((LauncherUI) this).show();
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
        return new ServerJarDownloadPage((LauncherUI) this).build();
    }

    Node createContentLibraryBrowser(ContentTarget target) {
        return contentBrowser.createContentLibraryBrowser(target);
    }

    void closeActiveModBrowserView() {
        if (activeModBrowserView != null) {
            activeModBrowserView.close();
            activeModBrowserView = null;
        }
    }

    void closeActiveServerBrowserView() {
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
        return launchForm.createForm();
    }

    private VBox createLoaderSelectionPage(String profileId, String minecraftVersion) {
        return launchForm.createLoaderSelectionPage(profileId, minecraftVersion);
    }

    LoaderChoice loaderChoiceForProfile(String profileId) {
        return launchForm.loaderChoiceForProfile(profileId);
    }

    void updateLoaderControls() {
        launchForm.updateLoaderControls();
    }

    void installSelectedLoader(Runnable afterSuccess) {
        launchForm.installSelectedLoader(afterSuccess);
    }

    ListCell<String> createVersionCell() {
        return launchForm.createVersionCell();
    }

    Button createSelectedVersionWikiButton() {
        return launchForm.createSelectedVersionWikiButton();
    }

    void updateSelectedVersionWikiButton() {
        launchForm.updateSelectedVersionWikiButton();
    }

    HBox createActionBar() {
        return launchForm.createActionBar();
    }

    void updateAuthFields() {
        launchForm.updateAuthFields();
    }

    void updateOfflineSkinControls() {
        launchForm.updateOfflineSkinControls();
    }

    boolean offlineSkinExists() {
        return launchForm.offlineSkinExists();
    }

    void updateRuntimeSummary() {
        runtimeSummaryPresenter.update();
    }

    String getAuthDisplayName() {
        return launchForm.getAuthDisplayName();
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

    void setControlsBusy(boolean busy) {
        launchForm.setControlsBusy(busy);
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
        contentBrowser.showContentDownloadDialog(target);
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
        new SettingsDialog((LauncherUI) this).show();
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
        desktopIntegration.openLocalFolder(folder, label);
    }

    void openExternalUrl(String url) {
        desktopIntegration.openExternalUrl(url);
    }

    void applyWindowIcon(Stage stage) {
        desktopIntegration.applyWindowIcon(stage);
    }

    File prepareChooserDir(String rawPath) {
        return desktopIntegration.prepareChooserDir(rawPath);
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
