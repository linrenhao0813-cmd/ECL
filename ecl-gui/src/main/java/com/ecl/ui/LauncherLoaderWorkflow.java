package com.ecl.ui;

import com.ecl.launcher.ModLoaderInstaller;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

import java.io.IOException;

/** Owns loader selection, installation, and the corresponding launch controls. */
final class LauncherLoaderWorkflow {
    private final LauncherUI ui;

    LauncherLoaderWorkflow(LauncherUI ui) {
        this.ui = ui;
    }

    void syncLoaderChoiceFromProfile(String profileId) {
        if (ui.loaderChoiceCombo == null || ui.syncingLoaderChoice) {
            return;
        }
        LoaderChoice detected = loaderChoiceForProfile(profileId);
        ui.syncingLoaderChoice = true;
        try {
            ui.loaderChoiceCombo.setValue(detected);
        } finally {
            ui.syncingLoaderChoice = false;
        }
        updateLoaderControls();
    }

    LoaderChoice loaderChoiceForProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return LoaderChoice.VANILLA;
        }
        return ui.versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .map(profile -> loaderChoiceForId(profile.loader()))
                .findFirst()
                .orElse(LoaderChoice.VANILLA);
    }

    private static LoaderChoice loaderChoiceForId(String loaderId) {
        if (loaderId == null || loaderId.isBlank()) {
            return LoaderChoice.VANILLA;
        }
        for (LoaderChoice choice : LoaderChoice.values()) {
            if (!choice.vanilla() && choice.loader.id().equalsIgnoreCase(loaderId)) {
                return choice;
            }
        }
        return LoaderChoice.VANILLA;
    }

    void handleLoaderChoiceChanged() {
        if (ui.syncingLoaderChoice || ui.loaderChoiceCombo == null || ui.versionCombo == null) {
            return;
        }
        LoaderChoice requested = ui.loaderChoiceCombo.getValue();
        String selectedProfile = ui.versionCombo.getValue();
        if (requested == null || selectedProfile == null || selectedProfile.isBlank()) {
            updateLoaderControls();
            return;
        }
        String minecraftVersion;
        try {
            minecraftVersion = ui.versionManager.resolveMinecraftVersionId(selectedProfile);
        } catch (IOException error) {
            ui.setStatus("无法识别 Minecraft 版本", ui.cleanMessage(error));
            updateLoaderControls();
            return;
        }
        if (requested.vanilla()) {
            if (!ui.versionCombo.getItems().contains(minecraftVersion)) {
                ui.versionCombo.getItems().add(0, minecraftVersion);
            }
            ui.versionCombo.setValue(minecraftVersion);
            updateLoaderControls();
            return;
        }
        ui.versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.minecraftVersion().equals(minecraftVersion))
                .filter(profile -> profile.loader().equalsIgnoreCase(requested.loader.id()))
                .findFirst()
                .ifPresent(profile -> ui.versionCombo.setValue(profile.profileId()));
        updateLoaderControls();
    }

    void updateLoaderControls() {
        if (ui.loaderChoiceCombo == null) {
            return;
        }
        LoaderChoice requested = ui.loaderChoiceCombo.getValue();
        String selectedProfile = ui.versionCombo == null ? null : ui.versionCombo.getValue();
        LoaderChoice installed = loaderChoiceForProfile(selectedProfile);
        boolean requiresInstall = requested != null && !requested.vanilla() && requested != installed;
        if (ui.installSelectedLoaderButton != null) {
            ui.installSelectedLoaderButton.setDisable(selectedProfile == null || selectedProfile.isBlank()
                    || !requiresInstall);
            if (requested == null || requested.vanilla()) {
                ui.installSelectedLoaderButton.setText("当前为原版");
            } else if (requiresInstall) {
                ui.installSelectedLoaderButton.setText("安装 " + requested.displayName);
            } else {
                ui.installSelectedLoaderButton.setText(requested.displayName + " 已安装");
            }
        }
        if (ui.launchBtn != null) {
            ui.launchBtn.setText(requiresInstall ? "安装并启动" : "启动游戏");
        }
    }

    void installSelectedLoader(Runnable afterSuccess) {
        LoaderChoice requested = ui.loaderChoiceCombo == null ? null : ui.loaderChoiceCombo.getValue();
        String selectedProfile = ui.versionCombo == null ? null : ui.versionCombo.getValue();
        if (requested == null || requested.vanilla()) {
            ui.setStatus("请选择模组加载器", "可选择 Fabric、Quilt、Forge 或 NeoForge。");
            return;
        }
        installLoaderForProfile(selectedProfile, requested.loader, afterSuccess);
    }

    private void installLoaderForProfile(String selectedProfile, ModLoaderInstaller.Loader loader,
                                         Runnable afterSuccess) {
        if (selectedProfile == null || selectedProfile.isBlank()) {
            ui.setStatus("请选择 Minecraft 版本", "安装加载器前需要先选择游戏版本。");
            return;
        }
        String minecraftVersion;
        try {
            minecraftVersion = ui.versionManager.resolveMinecraftVersionId(selectedProfile);
        } catch (IOException error) {
            ui.setStatus("无法识别 Minecraft 版本", ui.cleanMessage(error));
            return;
        }
        ui.setControlsBusy(true);
        ui.startProgressAnimation(ui.downloadProgress);
        ui.setStatus("正在安装加载器", loader.displayName() + " / Minecraft " + minecraftVersion);
        ui.downloadTaskCenter.submit(
                "Loader " + loader.displayName(), () -> context -> {
            try {
                ModLoaderInstaller.InstallResult result = ui.modLoaderInstaller.install(
                        minecraftVersion, loader, "", new ModLoaderInstaller.Listener() {
                            @Override
                            public void onStatus(String message) {
                                context.updateStatus(message);
                                Platform.runLater(() -> ui.setStatus("正在安装加载器", message));
                            }
                            @Override
                            public void onProgress(long downloaded, long total) {
                                context.updateProgress(downloaded, total);
                                Platform.runLater(() -> ui.updateProgress(ui.downloadProgress, downloaded, total));
                            }
                        });
                ui.gameRepository().applyDefaultIsolationSettingForNewInstance(result.profileId());
                Platform.runLater(() -> {
                    ui.stopProgressAnimation(ui.downloadProgress, true);
                    ui.setControlsBusy(false);
                    ui.versionActions.restoreVersionComboItems(result.profileId());
                    ui.versionCombo.setValue(result.profileId());
                    syncLoaderChoiceFromProfile(result.profileId());
                    ui.setStatus("加载器安装完成", result.loader().displayName() + " "
                            + result.loaderVersion() + " / Minecraft " + result.minecraftVersion());
                    if (afterSuccess != null) {
                        afterSuccess.run();
                    } else if (!ui.isHomeViewActive()) {
                        ui.renderActiveView();
                    }
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    ui.stopProgressAnimation(ui.downloadProgress, true);
                    ui.setControlsBusy(false);
                    updateLoaderControls();
                    ui.setStatus("加载器安装失败", ui.cleanMessage(error));
                });
                throw error;
            }
            return null;
        });
    }

    HBox createLoaderQuickActions(String profileId) {
        Button fabric = ui.createActionButton("安装 Fabric", "primary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.FABRIC,
                        ui::renderActiveView));
        Button quilt = ui.createActionButton("安装 Quilt", "secondary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.QUILT,
                        ui::renderActiveView));
        Button forge = ui.createActionButton("安装 Forge", "secondary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.FORGE,
                        ui::renderActiveView));
        Button neoForge = ui.createActionButton("安装 NeoForge", "secondary-button",
                () -> installLoaderForProfile(profileId, ModLoaderInstaller.Loader.NEOFORGE,
                        ui::renderActiveView));
        HBox actions = new HBox(10, fabric, quilt, forge, neoForge);
        actions.setAlignment(Pos.CENTER_LEFT);
        return actions;
    }
}
