package com.ecl.runtime;

import com.ecl.ECLConfig;
import com.ecl.util.JavaRuntimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class DefaultJavaManager implements JavaManager {
    private final String configuredPath;

    public DefaultJavaManager(String configuredPath) {
        this.configuredPath = configuredPath == null ? "" : configuredPath;
    }

    @Override
    public List<JavaRuntimeInfo> detect() {
        Path managedRoot = new File(ECLConfig.getBaseDir(), "runtimes").toPath()
                .toAbsolutePath().normalize();
        return JavaRuntimeUtil.discoverJavaExecutables(configuredPath).stream()
                .map(File::new)
                .map(file -> new JavaRuntimeInfo(file.toPath().toAbsolutePath().normalize(),
                        JavaRuntimeUtil.detectJavaFeatureVersion(file),
                        System.getProperty("os.arch", "unknown"), "unknown", isJdk(file),
                        file.toPath().toAbsolutePath().normalize().startsWith(managedRoot)))
                .filter(runtime -> runtime.featureVersion() > 0)
                .sorted(Comparator.comparingInt(JavaRuntimeInfo::featureVersion)
                        .thenComparing(runtime -> runtime.executable().toString()))
                .toList();
    }

    @Override
    public Optional<JavaRuntimeInfo> select(int requiredFeatureVersion) {
        List<JavaRuntimeInfo> detected = detect();
        Optional<JavaRuntimeInfo> exact = detected.stream()
                .filter(runtime -> runtime.featureVersion() == requiredFeatureVersion)
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return detected.stream()
                .filter(runtime -> runtime.featureVersion() > requiredFeatureVersion)
                .min(Comparator.comparingInt(runtime -> runtime.featureVersion() - requiredFeatureVersion));
    }

    @Override
    public JavaRuntimeInfo requireOrInstall(int requiredFeatureVersion, Consumer<String> status,
                                            BiConsumer<Long, Long> progress) throws IOException {
        Optional<JavaRuntimeInfo> existing = select(requiredFeatureVersion);
        if (existing.isPresent()) return existing.get();
        String executable = JavaRuntimeUtil.resolveOrDownloadJavaExecutable(
                configuredPath, requiredFeatureVersion, status, progress);
        File file = new File(executable);
        return new JavaRuntimeInfo(file.toPath().toAbsolutePath().normalize(),
                JavaRuntimeUtil.detectJavaFeatureVersion(file),
                System.getProperty("os.arch", "unknown"), "managed", isJdk(file), true);
    }

    private static boolean isJdk(File javaExecutable) {
        File bin = javaExecutable.getParentFile();
        if (bin == null) return false;
        return new File(bin, "javac.exe").isFile();
    }
}
