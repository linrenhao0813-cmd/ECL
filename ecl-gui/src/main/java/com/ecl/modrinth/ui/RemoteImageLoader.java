package com.ecl.modrinth.ui;

import com.ecl.ECLConfig;
import com.ecl.util.HttpUtil;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Non-blocking remote icon loader with shared in-memory futures and deterministic placeholders. */
public final class RemoteImageLoader {
    private static final int ICON_SIZE = 48;
    private static final int MAX_ICON_BYTES = 4 * 1024 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "ecl-remote-image");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, CompletableFuture<Image>> CACHE = new ConcurrentHashMap<>();
    private static final Image LOADING = placeholder(Color.web("#E5E7EB"), Color.web("#9CA3AF"));
    private static final Image MISSING = placeholder(Color.web("#F3F4F6"), Color.web("#CBD5E1"));

    private RemoteImageLoader() {
    }

    public static CompletableFuture<Image> load(URI uri) {
        if (uri == null) {
            return CompletableFuture.completedFuture(MISSING);
        }
        String key = uri.toString();
        CompletableFuture<Image> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Image> created = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = cachedBytes(key);
                try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                    Image image = new Image(input, ICON_SIZE, ICON_SIZE, true, true);
                    if (!image.isError()) {
                        return image;
                    }
                }
                return decodeWithImageIo(bytes);
            } catch (Exception ignored) {
                return MISSING;
            }
        }, EXECUTOR);
        CompletableFuture<Image> selected = CACHE.putIfAbsent(key, created);
        if (selected != null) {
            return selected;
        }
        return created;
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
        }
        byte[] bytes = HttpUtil.getBytes(key, MAX_ICON_BYTES);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "icon-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            // A read-only or busy cache must never prevent the cover from being displayed.
        }
        return bytes;
    }

    private static String cacheName(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest) + ".img";
    }

    private static Image decodeWithImageIo(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage buffered = ImageIO.read(input);
            if (buffered == null || buffered.getWidth() <= 0 || buffered.getHeight() <= 0) {
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
