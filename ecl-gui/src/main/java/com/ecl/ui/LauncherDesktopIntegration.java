package com.ecl.ui;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

/** Handles desktop operations: opening folders, external URLs, shortcuts, and window icons. */
final class LauncherDesktopIntegration {
    private final LauncherUI ui;

    LauncherDesktopIntegration(LauncherUI ui) {
        this.ui = ui;
    }

    void openLocalFolder(File folder, String label) {
        try {
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("无法创建目录: " + folder.getAbsolutePath());
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(folder);
            } else {
                ui.getHostServices().showDocument(folder.toURI().toString());
            }
            ui.setStatus("已打开" + label, folder.getAbsolutePath());
        } catch (Exception e) {
            try {
                ui.getHostServices().showDocument(new URI("file", "",
                        folder.getAbsolutePath().replace('\\', '/'), null).toString());
            } catch (URISyntaxException uriError) {
                LauncherUIView.LOGGER.warn("Failed to open local folder {}", folder, uriError);
                ui.setStatus("无法打开" + label, ui.cleanMessage(e));
            }
        }
    }

    void openExternalUrl(String url) {
        try {
            URI checked = com.ecl.util.NetworkUriPolicy.requireHttps(URI.create(url), "external URL");
            ui.getHostServices().showDocument(checked.toString());
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("拒绝打开不安全的外部地址", error);
        }
    }

    void createInstanceShortcut(boolean startMenu) {
        String profileId = ui.getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            ui.setStatus(com.ecl.util.Messages.get("shortcut.error.title"),
                    com.ecl.util.Messages.get("shortcut.error.selectInstance"));
            return;
        }
        Path executable = resolveLauncherExecutable();
        if (executable == null) {
            ui.setStatus(com.ecl.util.Messages.get("shortcut.error.title"),
                    com.ecl.util.Messages.get("shortcut.error.packagedExe"));
            return;
        }
        try {
            com.ecl.desktop.DesktopShortcutService shortcuts = new com.ecl.desktop.DesktopShortcutService();
            String name = "ECL - " + profileId;
            Path created = startMenu
                    ? shortcuts.createStartMenuShortcut(executable, name,
                            List.of("--instance", profileId))
                    : shortcuts.createDesktopShortcut(executable, name,
                            List.of("--instance", profileId));
            ui.setStatus(com.ecl.util.Messages.get("shortcut.created"), created.toString());
        } catch (IOException error) {
            ui.setStatus(com.ecl.util.Messages.get("shortcut.failed"), ui.cleanMessage(error));
        }
    }

    void applyWindowIcon(Stage stage) {
        URL icon = getClass().getResource("/icons/ecl-icon.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon.toExternalForm()));
        }
    }

    File prepareChooserDir(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        File file = new File(rawPath.trim());
        if (file.isDirectory()) {
            return file.exists() ? file : null;
        }
        File parent = file.getParentFile();
        return parent != null && parent.exists() ? parent : null;
    }

    private Path resolveLauncherExecutable() {
        return LauncherExecutableResolver.resolveCurrent(LauncherUI.class);
    }
}
