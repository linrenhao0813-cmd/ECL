package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.launcher.VersionManager;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Owns version delete / reinstall / refresh / loader-version restore and Wiki navigation. */
final class VersionActions {
    private final LauncherUI ui;

    VersionActions(LauncherUI ui) {
        this.ui = ui;
    }

    void deleteSelectedVersion() {
        String profileId = ui.getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            ui.setStatus("没有可删除的版本", "请先选择一个版本。");
            return;
        }
        try {
            com.ecl.util.FileUtil.requireSafeVersionId(profileId);
        } catch (IOException error) {
            ui.setStatus("版本 ID 无效", ui.cleanMessage(error));
            return;
        }
        if (ui.gameLaunch.isGameProcessRunning()) {
            ui.setStatus("游戏运行中不能删除版本",
                    ui.activeGameVersion + " 正在运行，请退出游戏后再删除。");
            return;
        }
        boolean localMetadata;
        try {
            localMetadata = com.ecl.util.FileUtil.safeVersionDirectory(
                    ECLConfig.getVersionsDir(), profileId).isDirectory();
        } catch (IOException error) {
            ui.setStatus("版本 ID 无效", ui.cleanMessage(error));
            return;
        }
        boolean localInstance = Files.isDirectory(
                ui.getConfiguredGameRootDir().toPath().resolve("versions").resolve(profileId));
        if (!localMetadata && !localInstance) {
            ui.setStatus("版本尚未安装", profileId + " 没有可删除的本地文件。");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将删除版本文件和独立实例目录（mods、存档、设置、日志）。此操作不可撤销。\n\n"
                        + profileId,
                ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(ui.primaryStage);
        confirm.setTitle("删除版本");
        confirm.setHeaderText("确认删除当前版本？");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        ui.setStatus("正在删除版本", profileId);
        ui.runAsync("ecl-delete-version", () -> {
            try {
                deleteProfileFiles(profileId, true);
                Platform.runLater(() -> {
                    ui.settingsManager.remove(ECLConfig.KEY_SELECTED_VERSION);
                    ui.settingsManager.save();
                    restoreVersionComboItems(null);
                    ui.setStatus("版本已删除", profileId + " 的版本文件和实例数据已移除。");
                    ui.renderActiveView();
                });
            } catch (IOException error) {
                Platform.runLater(() -> ui.setStatus("删除版本失败", ui.cleanMessage(error)));
                throw new RuntimeException(error);
            }
        });
    }

    void reinstallSelectedVersion() {
        String profileId = ui.getSelectedVersion();
        if (profileId == null || profileId.isBlank()) {
            ui.setStatus("没有可重装的版本", "请先选择一个版本。");
            return;
        }
        if (ui.gameLaunch.isGameProcessRunning()) {
            ui.setStatus("游戏运行中不能重装版本",
                    ui.activeGameVersion + " 正在运行，请退出游戏后再重装。");
            return;
        }
        VersionManager.LocalVersionProfile localProfile = ui.versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .findFirst().orElse(null);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将重建版本元数据和依赖库；独立实例中的 mods、存档和设置会保留。\n\n" + profileId,
                ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(ui.primaryStage);
        confirm.setTitle("重装版本");
        confirm.setHeaderText("确认重装当前版本？");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (localProfile == null) {
            String manifestUrl = ui.versionManager.getVersionUrl(profileId);
            if (manifestUrl == null || manifestUrl.isBlank()) {
                ui.setStatus("拒绝重装未知配置",
                        profileId + " 不是 Mojang 原版或可识别加载器配置，请先手动备份后处理。");
                return;
            }
            try {
                deleteProfileFiles(profileId, false);
                ui.versionCombo.setValue(profileId);
                ui.setStatus("版本已标记为重装", "点击启动后会重新下载 " + profileId + " 的完整文件。");
            } catch (IOException error) {
                ui.setStatus("重装准备失败", ui.cleanMessage(error));
            }
            return;
        }
        ModLoaderInstaller.Loader loader;
        try {
            loader = ModLoaderInstaller.Loader.valueOf(localProfile.loader().toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            ui.setStatus("无法自动重装加载器", "无法识别加载器 " + localProfile.loader()
                    + "，请使用“安装加载器”。");
            return;
        }
        ui.setControlsBusy(true);
        ui.startProgressAnimation(ui.downloadProgress);
        String requestedLoaderVersion;
        try {
            requestedLoaderVersion = detectInstalledLoaderVersion(profileId, loader);
        } catch (IOException error) {
            ui.setControlsBusy(false);
            ui.stopProgressAnimation(ui.downloadProgress, true);
            ui.setStatus("无法确定加载器版本", ui.cleanMessage(error));
            return;
        }
        ui.runAsync("ecl-reinstall-" + loader.id(), () -> {
            try {
                ModLoaderInstaller.InstallResult result = ui.modLoaderInstaller.install(
                        localProfile.minecraftVersion(), loader, requestedLoaderVersion,
                        new ModLoaderInstaller.Listener() {
                            @Override
                            public void onStatus(String message) {
                                Platform.runLater(() -> ui.setStatus("正在重装加载器", message));
                            }

                            @Override
                            public void onProgress(long downloaded, long total) {
                                Platform.runLater(() ->
                                        ui.updateProgress(ui.downloadProgress, downloaded, total));
                            }
                        });
                ui.gameRepository().applyDefaultIsolationSettingForNewInstance(result.profileId());
                Platform.runLater(() -> {
                    ui.stopProgressAnimation(ui.downloadProgress, true);
                    ui.setControlsBusy(false);
                    restoreVersionComboItems(profileId);
                    ui.versionCombo.setValue(profileId);
                    ui.setStatus("加载器已重装", profileId + "；原加载器版本和实例数据已保留。");
                    ui.renderActiveView();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    ui.stopProgressAnimation(ui.downloadProgress, true);
                    ui.setControlsBusy(false);
                    ui.setStatus("加载器重装失败", ui.cleanMessage(error));
                });
            }
        });
    }

    private String detectInstalledLoaderVersion(String profileId,
                                                ModLoaderInstaller.Loader loader) throws IOException {
        File jsonFile = com.ecl.util.FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), profileId);
        com.google.gson.JsonObject json = com.ecl.util.HttpUtil.readJson(jsonFile);
        com.google.gson.JsonArray libraries = json.has("libraries") && json.get("libraries").isJsonArray()
                ? json.getAsJsonArray("libraries") : new com.google.gson.JsonArray();
        for (com.google.gson.JsonElement item : libraries) {
            if (!item.isJsonObject()) continue;
            String coordinate = item.getAsJsonObject().has("name")
                    ? item.getAsJsonObject().get("name").getAsString() : "";
            String prefix = switch (loader) {
                case FABRIC -> "net.fabricmc:fabric-loader:";
                case QUILT -> "org.quiltmc:quilt-loader:";
                case FORGE -> "net.minecraftforge:forge:";
                case NEOFORGE -> "net.neoforged:neoforge:";
            };
            if (!coordinate.startsWith(prefix)) continue;
            String version = coordinate.substring(prefix.length());
            if (loader == ModLoaderInstaller.Loader.FORGE
                    && version.startsWith(localMinecraftVersion(json) + "-")) {
                version = version.substring(localMinecraftVersion(json).length() + 1);
            }
            if (!version.isBlank()) return version;
        }
        throw new IOException("版本配置中没有找到 " + loader.displayName() + " 版本号");
    }

    private String localMinecraftVersion(com.google.gson.JsonObject json) {
        String explicit = json.has("eclMinecraftVersion")
                ? json.get("eclMinecraftVersion").getAsString() : "";
        return explicit.isBlank() && json.has("inheritsFrom")
                ? json.get("inheritsFrom").getAsString() : explicit;
    }

    private void deleteProfileFiles(String profileId, boolean includeInstance) throws IOException {
        if (profileId.contains("/") || profileId.contains("\\") || profileId.contains("..")) {
            throw new IOException("版本 ID 无效");
        }
        Path metadataRoot = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        deleteTreeWithin(metadataRoot, metadataRoot.resolve(profileId));
        if (includeInstance) {
            Path instanceRoot = ui.getConfiguredGameRootDir().toPath().toAbsolutePath()
                    .normalize().resolve("versions").normalize();
            deleteTreeWithin(instanceRoot, instanceRoot.resolve(profileId));
        }
    }

    private void deleteTreeWithin(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new IOException("拒绝删除越界目录: " + target);
        }
        if (!Files.exists(normalizedTarget)) return;
        try (var stream = Files.walk(normalizedTarget)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    void restoreVersionComboItems(String preferredVersion) {
        if (ui.versionCombo == null || ui.versionTypeCombo == null || ui.versionManager == null) {
            return;
        }
        try {
            List<String> versions = ui.versionManager.mergeLocalLoaderProfiles(
                    ui.versionManager.getVersions(getSelectedVersionCategory()));
            ui.versionCombo.getItems().setAll(versions);
            if (preferredVersion != null && versions.contains(preferredVersion)) {
                ui.versionCombo.getSelectionModel().select(preferredVersion);
            } else if (!versions.isEmpty()) {
                ui.versionCombo.getSelectionModel().select(0);
            }
        } catch (Exception e) {
            LauncherUI.LOGGER.warn("Failed to restore version choices", e);
        }
    }

    void refreshVersions() {
        VersionManager.VersionCategory category = getSelectedVersionCategory();
        String categoryLabel = category.getLabel();
        ui.refreshBtn.setDisable(true);
        ui.versionCombo.setDisable(true);
        ui.versionTypeCombo.setDisable(true);
        ui.updateSelectedVersionWikiButton();
        ui.setStatus("正在获取版本列表...", "正在加载 " + categoryLabel + "，失败时会回退到本地缓存。 ");

        ui.runAsync("ecl-refresh-versions", () -> {
            try {
                ui.versionManager.refresh();
                List<String> versions = ui.versionManager.mergeLocalLoaderProfiles(
                        ui.versionManager.getVersions(category));
                Platform.runLater(() -> {
                    String current = ui.versionCombo.getValue();
                    ui.versionCombo.getItems().setAll(versions);
                    if (current != null && versions.contains(current)) {
                        ui.versionCombo.getSelectionModel().select(current);
                    } else if (!versions.isEmpty()) {
                        ui.versionCombo.getSelectionModel().select(0);
                    }
                    ui.setStatus("版本列表已更新", versions.isEmpty() ? "没有发现可用的" + categoryLabel + "。" : "已载入 " + versions.size() + " 个" + categoryLabel + "。 ");
                    ui.refreshBtn.setDisable(false);
                    ui.versionCombo.setDisable(false);
                    ui.versionTypeCombo.setDisable(false);
                    ui.updateRuntimeSummary();
                    ui.updateSelectedVersionWikiButton();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    ui.setStatus("获取版本列表失败", ui.cleanMessage(e));
                    ui.refreshBtn.setDisable(false);
                    ui.versionCombo.setDisable(false);
                    ui.versionTypeCombo.setDisable(false);
                    ui.updateRuntimeSummary();
                    ui.updateSelectedVersionWikiButton();
                });
            }
        });
    }

    VersionManager.VersionCategory getSelectedVersionCategory() {
        if (ui.versionTypeCombo == null || ui.versionTypeCombo.getValue() == null) {
            return VersionManager.VersionCategory.FEATURED;
        }
        return ui.versionTypeCombo.getValue();
    }

    VersionManager.VersionCategory parseVersionCategory(String value) {
        try {
            return VersionManager.VersionCategory.valueOf(value);
        } catch (Exception e) {
            LauncherUI.LOGGER.debug("Invalid saved version category: {}", value, e);
            return VersionManager.VersionCategory.FEATURED;
        }
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
