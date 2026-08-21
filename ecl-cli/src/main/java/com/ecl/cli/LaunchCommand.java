package com.ecl.cli;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.AuthType;
import com.ecl.auth.OfflineSkin;
import com.ecl.auth.OfflineSkinStore;
import com.ecl.config.SettingsManager;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import com.ecl.game.VersionRepository;
import com.ecl.launch.DefaultLauncher;
import com.ecl.launch.GameProcess;
import com.ecl.launch.LaunchEnvironment;
import com.ecl.launch.LaunchOptions;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "launch", description = "Prepare or start an installed version.")
final class LaunchCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Installed version id")
    private String versionId;

    @Option(names = "--account",
            description = "Account identity; defaults to the selected account")
    private String accountIdentity;

    @Option(names = "--username", defaultValue = "Player",
            description = "Offline fallback player name")
    private String username;

    @Option(names = "--memory", description = "Maximum heap in MiB")
    private Integer memory;

    @Option(names = "--dry-run",
            description = "Print the full command without starting Minecraft")
    private boolean dryRun;

    @Option(names = "--show-secrets",
            description = "DANGER: include credentials in dry-run output")
    private boolean showSecrets;

    @Option(names = "--wait",
            description = "Wait for the game process and return its exit code")
    private boolean wait;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        SettingsManager settings = CliEnvironment.loadSettings();
        File gameRoot = CliEnvironment.configuredGameRoot(settings);
        DefaultGameRepository games = new DefaultGameRepository(
                ECLConfig.getVersionsDir().toPath(), gameRoot.toPath(),
                DefaultIsolationType.parse(
                        settings.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE)));
        Path instanceRoot = games.instanceRoot(versionId);
        Path instance = games.runDirectory(versionId);
        AuthProvider auth = CliAccountAuthenticator.select(
                accountIdentity, username, !dryRun);
        OfflineSkin offlineSkin = auth.getType() == AuthType.OFFLINE
                ? new OfflineSkinStore()
                .find(OfflineSkinStore.identityForOffline(auth.getUsername())).orElse(null)
                : null;
        LaunchEnvironment environment = new LaunchEnvironment(
                ECLConfig.getVersionsDir(), ECLConfig.getLibrariesDir(),
                ECLConfig.getAssetsDir(), ECLConfig.LAUNCHER_NAME, ECLConfig.LAUNCHER_VERSION);
        LaunchOptions options = LaunchOptions.builder()
                .versionId(versionId)
                .auth(auth)
                .offlineSkin(offlineSkin)
                .gameDirectory(instance.toFile())
                .instanceDirectory(instanceRoot.toFile())
                .environment(environment)
                .maxMemoryMb(selectMemory(memory,
                        settings.get(ECLConfig.KEY_MAX_MEMORY_MB)))
                .javaExecutablePath(settings.get(ECLConfig.KEY_JAVA_PATH))
                .gameResolution(settings.get(ECLConfig.KEY_GAME_WIDTH),
                        settings.get(ECLConfig.KEY_GAME_HEIGHT))
                .fullscreen(settings.get(ECLConfig.KEY_GAME_FULLSCREEN))
                .serverAddress(settings.get(ECLConfig.KEY_QUICK_SERVER))
                .processorCount(settings.get(ECLConfig.KEY_PROCESSOR_COUNT))
                .build();
        DefaultLauncher launcher = new DefaultLauncher(
                new VersionRepository(ECLConfig.getVersionsDir()), environment);
        if (dryRun) {
            return preview(launcher, options, auth);
        }
        GameProcess process = launcher.launch(options);
        EclCli root = EclCli.root(spec);
        if (!root.jsonOutput()) {
            process.attachOutputListener(System.out::println);
        }
        root.print(Map.of("version", versionId, "pid", process.process().pid(),
                "started", true));
        if (!wait) {
            return 0;
        }
        process.waitForExit();
        return process.exitCode();
    }

    private int preview(DefaultLauncher launcher, LaunchOptions options, AuthProvider auth)
            throws Exception {
        com.ecl.launch.LaunchCommand command = launcher.preview(options);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", versionId);
        result.put("workingDirectory", command.workingDirectory().getAbsolutePath());
        result.put("command", showSecrets ? command.commandLine()
                : redactCommand(command.commandLine(), auth));
        result.put("environment", showSecrets ? command.environment()
                : redactEnvironment(command.environment(), auth));
        EclCli.root(spec).print(result);
        return 0;
    }

    static int selectMemory(Integer requested, int configured) {
        return requested != null && requested > 0 ? requested
                : configured > 0 ? configured : ECLConfig.calculateAutoMemoryMb();
    }

    static List<String> redactCommand(List<String> command, AuthProvider auth) {
        return LaunchCommandSanitizer.redactCommand(command, auth);
    }

    static Map<String, String> redactEnvironment(
            Map<String, String> environment, AuthProvider auth) {
        return LaunchCommandSanitizer.redactEnvironment(environment, auth);
    }
}
