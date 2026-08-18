package com.ecl.util;

import com.ecl.ECLConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaRuntimeUtil {
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("version \"([^\"]+)\"");

    private JavaRuntimeUtil() {
    }

    public static String detectSystemJavaExecutable() {
        return resolveJavaExecutable(null);
    }

    public static String resolveJavaExecutable(String configuredPath) {
        File configured = resolveJavaCandidate(configuredPath);
        if (configured != null) {
            return configured.getAbsolutePath();
        }

        File fromJavaHome = resolveJavaCandidate(System.getProperty("java.home"));
        if (fromJavaHome != null) {
            return fromJavaHome.getAbsolutePath();
        }

        File fromEnv = resolveJavaCandidate(System.getenv("JAVA_HOME"));
        if (fromEnv != null) {
            return fromEnv.getAbsolutePath();
        }

        File installed = findInstalledJava();
        if (installed != null) {
            return installed.getAbsolutePath();
        }

        return executableName();
    }

    public static String resolveJavaExecutable(String configuredPath, int requiredMajorVersion) throws IOException {
        if (requiredMajorVersion <= 0) {
            return resolveJavaExecutable(configuredPath);
        }

        Set<File> candidates = new LinkedHashSet<>();
        addCandidate(candidates, configuredPath);
        addCandidate(candidates, System.getProperty("java.home"));
        addCandidate(candidates, System.getenv("JAVA_HOME"));
        candidates.addAll(findInstalledJavaCandidates());

        // Minecraft metadata normally names the feature version it was built for. Prefer an
        // exact match before considering a newer runtime: newer Java releases can be present on
        // the machine but still be rejected by a loader or by native libraries.
        for (File candidate : candidates) {
            if (detectJavaFeatureVersion(candidate) == requiredMajorVersion) {
                return candidate.getAbsolutePath();
            }
        }

        File bestKnown = null;
        int bestKnownVersion = -1;
        for (File candidate : candidates) {
            int featureVersion = detectJavaFeatureVersion(candidate);
            if (featureVersion >= requiredMajorVersion) {
                return candidate.getAbsolutePath();
            }
            if (featureVersion > bestKnownVersion) {
                bestKnown = candidate;
                bestKnownVersion = featureVersion;
            }
        }

        if (bestKnown != null && bestKnownVersion > 0) {
            throw new IOException("当前 Minecraft 版本需要 Java " + requiredMajorVersion
                    + " 或更高版本，但当前可用 Java 最高为 " + bestKnownVersion
                    + ": " + bestKnown.getAbsolutePath());
        }

        String fallback = resolveJavaExecutable(configuredPath);
        if (!executableName().equals(fallback)) {
            return fallback;
        }

        throw new IOException("当前 Minecraft 版本需要 Java " + requiredMajorVersion
                + " 或更高版本，但没有找到可用的 Java 运行时。");
    }

    public static String resolveOrDownloadJavaExecutable(
            String configuredPath,
            int requiredMajorVersion,
            Consumer<String> status,
            BiConsumer<Long, Long> progress
    ) throws IOException {
        File explicitlyConfigured = resolveJavaCandidate(configuredPath);
        if (explicitlyConfigured != null
                && detectJavaFeatureVersion(explicitlyConfigured) >= requiredMajorVersion) {
            return explicitlyConfigured.getAbsolutePath();
        }
        try {
            return resolveExactJavaExecutable(configuredPath, requiredMajorVersion);
        } catch (IOException missing) {
            Consumer<String> safeStatus = status == null ? message -> { } : status;
            BiConsumer<Long, Long> safeProgress = progress == null
                    ? (downloaded, total) -> { } : progress;
            safeStatus.accept("未找到兼容 Java " + requiredMajorVersion + "，准备自动下载。");
            String downloaded = JavaRuntimeDownloader.download(
                    requiredMajorVersion, safeStatus, safeProgress);
            int detected = detectJavaFeatureVersion(new File(downloaded));
            if (detected != requiredMajorVersion) {
                throw new IOException("自动下载的 Java 版本不满足要求，需要 "
                        + requiredMajorVersion + "，实际为 " + detected, missing);
            }
            return downloaded;
        }
    }

    private static String resolveExactJavaExecutable(String configuredPath, int requiredMajorVersion)
            throws IOException {
        if (requiredMajorVersion <= 0) {
            return resolveJavaExecutable(configuredPath);
        }
        Set<File> candidates = new LinkedHashSet<>();
        addCandidate(candidates, configuredPath);
        addCandidate(candidates, System.getProperty("java.home"));
        addCandidate(candidates, System.getenv("JAVA_HOME"));
        candidates.addAll(findInstalledJavaCandidates());
        for (File candidate : candidates) {
            if (detectJavaFeatureVersion(candidate) == requiredMajorVersion) {
                return candidate.getAbsolutePath();
            }
        }
        throw new IOException("没有找到精确匹配的 Java " + requiredMajorVersion + " 运行时");
    }

    public static boolean isUsableJavaPath(String path) {
        return resolveJavaCandidate(path) != null;
    }

    /** Returns whether launch preparation would need to download a managed Java runtime. */
    public static boolean requiresManagedJavaDownload(String configuredPath, int requiredMajorVersion) {
        if (requiredMajorVersion <= 0) {
            return false;
        }
        File explicitlyConfigured = resolveJavaCandidate(configuredPath);
        if (explicitlyConfigured != null
                && detectJavaFeatureVersion(explicitlyConfigured) >= requiredMajorVersion) {
            return false;
        }
        try {
            resolveExactJavaExecutable(configuredPath, requiredMajorVersion);
            return false;
        } catch (IOException missing) {
            return true;
        }
    }

    /** Snapshot all currently discoverable Java executables in priority order. */
    public static List<String> discoverJavaExecutables(String configuredPath) {
        Set<File> candidates = new LinkedHashSet<>();
        addCandidate(candidates, configuredPath);
        addCandidate(candidates, System.getProperty("java.home"));
        addCandidate(candidates, System.getenv("JAVA_HOME"));
        candidates.addAll(findInstalledJavaCandidates());
        return candidates.stream().map(File::getAbsolutePath).toList();
    }

    private static File resolveJavaCandidate(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        File candidate = new File(path.trim());
        if (candidate.isDirectory()) {
            File fromBin = new File(candidate, "bin/" + executableName());
            if (fromBin.isFile()) {
                return fromBin;
            }

            File fromMacBundle = new File(candidate, "Contents/Home/bin/" + executableName());
            if (fromMacBundle.isFile()) {
                return fromMacBundle;
            }

            File fromHomebrewBundle = new File(candidate, "libexec/openjdk.jdk/Contents/Home/bin/" + executableName());
            if (fromHomebrewBundle.isFile()) {
                return fromHomebrewBundle;
            }

            File nestedExecutable = new File(candidate, executableName());
            if (nestedExecutable.isFile()) {
                return nestedExecutable;
            }
            return null;
        }

        return candidate.isFile() ? candidate : null;
    }

    private static File findInstalledJava() {
        List<File> candidates = findInstalledJavaCandidates();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static List<File> findInstalledJavaCandidates() {
        List<File> candidates = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        String userHome = System.getProperty("user.home", "");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (!userHome.isBlank()) {
            roots.add(new File(userHome, ".jdks"));
        }
        roots.add(new File(ECLConfig.getBaseDir(), "runtimes"));
        if (programFiles != null && !programFiles.isBlank()) {
            roots.add(new File(programFiles, "Java"));
            roots.add(new File(programFiles, "Eclipse Adoptium"));
            roots.add(new File(programFiles, "Microsoft"));
        }
        if (programFilesX86 != null && !programFilesX86.isBlank()) {
            roots.add(new File(programFilesX86, "Java"));
            roots.add(new File(programFilesX86, "Eclipse Adoptium"));
        }
        if (osName.contains("mac")) {
            roots.add(new File("/Library/Java/JavaVirtualMachines"));
            if (!userHome.isBlank()) {
                roots.add(new File(userHome, "Library/Java/JavaVirtualMachines"));
            }
            roots.add(new File("/opt/homebrew/opt/openjdk"));
            roots.add(new File("/usr/local/opt/openjdk"));
            roots.add(new File("/opt/homebrew/Cellar/openjdk"));
            roots.add(new File("/usr/local/Cellar/openjdk"));
        }

        for (File root : roots) {
            candidates.addAll(findJavaCandidatesUnderRoot(root));
        }
        return candidates;
    }

    private static List<File> findJavaCandidatesUnderRoot(File root) {
        List<File> candidates = new ArrayList<>();
        if (root == null || !root.exists()) {
            return candidates;
        }

        File direct = resolveJavaCandidate(root.getAbsolutePath());
        if (direct != null) {
            candidates.add(direct);
        }

        File[] children = root.listFiles(File::isDirectory);
        if (children == null || children.length == 0) {
            return candidates;
        }

        Arrays.sort(children, Comparator.comparing(File::getName).reversed());
        for (File child : children) {
            File candidate = resolveJavaCandidate(child.getAbsolutePath());
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    private static void addCandidate(Set<File> candidates, String path) {
        File candidate = resolveJavaCandidate(path);
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    public static int detectJavaFeatureVersion(File javaExecutable) {
        if (javaExecutable == null || !javaExecutable.isFile()) {
            return -1;
        }

        Process process = null;
        try {
            process = new ProcessBuilder(javaExecutable.getAbsolutePath(), "-version")
                    .redirectErrorStream(true)
                    .start();

            StringBuffer output = new StringBuffer();
            Process runningProcess = process;
            Thread drain = Thread.ofVirtual().name("ecl-java-version-output").start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && output.length() < 4_096) {
                        output.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                }
            });
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                drain.join(1_000);
                return -1;
            }
            drain.join(1_000);
            return parseJavaFeatureVersion(output.toString());
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static int parseJavaFeatureVersion(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return -1;
        }

        Matcher matcher = JAVA_VERSION_PATTERN.matcher(versionOutput);
        if (!matcher.find()) {
            return -1;
        }

        String version = matcher.group(1);
        String[] parts = version.split("[._+-]");
        if (parts.length == 0) {
            return -1;
        }

        try {
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return first;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String executableName() {
        return PlatformUtil.isWindows() ? "java.exe" : "java";
    }
}
