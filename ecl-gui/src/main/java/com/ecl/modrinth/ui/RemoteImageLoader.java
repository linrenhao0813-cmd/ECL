package com.ecl.modrinth.ui;

import com.ecl.ECLConfig;
import com.ecl.util.BoundedCache;
import com.ecl.util.HttpUtil;
import com.ecl.util.ThreadFactories;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Non-blocking remote icon loader with shared in-memory futures and deterministic placeholders. */
public final class RemoteImageLoader {
    private static final int ICON_SIZE = 48;
    private static final int MAX_ICON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_DIMENSION = 8_192;
    private static final long MAX_SOURCE_PIXELS = 16_000_000L;
    private static final int MAX_MEMORY_CACHE_ENTRIES = 256;
    private static final int MAX_DISK_CACHE_ENTRIES = 512;
    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(8, ThreadFactories.daemon("ecl-remote-image"));
    private static final BoundedCache<String, CompletableFuture<Image>> CACHE =
            new BoundedCache<>(MAX_MEMORY_CACHE_ENTRIES);
    private static final Image LOADING = placeholder(Color.web("#E5E7EB"), Color.web("#9CA3AF"));
    private static final Image MISSING = placeholder(Color.web("#F3F4F6"), Color.web("#CBD5E1"));

    private RemoteImageLoader() {
    }

    public static CompletableFuture<Image> load(URI uri) {
        if (uri == null) {
            return CompletableFuture.completedFuture(MISSING);
        }
        String key = uri.toString();
        return CACHE.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = cachedBytes(key);
                return decodeWithImageIo(bytes);
            } catch (Exception ignoredError) {
                return MISSING;
            }
        }, EXECUTOR));
    }

    /** Starts image requests before cells become visible so scrolling does not reveal blank covers. */
    public static void prefetch(Collection<URI> uris) {
        if (uris == null) return;
        uris.stream().filter(java.util.Objects::nonNull).distinct().forEach(RemoteImageLoader::load);
    }

    public static Image loadingPlaceholder() {
        return LOADING;
    }

    public static Image brokenPlaceholder() {
        return MISSING;
    }

    static int cacheSize() {
        return CACHE.size();
    }

    private static byte[] cachedBytes(String key) throws Exception {
        Path directory = ECLConfig.getBaseDir().toPath().resolve("cache").resolve("project-icons");
        Path file = directory.resolve(cacheName(key));
        if (Files.isRegularFile(file)) {
            long size = Files.size(file);
            if (size > 0 && size <= MAX_ICON_BYTES) {
                return Files.readAllBytes(file);
            }
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // A stale read-only cache entry must not block a fresh request.
            }
        }
        byte[] bytes = HttpUtil.getBytes(key, MAX_ICON_BYTES);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "icon-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            trimDiskCache(directory);
        } catch (Exception ignored) {
            // A read-only or busy cache must never prevent the cover from being displayed.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // Best-effort cleanup only.
                }
            }
        }
        return bytes;
    }

    private static String cacheName(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest) + ".img";
    }

    private static Image decodeWithImageIo(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                return MISSING;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return MISSING;
            }
            ImageReader reader = readers.next();
            BufferedImage buffered;
            try {
                reader.setInput(imageInput, true, true);
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                if (!isSourceSizeAllowed(sourceWidth, sourceHeight)) {
                    return MISSING;
                }
                int sample = Math.max(1,
                        (Math.max(sourceWidth, sourceHeight) + ICON_SIZE - 1) / ICON_SIZE);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                buffered = reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
            if (buffered == null || !isDecodedSizeAllowed(buffered.getWidth(), buffered.getHeight())) {
                return MISSING;
            }
            int width = buffered.getWidth();
            int height = buffered.getHeight();
            int[] pixels = buffered.getRGB(0, 0, width, height, null, 0, width);
            WritableImage image = new WritableImage(width, height);
            image.getPixelWriter().setPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), pixels, 0, width);
            return image;
        } catch (Exception ignored) {
            return MISSING;
        }
    }

    static boolean isSourceSizeAllowed(int width, int height) {
        return width > 0 && height > 0
                && width <= MAX_SOURCE_DIMENSION && height <= MAX_SOURCE_DIMENSION
                && (long) width * height <= MAX_SOURCE_PIXELS;
    }

    private static boolean isDecodedSizeAllowed(int width, int height) {
        return width > 0 && height > 0 && width <= ICON_SIZE && height <= ICON_SIZE;
    }

    private static void trimDiskCache(Path directory) {
        try (var files = Files.list(directory)) {
            List<Path> cachedFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".img"))
                    .sorted(Comparator.comparingLong(RemoteImageLoader::lastModified))
                    .toList();
            int excess = cachedFiles.size() - MAX_DISK_CACHE_ENTRIES;
            for (int index = 0; index < excess; index++) {
                Files.deleteIfExists(cachedFiles.get(index));
            }
        } catch (Exception ignored) {
            // Cache trimming is best-effort.
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static Image placeholder(Color background, Color mark) {
        WritableImage image = new WritableImage(ICON_SIZE, ICON_SIZE);
        PixelWriter pixels = image.getPixelWriter();
        for (int y = 0; y < ICON_SIZE; y++) {
            for (int x = 0; x < ICON_SIZE; x++) {
                boolean frame = x < 2 || y < 2 || x >= ICON_SIZE - 2 || y >= ICON_SIZE - 2;
                boolean tile = x >= 15 && x <= 32 && y >= 15 && y <= 32;
                pixels.setColor(x, y, frame || tile ? mark : background);
            }
        }
        return image;
    }
}
