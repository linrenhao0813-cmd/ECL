package com.ecl.ui;

import com.ecl.launcher.VersionManager;
import javafx.application.Platform;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Owns version refresh, selection restoration and Wiki navigation. */
final class VersionActions {
    private final LauncherUI ui;
    private final AtomicLong versionListGeneration = new AtomicLong();

    VersionActions(LauncherUI ui) {
        this.ui = ui;
    }

    static void deleteTreeWithin(Path root, Path target, Path preservedPath) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedPreserved = preservedPath == null
                ? null : preservedPath.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("拒绝删除越界目录: " + target);
        }
        if (!Files.exists(normalizedTarget)) return;
        try (var stream = Files.walk(normalizedTarget)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (normalizedPreserved != null
                        && (path.equals(normalizedPreserved) || normalizedPreserved.startsWith(path))) {
                    continue;
                }
                Files.deleteIfExists(path);
            }
        }
    }

    void restoreVersionComboItems(String preferredVersion) {
        restoreVersionComboItems(preferredVersion, true);
    }

    void restoreVersionComboItems(String preferredVersion, boolean selectFirstWhenMissing) {
        if (ui.versionCombo == null || ui.versionManager == null) {
            return;
        }
        long generation = versionListGeneration.incrementAndGet();
        // 首页只列出配置的 .minecraft/versions 下已经存在且具有本地启动配置的实例。
        // 新实例统一从下载页创建，避免在线版本选择意外替换当前启动目标。
        ui.runAsync("ecl-restore-versions", () -> {
            try {
                ui.versionManager.invalidateLocalVersionProfiles();
                List<String> versions = ui.gameRepository().installedInstanceDirectories();
                Platform.runLater(() -> {
                    if (generation != versionListGeneration.get() || ui.versionCombo == null) {
                        return;
                    }
                    applyInstalledVersions(versions,
                            selectFirstWhenMissing ? preferredVersion : ui.getSelectedVersion(), selectFirstWhenMissing);
                });
            } catch (Exception e) {
                LauncherUI.LOGGER.warn("Failed to restore installed instance choices", e);
            }
        });
    }

    void refreshVersions() {
        long generation = versionListGeneration.incrementAndGet();
        ui.versionCombo.setDisable(true);
        ui.updateSelectedVersionWikiButton();
        ui.setStatus("正在读取本地实例...", "正在扫描 .minecraft/versions 中已下载的实例。 ");

        ui.runAsync("ecl-refresh-versions", () -> {
            try {
                ui.versionManager.invalidateLocalVersionProfiles();
                List<String> versions = ui.gameRepository().installedInstanceDirectories();
                Platform.runLater(() -> {
                    if (generation != versionListGeneration.get()) {
                        return;
                    }
                    String current = ui.versionCombo.getValue();
                    applyInstalledVersions(versions, current);
                    ui.setStatus("本地实例已更新", versions.isEmpty()
                            ? "没有发现已下载实例，请先到“下载”页安装。"
                            : "已载入 " + versions.size() + " 个本地实例。 ");
                    ui.versionCombo.setDisable(false);
                    ui.updateRuntimeSummary();
                    ui.updateSelectedVersionWikiButton();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (generation != versionListGeneration.get()) {
                        return;
                    }
                    ui.setStatus("获取版本列表失败", ui.cleanMessage(e));
                    ui.versionCombo.setDisable(false);
                    ui.updateRuntimeSummary();
                    ui.updateSelectedVersionWikiButton();
                });
            }
        });
    }

    private void applyInstalledVersions(List<String> versions, String preferredVersion) {
        applyInstalledVersions(versions, preferredVersion, true);
    }

    private void applyInstalledVersions(List<String> versions, String preferredVersion, boolean selectFirstWhenMissing) {
        String selected = chooseInstalledVersion(versions, preferredVersion, selectFirstWhenMissing);
        ui.versionCombo.getItems().setAll(versions);
        if (selected == null) {
            ui.versionCombo.getSelectionModel().clearSelection();
            ui.versionCombo.setValue(null);
            return;
        }
        ui.versionCombo.getSelectionModel().select(selected);
    }

    static String chooseInstalledVersion(List<String> versions, String preferredVersion) {
        return chooseInstalledVersion(versions, preferredVersion, true);
    }

    static String chooseInstalledVersion(List<String> versions, String preferredVersion, boolean selectFirstWhenMissing) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        if (preferredVersion != null && versions.contains(preferredVersion)) {
            return preferredVersion;
        }
        return selectFirstWhenMissing ? versions.getFirst() : null;
    }

    VersionManager.VersionCategory getSelectedVersionCategory() {
        if (ui.versionTypeCombo == null || ui.versionTypeCombo.getValue() == null) {
            return VersionManager.VersionCategory.FEATURED;
        }
        return ui.versionTypeCombo.getValue();
    }

    void updateSelectedVersionWikiButton() {
        ui.updateSelectedVersionWikiButton();
    }

    /**
     * Content is downloaded into the instance directory of {@code contentVersion}. To keep the
     * launched game directory identical to the download target (so mods / shaderpacks /
     * resourcepacks are actually loaded), the launch selection is realigned to that version after
     * a successful import. Only selects the value when it is already offered by the combo.
     */
    void syncLaunchVersionToContent(String contentVersion) {
        if (contentVersion == null || contentVersion.isBlank()) {
            return;
        }
        ui.lastContentVersion = contentVersion;
        if (ui.versionCombo == null || contentVersion.equals(ui.versionCombo.getValue())) {
            return;
        }
        if (ui.versionCombo.getItems().contains(contentVersion)) {
            ui.versionCombo.setValue(contentVersion);
            ui.updateRuntimeSummary();
        }
    }

    void openMinecraftWikiVersionPage(String version) {
        if (version == null || version.isBlank()) {
            ui.setStatus("未选择版本", "请先选择一个正式版或快照版。");
            return;
        }
        if (!isWikiSupportedVersion(version)) {
            ui.setStatus("当前版本暂无 Wiki 入口", "仅正式版和快照版提供 mc 中文 Wiki 更新介绍按钮。");
            return;
        }

        String url = buildMinecraftWikiVersionUrl(version);
        try {
            ui.openExternalUrl(url);
            ui.setStatus("已打开版本介绍", version + " 的 mc 中文 Wiki 更新介绍已在浏览器中打开。");
        } catch (Exception e) {
            ui.setStatus("无法打开版本介绍", ui.cleanMessage(e));
        }
    }

    boolean isWikiSupportedVersion(String version) {
        return ui.versionManager != null && ui.versionManager.isReleaseOrSnapshot(version);
    }

    private String buildMinecraftWikiVersionUrl(String version) {
        String pageName = "Java版" + version;
        return LauncherUI.MC_CHINESE_WIKI_VERSION_URL_PREFIX
                + URLEncoder.encode(pageName, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
