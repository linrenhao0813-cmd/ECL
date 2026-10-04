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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaRuntimeUtil {
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("version \"([^\"]+)\"");
    private static final int PARALLEL_PROBE_THREADS = 4;

    /**
     * Cache of {@link #detectJavaFeatureVersion(File)} results keyed by
     * (absolute path, last-modified, size). Spawning a JVM per candidate costs hundreds of
     * milliseconds, so the same runtime is only probed once per on-disk identity.
     */
    private static final Map<JavaProbeKey, Integer> VERSION_CACHE = new ConcurrentHashMap<>();

    /** Number of actual child-process probes performed; exposed for cache-hit tests. */
    static final AtomicLong DETECTION_PROBE_COUNT = new AtomicLong();

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
        List<File> orderedCandidates = List.copyOf(candidates);
        Map<File, Integer> versions = probeInParallel(orderedCandidates);

        // Minecraft metadata normally names the feature version it was built for. Prefer an
        // exact match before considering a newer runtime: newer Java releases can be present on
        // the machine but still be rejected by a loader or by native libraries.
        for (File candidate : orderedCandidates) {
            if (versions.getOrDefault(candidate, -1) == requiredMajorVersion) {
                return candidate.getAbsolutePath();
            }
        }

        File bestKnown = null;
        int bestKnownVersion = -1;
        for (File candidate : orderedCandidates) {
            int featureVersion = versions.getOrDefault(candidate, -1);
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
        List<File> orderedCandidates = List.copyOf(candidates);
        Map<File, Integer> versions = probeInParallel(orderedCandidates);
        for (File candidate : orderedCandidates) {
            if (versions.getOrDefault(candidate, -1) == requiredMajorVersion) {
                return candidate.getAbsolutePath();
            }
        }
        throw new IOException("没有找到精确匹配的 Java " + requiredMajorVersion + " 运行时");
    }

    public static boolean isUsableJavaPath(String path) {
        File candidate = resolveJavaCandidate(path);
        return candidate != null && detectJavaFeatureVersion(candidate) > 0;
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
        JavaProbeKey key = new JavaProbeKey(javaExecutable.getAbsolutePath(),
                javaExecutable.lastModified(), javaExecutable.length());
        Integer cached = VERSION_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int detected = probeJavaFeatureVersion(javaExecutable);
        VERSION_CACHE.put(key, detected);
        return detected;
    }

    /** Drop all cached Java probes. Tests and runtime updates call this before re-detecting. */
    public static void clearVersionCache() {
        VERSION_CACHE.clear();
    }

    /**
     * Probe every candidate runtime in parallel so a cold start is bounded by the slowest single
     * probe rather than the sum of all probes. Results are cached per (path, mtime, size).
     */
    private static Map<File, Integer> probeInParallel(List<File> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(candidates.size(), PARALLEL_PROBE_THREADS), runnable -> {
                    Thread thread = new Thread(runnable, "ecl-java-probe");
                    thread.setDaemon(true);
                    return thread;
                });
        Map<File, Integer> versions = new ConcurrentHashMap<>();
        try {
            List<CompletableFuture<Void>> futures = candidates.stream()
                    .map(candidate -> CompletableFuture.runAsync(
                            () -> versions.put(candidate, detectJavaFeatureVersion(candidate)), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            executor.shutdownNow();
        }
        Map<File, Integer> ordered = new LinkedHashMap<>();
        candidates.forEach(candidate -> ordered.put(candidate, versions.getOrDefault(candidate, -1)));
        return ordered;
    }

    private static int probeJavaFeatureVersion(File javaExecutable) {
        DETECTION_PROBE_COUNT.incrementAndGet();
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
                    // The probe process ended before its stream was fully drained.
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
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                    // The process is already being discarded.
                }
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
        return "java.exe";
    }

    /** Identity of a probed java executable; the file attributes detect on-disk replacement. */
    private record JavaProbeKey(String path, long modifiedAt, long size) {
    }
}
