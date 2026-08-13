package com.ecl.launch;

import com.ecl.event.EventBus;
import com.ecl.event.GameLifecycleEvent;
import com.ecl.game.VersionChainException;
import com.ecl.game.VersionMetadata;
import com.ecl.game.VersionRepository;
import com.ecl.util.JavaRuntimeUtil;

import java.io.IOException;
import java.nio.file.Path;
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
        return prepare(options, true);
    }

    /**
     * Resolve and assemble a command without changing launcher state. This never downloads Java
     * or extracts natives, so callers can safely use it for a dry-run preview.
     */
    public LaunchCommand preview(LaunchOptions options) throws LaunchException {
        return prepare(options, false);
    }

    private LaunchCommand prepare(LaunchOptions options, boolean allowWrites) throws LaunchException {
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
                NativeLibraryExtractor.extract(metadata, environment, versionId,
                        options.instanceDirectory());
            }
            return commandBuilder.build(options, metadata, javaExecutable);
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
        LaunchCommand command = prepare(options);
        ProcessBuilder builder = new ProcessBuilder(command.commandLine());
        if (command.workingDirectory() != null) {
            builder.directory(command.workingDirectory());
        }
        Map<String, String> processEnvironment = builder.environment();
        processEnvironment.putAll(command.environment());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException startFailure) {
            throw new LaunchException(LaunchException.Kind.PROCESS_CREATION,
                    "无法启动游戏进程: " + startFailure.getMessage(), startFailure);
        }

        Path workingDirectory = command.workingDirectory() == null
                ? null : command.workingDirectory().toPath();
        GameProcess gameProcess = new GameProcess(process, versionId, workingDirectory);
        publish(GameLifecycleEvent.Phase.STARTED, versionId, 0, workingDirectory);
        return gameProcess;
    }

    private void publish(GameLifecycleEvent.Phase phase, String versionId, int exitCode, Path directory) {
        if (eventBus != null) {
            eventBus.post(new GameLifecycleEvent(phase, versionId, exitCode, directory));
        }
    }
}
