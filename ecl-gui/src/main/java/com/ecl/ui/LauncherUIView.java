package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MicrosoftAccountStore;
import com.ecl.auth.MinecraftSkinService;
import com.ecl.backup.WorldBackupService;
import com.ecl.config.SettingsManager;
import com.ecl.download.DownloadService;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.ServerJarDownloader;
import com.ecl.game.DefaultGameRepository;
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
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
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

class LauncherUIView extends javafx.application.Application {
    static final Logger LOGGER = LoggerFactory.getLogger(LauncherUI.class);
    static final String AUTH_OFFLINE = "OFFLINE";
    static final String AUTH_MICROSOFT = "MICROSOFT";
    static final String MC_CHINESE_WIKI_VERSION_URL_PREFIX = "https://zh.minecraft.wiki/w/";
    private static final double WINDOW_WIDTH = 1440;
    private static final double WINDOW_HEIGHT = 900;
    private static final double MIN_WINDOW_WIDTH = 1180;
    private static final double MIN_WINDOW_HEIGHT = 720;
    /** Minimum logical desktop area the shell needs before clamping the preferred window size. */
    private static final double MIN_USABLE_WIDTH = 960;
    private static final double MIN_USABLE_HEIGHT = 640;
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
    ProgressBar downloadProgress;
    Label statusLabel;
    Label detailLabel;
    Button launchBtn;
    Button updateInstanceButton;
    Button refreshBtn;
    Button settingsBtn;
    Button microsoftLoginBtn;
    Button microsoftAddAccountBtn;
    Button skinUploadBtn;
    Button offlineSkinRemoveBtn;
    ComboBox<MicrosoftAccountStore.Account> microsoftAccountCombo;
    volatile MicrosoftAccountStore.Account selectedMicrosoftAccount;
    volatile boolean lastMicrosoftAccountPersisted = true;
    Button selectedVersionWikiButton;
    ComboBox<String> authTypeCombo;

    Label authSummaryLabel;
    Label authHintLabel;
    Label versionSummaryLabel;
    Label topAuthBadgeLabel;
    Label instanceMetaLabel;
    Label contentTargetLabel;
    /** What the content page's install-target banner describes: instance, instances or server. */
    String contentTargetMode = "instance";
    /** Last category opened on the content page so returning there keeps the user's context. */
    String contentCategoryKey = "";
    /** Last mod search query, restored when the mod browser is rebuilt on a page switch. */
    String modSearchQuery = "";
    Label selectedVersionTitleLabel;
    Label selectedRuntimeMetaLabel;
    private final LauncherContentBrowser contentBrowser = new LauncherContentBrowser((LauncherUI) this);
    private final LauncherLaunchForm launchForm = new LauncherLaunchForm((LauncherUI) this);
    final LauncherPageFactory pageFactory = new LauncherPageFactory((LauncherUI) this);
    private final LauncherPathService pathService = new LauncherPathService((LauncherUI) this);
    final HomePageFactory homePageFactory = new HomePageFactory((LauncherUI) this);
    final InstanceUpdateWorkflow instanceUpdates = new InstanceUpdateWorkflow((LauncherUI) this);
    final ContentLibraryPageFactory contentLibraryPageFactory =
            new ContentLibraryPageFactory((LauncherUI) this);
    private final RuntimeSummaryPresenter runtimeSummaryPresenter =
            new RuntimeSummaryPresenter((LauncherUI) this);
    final AccountAvatarPresenter accountAvatarPresenter = new AccountAvatarPresenter((LauncherUI) this);
    VBox homePage;
    HBox workspacePane;
    ScrollPane mainScrollPane;
    HBox instanceBar;
    Button taskToggleButton;
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
    boolean backupOnLaunch;
    int backupKeepCount;
    boolean backupIncludeMods;
    /** Last launch failure headline, cleared when a new launch starts. */
    volatile String launchFailure;
    final AtomicBoolean applicationStopping = new AtomicBoolean();
    volatile Process activeGameProcess;
    volatile String activeGameVersion;
    final Map<Process, String> activeGameProcesses = new ConcurrentHashMap<>();
    final InstanceSelectionState instanceSelection = new InstanceSelectionState();
    InstanceDisplayMetadataCache instanceDisplay;
    /** Optional compact-layout hook owned by the currently rendered page. */
    java.util.function.Consumer<Boolean> compactLayoutConsumer;
    private final LauncherProgressController progressController = new LauncherProgressController();
    final LauncherPageRouter pageRouter = new LauncherPageRouter((LauncherUI) this);
    final LauncherDesktopIntegration desktopIntegration =
            new LauncherDesktopIntegration((LauncherUI) this);
    final LauncherNavigationRail navigationRail = new LauncherNavigationRail(pageRouter::setActiveView);
    final LauncherStatusBar statusBar = new LauncherStatusBar((LauncherUI) this);
    final LauncherInstanceBar instanceBarFactory = new LauncherInstanceBar((LauncherUI) this);
    final LauncherWindowLayout windowLayout = new LauncherWindowLayout((LauncherUI) this);
    Animation contentTransition;
    private ModBrowserView activeModBrowserView;
    ServerBrowserView activeServerBrowserView;
    ServerManagementPage activeServerManagementPage;
    AppView activeView = AppView.HOME;
    DownloadSection downloadSection = DownloadSection.INSTANCES;
    boolean accountSettingsSelected;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        initializeServices();
        loadLaunchSettings();
        showWindow();

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

    private void initializeServices() {
        controller = new MainController();
        settingsManager = controller.settings();
        Messages.setLocale(Locale.forLanguageTag(settingsManager.get(ECLConfig.KEY_LANGUAGE)));
        versionManager = controller.versions();
        downloader = controller.gameDownloader();
        downloadTaskCenter = controller.downloadTasks();
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
        instanceDisplay = new InstanceDisplayMetadataCache(controller);
    }

    private void loadLaunchSettings() {
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
        backupOnLaunch = settingsManager.get(ECLConfig.KEY_BACKUP_ON_LAUNCH);
        backupKeepCount = Math.max(1, Math.min(100,
                settingsManager.get(ECLConfig.KEY_BACKUP_KEEP_COUNT)));
        backupIncludeMods = settingsManager.get(ECLConfig.KEY_BACKUP_INCLUDE_MODS);
        contentTargets = createContentTargets();
        instanceSelection.setLaunchTarget(settingsManager.get(ECLConfig.KEY_SELECTED_VERSION));
        instanceSelection.launchTargetProperty().addListener(
                (observable, previous, value) -> persistLaunchTarget(value));
    }

    /** Keeps the persisted launch target in sync with the shared instance state. */
    private void persistLaunchTarget(String value) {
        settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, value == null ? "" : value);
        if (!settingsManager.save()) {
            setStatus(Messages.get("status.settingsSaveFailed"),
                    Messages.get("status.settingsSaveFailed.detail"));
        }
    }

    private void showWindow() {
        primaryStage.initStyle(StageStyle.UNDECORATED);

        Pane root = createRoot();
        root.getStyleClass().add("scene-root");
        Rectangle2D usable = screenUsableBounds();
        double availableWidth = Math.max(MIN_USABLE_WIDTH, usable.getWidth() - 80);
        double availableHeight = Math.max(MIN_USABLE_HEIGHT, usable.getHeight() - 80);
        Scene scene = new Scene(root,
                Math.min(WINDOW_WIDTH, availableWidth),
                Math.min(WINDOW_HEIGHT, availableHeight));
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        primaryStage.setTitle(Messages.get("app.title"));
        applyWindowIcon(primaryStage);
        primaryStage.setMinWidth(Math.min(MIN_WINDOW_WIDTH, availableWidth));
        primaryStage.setMinHeight(Math.min(MIN_WINDOW_HEIGHT, availableHeight));
        primaryStage.setScene(scene);
        windowLayout.installResponsiveBehavior(scene);
        applyTheme();
        primaryStage.show();
        primaryStage.centerOnScreen();
        root.setFocusTraversable(true);
        Platform.runLater(root::requestFocus);
    }

    /**
     * Uses the logical usable desktop area so Windows display scaling cannot push the window past
     * the visible screen.
     */
    private static Rectangle2D screenUsableBounds() {
        try {
            Screen screen = Screen.getPrimary();
            if (screen != null) {
                Rectangle2D bounds = screen.getVisualBounds();
                if (bounds.getWidth() > 0 && bounds.getHeight() > 0) {
                    return bounds;
                }
            }
        } catch (RuntimeException error) {
            LOGGER.debug("Cannot read screen bounds, falling back to the default window size", error);
        }
        return new Rectangle2D(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
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
                    this::languageDisplayName);
        }
        firstRunWizard.show(primaryStage);
    }

    private Pane createRoot() {
        Pane root = windowLayout.createRoot();
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

    void setActiveView(AppView view) {
        pageRouter.setActiveView(view);
    }

    void openDownloadSection(DownloadSection section) {
        downloadSection = section == null ? DownloadSection.INSTANCES : section;
        // Explicit navigation wins over the remembered category; callers of CONTENT mean mods.
        contentCategoryKey = downloadSection == DownloadSection.INSTANCES ? "" : "mod";
        if (activeView == AppView.DOWNLOADS) {
            pageRouter.renderActiveView();
        } else {
            setActiveView(AppView.DOWNLOADS);
        }
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

    /** True when the window is narrow enough to switch pages to their compact layout. */
    boolean isCompactWindow() {
        return primaryStage == null || primaryStage.getScene() == null
                || primaryStage.getScene().getWidth() < LauncherWindowLayout.COMPACT_RAIL_BREAKPOINT;
    }

    /** Reapplies the compact layout of the current page after a resize or a page switch. */
    void applyCompactLayout() {
        if (compactLayoutConsumer != null) {
            compactLayoutConsumer.accept(isCompactWindow());
        }
    }

    private List<ContentTarget> createContentTargets() {
        return ContentTargetFactory.create(
                this::resolveModsDir, this::resolveVersionGameDir,
                this::getConfiguredGameRootDir);
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
            activeModBrowserView.restoreSearch(modSearchQuery);
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
            // Keep the query so returning to the mod category restores the previous search.
            modSearchQuery = activeModBrowserView.searchQuery();
            activeModBrowserView.close();
            activeModBrowserView = null;
        }
    }

    void closeActiveServerBrowserView() {
        if (activeServerManagementPage != null) {
            activeServerManagementPage.close();
            activeServerManagementPage = null;
        }
        if (activeServerBrowserView != null) {
            activeServerBrowserView.close();
            activeServerBrowserView = null;
        }
    }

    /** 将地址写入直连服务器配置并持久化，供服务器浏览页“设为直连”调用。 */
    void setQuickServer(String address) {
        String candidate = address == null ? "" : address.trim();
        // Direct connect is a launch-target setting, so it needs a launch target to apply to.
        if (!candidate.isBlank() && getSelectedVersion() == null) {
            setStatus(Messages.get("server.quickServer.noInstance"),
                    Messages.get("server.quickServer.noInstance.detail"));
            return;
        }
        quickServer = candidate;
        settingsManager.set(ECLConfig.KEY_QUICK_SERVER, quickServer);
        settingsManager.save();
        setStatus(Messages.get("server.quickServer.set"), quickServer.isBlank()
                ? Messages.get("server.quickServer.cleared")
                : Messages.format("server.quickServer.next", quickServer));
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

    void openInstanceSettings(boolean focusAccount) {
        if (focusAccount) {
            openAccountSettings();
            return;
        }
        openDownloadSection(DownloadSection.INSTANCES);
        Platform.runLater(() -> {
            Control target = focusAccount ? authTypeCombo : versionCombo;
            if (target != null) {
                target.requestFocus();
            }
        });
    }

    void openAccountSettings() {
        accountSettingsSelected = true;
        if (activeView == AppView.SETTINGS) renderActiveView();
        else setActiveView(AppView.SETTINGS);
    }

    VBox createMainPage() {
        VBox page = new VBox(18);
        page.getStyleClass().add("launch-pane");
        page.setMinWidth(0);
        page.setMaxWidth(Double.MAX_VALUE);
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

    void updateLaunchButtonLabel() {
        launchForm.updateLaunchButtonLabel();
    }

    void syncLoaderChoiceFromProfile(String profileId) {
        launchForm.syncLoaderChoiceFromProfile(profileId);
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

    VBox createActionBar() {
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
        if (!applicationStopping.get()) {
            if (Platform.isFxApplicationThread()) {
                updateRuntimeSummary();
            } else {
                Platform.runLater(() -> {
                    if (!applicationStopping.get()) {
                        updateRuntimeSummary();
                    }
                });
            }
        }
    }

    /** Records a launch failure so the primary button can offer a retry and a diagnostics entry. */
    void markLaunchFailure(String message) {
        launchFailure = message == null || message.isBlank()
                ? Messages.get("status.launchFailed") : message;
        updateRuntimeSummary();
    }

    /** Clears the previous launch failure before a new attempt. */
    void clearLaunchFailure() {
        if (launchFailure == null) {
            return;
        }
        launchFailure = null;
        updateRuntimeSummary();
    }

    boolean hasRunningGameProcess() {
        return activeGameProcesses.keySet().stream().anyMatch(Process::isAlive);
    }

    boolean isVersionRunning(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        return activeGameProcesses.entrySet().stream()
                .anyMatch(entry -> version.equals(entry.getValue()) && entry.getKey().isAlive());
    }




    private void showContentDownloadDialog(ContentTarget target) {
        contentBrowser.showContentDownloadDialog(target);
    }

    /** The launch target profile id. Business code reads this instead of the selector control. */
    String getSelectedVersion() {
        return instanceSelection.launchTarget();
    }

    /** Switches the launch target and keeps the instance bar selector in sync. */
    void setLaunchTarget(String profileId) {
        instanceSelection.setLaunchTarget(profileId);
    }

    File getConfiguredGameRootDir() {
        return pathService.getConfiguredGameRootDir();
    }

    /** Default directory for downloaded server jars; not an instance directory. */
    File getServerDownloadDir() {
        return new File(getConfiguredGameRootDir(), "server-downloads");
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

    <T> void configureLocalizedCombo(ComboBox<T> combo, Function<T, String> displayName) {
        LauncherUiFactory.configureLocalizedCombo(combo, displayName);
    }

    String languageDisplayName(String tag) {
        return LauncherThemeManager.languageDisplayName(tag);
    }

    void switchLanguage(String languageTag) {
        if (languageTag == null || !pageFactory.confirmSettingsDeparture()) return;
        Messages.setLocale(Locale.forLanguageTag(languageTag));
        settingsManager.set(ECLConfig.KEY_LANGUAGE, languageTag);
        settingsManager.save();
        primaryStage.setTitle(Messages.get("app.title"));
        navigationRail.refreshTexts();
        statusBar.resetToIdle();
        statusBar.refreshTexts();
        instanceBarFactory.refreshTexts();
        if (authTypeCombo != null) authTypeCombo.requestLayout();
        homePage = null;
        contentTargets = createContentTargets();
        renderActiveView();
    }

    void applyTheme() {
        LauncherThemeManager.applyToAllWindows(primaryStage);
    }

    void applyThemeToScene(Scene scene) {
        LauncherThemeManager.applyToScene(scene);
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
