package com.ecl.launch;

import com.ecl.game.DownloadObject;
import com.ecl.game.Library;
import com.ecl.game.VersionMetadata;
import com.ecl.util.FileUtil;
import com.ecl.util.RuleEvaluator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts the platform-native libraries declared by a version into the version's {@code natives}
 * staging directory, once per source-fingerprint.
 *
 * <p>The extractor is defensive by design: entries are checked against expansion bombs before and
 * during copy, extraction is tracked by a marker file, and a later launch re-extracts whenever any
 * source jar or any already-extracted file differs from the recorded fingerprint. Re-running is
 * therefore idempotent and cheap.</p>
 */
public final class NativeLibraryExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeLibraryExtractor.class);
    private static final String EXTRACTION_MARKER = ".ecl-natives-extracted";

    // 安全限制：防止 ZIP 炸弹
    private static final long MAX_EXTRACTED_TOTAL_BYTES = 500 * 1024 * 1024L;  // 500 MB
    private static final long MAX_EXTRACTED_SINGLE_BYTES = 100 * 1024 * 1024L; // 100 MB 单个文件
    private static final int MAX_EXTRACTED_ENTRIES = 10_000;                    // 最多 1 万个文件

    private NativeLibraryExtractor() {
    }

    /**
     * Ensure native libraries for {@code versionId} are extracted and current.
     *
     * @throws IOException when the staging directory cannot be prepared or an entry violates limits
     */
    public static void extract(VersionMetadata metadata, LaunchEnvironment environment, String versionId)
            throws IOException {
        extract(metadata, environment, versionId, null);
    }

    public static void extract(VersionMetadata metadata, LaunchEnvironment environment, String versionId,
                               File instanceDirectory) throws IOException {
        Path nativesDir = instanceDirectory == null
                ? environment.nativesDirectory(versionId).toPath()
                : instanceDirectory.toPath().resolve("natives-"
                        + com.ecl.util.PlatformUtil.current().minecraftName());
        Files.createDirectories(nativesDir);
        if (metadata.libraries().isEmpty()) {
            return;
        }

        String nativeClassifier = FileUtil.getNativeClassifier();
        String osArch = nativeClassifier.indexOf('-') >= 0
                ? nativeClassifier.substring(0, nativeClassifier.indexOf('-'))
                : com.ecl.util.PlatformUtil.current().minecraftName();
        Set<File> nativeFiles = collectNativeFiles(metadata, environment, nativeClassifier, osArch,
                instanceDirectory);
        if (nativeFiles.isEmpty()) {
            return;
        }

        File marker = new File(nativesDir.toFile(), EXTRACTION_MARKER);
        String sourceFingerprint = fingerprint(nativeFiles);
        if (markerIsCurrent(marker, sourceFingerprint, nativesDir)) {
            return;
        }

        clearDirectory(nativesDir);
        ExtractionBudget extractionBudget = new ExtractionBudget(new ExtractionLimits(
                MAX_EXTRACTED_TOTAL_BYTES, MAX_EXTRACTED_SINGLE_BYTES, MAX_EXTRACTED_ENTRIES));
        for (File nativeFile : nativeFiles) {
            if (nativeFile.isFile()) {
                extractJar(nativeFile, nativesDir.toFile(), extractionBudget);
            }
        }
        try {
            writeMarkerAtomically(marker.toPath(), buildMarker(sourceFingerprint, nativesDir));
        } catch (IOException e) {
            LOGGER.warn("Failed to write natives extraction marker for version {}", versionId, e);
        }
    }

    private static Set<File> collectNativeFiles(VersionMetadata metadata, LaunchEnvironment environment,
                                               String nativeClassifier, String osArch,
                                               File instanceDirectory) {
        Set<DownloadObject> chosen = new LinkedHashSet<>();
        for (Library library : metadata.libraries()) {
            if (!libraryHasAllowedRules(library) || library.classifiers().isEmpty()) {
                continue;
            }
            for (String key : nativeClassifierKeys(library, nativeClassifier, osArch)) {
                DownloadObject classifierObject = library.classifiers().get(key);
                if (classifierObject != null) {
                    chosen.add(classifierObject);
                    break;
                }
            }
        }
        Set<File> files = new LinkedHashSet<>();
        for (DownloadObject object : chosen) {
            Library owner = metadata.libraries().stream()
                    .filter(library -> library.classifiers().containsValue(object))
                    .findFirst().orElse(null);
            File base = owner != null && owner.isLocal() && instanceDirectory != null
                    ? new File(instanceDirectory, "libraries") : environment.librariesDirectory();
            File nativeFile = new File(base, object.path());
            files.add(nativeFile);
        }
        return files;
    }

    private static boolean libraryHasAllowedRules(Library library) {
        return library.raw() == null || !library.raw().has("rules")
                || RuleEvaluator.isAllowed(library.raw().getAsJsonArray("rules"));
    }

    private static Set<String> nativeClassifierKeys(Library library, String nativeClassifier, String osArch) {
        Set<String> keys = new LinkedHashSet<>();
        String template = library.natives().get(osArch);
        if (template != null && !template.isBlank()) {
            keys.add(template.replace("${arch}", architectureBits()));
        }
        keys.addAll(java.util.Arrays.asList(
                com.ecl.util.MinecraftRuleUtil.nativeKeys(nativeClassifier)));
        return keys;
    }

    private static String architectureBits() {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return architecture.contains("64") || architecture.contains("aarch64") ? "64" : "32";
    }

    private static boolean markerIsCurrent(File marker, String sourceFingerprint, Path nativesDir) {
        if (!marker.isFile()) {
            return false;
        }
        try {
            return buildMarker(sourceFingerprint, nativesDir).equals(
                    Files.readString(marker.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.debug("Failed to read natives extraction marker {}", marker, e);
            return false;
        }
    }

    private static String fingerprint(Iterable<File> nativeFiles) throws IOException {
        StringBuilder fingerprint = new StringBuilder();
        for (File nativeFile : nativeFiles) {
            fingerprint.append(nativeFile.getAbsolutePath())
                    .append('|').append(nativeFile.isFile() ? FileUtil.sha1(nativeFile) : "missing")
                    .append('\n');
        }
        return fingerprint.toString();
    }

    /** Marker content includes the decode of everything currently extracted, for corruption checks. */
    static String buildMarker(String sourceFingerprint, Path nativesDir) throws IOException {
        StringBuilder fingerprint = new StringBuilder("sources\n")
                .append(sourceFingerprint)
                .append("extracted\n");
        try (var entries = Files.walk(nativesDir)) {
            for (Path entry : entries.filter(Files::isRegularFile).sorted().toList()) {
                if (entry.getFileName().toString().equals(EXTRACTION_MARKER)) {
                    continue;
                }
                fingerprint.append(nativesDir.relativize(entry).toString().replace(File.separatorChar, '/'))
                        .append('|').append(FileUtil.sha1(entry.toFile()))
                        .append('\n');
            }
        }
        return fingerprint.toString();
    }

    private static void clearDirectory(Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) {
            return;
        }
        try (var entries = Files.list(targetDir)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry)) {
                    FileUtil.deleteDirectory(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static void writeMarkerAtomically(Path marker, String fingerprint) throws IOException {
        Files.createDirectories(marker.getParent());
        Path tempFile = Files.createTempFile(marker.getParent(), "natives-", ".marker.tmp");
        try {
            Files.writeString(tempFile, fingerprint, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tempFile, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /** Extract the entries of {@code jarFile} into {@code targetDir}, respecting the shared budget. */
    static void extractJar(File jarFile, File targetDir, ExtractionBudget budget) throws IOException {
        Path targetRoot = targetDir.toPath().toAbsolutePath().normalize();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/") || entry.isDirectory()) {
                    continue;
                }
                if (++budget.entryCount > budget.limits.maxEntries()) {
                    throw new IOException("ZIP 炸弹防护: 解压文件数超过上限 "
                            + budget.limits.maxEntries() + ": " + jarFile);
                }
                long declaredSize = entry.getSize();
                if (declaredSize > budget.limits.maxSingleBytes()) {
                    throw new IOException("ZIP 炸弹防护: 单个解压文件声明大小超过限制 ("
                            + declaredSize + " bytes): " + entry.getName());
                }
                if (declaredSize >= 0
                        && declaredSize > budget.limits.maxTotalBytes() - budget.totalBytes) {
                    throw new IOException("ZIP 炸弹防护: 解压总大小超过上限 "
                            + budget.limits.maxTotalBytes() / 1024 / 1024 + " MB: " + jarFile);
                }
                Path outPath = targetRoot.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetRoot)) {
                    throw new IOException("Native entry escapes extraction directory: " + entry.getName());
                }
                Path parent = outPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                long entryBytes = 0;
                try (InputStream is = jar.getInputStream(entry);
                     OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(outPath), 64 * 1024)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        if (read > budget.limits.maxSingleBytes() - entryBytes) {
                            throw new IOException("ZIP 炸弹防护: 解压时单文件超过限制 ("
                                    + budget.limits.maxSingleBytes() / 1024 / 1024 + " MB): " + entry.getName());
                        }
                        if (read > budget.limits.maxTotalBytes() - budget.totalBytes) {
                            throw new IOException("ZIP 炸弹防护: 解压总大小超过上限 "
                                    + budget.limits.maxTotalBytes() / 1024 / 1024 + " MB: " + jarFile);
                        }
                        os.write(buffer, 0, read);
                        entryBytes += read;
                        budget.totalBytes += read;
                    }
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(outPath);
                    } catch (IOException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                    throw e;
                }
            }
        }
    }

    record ExtractionLimits(long maxTotalBytes, long maxSingleBytes, int maxEntries) {
        ExtractionLimits {
            if (maxTotalBytes <= 0 || maxSingleBytes <= 0 || maxEntries <= 0
                    || maxSingleBytes > maxTotalBytes) {
                throw new IllegalArgumentException("Invalid native extraction limits");
            }
        }
    }

    static final class ExtractionBudget {
        private final ExtractionLimits limits;
        private long totalBytes;
        private int entryCount;

        ExtractionBudget(ExtractionLimits limits) {
            this.limits = limits;
        }
    }
}
