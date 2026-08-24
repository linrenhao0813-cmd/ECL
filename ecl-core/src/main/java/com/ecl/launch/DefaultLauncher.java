package com.ecl.launch;

import com.ecl.auth.offline.OfflineSkinInjector;
import com.ecl.event.EventBus;
import com.ecl.event.GameLifecycleEvent;
import com.ecl.game.VersionChainException;
import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import com.ecl.util.JavaRuntimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standard {@link Launcher} implementation: resolves the version, selects a Java runtime,
 * stages native libraries, assembles and starts the process.
 *
 * <p>Kept intentionally small — every real responsibility delegates to a single-purpose component
 * (version model, command builder, native extractor) so the sequence stays visible and each step is
 * testable on its own.</p>
 */
public final class DefaultLauncher implements Launcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLauncher.class);

    private final VersionRepository repository;
    private final LaunchEnvironment environment;
    private final LaunchCommandBuilder commandBuilder;
    private final EventBus eventBus;

    public DefaultLauncher(VersionRepository repository, LaunchEnvironment environment) {
        this(repository, environment, null);
    }

    public DefaultLauncher(VersionRepository repository, LaunchEnvironment environment, EventBus eventBus) {
        this(repository, environment, new LaunchCommandBuilder(), eventBus);
    }

    DefaultLauncher(VersionRepository repository, LaunchEnvironment environment,
                    LaunchCommandBuilder commandBuilder, EventBus eventBus) {
        this.repository = repository;
        this.environment = environment;
        this.commandBuilder = commandBuilder;
        this.eventBus = eventBus;
    }

    @Override
    public LaunchCommand prepare(LaunchOptions options) throws LaunchException {
        return prepare(options, true, List.of());
    }

    /**
     * Resolve and assemble a command without changing launcher state. This never downloads Java
     * or extracts natives, so callers can safely use it for a dry-run preview.
     */
    public LaunchCommand preview(LaunchOptions options) throws LaunchException {
        return prepare(options, false, List.of());
    }

    private LaunchCommand prepare(LaunchOptions options, boolean allowWrites,
                                  List<String> extraJvmArgs) throws LaunchException {
        String versionId = options.versionId();
        try {
            VersionMetadata metadata = repository.resolve(versionId);

            int requiredJava = JavaVersionRequirement.forMetadata(metadata);
            String javaExecutable;
            try {
                javaExecutable = allowWrites
                        ? JavaRuntimeUtil.resolveOrDownloadJavaExecutable(
                                options.javaExecutablePath(), requiredJava,
                                message -> LOGGER.info("{}", message),
                                (downloaded, total) -> { })
                        : JavaRuntimeUtil.resolveJavaExecutable(
                                options.javaExecutablePath(), requiredJava);
            } catch (IOException javaFailure) {
                throw new LaunchException(LaunchException.Kind.JAVA_UNAVAILABLE,
                        "没有可用的 Java " + requiredJava + " 运行时: " + javaFailure.getMessage(),
                        javaFailure);
            }

            if (allowWrites) {
                NativeLibraryExtractor.extract(metadata, options);
            }
            return commandBuilder.build(options, metadata, javaExecutable, extraJvmArgs);
        } catch (LaunchException alreadyClassified) {
            throw alreadyClassified;
        } catch (VersionChainException chainBroken) {
            throw new LaunchException(LaunchException.Kind.VERSION_INVALID,
                    "版本元数据不可用: " + chainBroken.getMessage(), chainBroken);
        } catch (IOException ioFailure) {
            throw new LaunchException(LaunchException.Kind.UNKNOWN, ioFailure.getMessage(), ioFailure);
        }
    }

    @Override
    public GameProcess launch(LaunchOptions options) throws LaunchException {
        String versionId = options.versionId();
        OfflineSkinInjector.Injection skinInjection = null;
        LaunchCommand command;
        try {
            skinInjection = OfflineSkinInjector.prepare(options.auth(), options.offlineSkin());
            command = prepare(options, true, skinInjection.jvmArgs());
        } catch (LaunchException prepareFailure) {
            if (skinInjection != null) {
                skinInjection.close();
            }
            throw prepareFailure;
        } catch (IOException skinFailure) {
            if (skinInjection != null) {
                skinInjection.close();
            }
            throw new LaunchException(LaunchException.Kind.UNKNOWN,
                    "无法准备离线皮肤服务: " + skinFailure.getMessage(), skinFailure);
        }
        ProcessBuilder builder = new ProcessBuilder(command.commandLine());
        if (command.workingDirectory() != null) {
            builder.directory(command.workingDirectory());
        }
        Map<String, String> processEnvironment = builder.environment();
        processEnvironment.putAll(command.environment());
        builder.redirectErrorStream(true);
        File outputFile = options.processOutputFile();
        if (outputFile != null) {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.isDirectory()
                    && !parent.mkdirs() && !parent.isDirectory()) {
                skinInjection.close();
                throw new LaunchException(LaunchException.Kind.PROCESS_CREATION,
                        "无法创建游戏日志目录: " + parent);
            }
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException startFailure) {
            skinInjection.close();
            throw new LaunchException(LaunchException.Kind.PROCESS_CREATION,
                    "无法启动游戏进程: " + startFailure.getMessage(), startFailure);
        }
        skinInjection.closeWhen(process);

        Path workingDirectory = command.workingDirectory() == null
                ? null : command.workingDirectory().toPath();
        GameProcess gameProcess;
        try {
            gameProcess = new GameProcess(process, versionId, workingDirectory, outputFile);
        } catch (IOException outputFailure) {
            process.destroyForcibly();
            skinInjection.close();
            throw new LaunchException(LaunchException.Kind.PROCESS_CREATION,
                    "无法打开游戏日志文件: " + outputFailure.getMessage(), outputFailure);
        }
        publish(GameLifecycleEvent.Phase.STARTED, versionId, 0, workingDirectory);
        gameProcess.whenExited().thenAccept(exited -> publish(
                GameLifecycleEvent.Phase.EXITED, versionId, exited.exitCode(), workingDirectory));
        return gameProcess;
    }

    private void publish(GameLifecycleEvent.Phase phase, String versionId, int exitCode, Path directory) {
        if (eventBus != null) {
            eventBus.post(new GameLifecycleEvent(phase, versionId, exitCode, directory));
        }
    }
}
