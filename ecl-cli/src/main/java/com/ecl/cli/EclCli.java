package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthAccount;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.DefaultAccountService;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.OfflineAuth;
import com.ecl.auth.YggdrasilAuth;
import com.ecl.config.SettingsManager;
import com.ecl.diagnostic.DiagnosticBundleService;
import com.ecl.exception.AuthException;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import com.ecl.launch.DefaultLauncher;
import com.ecl.launch.GameProcess;
import com.ecl.launch.LaunchCommand;
import com.ecl.launch.LaunchEnvironment;
import com.ecl.launch.LaunchOptions;
import com.ecl.pack.DefaultPackService;
import com.ecl.pack.PackFormat;
import com.ecl.pack.PackImportResult;
import com.ecl.pack.PackPreview;
import com.ecl.modrinth.pack.MrpackInstaller;
import com.ecl.runtime.DefaultJavaManager;
import com.ecl.runtime.JavaRuntimeInfo;
import com.ecl.util.JavaRuntimeUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "ecl",
        mixinStandardHelpOptions = true,
        version = "ECL 1.0.0",
        description = "ECL headless command line interface.",
        subcommands = {
                EclCli.DoctorCommand.class,
                EclCli.JavaCommand.class,
                EclCli.VersionCommand.class,
                EclCli.AccountCommand.class,
                EclCli.LaunchCommandLine.class,
                EclCli.ModCommand.class,
                EclCli.PackCommand.class,
                EclCli.DiagnosticsCommand.class,
                EclCli.SettingsCommand.class
        }
)
public final class EclCli implements Runnable {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Option(names = "--json", scope = ScopeType.INHERIT, description = "Write machine-readable JSON.")
    private boolean json;

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    public static int execute(String... args) {
        return commandLine().execute(args);
    }

    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new EclCli());
        commandLine.setExecutionExceptionHandler((failure, failedCommand, parseResult) -> {
            EclCli root = (EclCli) failedCommand.getCommandSpec().root().userObject();
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            if (root.json) {
                failedCommand.getOut().println(JSON.writeValueAsString(Map.of(
                        "status", "error",
                        "error", message,
                        "type", failure.getClass().getSimpleName())));
            } else {
                failedCommand.getErr().println("Error: " + message);
            }
            return failedCommand.getCommandSpec().exitCodeOnExecutionException();
        });
        return commandLine;
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    private void print(Object value) {
        if (!json) {
            if (value instanceof Iterable<?> iterable) {
                iterable.forEach(System.out::println);
            } else {
                System.out.println(value);
            }
            return;
        }
        try {
            System.out.println(JSON.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "Unable to encode JSON", exception);
        }
    }

    private static EclCli root(CommandLine.Model.CommandSpec spec) {
        return (EclCli) spec.root().userObject();
    }

    private static SettingsManager loadSettings() {
        SettingsManager settings = new SettingsManager();
        settings.load();
        return settings;
    }

    private static File configuredGameRoot(SettingsManager settings) {
        String configured = settings.get(ECLConfig.KEY_GAME_DIR);
        return configured == null || configured.isBlank() ? ECLConfig.getGameDir() : new File(configured);
    }

    @Command(name = "doctor", description = "Inspect the Java runtime and ECL data directories.")
    static final class DoctorCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private EclCli parent;

        @Override
        public Integer call() {
            ECLConfig.ensureDirs();
            File baseDir = ECLConfig.getBaseDir();
            String javaExecutable = JavaRuntimeUtil.detectSystemJavaExecutable();
            Map<String, Object> result = new LinkedHashMap<>();
            boolean writable = Files.isWritable(baseDir.toPath());
            boolean healthy = javaExecutable != null && writable;
            result.put("status", healthy ? "ok" : "warning");
            result.put("java", javaExecutable == null ? "not-found" : javaExecutable);
            result.put("dataDirectory", baseDir.getAbsolutePath());
            result.put("dataDirectoryWritable", writable);
            result.put("os", System.getProperty("os.name"));
            result.put("architecture", System.getProperty("os.arch"));
            parent.print(result);
            return healthy ? 0 : 2;
        }
    }

    @Command(name = "java", description = "Inspect Java runtimes.",
            subcommands = {JavaDetectCommand.class, JavaListCommand.class})
    static final class JavaCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "detect", description = "Detect the best available Java executable.")
    static final class JavaDetectCommand implements Callable<Integer> {
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            EclCli root = (EclCli) spec.root().userObject();
            String executable = JavaRuntimeUtil.detectSystemJavaExecutable();
            root.print(Map.of(
                    "found", executable != null,
                    "executable", executable == null ? "" : executable
            ));
            return executable == null ? 2 : 0;
        }
    }

    @Command(name = "list", description = "List every detected Java runtime.")
    static final class JavaListCommand implements Callable<Integer> {
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            EclCli root = root(spec);
            SettingsManager settings = loadSettings();
            List<JavaRuntimeInfo> runtimes = new DefaultJavaManager(
                    settings.get(ECLConfig.KEY_JAVA_PATH)).detect();
            root.print(root.json ? Map.of("runtimes", runtimes) : runtimes);
            return runtimes.isEmpty() ? 2 : 0;
        }
    }

    @Command(name = "version", description = "Manage installed game versions.",
            subcommands = {VersionListCommand.class, VersionInspectCommand.class})
    static final class VersionCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "list", description = "List locally installed game versions.")
    static final class VersionListCommand implements Callable<Integer> {
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            File[] directories = ECLConfig.getVersionsDir().listFiles(File::isDirectory);
            List<String> versions = directories == null
                    ? List.of()
                    : Arrays.stream(directories).map(File::getName).sorted().toList();
            EclCli root = (EclCli) spec.root().userObject();
            root.print(root.json ? Map.of("versions", versions) : versions);
            return 0;
        }
    }

    @Command(name = "inspect", description = "Resolve inherited metadata for an installed version.")
    static final class VersionInspectCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Installed version id")
        private String versionId;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            VersionMetadata metadata = new VersionRepository(ECLConfig.getVersionsDir()).resolve(versionId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", metadata.id());
            result.put("minecraftVersion", metadata.minecraftVersion());
            result.put("loader", metadata.modLoader() == null ? "vanilla" : metadata.modLoader());
            result.put("mainClass", metadata.mainClass());
            result.put("javaVersion", metadata.javaMajorVersion());
            result.put("libraries", metadata.libraries().size());
            root(spec).print(result);
            return 0;
        }
    }

    @Command(name = "account", description = "Manage launcher accounts.",
            subcommands = {AccountListCommand.class, AccountAddOfflineCommand.class,
                    AccountRemoveCommand.class, AccountDefaultCommand.class})
    static final class AccountCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "list", description = "List accounts without exposing credentials.")
    static final class AccountListCommand implements Callable<Integer> {
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            List<AuthAccount> accounts = new DefaultAccountService().list().stream()
                    .map(AuthAccount::withoutSecrets).toList();
            root(spec).print(root(spec).json ? Map.of("accounts", accounts) : accounts);
            return 0;
        }
    }

    @Command(name = "add-offline", description = "Add an offline account.")
    static final class AccountAddOfflineCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Player name")
        private String username;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            AuthAccount account = new DefaultAccountService().addOffline(username).withoutSecrets();
            root(spec).print(account);
            return 0;
        }
    }

    @Command(name = "remove", description = "Remove an account by identity.")
    static final class AccountRemoveCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "TYPE:uuid identity from account list")
        private String identity;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            boolean removed = new DefaultAccountService().remove(identity);
            root(spec).print(Map.of("identity", identity, "removed", removed));
            return removed ? 0 : 2;
        }
    }

    @Command(name = "default", description = "Select the default account.")
    static final class AccountDefaultCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "TYPE:uuid identity from account list")
        private String identity;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            new DefaultAccountService().setDefault(identity);
            root(spec).print(Map.of("identity", identity, "default", true));
            return 0;
        }
    }

    @Command(name = "launch", description = "Prepare or start an installed version.")
    static final class LaunchCommandLine implements Callable<Integer> {
        @Parameters(index = "0", description = "Installed version id")
        private String versionId;
        @Option(names = "--account", description = "Account identity; defaults to the selected account")
        private String accountIdentity;
        @Option(names = "--username", defaultValue = "Player", description = "Offline fallback player name")
        private String username;
        @Option(names = "--memory", description = "Maximum heap in MiB")
        private Integer memory;
        @Option(names = "--dry-run", description = "Print the full command without starting Minecraft")
        private boolean dryRun;
        @Option(names = "--show-secrets", description = "DANGER: include credentials in dry-run output")
        private boolean showSecrets;
        @Option(names = "--wait", description = "Wait for the game process and return its exit code")
        private boolean wait;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            SettingsManager settings = loadSettings();
            File gameRoot = configuredGameRoot(settings);
            DefaultGameRepository games = new DefaultGameRepository(
                    ECLConfig.getVersionsDir().toPath(), gameRoot.toPath(),
                    DefaultIsolationType.parse(settings.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
            Path instanceRoot = games.instanceRoot(versionId);
            Path instance = games.runDirectory(versionId);
            AuthProvider auth = selectAuth(accountIdentity, username, !dryRun);
            LaunchEnvironment environment = new LaunchEnvironment(ECLConfig.getVersionsDir(),
                    ECLConfig.getLibrariesDir(), ECLConfig.getAssetsDir(),
                    ECLConfig.LAUNCHER_NAME, ECLConfig.LAUNCHER_VERSION);
            int selectedMemory = selectMemory(memory, settings.get(ECLConfig.KEY_MAX_MEMORY_MB));
            LaunchOptions options = LaunchOptions.builder()
                    .versionId(versionId)
                    .auth(auth)
                    .gameDirectory(instance.toFile())
                    .instanceDirectory(instanceRoot.toFile())
                    .environment(environment)
                    .maxMemoryMb(selectedMemory)
                    .javaExecutablePath(settings.get(ECLConfig.KEY_JAVA_PATH))
                    .gameResolution(settings.get(ECLConfig.KEY_GAME_WIDTH), settings.get(ECLConfig.KEY_GAME_HEIGHT))
                    .fullscreen(settings.get(ECLConfig.KEY_GAME_FULLSCREEN))
                    .serverAddress(settings.get(ECLConfig.KEY_QUICK_SERVER))
                    .processorCount(settings.get(ECLConfig.KEY_PROCESSOR_COUNT))
                    .build();
            DefaultLauncher launcher = new DefaultLauncher(
                    new VersionRepository(ECLConfig.getVersionsDir()), environment);
            if (dryRun) {
                LaunchCommand command = launcher.preview(options);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("version", versionId);
                result.put("workingDirectory", command.workingDirectory().getAbsolutePath());
                result.put("command", showSecrets ? command.commandLine()
                        : redactCommand(command.commandLine(), auth));
                result.put("environment", showSecrets ? command.environment()
                        : redactEnvironment(command.environment(), auth));
                root(spec).print(result);
                return 0;
            }
            GameProcess process = launcher.launch(options);
            EclCli root = root(spec);
            if (!root.json) process.attachOutputListener(System.out::println);
            root.print(Map.of("version", versionId, "pid", process.process().pid(), "started", true));
            if (!wait) return 0;
            process.waitForExit();
            return process.exitCode();
        }

        private static AuthProvider selectAuth(String identity, String fallbackName,
                                               boolean authenticate) throws IOException {
            DefaultAccountService accounts = new DefaultAccountService();
            AuthAccount account = identity == null || identity.isBlank()
                    ? accounts.defaultAccount().orElse(null)
                    : accounts.list().stream().filter(item -> item.identity().equalsIgnoreCase(identity))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown account: " + identity));
            if (account == null || account.type() == com.ecl.auth.AuthType.OFFLINE) {
                return account == null ? new OfflineAuth(fallbackName) : accounts.createProvider(account);
            }
            if (!authenticate) return accounts.createProvider(account);
            if (account.type() == com.ecl.auth.AuthType.MICROSOFT) {
                MicrosoftAuth provider = new MicrosoftAuth(new MicrosoftAuth.CachedSession(
                        account.refreshToken(), account.accessToken(), account.tokenExpiry(),
                        account.username(), account.uuid()), new MicrosoftAuth.LoginListener() {
                    @Override
                    public void onDeviceCode(MicrosoftAuth.DeviceCode code) {
                        System.err.println("Microsoft authorization: " + code.getVerificationUri()
                                + " code " + code.getUserCode());
                    }

                    @Override
                    public void onStatus(String message) {
                        System.err.println(message);
                    }
                });
                provider.login();
                MicrosoftAuth.CachedSession session = provider.getCachedSession();
                accounts.save(new AuthAccount(account.type(), session.uuid(), session.username(),
                        session.username(), session.accessToken(), session.refreshToken(),
                        session.accessTokenExpiresAt(), account.authServerUrl(), account.defaultAccount()));
                return provider;
            }
            YggdrasilAuth provider = (YggdrasilAuth) accounts.createProvider(account);
            if (!provider.validate()) {
                throw new AuthException("Saved Yggdrasil session is invalid; log in again before launch");
            }
            provider.refresh();
            accounts.save(new AuthAccount(account.type(), provider.getUUID(), provider.getUsername(),
                    provider.getUsername(), provider.getAccessToken(), provider.getClientToken(),
                    account.tokenExpiry(), account.authServerUrl(), account.defaultAccount()));
            return provider;
        }

        static int selectMemory(Integer requested, int configured) {
            return requested != null && requested > 0 ? requested
                    : configured > 0 ? configured : ECLConfig.calculateAutoMemoryMb();
        }

        static List<String> redactCommand(List<String> command, AuthProvider auth) {
            List<String> redacted = new ArrayList<>(command.size());
            Set<String> sensitiveOptions = Set.of("--accesstoken", "--access-token", "--session");
            boolean redactNext = false;
            for (String argument : command) {
                String lower = argument.toLowerCase(java.util.Locale.ROOT);
                if (redactNext) {
                    redacted.add("<redacted>");
                    redactNext = false;
                } else if (sensitiveOptions.contains(lower)) {
                    redacted.add(argument);
                    redactNext = true;
                } else if (sensitiveOptions.stream().anyMatch(option -> lower.startsWith(option + "="))) {
                    redacted.add(argument.substring(0, argument.indexOf('=') + 1) + "<redacted>");
                } else {
                    redacted.add(redactKnownSecret(argument, auth));
                }
            }
            return List.copyOf(redacted);
        }

        static Map<String, String> redactEnvironment(Map<String, String> environment,
                                                     AuthProvider auth) {
            Map<String, String> redacted = new LinkedHashMap<>();
            environment.forEach((key, value) -> {
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                redacted.put(key, lower.contains("token") || lower.contains("secret")
                        || lower.contains("password") || lower.contains("session")
                        ? "<redacted>" : redactKnownSecret(value, auth));
            });
            return Map.copyOf(redacted);
        }

        private static String redactKnownSecret(String value, AuthProvider auth) {
            String token = auth.getAccessToken();
            return token != null && !token.isBlank() && value != null && value.contains(token)
                    ? value.replace(token, "<redacted>") : value;
        }
    }

    @Command(name = "mod", description = "Manage local instance mods.",
            subcommands = {ModListCommand.class, ModEnableCommand.class, ModDisableCommand.class})
    static final class ModCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    abstract static class InstanceModCommand {
        @Parameters(index = "0", description = "Installed version id")
        protected String versionId;

        protected Path modsDirectory() {
            SettingsManager settings = loadSettings();
            DefaultGameRepository games = new DefaultGameRepository(ECLConfig.getVersionsDir().toPath(),
                    configuredGameRoot(settings).toPath(),
                    DefaultIsolationType.parse(settings.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
            try {
                return games.runDirectory(versionId).resolve("mods");
            } catch (IOException error) {
                throw new IllegalStateException("无法解析实例运行目录: " + versionId, error);
            }
        }
    }

    @Command(name = "list", description = "List enabled and disabled mod files.")
    static final class ModListCommand extends InstanceModCommand implements Callable<Integer> {
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            Path mods = modsDirectory();
            List<Map<String, Object>> entries = new ArrayList<>();
            if (Files.isDirectory(mods)) {
                try (var stream = Files.list(mods)) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".jar")
                                    || path.getFileName().toString().endsWith(".jar.disabled"))
                            .sorted()
                            .forEach(path -> entries.add(Map.of(
                                    "file", path.getFileName().toString(),
                                    "enabled", !path.getFileName().toString().endsWith(".disabled"))));
                }
            }
            root(spec).print(root(spec).json ? Map.of("version", versionId, "mods", entries) : entries);
            return 0;
        }
    }

    @Command(name = "enable", description = "Enable a .jar.disabled mod file.")
    static final class ModEnableCommand extends InstanceModCommand implements Callable<Integer> {
        @Parameters(index = "1", description = "Mod filename")
        private String filename;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            Path source = safeModPath(modsDirectory(), filename.endsWith(".disabled")
                    ? filename : filename + ".disabled");
            String enabledName = source.getFileName().toString().replaceFirst("\\.disabled$", "");
            Path target = safeModPath(modsDirectory(), enabledName);
            moveModWithoutOverwrite(source, target);
            root(spec).print(Map.of("version", versionId, "file", target.getFileName().toString(), "enabled", true));
            return 0;
        }
    }

    @Command(name = "disable", description = "Disable a local mod without deleting it.")
    static final class ModDisableCommand extends InstanceModCommand implements Callable<Integer> {
        @Parameters(index = "1", description = "Mod filename")
        private String filename;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            Path source = safeModPath(modsDirectory(), filename);
            Path target = safeModPath(modsDirectory(), source.getFileName() + ".disabled");
            moveModWithoutOverwrite(source, target);
            root(spec).print(Map.of("version", versionId, "file", target.getFileName().toString(), "enabled", false));
            return 0;
        }
    }

    private static Path safeModPath(Path directory, String filename) {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("Mod filename is required");
        Path root = directory.toAbsolutePath().normalize();
        Path resolved = root.resolve(filename).normalize();
        if (!resolved.getParent().equals(root)) throw new IllegalArgumentException("Invalid mod filename");
        return resolved;
    }

    static void moveModWithoutOverwrite(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("Refusing to overwrite existing mod: " + target.getFileName());
        }
        Files.move(source, target);
    }

    @Command(name = "pack", description = "Preview, import, and export instance packs.",
            subcommands = {PackPreviewCommand.class, PackImportCommand.class, PackExportCommand.class})
    static final class PackCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "preview", description = "Inspect a pack without importing it.")
    static final class PackPreviewCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Pack archive")
        private Path archive;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            PackPreview preview = new DefaultPackService().preview(archive);
            root(spec).print(preview);
            return 0;
        }
    }

    @Command(name = "import", description = "Import a pack transactionally.")
    static final class PackImportCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Pack archive")
        private Path archive;
        @Option(names = "--name", description = "Preferred instance name")
        private String name;
        @Option(names = "--instances", description = "Instances root directory")
        private Path instancesRoot;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            File gameRoot = configuredGameRoot(loadSettings());
            if (instancesRoot == null
                    && new DefaultPackService().preview(archive).format() == PackFormat.MRPACK) {
                MrpackInstaller.InstallResult installed = new MrpackInstaller().install(
                        archive.toFile(), gameRoot, name, System.err::println);
                PackImportResult result = new PackImportResult(PackFormat.MRPACK,
                        installed.profileId(), installed.instanceDirectory(), installed.downloadedFiles());
                root(spec).print(result);
                return 0;
            }
            Path rootDirectory = instancesRoot == null
                    ? gameRoot.toPath().resolve("versions") : instancesRoot;
            PackImportResult result = new DefaultPackService().importPack(archive, rootDirectory, name);
            root(spec).print(result);
            return 0;
        }
    }

    @Command(name = "export", description = "Export an instance to ECL, MultiMC, CurseForge, or MRPACK format.")
    static final class PackExportCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Instance directory")
        private Path instance;
        @Parameters(index = "1", description = "Output archive")
        private Path output;
        @Option(names = "--minecraft", required = true, description = "Minecraft version")
        private String minecraftVersion;
        @Option(names = "--format", defaultValue = "ECL",
                description = "ECL, MULTIMC, CURSEFORGE, or MRPACK")
        private PackFormat format;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            Path exported = new DefaultPackService().exportInstance(instance, minecraftVersion, format, output);
            root(spec).print(Map.of("format", format, "output", exported.toAbsolutePath().toString()));
            return 0;
        }
    }

    @Command(name = "diagnostics", description = "Export a redacted diagnostic ZIP.")
    static final class DiagnosticsCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Output ZIP")
        private Path output;
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            SettingsManager settings = loadSettings();
            Path result = new DiagnosticBundleService().export(output, ECLConfig.getBaseDir().toPath(),
                    configuredGameRoot(settings).toPath());
            root(spec).print(Map.of("output", result.toString(), "redacted", true));
            return 0;
        }
    }

    @Command(
            name = "settings",
            description = "Read or write launcher settings.",
            subcommands = {SettingsGetCommand.class, SettingsSetCommand.class}
    )
    static final class SettingsCommand implements Runnable {
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "get", description = "Read a setting.")
    static final class SettingsGetCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Setting key")
        private String key;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            SettingsManager settings = new SettingsManager();
            settings.load();
            String value = settings.getString(key, "");
            EclCli root = (EclCli) spec.root().userObject();
            root.print(root.json ? Map.of("key", key, "value", value) : value);
            return 0;
        }
    }

    @Command(name = "set", description = "Write a string setting.")
    static final class SettingsSetCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Setting key")
        private String key;

        @Parameters(index = "1", description = "Setting value")
        private String value;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            SettingsManager settings = new SettingsManager();
            settings.load();
            settings.setString(key, value);
            if (!settings.save()) {
                throw new IOException("Unable to save settings");
            }
            EclCli root = (EclCli) spec.root().userObject();
            root.print(root.json ? Map.of("key", key, "value", value, "saved", true) : "saved");
            return 0;
        }
    }
}
