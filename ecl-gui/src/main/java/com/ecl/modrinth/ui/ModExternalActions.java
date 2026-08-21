package com.ecl.modrinth.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Opens project links and local folders while reporting desktop failures. */
final class ModExternalActions {
    private final Consumer<String> statusConsumer;

    ModExternalActions(Consumer<String> statusConsumer) {
        this.statusConsumer = statusConsumer;
    }

    void openUri(URI uri) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (IOException error) {
            statusConsumer.accept("无法打开项目页面: " + error.getMessage());
        }
    }

    void openPath(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (IOException error) {
            statusConsumer.accept("无法打开文件位置: " + error.getMessage());
        }
    }
}
