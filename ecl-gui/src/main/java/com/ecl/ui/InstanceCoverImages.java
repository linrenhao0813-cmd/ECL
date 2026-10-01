package com.ecl.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Loads instance cover images defensively; a missing or unreadable file never breaks the list. */
final class InstanceCoverImages {
    private static final double THUMBNAIL_SIZE = 34;
    private static final Map<String, Image> CACHE = new HashMap<>();

    private InstanceCoverImages() {
    }

    /** @return a rounded thumbnail for {@code path}, or {@code null} when there is nothing to show. */
    static ImageView thumbnail(String path) {
        Image image = load(path);
        if (image == null) {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setFitWidth(THUMBNAIL_SIZE);
        view.setFitHeight(THUMBNAIL_SIZE);
        view.setPreserveRatio(true);
        view.getStyleClass().add("instance-cover-thumb");
        return view;
    }

    /** @return the decoded image, or {@code null} when the path is blank or cannot be read. */
    static Image load(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path.trim());
        if (!Files.isRegularFile(file.toPath())) {
            return null;
        }
        String key = file.getAbsolutePath();
        Image cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Image image = new Image(file.toURI().toString(), THUMBNAIL_SIZE * 4,
                    THUMBNAIL_SIZE * 4, true, true, false);
            if (image.isError() || image.getWidth() <= 0) {
                return null;
            }
            CACHE.put(key, image);
            return image;
        } catch (RuntimeException error) {
            LauncherUI.LOGGER.debug("Cannot load instance cover {}", key, error);
            return null;
        }
    }

    /** Drops the cache entry for a path that has been replaced. */
    static void invalidate(String path) {
        if (path != null && !path.isBlank()) {
            CACHE.remove(new File(path.trim()).getAbsolutePath());
        }
    }
}
