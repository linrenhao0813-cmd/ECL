package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.MicrosoftAccountStore;
import com.ecl.auth.MinecraftSkinService;
import com.ecl.auth.OfflineAuth;
import com.ecl.auth.OfflineSkin;
import com.ecl.auth.OfflineSkinStore;
import com.ecl.auth.YggdrasilAuth;
import com.ecl.backup.BackupEntry;
import com.ecl.backup.WorldBackupService;
import com.ecl.config.SettingsManager;
import com.ecl.diagnostic.DiagnosticBundleService;
import com.ecl.download.DownloadService;
import com.ecl.download.ContentDownloader;
import com.ecl.download.GameDownloader;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.download.ModrinthDownloader;
import com.ecl.download.ServerJarDownloader;
import com.ecl.desktop.DesktopShortcutService;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import com.ecl.game.InstanceGameSettings;
import com.ecl.game.InstanceGameSettingsStore;
import com.ecl.game.PlaytimeTracker;
import com.ecl.launcher.CrashAnalyzer;
import com.ecl.launch.GameProcess;
import com.ecl.launch.LaunchOptions;
import com.ecl.launch.Launcher;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.launcher.VersionManager;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.model.ReleaseChannel;
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
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.ScrollEvent;
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
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ecl.util.TextUtil.abbreviate;
import static com.ecl.util.TextUtil.formatCount;

public class LauncherUI extends javafx.application.Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(LauncherUI.class);
    private static final String AUTH_OFFLINE = "OFFLINE";
    private static final String AUTH_MICROSOFT = "MICROSOFT";
    private static final String AUTH_YGGDRASIL = "YGGDRASIL";
    private static final String MC_CHINESE_WIKI_VERSION_URL_PREFIX = "https://zh.minecraft.wiki/w/";
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

    private VersionManager versionManager;
    private DownloadService downloader;
    private DownloadTaskCenter downloadTaskCenter;
    private ModrinthDownloader modrinthDownloader;
    private ServerJarDownloader serverJarDownloader;
    private ModLoaderInstaller modLoaderInstaller;
    private MrpackInstaller mrpackInstaller;
    private WorldBackupService worldBackupService;
    private MicrosoftAccountStore microsoftAccountStore;
    private MinecraftSkinService minecraftSkinService;
    private Launcher gameLauncher;
    private SettingsManager settingsManager;
    private MainController controller;
    private Stage primaryStage;
    private Stage firstRunStage;

    private ComboBox<String> versionCombo;
    private String lastContentVersion;
    private ComboBox<VersionManager.VersionCategory> versionTypeCombo;
    private ComboBox<LoaderChoice> loaderChoiceCombo;
    private Button installSelectedLoaderButton;
    private boolean syncingLoaderChoice;
    private TextField usernameField;
    private PasswordField passwordField;
    private ProgressBar downloadProgress;
    private Label statusLabel;
    private Label detailLabel;
    private Button launchBtn;
    private Button refreshBtn;
    private Button settingsBtn;
    private Button microsoftLoginBtn;
    private Button microsoftAddAccountBtn;
    private Button skinUploadBtn;
    private Button homeSkinUploadButton;
    private Button offlineSkinRemoveBtn;
    private ComboBox<MicrosoftAccountStore.Account> microsoftAccountCombo;
    private volatile MicrosoftAccountStore.Account selectedMicrosoftAccount;
    private volatile boolean lastMicrosoftAccountPersisted = true;
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
    private Label selectedRuntimeMetaLabel;
    private Label launchReadinessLabel;
    private Label homeAccountNameLabel;
    private Label homeAccountTypeLabel;
    private Label homeAccountAvatarLabel;
    private Label homeEnvironmentStatusLabel;
    private Label topTaskLabel;
    private Label playtimeTotalLabel;
    private Label playtimeRecentLabel;
    private Label playtimeLaunchCountLabel;
    private ListView<DownloadTaskCenter.TaskSnapshot> downloadTaskList;
    private Label downloadTaskSummaryLabel;
    private TitledPane instanceSettingsPane;
    private VBox homePage;
    private HBox workspacePane;
    private List<ContentTarget> contentTargets;

    private String javaPath;
    private File gameDir;
    private String extraJvmArgs;
    private int maxMemoryMb;
    private int gameWidth;
    private int gameHeight;
    private boolean gameFullscreen;
    private String quickServer;
    private boolean closeAfterLaunch;
    private int processorCount;
    private boolean showGameConsole;
    private boolean backupOnLaunch;
    private int backupKeepCount;
    private boolean backupIncludeMods;
    private final BoundedLogBuffer liveGameLog =
            new BoundedLogBuffer(ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS);
    private final PlaytimeTracker playtimeTracker = new PlaytimeTracker();
    private TextArea liveConsoleArea;
    private final StringBuilder pendingConsoleText = new StringBuilder();
    private final AtomicBoolean consoleFlushScheduled = new AtomicBoolean();
    private final AtomicBoolean applicationStopping = new AtomicBoolean();
    private volatile Process activeGameProcess;
    private volatile String activeGameVersion;
    private double windowDragOffsetX;
    private double windowDragOffsetY;
    private final Map<ProgressBar, Timeline> progressAnimations = new HashMap<>();
    private final Map<AppView, Button> navButtons = new HashMap<>();
    private VBox navButtonColumn;
    private Animation contentTransition;
    private ModBrowserView activeModBrowserView;
    private ServerBrowserView activeServerBrowserView;
    private AppView activeView = AppView.HOME;

    private enum AppView {
        DOWNLOADS(ICON_LOG, "D", "nav.downloads"),
        HOME(ICON_HOME, "⌂", "nav.home"),
        VERSIONS(ICON_STONE_BLOCK, "□", "nav.versions"),
        MODRINTH(ICON_MODRINTH, "◎", "nav.modrinth"),
        SERVERS(ICON_SIGNAL, "◈", "nav.servers"),
        LOGS(ICON_LOG, "▤", "nav.logs"),
        SETTINGS(ICON_GEAR, "⚙", "nav.settings");

        private final String iconResource;
        private final String fallbackIcon;
        private final String labelKey;

        AppView(String iconResource, String fallbackIcon, String labelKey) {
            this.iconResource = iconResource;
            this.fallbackIcon = fallbackIcon;
            this.labelKey = labelKey;
        }

        public String getLabel() {
            return Messages.get(labelKey);
        }
    }

    private static class ContentTarget {
        private final String title;
        private final String subtitle;
        private final String initial;
        private final String projectType;
        private final String[] loaders;
        private final String[] allowedExtensions;
        private final boolean downloadDependencies;
        private final String searchHint;
        private final Function<String, File> folderResolver;

        private ContentTarget(String title, String subtitle, String initial, String projectType,
                              String[] loaders, String[] allowedExtensions,
                              boolean downloadDependencies, String searchHint, Function<String, File> folderResolver) {
            this.title = title;
            this.subtitle = subtitle;
            this.initial = initial;
            this.projectType = projectType;
            this.loaders = loaders;
            this.allowedExtensions = allowedExtensions;
            this.downloadDependencies = downloadDependencies;
            this.searchHint = searchHint;
            this.folderResolver = folderResolver;
        }

        private boolean usesLoader() {
            return loaders != null && loaders.length > 0;
        }
    }

    /** Fixed-size character ring that retains only the newest log tail without head deletions. */
    private static final class BoundedLogBuffer {
        private final char[] chars;
        private int start;
        private int size;

        private BoundedLogBuffer(int capacity) {
            chars = new char[Math.max(1, capacity)];
        }

        private synchronized void appendLine(String line) {
            append(line);
            append(System.lineSeparator());
        }

        private synchronized void append(CharSequence text) {
            int textLength = text.length();
            if (textLength == 0) {
                return;
            }
            int capacity = chars.length;

            if (textLength >= capacity) {
                // 只保留 text 的最后 capacity 个字符
                int offset = textLength - capacity;
                copyIn(text, offset, 0, capacity);
                start = 0;
                size = capacity;
                return;
            }

            int writePos = (start + size) % capacity;
            int remaining = capacity - size;
            if (textLength <= remaining) {
                // 直接写入尾部空闲区，不淘汰旧数据
                copyIn(text, 0, writePos, textLength);
                size += textLength;
                return;
            }
            // 需要淘汰 (textLength - remaining) 个最旧字符：
            // 先把 text[0..remaining) 写入尾部空闲区，再把 text[remaining..) 写到 start 起始位置（覆盖最旧字符）
            if (remaining > 0) {
                copyIn(text, 0, writePos, remaining);
            }
            int secondChunk = textLength - remaining;
            copyIn(text, remaining, start, secondChunk);
            start = (start + secondChunk) % capacity;
            size = capacity;
        }

        private void copyIn(CharSequence text, int textOffset, int destOffset, int length) {
            if (length <= 0) {
                return;
            }
            int capacity = chars.length;
            int wrappedDest = destOffset % capacity;
            if (text instanceof String s) {
                s.getChars(textOffset, textOffset + length, chars, wrappedDest);
            } else {
                int contiguous = capacity - wrappedDest;
                if (length <= contiguous) {
                    for (int i = 0; i < length; i++) {
                        chars[wrappedDest + i] = text.charAt(textOffset + i);
                    }
                } else {
                    for (int i = 0; i < contiguous; i++) {
                        chars[wrappedDest + i] = text.charAt(textOffset + i);
                    }
                    int second = length - contiguous;
                    for (int i = 0; i < second; i++) {
                        chars[i] = text.charAt(textOffset + contiguous + i);
                    }
                }
            }
        }

        private synchronized void clear() {
            start = 0;
            size = 0;
        }

        @Override
        public synchronized String toString() {
            StringBuilder result = new StringBuilder(size);
            int firstPart = Math.min(size, chars.length - start);
            result.append(chars, start, firstPart);
            if (firstPart < size) {
                result.append(chars, 0, size - firstPart);
            }
            return result.toString();
        }
    }

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
            refreshVersions();
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
            if (downloadTaskList != null) downloadTaskList.getItems().setAll(tasks);
            if (downloadTaskSummaryLabel != null) {
                downloadTaskSummaryLabel.setText(active == 0
                        ? (failed == 0 ? Messages.get("download.summary.none")
                                : Messages.format("download.summary.failed", failed))
                        : Messages.format("download.summary.active", active));
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

    private enum LoaderChoice {
        VANILLA(null, "原版"),
        FABRIC(ModLoaderInstaller.Loader.FABRIC, "Fabric"),
        QUILT(ModLoaderInstaller.Loader.QUILT, "Quilt"),
        FORGE(ModLoaderInstaller.Loader.FORGE, "Forge"),
        NEOFORGE(ModLoaderInstaller.Loader.NEOFORGE, "NeoForge");

        private final ModLoaderInstaller.Loader loader;
        private final String displayName;

        LoaderChoice(ModLoaderInstaller.Loader loader, String displayName) {
            this.loader = loader;
            this.displayName = displayName;
        }

        private boolean vanilla() {
            return loader == null;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private void showFirstRunWizard() {
        if (firstRunStage != null && firstRunStage.isShowing()) {
            firstRunStage.toFront();
            return;
        }
        List<String> steps = List.of("wizard.welcome", "wizard.java", "wizard.account", "wizard.version");
        int[] currentStep = {0};
        Stage wizard = new Stage(StageStyle.DECORATED);
        firstRunStage = wizard;
        wizard.setTitle(Messages.get("wizard.title"));
        wizard.initOwner(primaryStage);
        wizard.initModality(Modality.WINDOW_MODAL);

        Label progress = new Label();
        progress.getStyleClass().add("section-kicker");
        Label body = new Label();
        body.getStyleClass().add("section-title");
        body.setWrapText(true);

        ComboBox<String> languageBox = new ComboBox<>();
        languageBox.getItems().addAll("zh-CN", "zh-TW", "en");
        languageBox.setValue(Messages.locale().toLanguageTag());
        configureLocalizedCombo(languageBox, this::languageDisplayName);
        Button back = createActionButton(Messages.get("wizard.back"), "ghost-button", () -> { });
        Button skip = createActionButton(Messages.get("wizard.skip"), "ghost-button", () -> finishFirstRun(wizard));
        Button next = createActionButton(Messages.get("wizard.next"), "primary-button", () -> { });
        Runnable update = () -> {
            progress.setText((currentStep[0] + 1) + " / " + steps.size());
            body.setText(Messages.get(steps.get(currentStep[0])));
            back.setText(Messages.get("wizard.back"));
            skip.setText(Messages.get("wizard.skip"));
            next.setText(Messages.get(currentStep[0] == steps.size() - 1 ? "wizard.finish" : "wizard.next"));
            back.setDisable(currentStep[0] == 0);
        };
        languageBox.setOnAction(event -> {
            switchLanguage(languageBox.getValue());
            wizard.setTitle(Messages.get("wizard.title"));
            update.run();
        });
        back.setOnAction(event -> {
            if (currentStep[0] > 0) currentStep[0]--;
            update.run();
        });
        next.setOnAction(event -> {
            if (currentStep[0] == steps.size() - 1) finishFirstRun(wizard);
            else {
                currentStep[0]++;
                update.run();
            }
        });
        HBox actions = new HBox(10, skip, new Region(), back, next);
        HBox.setHgrow(actions.getChildren().get(1), Priority.ALWAYS);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(22, progress, body, createControlRow(Messages.get("settings.language"), languageBox), actions);
        root.getStyleClass().addAll("scene-root", "wizard-root");
        root.setPadding(new Insets(32));
        Scene scene = new Scene(root, 620, 330);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        wizard.setScene(scene);
        applyThemeToScene(scene, settingsManager.get(ECLConfig.KEY_THEME));
        wizard.setOnCloseRequest(event -> finishFirstRun(wizard));
        update.run();
        wizard.show();
    }

    private void finishFirstRun(Stage wizard) {
        settingsManager.set(ECLConfig.KEY_FIRST_RUN_COMPLETED, true);
        settingsManager.save();
        wizard.setOnCloseRequest(null);
        wizard.close();
        firstRunStage = null;
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

    private void setActiveView(AppView view) {
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

    private void renderActiveView() {
        renderActiveView(0);
    }

    private void renderActiveView(int slideDirection) {
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
        return List.of(
                new ContentTarget(
                        Messages.get("content.mods.title"), Messages.get("content.mods.subtitle"), "M", "mod",
                        new String[]{"fabric", "forge", "neoforge", "quilt"}, new String[]{".jar"},
                        true, Messages.get("content.mods.searchHint"), this::resolveModsDir),
                new ContentTarget(
                        Messages.get("content.shaders.title"), Messages.get("content.shaders.subtitle"), "S", "shader",
                        new String[0], new String[]{".zip"},
                        false, Messages.get("content.shaders.searchHint"), version -> new File(resolveVersionGameDir(version), "shaderpacks")),
                new ContentTarget(
                        Messages.get("content.resourcepacks.title"), Messages.get("content.resourcepacks.subtitle"), "R", "resourcepack",
                        new String[0], new String[]{".zip"},
                        false, Messages.get("content.resourcepacks.searchHint"), version -> new File(resolveVersionGameDir(version), "resourcepacks")),
                new ContentTarget(
                        Messages.get("content.modpacks.title"), Messages.get("content.modpacks.subtitle"), "P", "modpack",
                        new String[0], new String[]{".mrpack"},
                        false, Messages.get("content.modpacks.searchHint"),
                        version -> new File(resolveVersionGameDir(version), "modpacks")),
                new ContentTarget(
                        Messages.get("content.server.title"), Messages.get("content.server.subtitle"), "V", "server",
                        new String[0], new String[]{".jar"},
                        false, Messages.get("content.server.searchHint"),
                        version -> new File(getConfiguredGameRootDir(), "server-downloads"))
        );
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
                Runtime.version().feature(), getMemoryDisplayText()));
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
        homeSkinUploadButton = createLinkButton(Messages.get("home.uploadSkin"), this::chooseAndUploadSkin);
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
        memorySummaryLabel = createValueLabel(getMemoryDisplayText());
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
        VBox page = createMainPage();
        downloadTaskSummaryLabel = createBodyText(Messages.get("download.summary.none"));

        downloadTaskList = new ListView<>();
        downloadTaskList.setPrefHeight(430);
        downloadTaskList.setPlaceholder(createBodyText(Messages.get("download.placeholder")));
        downloadTaskList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(DownloadTaskCenter.TaskSnapshot item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.title());
                title.getStyleClass().add("content-title");
                Label status = new Label(downloadStatusText(item));
                status.getStyleClass().add("status-detail");
                Label detail = new Label(item.detail());
                detail.getStyleClass().add("content-subtitle");
                detail.setWrapText(true);
                ProgressBar progress = new ProgressBar(item.progress());
                progress.setPrefWidth(210);
                progress.setVisible(item.status() == DownloadTaskCenter.Status.QUEUED
                        || item.status() == DownloadTaskCenter.Status.RUNNING
                        || item.status() == DownloadTaskCenter.Status.CANCELLING);
                Label meta = new Label(downloadTaskMeta(item));
                meta.getStyleClass().add("content-subtitle");
                VBox text = new VBox(3, title, status, detail, meta);
                HBox.setHgrow(text, Priority.ALWAYS);

                Button action = null;
                if (item.status() == DownloadTaskCenter.Status.QUEUED
                        || item.status() == DownloadTaskCenter.Status.RUNNING) {
                    action = createActionButton(Messages.get("download.action.cancel"), "ghost-button",
                            () -> downloadTaskCenter.cancel(item.id()));
                } else if (item.status() == DownloadTaskCenter.Status.FAILED
                        || item.status() == DownloadTaskCenter.Status.CANCELLED) {
                    action = createActionButton(Messages.get("download.action.retry"), "secondary-button",
                            () -> downloadTaskCenter.retry(item.id()));
                }
                HBox row = new HBox(12, text, progress);
                if (action != null) row.getChildren().add(action);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 4, 8, 4));
                setGraphic(row);
            }
        });
        downloadTaskList.getItems().setAll(downloadTaskCenter.snapshots());

        ComboBox<Integer> concurrency = new ComboBox<>();
        concurrency.getItems().addAll(1, 2, 3, 4, 5, 6, 7, 8);
        concurrency.setValue(downloadTaskCenter.maxConcurrent());
        concurrency.setOnAction(event -> {
            Integer value = concurrency.getValue();
            if (value == null) return;
            downloadTaskCenter.setMaxConcurrent(value);
            settingsManager.set(ECLConfig.KEY_DOWNLOAD_MAX_CONCURRENT, value);
            settingsManager.save();
        });

        ComboBox<String> rate = new ComboBox<>();
        rate.getItems().addAll(Messages.get("download.rate.unlimited"), "256 KB/s", "512 KB/s",
                "1 MB/s", "2 MB/s", "4 MB/s", "8 MB/s");
        rate.setValue(downloadRateLabel(downloadTaskCenter.bandwidthLimitBytesPerSecond()));
        rate.setOnAction(event -> {
            long bytes = parseDownloadRate(rate.getValue());
            downloadTaskCenter.setBandwidthLimitBytesPerSecond(bytes);
            settingsManager.set(ECLConfig.KEY_DOWNLOAD_RATE_LIMIT_KB, (int) (bytes / 1024));
            settingsManager.save();
        });

        Button clear = createActionButton(Messages.get("download.action.clear"), "ghost-button",
                downloadTaskCenter::clearFinished);
        HBox settings = new HBox(12,
                createControlRow(Messages.get("download.settings.concurrency"), concurrency),
                createControlRow(Messages.get("download.settings.speedLimit"), rate), clear);
        settings.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(settings.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(settings.getChildren().get(1), Priority.ALWAYS);

        page.getChildren().addAll(
                createSurface(Messages.get("download.center.title"), Messages.get("download.center.subtitle"),
                        downloadTaskSummaryLabel, settings),
                createSurface(Messages.get("download.tasks.title"), Messages.get("download.tasks.subtitle"),
                        downloadTaskList));
        return page;
    }

    private String downloadStatusText(DownloadTaskCenter.TaskSnapshot task) {
        return switch (task.status()) {
            case QUEUED -> Messages.get("download.status.queued");
            case RUNNING -> Messages.get("download.status.running");
            case CANCELLING -> Messages.get("download.status.cancelling");
            case COMPLETED -> Messages.get("download.status.completed");
            case FAILED -> Messages.format("download.status.failed", task.errorMessage());
            case CANCELLED -> Messages.get("download.status.cancelled");
        };
    }

    private String downloadTaskMeta(DownloadTaskCenter.TaskSnapshot task) {
        String transfer = task.totalBytes() > 0
                ? formatBytes(task.downloadedBytes()) + " / " + formatBytes(task.totalBytes())
                : formatBytes(task.downloadedBytes());
        String speed = task.speedBytesPerSecond() > 0
                ? " · " + formatBytes(task.speedBytesPerSecond()) + "/s" : "";
        return Messages.format("download.meta", transfer, speed, task.attempts());
    }

    private String downloadRateLabel(long bytes) {
        if (bytes <= 0) return Messages.get("download.rate.unlimited");
        if (bytes % (1024L * 1024L) == 0) return (bytes / (1024L * 1024L)) + " MB/s";
        return (bytes / 1024L) + " KB/s";
    }

    private long parseDownloadRate(String value) {
        if (value == null || value.equals(Messages.get("download.rate.unlimited"))) return 0;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return 0;
        long kb = Long.parseLong(digits);
        return value.contains("MB") ? kb * 1024L * 1024L : kb * 1024L;
    }

    private VBox createVersionsPage() {
        VBox page = createMainPage();

        Button refreshVersionsButton = createActionButton(Messages.get("button.refresh"), "primary-button", this::refreshVersions);
        Button installLoaderButton = createActionButton(Messages.get("loader.install"), "primary-button",
                this::showLoaderInstallDialog);
        Button reinstallButton = createActionButton(Messages.get("version.reinstall"), "secondary-button",
                this::reinstallSelectedVersion);
        Button deleteButton = createActionButton(Messages.get("version.delete"), "secondary-button",
                this::deleteSelectedVersion);
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
                createInfoRow(Messages.get("label.currentFilter"), createStaticValueLabel(getSelectedVersionCategory().getLabel())),
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
        String configured = System.getProperty("ecl.executable", "");
        if (configured.isBlank()) configured = System.getenv("ECL_EXECUTABLE");
        String runningCommand = ProcessHandle.current().info().command().orElse("");
        Path codeSource = null;
        try {
            codeSource = Path.of(LauncherUI.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (URISyntaxException | RuntimeException ignored) {
            // Development launches do not have a packaged executable.
        }
        return resolveLauncherExecutableCandidate(configured, runningCommand,
                Path.of(System.getProperty("user.dir", ".")), codeSource);
    }

    static Path resolveLauncherExecutableCandidate(String configured, String runningCommand,
                                                    Path workingDirectory, Path codeSource) {
        List<Path> candidates = new java.util.ArrayList<>();
        addExplicitExecutableCandidate(candidates, configured);
        if (isPackagedEclExecutable(runningCommand)) candidates.add(Path.of(runningCommand));
        if (workingDirectory != null) candidates.add(workingDirectory.resolve("ECL.exe"));
        if (codeSource != null) {
            if (Files.isDirectory(codeSource)) {
                candidates.add(codeSource.resolve("ECL.exe"));
            } else if (isPackagedEclExecutable(codeSource.toString())) {
                candidates.add(codeSource);
            }
            if (codeSource.getParent() != null) {
                candidates.add(codeSource.getParent().resolve("ECL.exe"));
            }
        }
        return candidates.stream().filter(path -> path != null && Files.isRegularFile(path))
                .map(path -> path.toAbsolutePath().normalize()).findFirst().orElse(null);
    }

    private static void addExplicitExecutableCandidate(List<Path> candidates, String configured) {
        if (configured == null || configured.isBlank()) return;
        Path candidate = Path.of(configured);
        String fileName = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".exe")) candidates.add(candidate);
    }

    private static boolean isPackagedEclExecutable(String command) {
        if (command == null || command.isBlank()) return false;
        try {
            Path path = Path.of(command);
            return path.getFileName() != null
                    && "ecl.exe".equalsIgnoreCase(path.getFileName().toString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void showBackupManagerDialog() {
        String profileId = getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            setStatus("无法打开备份管理", "请先选择一个实例。");
            return;
        }

        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("备份管理 · " + profileId);
        applyWindowIcon(dialog);

        ListView<BackupEntry> backupList = new ListView<>();
        backupList.setPrefHeight(270);
        backupList.setPlaceholder(createBodyText("还没有备份。点击“新建备份”保存当前存档。"));
        backupList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(BackupEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault()).format(item.createdAt());
                setText(created + "    " + formatBytes(item.archiveSize()) + "\n"
                        + formatBackupContents(item) + " · " + item.files().size() + " 个文件");
                setTooltip(new Tooltip(item.archivePath().toString()));
            }
        });

        CheckBox includeSaves = new CheckBox("存档 saves（必选）");
        includeSaves.setSelected(true);
        includeSaves.setDisable(true);
        CheckBox includeMods = new CheckBox("模组 mods");
        includeMods.setSelected(backupIncludeMods);
        CheckBox includeShaders = new CheckBox("光影包 shaderpacks");
        CheckBox includeResources = new CheckBox("材质包 resourcepacks");
        CheckBox includeConfig = new CheckBox("配置 config");
        HBox backupOptions = new HBox(18, includeSaves, includeMods, includeShaders,
                includeResources, includeConfig);
        backupOptions.setAlignment(Pos.CENTER_LEFT);

        Label operationStatus = createBodyText("备份保存在 "
                + worldBackupService.backupDirectory(profileId));
        ProgressBar operationProgress = new ProgressBar(0);
        operationProgress.setMaxWidth(Double.MAX_VALUE);

        Button createButton = new Button("新建备份");
        createButton.getStyleClass().addAll("app-button", "primary-button");
        Button restoreButton = new Button("恢复所选");
        restoreButton.getStyleClass().addAll("app-button", "secondary-button");
        restoreButton.setDisable(true);
        Button deleteButton = new Button("删除所选");
        deleteButton.getStyleClass().addAll("app-button", "secondary-button");
        deleteButton.setDisable(true);
        Button openDirectoryButton = new Button("打开备份目录");
        openDirectoryButton.getStyleClass().addAll("app-button", "ghost-button");
        Button closeButton = new Button("关闭");
        closeButton.getStyleClass().addAll("app-button", "ghost-button");
        closeButton.setOnAction(event -> dialog.close());
        openDirectoryButton.setOnAction(event -> openLocalFolder(
                worldBackupService.backupDirectory(profileId).toFile(), "备份目录"));

        AtomicBoolean operationRunning = new AtomicBoolean();
        java.util.function.Consumer<Boolean> setBusy = busy -> {
            operationRunning.set(busy);
            createButton.setDisable(busy);
            openDirectoryButton.setDisable(busy);
            closeButton.setDisable(busy);
            boolean noSelection = backupList.getSelectionModel().getSelectedItem() == null;
            restoreButton.setDisable(busy || noSelection);
            deleteButton.setDisable(busy || noSelection);
            backupOptions.setDisable(busy);
            if (!busy) operationProgress.setProgress(0);
        };
        Runnable refreshList = () -> {
            try {
                BackupEntry selected = backupList.getSelectionModel().getSelectedItem();
                backupList.getItems().setAll(worldBackupService.listBackups(profileId));
                if (selected != null) {
                    backupList.getItems().stream()
                            .filter(item -> item.archivePath().equals(selected.archivePath()))
                            .findFirst().ifPresent(item -> backupList.getSelectionModel().select(item));
                }
            } catch (IOException error) {
                operationStatus.setText("读取备份失败：" + cleanMessage(error));
            }
        };
        backupList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!operationRunning.get()) {
                restoreButton.setDisable(newValue == null);
                deleteButton.setDisable(newValue == null);
            }
        });

        createButton.setOnAction(event -> {
            Path instanceDirectory = resolveVersionGameDir(profileId).toPath();
            if (!Files.isDirectory(instanceDirectory)) {
                operationStatus.setText("实例目录不存在，无法创建备份：" + instanceDirectory);
                return;
            }
            EnumSet<BackupEntry.Content> content = EnumSet.of(BackupEntry.Content.SAVES);
            if (includeMods.isSelected()) content.add(BackupEntry.Content.MODS);
            if (includeShaders.isSelected()) content.add(BackupEntry.Content.SHADERPACKS);
            if (includeResources.isSelected()) content.add(BackupEntry.Content.RESOURCEPACKS);
            if (includeConfig.isSelected()) content.add(BackupEntry.Content.CONFIG);
            setBusy.accept(true);
            operationStatus.setText("正在创建备份…");
            operationProgress.setProgress(-1);
            WorldBackupService.ProgressListener progress = createBackupProgressListener(
                    operationProgress, operationStatus, "正在备份");
            runAsync("ecl-world-backup-create", () -> {
                try {
                    BackupEntry created = worldBackupService.createBackup(profileId,
                            resolveBackupSourceVersion(profileId), instanceDirectory, content, progress);
                    Platform.runLater(() -> {
                        refreshList.run();
                        backupList.getItems().stream()
                                .filter(item -> item.archivePath().equals(created.archivePath()))
                                .findFirst().ifPresent(item -> backupList.getSelectionModel().select(item));
                        operationStatus.setText("备份已创建：" + created.archivePath().getFileName());
                        setStatus("实例备份完成", profileId + " · " + formatBytes(created.archiveSize()));
                        setBusy.accept(false);
                    });
                } catch (Exception error) {
                    LOGGER.warn("Cannot create manual backup for {}", profileId, error);
                    Platform.runLater(() -> {
                        operationStatus.setText("备份失败：" + cleanMessage(error));
                        setBusy.accept(false);
                    });
                }
            });
        });

        restoreButton.setOnAction(event -> {
            BackupEntry selected = backupList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (isGameProcessRunning()) {
                operationStatus.setText("游戏正在运行，退出游戏后才能恢复存档。");
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "将用所选备份替换实例中的“" + formatBackupContents(selected)
                            + "”。恢复过程会保留可回滚副本，确认继续吗？",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.initOwner(dialog);
            confirm.setTitle("恢复实例备份");
            confirm.setHeaderText("恢复 " + selected.archivePath().getFileName());
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

            setBusy.accept(true);
            operationStatus.setText("正在校验并恢复备份…");
            operationProgress.setProgress(-1);
            WorldBackupService.ProgressListener progress = createBackupProgressListener(
                    operationProgress, operationStatus, "正在恢复");
            runAsync("ecl-world-backup-restore", () -> {
                try {
                    if (isGameProcessRunning()) {
                        throw new IOException("游戏正在运行，不能恢复实例文件");
                    }
                    worldBackupService.restore(selected, resolveVersionGameDir(profileId).toPath(), progress);
                    Platform.runLater(() -> {
                        operationStatus.setText("恢复完成：" + selected.archivePath().getFileName());
                        setStatus("实例备份已恢复", profileId + " 的存档与所选内容已恢复。");
                        setBusy.accept(false);
                    });
                } catch (Exception error) {
                    LOGGER.warn("Cannot restore backup {}", selected.archivePath(), error);
                    Platform.runLater(() -> {
                        operationStatus.setText("恢复失败，原文件已回滚：" + cleanMessage(error));
                        setBusy.accept(false);
                    });
                }
            });
        });

        deleteButton.setOnAction(event -> {
            BackupEntry selected = backupList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "将永久删除备份文件及其元数据。",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.initOwner(dialog);
            confirm.setTitle("删除实例备份");
            confirm.setHeaderText(selected.archivePath().getFileName().toString());
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            setBusy.accept(true);
            operationStatus.setText("正在删除备份…");
            runAsync("ecl-world-backup-delete", () -> {
                try {
                    worldBackupService.deleteBackup(selected);
                    Platform.runLater(() -> {
                        refreshList.run();
                        operationStatus.setText("备份已删除。");
                        setBusy.accept(false);
                    });
                } catch (Exception error) {
                    Platform.runLater(() -> {
                        operationStatus.setText("删除失败：" + cleanMessage(error));
                        setBusy.accept(false);
                    });
                }
            });
        });

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actionBar = new HBox(10, createButton, restoreButton, deleteButton,
                actionSpacer, openDirectoryButton, closeButton);
        actionBar.setAlignment(Pos.CENTER_RIGHT);
        VBox dialogRoot = new VBox(16,
                createSurface("// " + profileId + " 的存档备份",
                        "恢复时会先校验压缩包，并为当前文件创建临时回滚副本。", backupList),
                createSurface("新建备份包含内容", "saves 始终包含，其余目录按需选择", backupOptions),
                operationStatus, operationProgress, actionBar);
        dialogRoot.getStyleClass().add("root-pane");
        dialogRoot.setPadding(new Insets(20));
        Scene scene = new Scene(dialogRoot, 920, 650);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        dialog.setScene(scene);
        applyThemeToScene(scene, settingsManager.get(ECLConfig.KEY_THEME));
        refreshList.run();
        dialog.show();
    }

    private String formatBackupContents(BackupEntry backup) {
        StringBuilder result = new StringBuilder();
        for (BackupEntry.Content content : BackupEntry.Content.values()) {
            if (!backup.includedContent().contains(content)) continue;
            if (!result.isEmpty()) result.append("、");
            result.append(content.displayName());
        }
        return result.toString();
    }

    private String resolveBackupSourceVersion(String profileId) {
        try {
            return versionManager.resolveMinecraftVersionId(profileId);
        } catch (IOException error) {
            LOGGER.debug("Cannot resolve Minecraft version for backup {}", profileId, error);
            return profileId;
        }
    }

    private WorldBackupService.ProgressListener createBackupProgressListener(
            ProgressBar progressBar, Label status, String action) {
        AtomicLong lastUpdate = new AtomicLong();
        return (completed, total, entry) -> {
            long now = System.nanoTime();
            long previous = lastUpdate.get();
            if (completed < total && previous != 0 && now - previous < 80_000_000L) return;
            if (!lastUpdate.compareAndSet(previous, now) && completed < total) return;
            Platform.runLater(() -> {
                progressBar.setProgress(total <= 0 ? -1
                        : Math.max(0, Math.min(1, completed / (double) total)));
                String current = entry == null || entry.isBlank() ? ""
                        : " · " + abbreviate(entry, 64);
                status.setText(action + " " + formatBytes(completed)
                        + (total > 0 ? " / " + formatBytes(total) : "") + current);
            });
        };
    }

    private void showLoaderInstallDialog() {
        String selected = getSelectedVersion();
        String minecraftVersion = selected;
        if (selected != null && !selected.isBlank()) {
            try {
                minecraftVersion = versionManager.resolveMinecraftVersionId(selected);
            } catch (IOException ignored) {
                minecraftVersion = selected;
            }
        }

        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("安装 Mod 加载器");
        applyWindowIcon(dialog);

        TextField minecraftField = new TextField(minecraftVersion == null ? "" : minecraftVersion);
        minecraftField.setPromptText("例如 1.21.4");
        applyFieldStyle(minecraftField);
        ComboBox<String> loaderField = new ComboBox<>();
        loaderField.getItems().setAll("Fabric", "Quilt", "Forge", "NeoForge");
        loaderField.getSelectionModel().selectFirst();
        applyFieldStyle(loaderField);
        TextField versionField = new TextField();
        versionField.setPromptText("留空自动选择最新兼容版本");
        applyFieldStyle(versionField);
        Label installStatus = createBodyText(
                "Fabric / Quilt 使用官方 profile；Forge / NeoForge 会运行官方 installer。");

        Button install = new Button("安装");
        install.getStyleClass().addAll("app-button", "primary-button");
        Button cancel = new Button("取消");
        cancel.getStyleClass().addAll("app-button", "ghost-button");
        cancel.setOnAction(e -> dialog.close());
        install.setOnAction(e -> {
            String gameVersion = minecraftField.getText().trim();
            if (gameVersion.isBlank()) {
                installStatus.setText("请填写 Minecraft 版本。");
                return;
            }
            ModLoaderInstaller.Loader loader = switch (loaderField.getValue()) {
                case "Quilt" -> ModLoaderInstaller.Loader.QUILT;
                case "Forge" -> ModLoaderInstaller.Loader.FORGE;
                case "NeoForge" -> ModLoaderInstaller.Loader.NEOFORGE;
                default -> ModLoaderInstaller.Loader.FABRIC;
            };
            install.setDisable(true);
            cancel.setDisable(true);
            setControlsBusy(true);
            startProgressAnimation(downloadProgress);
            DownloadTaskCenter.TaskHandle<Void> loaderTask = downloadTaskCenter.submit(
                    "Loader " + loader.displayName(), context -> {
                try {
                    ModLoaderInstaller.InstallResult result = modLoaderInstaller.install(
                            gameVersion, loader, versionField.getText().trim(),
                            new ModLoaderInstaller.Listener() {
                                @Override
                                public void onStatus(String message) {
                                    context.updateStatus(message);
                                    Platform.runLater(() -> {
                                        installStatus.setText(message);
                                        setStatus("正在安装加载器", message);
                                    });
                                }

                                @Override
                                public void onProgress(long downloaded, long total) {
                                    context.updateProgress(downloaded, total);
                                    Platform.runLater(() ->
                                            updateProgress(downloadProgress, downloaded, total));
                                }
                    });
                    gameRepository().applyDefaultIsolationSettingForNewInstance(result.profileId());
                    if (context.isCancelled()) return null;
                    Platform.runLater(() -> {
                        if (context.isCancelled()) return;
                        stopProgressAnimation(downloadProgress, true);
                        setControlsBusy(false);
                        restoreVersionComboItems(result.profileId());
                        versionCombo.setValue(result.profileId());
                        setStatus("加载器安装完成",
                                result.loader().displayName() + " " + result.loaderVersion()
                                        + " / Minecraft " + result.minecraftVersion());
                        dialog.close();
                        renderActiveView();
                    });
                } catch (Exception error) {
                    boolean cancelled = context.isCancelled() || isCancellation(error);
                    Platform.runLater(() -> {
                        stopProgressAnimation(downloadProgress, true);
                        setControlsBusy(false);
                        install.setDisable(false);
                        cancel.setDisable(false);
                        if (cancelled) {
                            installStatus.setText(Messages.get("download.status.cancelled"));
                            setStatus(Messages.get("download.status.cancelled"), "");
                        } else {
                            String message = cleanMessage(error);
                            installStatus.setText(Messages.format("download.status.failed", message));
                            setStatus(Messages.get("status.downloadFailed"), message);
                        }
                    });
                    throw error;
                }
                return null;
            });
        });

        HBox buttons = new HBox(10, install, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(14,
                createSurface("Minecraft 版本", "加载器必须与游戏版本严格匹配", minecraftField),
                createSurface("加载器", null, loaderField),
                createSurface("加载器版本", "通常留空即可", versionField),
                installStatus, buttons);
        root.getStyleClass().add("root-pane");
        root.setPadding(new Insets(20));
        Scene scene = new Scene(root, 560, 470);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        dialog.setScene(scene);
        applyThemeToScene(scene, settingsManager.get(ECLConfig.KEY_THEME));
        dialog.show();
    }

    private void deleteSelectedVersion() {
        String profileId = getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            setStatus("没有可删除的版本", "请先选择一个版本。");
            return;
        }
        try {
            com.ecl.util.FileUtil.requireSafeVersionId(profileId);
        } catch (IOException error) {
            setStatus("版本 ID 无效", cleanMessage(error));
            return;
        }
        if (isGameProcessRunning()) {
            setStatus("游戏运行中不能删除版本",
                    activeGameVersion + " 正在运行，请退出游戏后再删除。");
            return;
        }
        boolean localMetadata;
        try {
            localMetadata = com.ecl.util.FileUtil.safeVersionDirectory(
                    ECLConfig.getVersionsDir(), profileId).isDirectory();
        } catch (IOException error) {
            setStatus("版本 ID 无效", cleanMessage(error));
            return;
        }
        boolean localInstance = Files.isDirectory(
                getConfiguredGameRootDir().toPath().resolve("versions").resolve(profileId));
        if (!localMetadata && !localInstance) {
            setStatus("版本尚未安装", profileId + " 没有可删除的本地文件。");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将删除版本文件和独立实例目录（mods、存档、设置、日志）。此操作不可撤销。\n\n"
                        + profileId,
                ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(primaryStage);
        confirm.setTitle("删除版本");
        confirm.setHeaderText("确认删除当前版本？");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        setStatus("正在删除版本", profileId);
        runAsync("ecl-delete-version", () -> {
            try {
                deleteProfileFiles(profileId, true);
                Platform.runLater(() -> {
                    settingsManager.remove(ECLConfig.KEY_SELECTED_VERSION);
                    settingsManager.save();
                    restoreVersionComboItems(null);
                    setStatus("版本已删除", profileId + " 的版本文件和实例数据已移除。");
                    renderActiveView();
                });
            } catch (IOException error) {
                Platform.runLater(() -> setStatus("删除版本失败", cleanMessage(error)));
                throw new RuntimeException(error);
            }
        });
    }

    private void reinstallSelectedVersion() {
        String profileId = getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            setStatus("没有可重装的版本", "请先选择一个版本。");
            return;
        }
        if (isGameProcessRunning()) {
            setStatus("游戏运行中不能重装版本",
                    activeGameVersion + " 正在运行，请退出游戏后再重装。");
            return;
        }
        VersionManager.LocalVersionProfile localProfile = versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .findFirst().orElse(null);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将重建版本元数据和依赖库；独立实例中的 mods、存档和设置会保留。\n\n" + profileId,
                ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(primaryStage);
        confirm.setTitle("重装版本");
        confirm.setHeaderText("确认重装当前版本？");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (localProfile == null) {
            String manifestUrl = versionManager.getVersionUrl(profileId);
            if (manifestUrl == null || manifestUrl.isBlank()) {
                setStatus("拒绝重装未知配置",
                        profileId + " 不是 Mojang 原版或可识别加载器配置，请先手动备份后处理。");
                return;
            }
            try {
                deleteProfileFiles(profileId, false);
                versionCombo.setValue(profileId);
                setStatus("版本已标记为重装", "点击启动后会重新下载 " + profileId + " 的完整文件。");
            } catch (IOException error) {
                setStatus("重装准备失败", cleanMessage(error));
            }
            return;
        }
        ModLoaderInstaller.Loader loader;
        try {
            loader = ModLoaderInstaller.Loader.valueOf(localProfile.loader().toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            setStatus("无法自动重装加载器", "无法识别加载器 " + localProfile.loader()
                    + "，请使用“安装加载器”。");
            return;
        }
        setControlsBusy(true);
        startProgressAnimation(downloadProgress);
        String requestedLoaderVersion;
        try {
            requestedLoaderVersion = detectInstalledLoaderVersion(profileId, loader);
        } catch (IOException error) {
            setControlsBusy(false);
            stopProgressAnimation(downloadProgress, true);
            setStatus("无法确定加载器版本", cleanMessage(error));
            return;
        }
        runAsync("ecl-reinstall-" + loader.id(), () -> {
            try {
                ModLoaderInstaller.InstallResult result = modLoaderInstaller.install(
                        localProfile.minecraftVersion(), loader, requestedLoaderVersion,
                        new ModLoaderInstaller.Listener() {
                            @Override
                            public void onStatus(String message) {
                                Platform.runLater(() -> setStatus("正在重装加载器", message));
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                Platform.runLater(() ->
                                        updateProgress(downloadProgress, downloaded, total));
                            }
                        });
                gameRepository().applyDefaultIsolationSettingForNewInstance(result.profileId());
                Platform.runLater(() -> {
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    restoreVersionComboItems(profileId);
                    versionCombo.setValue(profileId);
                    setStatus("加载器已重装", profileId + "；原加载器版本和实例数据已保留。");
                    renderActiveView();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    stopProgressAnimation(downloadProgress, true);
                    setControlsBusy(false);
                    setStatus("加载器重装失败", cleanMessage(error));
                });
            }
        });
    }

    private boolean isGameProcessRunning() {
        Process process = activeGameProcess;
        return process != null && process.isAlive();
    }

    private String detectInstalledLoaderVersion(String profileId,
                                                ModLoaderInstaller.Loader loader) throws IOException {
        File jsonFile = com.ecl.util.FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), profileId);
        com.google.gson.JsonObject json = com.ecl.util.HttpUtil.readJson(jsonFile);
        com.google.gson.JsonArray libraries = json.has("libraries") && json.get("libraries").isJsonArray()
                ? json.getAsJsonArray("libraries") : new com.google.gson.JsonArray();
        for (com.google.gson.JsonElement item : libraries) {
            if (!item.isJsonObject()) continue;
            String coordinate = item.getAsJsonObject().has("name")
                    ? item.getAsJsonObject().get("name").getAsString() : "";
            String prefix = switch (loader) {
                case FABRIC -> "net.fabricmc:fabric-loader:";
                case QUILT -> "org.quiltmc:quilt-loader:";
                case FORGE -> "net.minecraftforge:forge:";
                case NEOFORGE -> "net.neoforged:neoforge:";
            };
            if (!coordinate.startsWith(prefix)) continue;
            String version = coordinate.substring(prefix.length());
            if (loader == ModLoaderInstaller.Loader.FORGE
                    && version.startsWith(localMinecraftVersion(json) + "-")) {
                version = version.substring(localMinecraftVersion(json).length() + 1);
            }
            if (!version.isBlank()) return version;
        }
        throw new IOException("版本配置中没有找到 " + loader.displayName() + " 版本号");
    }

    private String localMinecraftVersion(com.google.gson.JsonObject json) {
        String explicit = json.has("eclMinecraftVersion")
                ? json.get("eclMinecraftVersion").getAsString() : "";
        return explicit.isBlank() && json.has("inheritsFrom")
                ? json.get("inheritsFrom").getAsString() : explicit;
    }

    private void deleteProfileFiles(String profileId, boolean includeInstance) throws IOException {
        if (profileId.contains("/") || profileId.contains("\\") || profileId.contains("..")) {
            throw new IOException("版本 ID 无效");
        }
        Path metadataRoot = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        deleteTreeWithin(metadataRoot, metadataRoot.resolve(profileId));
        if (includeInstance) {
            Path instanceRoot = getConfiguredGameRootDir().toPath().toAbsolutePath()
                    .normalize().resolve("versions").normalize();
            deleteTreeWithin(instanceRoot, instanceRoot.resolve(profileId));
        }
    }

    private void deleteTreeWithin(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("拒绝删除越界目录: " + target);
        }
        if (!Files.exists(normalizedTarget)) return;
        try (var stream = Files.walk(normalizedTarget)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
                ? parseVersionCategory(settingsManager.get(ECLConfig.KEY_VERSION_CATEGORY))
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
            updateSelectedVersionWikiButton();
            syncLoaderChoiceFromProfile(newValue);
        });

        versionTypeCombo = new ComboBox<>();
        versionTypeCombo.getItems().addAll(VersionManager.VersionCategory.values());
        versionTypeCombo.setValue(previousCategory);
        versionTypeCombo.setPrefWidth(176);
        versionTypeCombo.setTooltip(new Tooltip("默认显示正式版、预览版/快照和愚人节版，也可以只看某一类"));
        versionTypeCombo.setOnAction(e -> {
            settingsManager.set(ECLConfig.KEY_VERSION_CATEGORY, getSelectedVersionCategory().name());
            if (!settingsManager.save()) {
                setStatus("设置保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
                return;
            }
            refreshVersions();
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
        restoreVersionComboItems(previousVersion);
        updateSelectedVersionWikiButton();

        TextField gameDirField = new TextField(abbreviate(getActiveGameDir().getAbsolutePath(), 72));
        gameDirField.setEditable(false);
        applyFieldStyle(gameDirField);

        TextField jvmField = new TextField(extraJvmArgs == null || extraJvmArgs.isBlank()
                ? "未设置（内存: " + getMemoryDisplayText() + "）"
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
        microsoftLoginBtn.setOnAction(e -> loginMicrosoftAccount());
        microsoftAddAccountBtn = new Button("添加账号");
        microsoftAddAccountBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        microsoftAddAccountBtn.setTooltip(new Tooltip("使用设备码添加另一个 Microsoft 账号"));
        microsoftAddAccountBtn.setOnAction(e -> addMicrosoftAccount());
        skinUploadBtn = new Button("上传皮肤");
        skinUploadBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        skinUploadBtn.setTooltip(new Tooltip("上传 PNG 皮肤到当前 Minecraft Java 正版账号"));
        skinUploadBtn.setOnAction(e -> chooseAndUploadSkin());
        offlineSkinRemoveBtn = new Button("清除皮肤");
        offlineSkinRemoveBtn.getStyleClass().addAll("app-button", "ghost-button", "compact-button");
        offlineSkinRemoveBtn.setTooltip(new Tooltip("移除当前离线账号已导入的本地皮肤"));
        offlineSkinRemoveBtn.setOnAction(e -> removeOfflineSkin());
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

    private void restoreVersionComboItems(String preferredVersion) {
        if (versionCombo == null || versionTypeCombo == null || versionManager == null) {
            return;
        }
        try {
            List<String> versions = versionManager.mergeLocalLoaderProfiles(
                    versionManager.getVersions(getSelectedVersionCategory()));
            versionCombo.getItems().setAll(versions);
            if (preferredVersion != null && versions.contains(preferredVersion)) {
                versionCombo.getSelectionModel().select(preferredVersion);
            } else if (!versions.isEmpty()) {
                versionCombo.getSelectionModel().select(0);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to restore version choices", e);
        }
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

    private LoaderChoice loaderChoiceForProfile(String profileId) {
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

    private void updateLoaderControls() {
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

    private void installSelectedLoader(Runnable afterSuccess) {
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
                    restoreVersionComboItems(result.profileId());
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

    private Button createSelectedVersionWikiButton() {
        Button button = new Button("更新说明");
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
        launchBtn.setGraphicTextGap(10);
        launchBtn.getStyleClass().addAll("app-button", "launch-button");
        launchBtn.setDefaultButton(true);
        launchBtn.setOnAction(e -> launchGame());
        updateLoaderControls();

        Button switchInstanceButton = createLinkButton(
                "选择版本 / 加载器  ›",
                () -> expandInstanceSettings(versionCombo));

        refreshBtn = new Button("刷新版本");
        refreshBtn.getStyleClass().addAll("app-button", "secondary-button");
        refreshBtn.setOnAction(e -> refreshVersions());
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

    private void updateAuthFields() {
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

    private void updateOfflineSkinControls() {
        if (offlineSkinRemoveBtn == null) {
            return;
        }
        boolean offline = AUTH_OFFLINE.equals(authTypeCombo.getValue());
        setFieldVisible(offlineSkinRemoveBtn, offline && offlineSkinExists());
        offlineSkinRemoveBtn.setDisable(false);
    }

    private boolean offlineSkinExists() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (username.isBlank()) {
            return false;
        }
        return new OfflineSkinStore()
                .find(OfflineSkinStore.identityForOffline(username))
                .isPresent();
    }

    private void updateRuntimeSummary() {
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
        String memoryText = getMemoryDisplayText();
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
        if (isGameProcessRunning()) {
            readiness = Messages.get("home.readiness.running");
        } else if (selectedVersion != null && !selectedVersion.isBlank()) {
            readiness = versionManager.isVersionDownloaded(selectedVersion)
                    ? Messages.get("home.readiness.installed")
                    : Messages.get("home.readiness.prepareFirst");
        }
        if (launchReadinessLabel != null) {
            launchReadinessLabel.setText("●  " + readiness);
        }
        updatePlaytimeSummary();
    }

    private void updatePlaytimeSummary() {
        if (playtimeTotalLabel == null || versionCombo == null) return;
        String selected = versionCombo.getValue();
        if (selected == null || selected.isBlank()) {
            playtimeTotalLabel.setText(Messages.get("label.notSelected"));
            playtimeRecentLabel.setText(Messages.get("playtime.never"));
            playtimeLaunchCountLabel.setText("0");
            return;
        }
        try {
            PlaytimeTracker.PlaytimeStats stats = playtimeTracker.stats(
                    resolveVersionInstanceRoot(selected).toPath());
            playtimeTotalLabel.setText(formatPlaytime(stats.totalSeconds()));
            playtimeRecentLabel.setText(stats.lastLaunchedAt().isBlank() ? Messages.get("playtime.never")
                    : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.parse(stats.lastLaunchedAt())));
            playtimeLaunchCountLabel.setText(String.valueOf(stats.launchCount()));
        } catch (IOException | RuntimeException error) {
            playtimeTotalLabel.setText(Messages.get("playtime.unavailable"));
            playtimeRecentLabel.setText("-");
            playtimeLaunchCountLabel.setText("-");
        }
    }

    private String formatPlaytime(long seconds) {
        long hours = Math.max(0, seconds) / 3600;
        long minutes = (Math.max(0, seconds) % 3600) / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private int getEffectiveMaxMemoryMb() {
        return maxMemoryMb == ECLConfig.AUTO_MEMORY_MB
                ? ECLConfig.calculateAutoMemoryMb()
                : maxMemoryMb;
    }

    private String getMemoryDisplayText() {
        int effectiveMemoryMb = getEffectiveMaxMemoryMb();
        return maxMemoryMb == ECLConfig.AUTO_MEMORY_MB
                ? "自动 " + effectiveMemoryMb + " MB"
                : effectiveMemoryMb + " MB";
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

    private void setStatus(String title, String detail) {
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
                List<String> versions = versionManager.mergeLocalLoaderProfiles(
                        versionManager.getVersions(category));
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
        } catch (Exception e) {
            LOGGER.debug("Invalid saved version category: {}", value, e);
            return VersionManager.VersionCategory.FEATURED;
        }
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

    private void launchGame() {
        String selectedVersion = versionCombo.getValue();
        if (selectedVersion == null || selectedVersion.isBlank()) {
            setStatus("请选择游戏版本", "先刷新并选择一个可启动的 Minecraft 版本。 ");
            return;
        }
        LoaderChoice requestedLoader = loaderChoiceCombo == null
                ? LoaderChoice.VANILLA : loaderChoiceCombo.getValue();
        if (requestedLoader != null && !requestedLoader.vanilla()
                && loaderChoiceForProfile(selectedVersion) != requestedLoader) {
            installSelectedLoader(this::launchGame);
            return;
        }

        if (lastContentVersion != null && !lastContentVersion.equals(selectedVersion)
                && versionManager.isVersionDownloaded(lastContentVersion)) {
            setStatus("注意：启动版本与已下载内容的版本不一致",
                    "模组 / 光影 / 材质包已下载到 " + lastContentVersion
                            + " 的实例目录，当前将启动 " + selectedVersion
                            + "，这些内容不会被加载。可切换到 " + lastContentVersion + " 再启动。 ");
        }

        String configuredJavaPath = javaPath == null ? "" : javaPath.trim();
        if (!configuredJavaPath.isBlank() && !JavaRuntimeUtil.isUsableJavaPath(configuredJavaPath)) {
            setStatus("Java 路径无效", "高级设置里的 Java 路径不可用，请重新选择 java.exe 或 JDK 根目录。 ");
            return;
        }

        if (configuredJavaPath.isBlank()) {
            javaPath = "";
        }

        settingsManager.set(ECLConfig.KEY_JAVA_PATH, javaPath);
        settingsManager.set(ECLConfig.KEY_GAME_DIR, gameDir.getAbsolutePath());
        settingsManager.set(ECLConfig.KEY_JVM_ARGS, extraJvmArgs == null ? "" : extraJvmArgs);
        settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, maxMemoryMb);
        settingsManager.set(ECLConfig.KEY_SELECTED_VERSION, selectedVersion);
        settingsManager.set(ECLConfig.KEY_AUTH_TYPE, authTypeCombo.getValue());
        settingsManager.set(ECLConfig.KEY_USERNAME, usernameField.getText().trim());
        if (AUTH_YGGDRASIL.equals(authTypeCombo.getValue())) {
            settingsManager.set(ECLConfig.KEY_YGGDRASIL_SERVER, yggdrasilServerField.getText().trim());
        }
        if (!settingsManager.save()) {
            setStatus("设置保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
            return;
        }
        updateRuntimeSummary();

        if (!versionManager.isVersionDownloaded(selectedVersion)) {
            downloadAndLaunch(selectedVersion);
        } else {
            startGame(selectedVersion);
        }
    }

    private void downloadAndLaunch(String version) {
        VersionManager.VersionDownloadTarget downloadTarget;
        try {
            downloadTarget = versionManager.resolveDownloadTarget(version);
        } catch (IOException error) {
            setStatus("无法解析基础版本", cleanMessage(error));
            return;
        }
        String downloadVersion = downloadTarget.downloadVersionId();
        String url = downloadTarget.versionUrl();
        if (url == null || url.isBlank()) {
            setStatus("找不到基础版本下载地址",
                    version + " 需要 " + downloadVersion
                            + "，但当前 Mojang 版本清单中没有该版本。请刷新版本列表后重试。");
            return;
        }

        setControlsBusy(true);
        downloadProgress.setProgress(0);
        startProgressAnimation(downloadProgress);
        setStatus("正在准备下载",
                version.equals(downloadVersion)
                        ? version + " 首次启动需要补齐客户端、依赖库和资源文件。"
                        : version + " 将继承 " + downloadVersion
                                + "，正在补齐基础客户端、依赖库和资源文件。");

        DownloadTaskCenter.TaskHandle<Void> task = downloadTaskCenter.submit(
                "Minecraft " + downloadVersion, context -> {
                    AtomicReference<String> downloadFailure = new AtomicReference<>();
                    downloader.setListener(new GameDownloader.DownloadListener() {
                        @Override
                        public void onStatus(String message) {
                            context.updateStatus(message);
                            Platform.runLater(() -> setStatus("下载中", message));
                        }

                        @Override
                        public void onProgress(long downloaded, long total) {
                            context.updateProgress(downloaded, total);
                            Platform.runLater(() -> {
                                updateProgress(downloadProgress, downloaded, total);
                                detailLabel.setText("当前进度: " + formatBytes(downloaded)
                                        + (total > 0 ? " / " + formatBytes(total) : ""));
                            });
                        }

                        @Override
                        public void onError(String message) {
                            downloadFailure.set(message);
                            Platform.runLater(() -> {
                                if (context.isCancelled()) return;
                                setStatus("下载失败", message);
                                stopProgressAnimation(downloadProgress, true);
                                setControlsBusy(false);
                            });
                        }

                        @Override
                        public void onComplete() {
                            Platform.runLater(() -> {
                                if (context.isCancelled()) return;
                                downloadProgress.setProgress(1);
                                stopProgressAnimation(downloadProgress, true);
                                if (!versionManager.isVersionDownloaded(version)) {
                                    setStatus("基础版本仍不完整",
                                            downloadVersion + " 下载完成，但 " + version
                                                    + " 的继承客户端仍不可用，请检查版本配置。");
                                    setControlsBusy(false);
                                    return;
                                }
                                setStatus("下载完成",
                                        downloadVersion + " 已就绪，准备启动 " + version + "。");
                                try {
                                    gameRepository().applyDefaultIsolationSettingForNewInstance(version);
                                } catch (IOException error) {
                                    LOGGER.warn("Cannot persist default isolation for {}", version, error);
                                }
                                startGame(version);
                            });
                        }
                    });
                    context.registerCancellation(downloader::cancelDownload);
                    Future<?> future = downloader.downloadVersionAsync(downloadVersion, url);
                    future.get();
                    String failure = downloadFailure.get();
                    if (failure != null && !failure.isBlank()) throw new IOException(failure);
                    return null;
                });
        task.completion().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error == null) return;
            stopProgressAnimation(downloadProgress, true);
            if (isCancellation(error)) {
                setStatus(Messages.get("download.status.cancelled"), cleanMessage(error));
            } else {
                setStatus(Messages.get("download.status.failedTitle"), cleanMessage(error));
            }
            setControlsBusy(false);
        }));
    }

    private void startGame(String version) {
        String authType = authTypeCombo.getValue();
        String server = yggdrasilServerField.getText().trim();
        String username = usernameField.getText().trim();
        AtomicReference<String> passwordRef = new AtomicReference<>(passwordField.getText());
        passwordField.clear(); // 尽快清除 UI 中的密码，减少敏感数据驻留时间

        setControlsBusy(true);
        stopProgressAnimation(downloadProgress, true);
        setStatus("正在启动游戏...", "准备认证、拼接类路径并拉起客户端进程。 ");

        runAsync("ecl-launch-game", () -> {
            File launchDir = resolveVersionGameDir(version);
            String password = passwordRef.getAndSet(null);
            try {
                ensureVersionGameDirs(version);
                AuthProvider auth = buildAuthProvider(authType, server, username, password);
                password = null;
                OfflineSkin offlineSkin = auth.getType() == com.ecl.auth.AuthType.OFFLINE
                        ? new OfflineSkinStore()
                                .find(OfflineSkinStore.identityForOffline(auth.getUsername()))
                                .orElse(null)
                        : null;
                LaunchOptions options = LaunchOptions.builder()
                        .versionId(version)
                        .auth(auth)
                        .offlineSkin(offlineSkin)
                        .gameDirectory(launchDir)
                        .instanceDirectory(resolveVersionInstanceRoot(version))
                        .environment(controller.launchEnvironment())
                        .maxMemoryMb(getEffectiveMaxMemoryMb())
                        .jvmArguments(TextUtil.parseCommandLine(
                                extraJvmArgs == null ? "" : extraJvmArgs))
                        .javaExecutablePath(javaPath)
                        .gameResolution(gameWidth, gameHeight)
                        .fullscreen(gameFullscreen)
                        .serverAddress(quickServer)
                        .processorCount(processorCount)
                        .build();
                createAutomaticBackupBeforeLaunch(version, launchDir.toPath());
                controller.invalidateLaunchVersion(version);
                GameProcess gameProcess = gameLauncher.launch(options);
                Process process = gameProcess.process();
                long launchStartedAt = process.info().startInstant()
                        .map(Instant::toEpochMilli)
                        .orElseGet(System::currentTimeMillis);
                try {
                    playtimeTracker.recordLaunch(resolveVersionInstanceRoot(version).toPath(), launchStartedAt);
                } catch (IOException statsError) {
                    LOGGER.warn("Cannot record launch statistics for {}", version, statsError);
                }
                activeGameProcess = process;
                activeGameVersion = version;
                UUID runningInstanceId = registerRunningModInstance(version);
                boolean minimizeThisLaunch = closeAfterLaunch;

                runOnUiIfActive(() -> {
                    setStatus("游戏已启动", version + " 正在运行，实例目录: " + launchDir.getAbsolutePath());
                    updateRuntimeSummary();
                    setControlsBusy(false);
                    if (showGameConsole) {
                        setActiveView(AppView.LOGS);
                    }
                    if (minimizeThisLaunch) {
                        primaryStage.setIconified(true);
                    }
                });
                monitorGameProcess(gameProcess, version, launchDir, launchStartedAt,
                        runningInstanceId, minimizeThisLaunch);
            } catch (Exception e) {
                runOnUiIfActive(() -> {
                    CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, launchDir);
                    setStatus("启动失败", report.getTitle());
                    showGameErrorDialog(report);
                    setControlsBusy(false);
                });
            } finally {
                password = null;
                passwordRef.set(null);
            }
        });
    }

    private void createAutomaticBackupBeforeLaunch(String profileId, Path instanceDirectory) {
        if (!backupOnLaunch) return;
        EnumSet<BackupEntry.Content> content = EnumSet.of(BackupEntry.Content.SAVES);
        if (backupIncludeMods) content.add(BackupEntry.Content.MODS);
        try {
            BackupEntry created = worldBackupService.createBackup(profileId,
                    resolveBackupSourceVersion(profileId), instanceDirectory, content, null);
            List<BackupEntry> removed = worldBackupService.prune(profileId, backupKeepCount);
            LOGGER.info("Created pre-launch backup {} for {} ({} bytes); pruned {} old backups",
                    created.archivePath(), profileId, created.archiveSize(), removed.size());
        } catch (Exception error) {
            LOGGER.warn("Pre-launch backup failed for {}; game launch will continue",
                    profileId, error);
        }
    }

    private UUID registerRunningModInstance(String version) {
        try {
            ModInstanceContext instance = VersionProfileModInstanceContext.load(
                    version,
                    ECLConfig.getVersionsDir().toPath(),
                    getConfiguredGameRootDir().toPath(),
                    resolveVersionGameDir(version).toPath());
            controller.registerModInstance(instance);
            controller.setInstanceRunning(instance.instanceId(), true);
            return instance.instanceId();
        } catch (Exception e) {
            LOGGER.warn("Cannot register running mod instance for {}", version, e);
            return null;
        }
    }

    private void monitorGameProcess(GameProcess gameProcess, String version, File launchDir,
                                    long launchStartedAt,
                                    UUID runningInstanceId, boolean restoreLauncher) {
        // 守护线程：关闭启动器窗口后进程能立即退出，不会被该监控线程拖住；
        // 游戏本体是独立进程，启动器退出不影响其继续运行。
        Thread.ofPlatform().name("ecl-monitor-game-" + version).daemon(true).start(() -> {
            BoundedLogBuffer output = new BoundedLogBuffer(ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS);
            com.ecl.launch.ProcessOutputListener outputListener = line -> {
                output.appendLine(line);
                appendGameConsoleLine(line);
            };
            gameProcess.attachOutputListener(outputListener);
            Process process = gameProcess.process();
            try {
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    runOnUiIfActive(() -> setStatus("游戏已正常退出", version + " 退出码 0。"));
                    return;
                }

                CrashAnalyzer.Report report = CrashAnalyzer.analyzeGameExit(version, exitCode, output.toString(), launchDir, launchStartedAt);
                runOnUiIfActive(() -> {
                    setStatus("游戏异常退出", report.getTitle());
                    showGameErrorDialog(report);
                });
            } catch (Exception e) {
                CrashAnalyzer.Report report = CrashAnalyzer.analyzeLaunchException(version, e, launchDir);
                runOnUiIfActive(() -> {
                    setStatus("错误分析失败", report.getTitle());
                    showGameErrorDialog(report);
                });
            } finally {
                gameProcess.detachOutputListener(outputListener);
                try {
                    playtimeTracker.recordSession(resolveVersionInstanceRoot(version).toPath(),
                            launchStartedAt, System.currentTimeMillis());
                } catch (IOException statsError) {
                    LOGGER.warn("Cannot record playtime statistics for {}", version, statsError);
                }
                if (runningInstanceId != null) {
                    controller.setInstanceRunning(runningInstanceId, false);
                }
                if (activeGameProcess == process) {
                    activeGameProcess = null;
                    activeGameVersion = null;
                }
                runOnUiIfActive(() -> {
                    updateRuntimeSummary();
                    if (restoreLauncher) {
                        primaryStage.setIconified(false);
                        primaryStage.show();
                        primaryStage.toFront();
                    }
                });
            }
        });
    }

    private void runOnUiIfActive(Runnable action) {
        if (applicationStopping.get()) return;
        Platform.runLater(() -> {
            if (!applicationStopping.get()) action.run();
        });
    }

    private void appendGameConsoleLine(String line) {
        liveGameLog.appendLine(line);
        if (applicationStopping.get()) return;
        synchronized (pendingConsoleText) {
            pendingConsoleText.append(line).append(System.lineSeparator());
            int excess = pendingConsoleText.length() - ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS;
            if (excess > 0) {
                pendingConsoleText.delete(0, excess);
            }
        }
        if (consoleFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushPendingConsoleText);
        }
    }

    private void flushPendingConsoleText() {
        String batch;
        synchronized (pendingConsoleText) {
            batch = pendingConsoleText.toString();
            pendingConsoleText.setLength(0);
        }
        consoleFlushScheduled.set(false);
        TextArea area = liveConsoleArea;
        if (area != null && !batch.isEmpty()) {
            area.appendText(batch);
            int excess = area.getLength() - ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS;
            if (excess > 0) area.deleteText(0, excess);
            area.positionCaret(area.getLength());
        }
        synchronized (pendingConsoleText) {
            if (pendingConsoleText.length() > 0
                    && consoleFlushScheduled.compareAndSet(false, true)) {
                Platform.runLater(this::flushPendingConsoleText);
            }
        }
    }

    private void showGameErrorDialog(CrashAnalyzer.Report report) {
        CrashDiagnosticDialog.show(primaryStage, report, resolveModsDir(getSelectedVersion()),
                folder -> openLocalFolder(folder, "诊断目录"));
    }

    private void loginMicrosoftAccount() {
        authTypeCombo.setValue(AUTH_MICROSOFT);
        updateAuthFields();
        setControlsBusy(true);
        setStatus("微软正版登录", "正在尝试恢复已保存的 Microsoft 登录状态。");

        runAsync("ecl-login-microsoft", () -> {
            try {
                MicrosoftAuth microsoftAuth = authenticateMicrosoftAccount(false);
                Platform.runLater(() -> {
                    usernameField.setText(microsoftAuth.getUsername());
                    refreshMicrosoftAccountChoices(microsoftAuth.getUUID());
                    setStatus(lastMicrosoftAccountPersisted
                                    ? "微软正版登录成功" : "微软登录成功，多账号保存失败",
                            lastMicrosoftAccountPersisted
                                    ? "已登录 " + microsoftAuth.getUsername() + "，现在可以直接启动游戏。"
                                    : "当前登录可用，但账号列表无法写入；请检查 ECL 数据目录权限。");
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
        return authenticateMicrosoftAccount(false);
    }

    private void addMicrosoftAccount() {
        authTypeCombo.setValue(AUTH_MICROSOFT);
        updateAuthFields();
        setControlsBusy(true);
        setStatus("添加 Microsoft 账号", "正在申请新的设备登录代码。");
        runAsync("ecl-add-microsoft-account", () -> {
            try {
                MicrosoftAuth auth = authenticateMicrosoftAccount(true);
                Platform.runLater(() -> {
                    usernameField.setText(auth.getUsername());
                    refreshMicrosoftAccountChoices(auth.getUUID());
                    setStatus(lastMicrosoftAccountPersisted
                                    ? "Microsoft 账号已添加" : "账号登录成功但保存失败",
                            lastMicrosoftAccountPersisted
                                    ? auth.getUsername() + " 已保存，可在账号下拉框中切换。"
                                    : "当前登录可用，但无法写入多账号列表；请检查数据目录权限。");
                    setControlsBusy(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    setStatus("添加 Microsoft 账号失败", cleanMessage(error));
                    setControlsBusy(false);
                });
            }
        });
    }

    private void chooseAndUploadSkin() {
        String authType = authTypeCombo.getValue();
        if (AUTH_OFFLINE.equals(authType)) {
            chooseAndImportOfflineSkin();
            return;
        }
        if (!AUTH_MICROSOFT.equals(authType)) {
            setStatus("当前登录方式不支持皮肤操作",
                    "请切换到 Microsoft 正版登录上传官方皮肤，或切换到离线登录导入本地皮肤。");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Minecraft Java 版皮肤");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG 皮肤图片 (*.png)", "*.png"));
        File selected = chooser.showOpenDialog(primaryStage);
        if (selected == null) return;

        MinecraftSkinService.SkinImage skin;
        try {
            skin = minecraftSkinService.inspect(selected.toPath());
        } catch (IOException error) {
            setStatus("皮肤文件无效", cleanMessage(error));
            return;
        }

        Dialog<MinecraftSkinService.Variant> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle("上传 Minecraft 皮肤");
        dialog.setHeaderText("确认皮肤模型");
        dialog.setOnShown(event -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                applyWindowIcon(stage);
            }
        });

        ImageView preview = new ImageView(new Image(selected.toURI().toString()));
        preview.setFitWidth(192);
        preview.setFitHeight(192);
        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        preview.getStyleClass().add("skin-preview");

        ComboBox<MinecraftSkinService.Variant> variant = new ComboBox<>();
        variant.getItems().setAll(MinecraftSkinService.Variant.values());
        variant.setValue(MinecraftSkinService.Variant.CLASSIC);
        variant.setMaxWidth(Double.MAX_VALUE);
        applyFieldStyle(variant);

        Label fileInfo = createBodyText(selected.getName() + " · "
                + skin.width() + "×" + skin.height() + " · " + formatBytes(skin.fileSize()));
        Label accountInfo = createBodyText("上传到：" + (selectedMicrosoftAccount == null
                ? settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_NAME)
                : selectedMicrosoftAccount.username()));
        VBox content = new VBox(12, preview, fileInfo, accountInfo,
                new Label("角色模型"), variant);
        content.setAlignment(Pos.CENTER);
        dialog.getDialogPane().setContent(content);
        ButtonType upload = new ButtonType("上传并使用", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(upload, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == upload ? variant.getValue() : null);
        dialog.showAndWait().ifPresent(selectedVariant ->
                uploadSkin(selected.toPath(), selectedVariant));
    }

    private void uploadSkin(Path skin, MinecraftSkinService.Variant variant) {
        setControlsBusy(true);
        setStatus("正在上传皮肤", "正在验证 Microsoft 登录并连接 Minecraft 皮肤服务…");
        runAsync("ecl-upload-skin", () -> {
            try {
                MicrosoftAuth auth = authenticateMicrosoftAccount(false);
                MinecraftSkinService.UploadResult result = minecraftSkinService.upload(
                        auth, skin, variant);
                Platform.runLater(() -> {
                    String account = result.profileName() == null || result.profileName().isBlank()
                            ? auth.getUsername() : result.profileName();
                    setStatus("皮肤上传成功", account + " 已使用 " + variant + " 皮肤。重新进入游戏后生效。");
                    setControlsBusy(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    setStatus("皮肤上传失败", cleanMessage(error));
                    setControlsBusy(false);
                });
            }
        });
    }

    /**
     * Offline account path: pick a PNG, confirm the model, and copy it into the launcher data
     * directory. The skin is injected at launch time through the built-in Yggdrasil skin service,
     * so it works in single player and on offline-mode servers without any mods or premium login.
     */
    private void chooseAndImportOfflineSkin() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (username.isBlank()) {
            setStatus("请输入玩家名称", "离线皮肤需要绑定到具体的离线玩家名，请先在“账号模式”中填写玩家名称。");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择离线账号皮肤（PNG）");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG 皮肤图片 (*.png)", "*.png"));
        File selected = chooser.showOpenDialog(primaryStage);
        if (selected == null) return;

        MinecraftSkinService.SkinImage skin;
        try {
            skin = minecraftSkinService.inspect(selected.toPath());
        } catch (IOException error) {
            setStatus("皮肤文件无效", cleanMessage(error));
            return;
        }

        Dialog<MinecraftSkinService.Variant> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle("导入离线皮肤");
        dialog.setHeaderText("确认皮肤模型");
        dialog.setOnShown(event -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                applyWindowIcon(stage);
            }
        });

        ImageView preview = new ImageView(new Image(selected.toURI().toString()));
        preview.setFitWidth(192);
        preview.setFitHeight(192);
        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        preview.getStyleClass().add("skin-preview");

        ComboBox<MinecraftSkinService.Variant> variant = new ComboBox<>();
        variant.getItems().setAll(MinecraftSkinService.Variant.values());
        variant.setValue(MinecraftSkinService.Variant.CLASSIC);
        variant.setMaxWidth(Double.MAX_VALUE);
        applyFieldStyle(variant);

        Label fileInfo = createBodyText(selected.getName() + " · "
                + skin.width() + "×" + skin.height() + " · " + formatBytes(skin.fileSize()));
        Label accountInfo = createBodyText("应用到离线账号：" + username
                + "\n皮肤与玩家名（含大小写）绑定；改名后需要重新导入。"
                + "\n本地皮肤服务会在启动游戏时自动注入，无需正版账号。");
        VBox content = new VBox(12, preview, fileInfo, accountInfo,
                new Label("角色模型"), variant);
        content.setAlignment(Pos.CENTER);
        dialog.getDialogPane().setContent(content);
        ButtonType importButton = new ButtonType("导入并使用", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == importButton ? variant.getValue() : null);
        dialog.showAndWait().ifPresent(selectedVariant ->
                importOfflineSkin(selected.toPath(), username, selectedVariant));
    }

    private void importOfflineSkin(Path skin, String username, MinecraftSkinService.Variant variant) {
        setControlsBusy(true);
        setStatus("正在导入皮肤", "正在校验并复制皮肤到本地数据目录…");
        runAsync("ecl-import-offline-skin", () -> {
            try {
                String identity = OfflineSkinStore.identityForOffline(username);
                new OfflineSkinStore().importSkin(identity, skin, variant);
                Platform.runLater(() -> {
                    setStatus("皮肤导入成功",
                            "离线账号 " + username + " 已使用本地皮肤，重新启动游戏后生效。");
                    setControlsBusy(false);
                    updateOfflineSkinControls();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    setStatus("皮肤导入失败", cleanMessage(error));
                    setControlsBusy(false);
                });
            }
        });
    }

    private void removeOfflineSkin() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (username.isBlank()) {
            return;
        }
        String identity = OfflineSkinStore.identityForOffline(username);
        try {
            boolean removed = new OfflineSkinStore().remove(identity);
            if (removed) {
                setStatus("皮肤已清除", "离线账号 " + username + " 已恢复默认皮肤。");
            } else {
                setStatus("皮肤未找到", "该账号当前没有导入本地皮肤。");
            }
        } catch (RuntimeException failure) {
            setStatus("清除皮肤失败", cleanMessage(failure));
        }
        updateOfflineSkinControls();
    }

    private MicrosoftAuth authenticateMicrosoftAccount(boolean forceNew) {
        MicrosoftAccountStore.Account selected = forceNew ? null : selectedMicrosoftAccount;
        MicrosoftAuth.CachedSession cachedSession = selected == null
                ? new MicrosoftAuth.CachedSession(
                        forceNew ? null : settingsManager.getEncrypted("microsoftRefreshToken"),
                        forceNew ? null : settingsManager.getEncrypted("microsoftAccessToken"),
                        forceNew ? 0 : settingsManager.get(ECLConfig.KEY_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT),
                        forceNew ? null : settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_NAME),
                        forceNew ? null : settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_UUID))
                : new MicrosoftAuth.CachedSession(
                        selected.refreshToken(), selected.accessToken(),
                        selected.accessTokenExpiresAt(), selected.username(), selected.uuid());
        MicrosoftAuth microsoftAuth = new MicrosoftAuth(cachedSession, new MicrosoftAuth.LoginListener() {
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
        MicrosoftAuth.CachedSession authenticatedSession = microsoftAuth.getCachedSession();
        String refreshToken = authenticatedSession.refreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            settingsManager.setEncrypted("microsoftRefreshToken", refreshToken);
        }
        settingsManager.setEncrypted("microsoftAccessToken", authenticatedSession.accessToken());
        settingsManager.set(ECLConfig.KEY_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT,
                authenticatedSession.accessTokenExpiresAt());
        settingsManager.set(ECLConfig.KEY_AUTH_TYPE, AUTH_MICROSOFT);
        settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_NAME, authenticatedSession.username());
        settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_UUID, authenticatedSession.uuid());
        settingsManager.set(ECLConfig.KEY_USERNAME, authenticatedSession.username());
        MicrosoftAccountStore.Account storedAccount = new MicrosoftAccountStore.Account(
                authenticatedSession.uuid(), authenticatedSession.username(),
                authenticatedSession.refreshToken(), authenticatedSession.accessToken(),
                authenticatedSession.accessTokenExpiresAt());
        lastMicrosoftAccountPersisted = microsoftAccountStore.save(storedAccount);
        selectedMicrosoftAccount = storedAccount;
        if (!settingsManager.save()) {
            Platform.runLater(() -> setStatus("微软登录信息保存失败", "登录已成功，但无法保存刷新令牌，请检查目录权限或查看日志。"));
        }
        return microsoftAuth;
    }

    private void refreshMicrosoftAccountChoices(String selectedUuid) {
        if (microsoftAccountCombo == null) return;
        List<MicrosoftAccountStore.Account> accounts = microsoftAccountStore.list();
        microsoftAccountCombo.getItems().setAll(accounts);
        accounts.stream()
                .filter(account -> account.uuid().equalsIgnoreCase(selectedUuid))
                .findFirst()
                .ifPresent(microsoftAccountCombo::setValue);
    }

    private void showMicrosoftDeviceCodeDialog(MicrosoftAuth.DeviceCode deviceCode) {
        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("Microsoft 登录");
        applyWindowIcon(dialog);

        Label title = new Label("Microsoft 登录");
        title.getStyleClass().add("section-title");
        Label message = createBodyText("登录代码已自动复制。打开浏览器访问验证地址并粘贴代码完成授权，授权成功后可以关闭本窗口。");

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
                createInfoRow("登录码", createStaticValueLabel(deviceCode.getUserCode())),
                codeField,
                createInfoRow("验证 URL", createStaticValueLabel(deviceCode.getVerificationUri())),
                urlField,
                actions
        );
        root.getStyleClass().add("root-pane");
        root.setPadding(new Insets(18));

        Scene scene = new Scene(root, 520, 330);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        applyThemeToScene(scene, settingsManager.get(ECLConfig.KEY_THEME));
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
        } catch (Exception e) {
            LOGGER.warn("Failed to copy Microsoft device code", e);
            return false;
        }
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
            String effectivePassword = password;
            if (effectivePassword == null || effectivePassword.isBlank()) {
                effectivePassword = settingsManager.getEncrypted(
                        yggdrasilCredentialKey(server, username));
            }
            if (server.isBlank() || username.isBlank()
                    || effectivePassword == null || effectivePassword.isBlank()) {
                throw new IllegalArgumentException("请填写完整的外置登录信息。");
            }
            YggdrasilAuth yggdrasilAuth = new YggdrasilAuth(server);
            yggdrasilAuth.setCredentials(username, effectivePassword);
            yggdrasilAuth.login();
            settingsManager.setEncrypted(yggdrasilCredentialKey(server, username), effectivePassword);
            settingsManager.remove("_enc_yggdrasilPassword");
            settingsManager.set(ECLConfig.KEY_YGGDRASIL_SERVER, server);
            settingsManager.set(ECLConfig.KEY_USERNAME, yggdrasilAuth.getUsername());
            if (!settingsManager.save()) {
                LOGGER.warn("Failed to persist Yggdrasil session settings");
            }
            return yggdrasilAuth;
        }

        String offlineName = username.isBlank() ? "Player" : username;
        return new OfflineAuth(offlineName);
    }

    private String yggdrasilCredentialKey(String server, String username) {
        String normalizedServer = server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
        while (normalizedServer.endsWith("/")) {
            normalizedServer = normalizedServer.substring(0, normalizedServer.length() - 1);
        }
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String identity = normalizedServer + "\n" + normalizedUsername;
        return "yggdrasilPassword."
                + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
    private void setControlsBusy(boolean busy) {
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
        updateSelectedVersionWikiButton();
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
                        restoreVersionComboItems(completedPack.profileId());
                        syncLaunchVersionToContent(completedPack.profileId());
                        dialogStatus.setText(mainFile + " 安装完成，正在启动整合包…");
                        setStatus("整合包安装完成", "正在启动 " + completedPack.name());
                        Platform.runLater(this::launchGame);
                    } else {
                        syncLaunchVersionToContent(instance.profileId());
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

    private String getSelectedVersion() {
        return versionCombo == null ? null : versionCombo.getValue();
    }

    /**
     * Content is downloaded into the instance directory of {@code contentVersion}. To keep the
     * launched game directory identical to the download target (so mods / shaderpacks /
     * resourcepacks are actually loaded), the launch selection is realigned to that version after
     * a successful import. Only selects the value when it is already offered by the combo.
     */
    private void syncLaunchVersionToContent(String contentVersion) {
        if (contentVersion == null || contentVersion.isBlank()) {
            return;
        }
        lastContentVersion = contentVersion;
        if (versionCombo == null || contentVersion.equals(versionCombo.getValue())) {
            return;
        }
        if (versionCombo.getItems().contains(contentVersion)) {
            versionCombo.setValue(contentVersion);
            updateRuntimeSummary();
        }
    }

    private File getConfiguredGameRootDir() {
        return gameDir == null ? ECLConfig.getGameDir() : gameDir;
    }

    /**
     * Normalizes a configured root directory into the single source of truth used for every
     * content path (mods / shaderpacks / resourcepacks / saves). The legacy {@code <base>/game}
     * location is folded back into the standard game directory so download targets and the launched
     * game directory can never diverge because of an outdated saved path.
     */
    private File resolveConfiguredGameRootDir(File candidate) {
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

    private File resolveVersionGameDir(String gameVersion) {
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

    private File resolveVersionInstanceRoot(String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return getConfiguredGameRootDir();
        }
        return gameRepository().instanceRoot(sanitizeVersionDirectoryName(gameVersion)).toFile();
    }

    private DefaultGameRepository gameRepository() {
        return new DefaultGameRepository(ECLConfig.getVersionsDir().toPath(),
                getConfiguredGameRootDir().toPath(), DefaultIsolationType.parse(
                        settingsManager.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
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
        javaField.setPromptText("java.exe 或 JDK 根目录");
        applyFieldStyle(javaField);

        Button detectBtn = new Button("自动检测");
        detectBtn.getStyleClass().addAll("app-button", "secondary-button");
        detectBtn.setOnAction(e -> javaField.setText(JavaRuntimeUtil.detectSystemJavaExecutable()));

        Button javaBrowseBtn = new Button("浏览");
        javaBrowseBtn.getStyleClass().addAll("app-button", "secondary-button");
        javaBrowseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 Java 可执行文件");
            File initial = prepareChooserDir(javaField.getText());
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

        TextField dirField = new TextField(gameDir.getAbsolutePath());
        dirField.setPromptText("输入游戏目录");
        applyFieldStyle(dirField);

        Button dirBrowseBtn = new Button("浏览");
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

        ComboBox<String> isolationPolicyField = new ComboBox<>();
        isolationPolicyField.getItems().setAll("始终隔离", "仅 Mod/加载器实例隔离", "全部共享");
        isolationPolicyField.getSelectionModel().select(switch (DefaultIsolationType.parse(
                settingsManager.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE))) {
            case ALWAYS -> 0;
            case MODDED -> 1;
            case NEVER -> 2;
        });
        applyFieldStyle(isolationPolicyField);

        String selectedInstanceId = getSelectedVersion();
        ComboBox<String> instanceDirectoryModeField = new ComboBox<>();
        instanceDirectoryModeField.getItems().setAll("跟随默认策略", "使用独立实例目录", "使用自定义目录");
        TextField customInstanceDirectoryField = new TextField();
        customInstanceDirectoryField.setPromptText("选择此实例的自定义运行目录");
        applyFieldStyle(customInstanceDirectoryField);
        if (selectedInstanceId == null || selectedInstanceId.isBlank()) {
            instanceDirectoryModeField.getSelectionModel().select(0);
            instanceDirectoryModeField.setDisable(true);
            customInstanceDirectoryField.setDisable(true);
        } else {
            try {
                InstanceGameSettings currentInstanceSettings = new InstanceGameSettingsStore().load(
                        gameRepository().instanceRoot(selectedInstanceId));
                if (!currentInstanceSettings.overridesRunningDirectory()) {
                    instanceDirectoryModeField.getSelectionModel().select(0);
                } else if (currentInstanceSettings.hasCustomDirectory()) {
                    instanceDirectoryModeField.getSelectionModel().select(2);
                    customInstanceDirectoryField.setText(currentInstanceSettings.runningDirectory());
                } else {
                    instanceDirectoryModeField.getSelectionModel().select(1);
                }
            } catch (IOException error) {
                LOGGER.warn("Cannot load instance directory settings for {}", selectedInstanceId, error);
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
            File initial = prepareChooserDir(customInstanceDirectoryField.getText());
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

        TextField memoryField = new TextField(maxMemoryMb == ECLConfig.AUTO_MEMORY_MB ? "" : Integer.toString(maxMemoryMb));
        memoryField.setPromptText("自动（当前 " + ECLConfig.calculateAutoMemoryMb() + " MB）");
        applyFieldStyle(memoryField);

        TextField jvmField = new TextField(extraJvmArgs);
        jvmField.setPromptText("例如：-XX:+UseG1GC -Dfile.encoding=UTF-8");
        applyFieldStyle(jvmField);

        TextField widthField = new TextField(Integer.toString(gameWidth));
        widthField.setPromptText("宽度");
        applyFieldStyle(widthField);
        TextField heightField = new TextField(Integer.toString(gameHeight));
        heightField.setPromptText("高度");
        applyFieldStyle(heightField);
        HBox resolutionBox = new HBox(10, widthField, new Label("×"), heightField);
        resolutionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(widthField, Priority.ALWAYS);
        HBox.setHgrow(heightField, Priority.ALWAYS);

        CheckBox fullscreenField = new CheckBox("全屏启动");
        fullscreenField.setSelected(gameFullscreen);
        TextField serverField = new TextField(quickServer == null ? "" : quickServer);
        serverField.setPromptText("可选，例如 play.example.com:25565");
        applyFieldStyle(serverField);
        TextField processorField = new TextField(processorCount <= 0 ? "" : Integer.toString(processorCount));
        processorField.setPromptText("留空使用全部可用核心");
        applyFieldStyle(processorField);
        CheckBox closeAfterLaunchField = new CheckBox("游戏启动后隐藏启动器，退出后恢复");
        closeAfterLaunchField.setSelected(closeAfterLaunch);
        CheckBox showConsoleField = new CheckBox("启动后自动打开实时控制台");
        showConsoleField.setSelected(showGameConsole);
        VBox behaviorBox = new VBox(10, fullscreenField, closeAfterLaunchField, showConsoleField);

        CheckBox backupOnLaunchField = new CheckBox("每次启动游戏前自动备份存档");
        backupOnLaunchField.setSelected(backupOnLaunch);
        TextField backupKeepCountField = new TextField(Integer.toString(backupKeepCount));
        backupKeepCountField.setPromptText("保留份数（1-100）");
        backupKeepCountField.setMaxWidth(160);
        applyFieldStyle(backupKeepCountField);
        CheckBox backupIncludeModsField = new CheckBox("自动备份同时包含 mods");
        backupIncludeModsField.setSelected(backupIncludeMods);
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
        modReleaseChannelField.getSelectionModel().select(switch (controller.preferredModReleaseChannel()) {
            case RELEASE_ONLY -> 0;
            case RELEASE_AND_BETA -> 1;
            case ALL -> 2;
        });
        applyFieldStyle(modReleaseChannelField);

        PasswordField curseForgeApiKeyField = new PasswordField();
        String storedCurseForgeKey = settingsManager.getEncrypted(
                ECLConfig.KEY_CURSEFORGE_API_KEY);
        curseForgeApiKeyField.setText(storedCurseForgeKey == null ? "" : storedCurseForgeKey);
        curseForgeApiKeyField.setPromptText("可留空，或使用 CURSEFORGE_API_KEY 环境变量");
        applyFieldStyle(curseForgeApiKeyField);

        VBox dialogRoot = new VBox(18,
                createSurface("Java 路径", "指向 java.exe 或 JDK 根目录", javaBox),
                createSurface("游戏目录", "Minecraft 实例根目录", dirBox),
                createSurface("默认版本隔离", "推荐仅隔离带加载器的实例；整合包始终隔离",
                        isolationPolicyField),
                createSurface("当前实例运行目录",
                        selectedInstanceId == null || selectedInstanceId.isBlank()
                                ? "选择一个已安装版本后可配置实例级覆盖"
                                : "当前实例：" + selectedInstanceId,
                        instanceDirectoryBox),
                createSurface("最大内存", "留空使用自动分配（MB）", memoryField),
                createSurface("JVM 参数", "追加到默认启动参数之后", jvmField),
                createSurface("窗口分辨率", "窗口模式下的宽度和高度", resolutionBox),
                createSurface("直连服务器", "启动后直接连接，可留空", serverField),
                createSurface("处理器核心数", "通过 ActiveProcessorCount 限制游戏可见核心数", processorField),
                createSurface("启动行为", null, behaviorBox),
                createSurface("存档自动备份",
                        "备份位于 ECL 数据目录；失败只写入日志，不会阻止游戏启动",
                        backupBehaviorBox),
                createSurface("Modrinth 发布通道",
                        "控制默认版本、依赖版本和更新版本的稳定性范围",
                        modReleaseChannelField),
                createSurface("CurseForge API Key",
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
                setStatus("错误: Java 路径无效", "请选择一个可用的 java.exe 或 JDK 根目录后再保存。");
                return;
            }

            String configuredGameDir = dirField.getText().trim();
            if (configuredGameDir.isBlank()) {
                configuredGameDir = ECLConfig.getGameDir().getAbsolutePath();
            }

            int configuredMemoryMb;
            try {
                configuredMemoryMb = parseMemorySetting(memoryField.getText());
            } catch (IllegalArgumentException memoryError) {
                setStatus("错误: 内存格式无效", memoryError.getMessage());
                return;
            }
            int configuredWidth;
            int configuredHeight;
            int configuredProcessors;
            int configuredBackupKeepCount;
            try {
                configuredWidth = parseRangedInt(widthField.getText(), "窗口宽度", 320, 16_384);
                configuredHeight = parseRangedInt(heightField.getText(), "窗口高度", 240, 16_384);
                configuredProcessors = processorField.getText().isBlank() ? 0
                        : parseRangedInt(processorField.getText(), "核心数", 1,
                                Math.max(1, Runtime.getRuntime().availableProcessors()));
                configuredBackupKeepCount = parseRangedInt(
                        backupKeepCountField.getText(), "备份保留份数", 1, 100);
            } catch (IllegalArgumentException valueError) {
                setStatus("游戏参数无效", valueError.getMessage());
                return;
            }

            javaPath = configuredJava.isBlank() ? "" : JavaRuntimeUtil.resolveJavaExecutable(configuredJava);
            gameDir = resolveConfiguredGameRootDir(new File(configuredGameDir));
            gameDir.mkdirs();
            extraJvmArgs = jvmField.getText().trim();
            maxMemoryMb = configuredMemoryMb;
            gameWidth = configuredWidth;
            gameHeight = configuredHeight;
            gameFullscreen = fullscreenField.isSelected();
            quickServer = serverField.getText().trim();
            processorCount = configuredProcessors;
            closeAfterLaunch = closeAfterLaunchField.isSelected();
            showGameConsole = showConsoleField.isSelected();
            backupOnLaunch = backupOnLaunchField.isSelected();
            backupKeepCount = configuredBackupKeepCount;
            backupIncludeMods = backupIncludeModsField.isSelected();

            DefaultIsolationType configuredIsolationType = switch (
                    isolationPolicyField.getSelectionModel().getSelectedIndex()) {
                case 0 -> DefaultIsolationType.ALWAYS;
                case 2 -> DefaultIsolationType.NEVER;
                default -> DefaultIsolationType.MODDED;
            };

            settingsManager.set(ECLConfig.KEY_JAVA_PATH, javaPath);
            settingsManager.set(ECLConfig.KEY_GAME_DIR, gameDir.getAbsolutePath());
            settingsManager.set(ECLConfig.KEY_JVM_ARGS, extraJvmArgs);
            settingsManager.set(ECLConfig.KEY_MAX_MEMORY_MB, maxMemoryMb);
            settingsManager.set(ECLConfig.KEY_GAME_WIDTH, gameWidth);
            settingsManager.set(ECLConfig.KEY_GAME_HEIGHT, gameHeight);
            settingsManager.set(ECLConfig.KEY_GAME_FULLSCREEN, gameFullscreen);
            settingsManager.set(ECLConfig.KEY_QUICK_SERVER, quickServer);
            settingsManager.set(ECLConfig.KEY_PROCESSOR_COUNT, processorCount);
            settingsManager.set(ECLConfig.KEY_CLOSE_AFTER_LAUNCH, closeAfterLaunch);
            settingsManager.set(ECLConfig.KEY_SHOW_GAME_CONSOLE, showGameConsole);
            settingsManager.set(ECLConfig.KEY_BACKUP_ON_LAUNCH, backupOnLaunch);
            settingsManager.set(ECLConfig.KEY_BACKUP_KEEP_COUNT, backupKeepCount);
            settingsManager.set(ECLConfig.KEY_BACKUP_INCLUDE_MODS, backupIncludeMods);
            settingsManager.set(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE,
                    configuredIsolationType.name());
            ReleaseChannel modReleaseChannel = switch (
                    modReleaseChannelField.getSelectionModel().getSelectedIndex()) {
                case 0 -> ReleaseChannel.RELEASE_ONLY;
                case 2 -> ReleaseChannel.ALL;
                default -> ReleaseChannel.RELEASE_AND_BETA;
            };
            settingsManager.set(ECLConfig.KEY_MOD_RELEASE_CHANNEL, modReleaseChannel.name());
            settingsManager.setEncrypted(ECLConfig.KEY_CURSEFORGE_API_KEY,
                    curseForgeApiKeyField.getText().trim());
            if (selectedInstanceId != null && !selectedInstanceId.isBlank()) {
                try {
                    DefaultGameRepository repository = gameRepository();
                    switch (instanceDirectoryModeField.getSelectionModel().getSelectedIndex()) {
                        case 1 -> repository.setIsolated(selectedInstanceId);
                        case 2 -> {
                            String customDirectory = customInstanceDirectoryField.getText().trim();
                            if (customDirectory.isBlank()) {
                                setStatus("实例目录无效", "选择自定义目录时必须填写路径。");
                                return;
                            }
                            repository.setCustomRunDirectory(selectedInstanceId,
                                    Path.of(customDirectory));
                        }
                        default -> repository.inheritRunDirectoryPolicy(selectedInstanceId);
                    }
                } catch (IOException | RuntimeException directoryError) {
                    setStatus("实例目录保存失败", directoryError.getMessage());
                    return;
                }
            }
            if (!settingsManager.save()) {
                setStatus("保存失败", "无法写入 settings.json，请检查目录权限或查看日志。");
                return;
            }

            updateRuntimeSummary();
            setStatus("设置已保存", "新的运行环境、版本隔离与 Modrinth 发布通道已经生效。");
            dialog.close();
        });

        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().addAll("app-button", "ghost-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttonBar = new HBox(12, saveBtn, cancelBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        dialogRoot.getChildren().add(buttonBar);

        Scene scene = new Scene(createWheelScrollPane(dialogRoot), 760, 650);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        applyThemeToScene(scene, settingsManager.get(ECLConfig.KEY_THEME));
        dialog.show();
    }

    private int parseMemorySetting(String value) {
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

    private int parseRangedInt(String value, String label, int min, int max) {
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
            } catch (URISyntaxException uriError) {
                LOGGER.warn("Failed to open local folder {}", folder, uriError);
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
        controller.runAsync(threadName, action);
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

    private HBox createControlRow(String key, Node control) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("info-key");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, keyLabel, spacer, control);
        row.getStyleClass().add("info-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void configureLocalizedCombo(ComboBox<String> combo, Function<String, String> displayName) {
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : displayName.apply(item));
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : displayName.apply(item));
            }
        });
        applyFieldStyle(combo);
        combo.setPrefWidth(220);
    }

    private String languageDisplayName(String tag) {
        return switch (tag == null ? "" : tag) {
            case "zh-TW" -> Messages.get("language.zhTW");
            case "en" -> Messages.get("language.en");
            default -> Messages.get("language.zhCN");
        };
    }

    private String themeDisplayName(String theme) {
        return "LIGHT".equalsIgnoreCase(theme) ? Messages.get("theme.light") : Messages.get("theme.dark");
    }

    private String normalizeTheme(String theme) {
        return "LIGHT".equalsIgnoreCase(theme) ? "LIGHT" : "DARK";
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
        if (primaryStage != null && primaryStage.getScene() != null) {
            applyThemeToScene(primaryStage.getScene(), requestedTheme);
        }
        for (Window window : Window.getWindows()) {
            if (window != primaryStage && window.getScene() != null) {
                applyThemeToScene(window.getScene(), requestedTheme);
            }
        }
    }

    private void applyThemeToScene(Scene scene, String requestedTheme) {
        if (scene == null || scene.getRoot() == null) return;
        Node root = scene.getRoot();
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add("LIGHT".equals(normalizeTheme(requestedTheme)) ? "theme-light" : "theme-dark");
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
            case "server" -> ICON_SIGNAL;
            default -> ICON_GRASS_BLOCK;
        };
    }

    private String formatBytes(long bytes) {
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

    private boolean isCancellation(Throwable throwable) {
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

    private String cleanMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
