package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.MinecraftSkinService;
import com.ecl.auth.OfflineSkinStore;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Owns premium skin upload and offline skin import / removal flows. */
final class SkinCoordinator {
    private final LauncherUI ui;

    SkinCoordinator(LauncherUI ui) {
        this.ui = ui;
    }

    void chooseAndUploadSkin() {
        String authType = ui.authTypeCombo.getValue();
        if (LauncherUI.AUTH_OFFLINE.equals(authType)) {
            chooseAndImportOfflineSkin();
            return;
        }
        if (!LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            ui.setStatus("当前登录方式不支持皮肤操作",
                    "请切换到 Microsoft 正版登录上传官方皮肤，或切换到离线登录导入本地皮肤。");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Minecraft Java 版皮肤");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG 皮肤图片 (*.png)", "*.png"));
        File selected = chooser.showOpenDialog(ui.primaryStage);
        if (selected == null) return;

        MinecraftSkinService.SkinImage skin;
        try {
            skin = ui.minecraftSkinService.inspect(selected.toPath());
        } catch (IOException error) {
            ui.setStatus("皮肤文件无效", ui.cleanMessage(error));
            return;
        }

        Dialog<MinecraftSkinService.Variant> dialog = new Dialog<>();
        dialog.initOwner(ui.primaryStage);
        dialog.setTitle("上传 Minecraft 皮肤");
        dialog.setHeaderText("确认皮肤模型");
        dialog.setOnShown(event -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                ui.applyWindowIcon(stage);
            }
        });

        ImageView preview = new ImageView(new Image(selected.toURI().toString()));
        preview.setFitWidth(192);
        preview.setFitHeight(192);
        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        preview.getStyleClass().add("skin-preview");

        ComboBox<MinecraftSkinService.Variant> variant = new ComboBox<>();
        variant.getItems().setAll(MinecraftSkinService.Variant.values());
        variant.setValue(MinecraftSkinService.Variant.CLASSIC);
        variant.setMaxWidth(Double.MAX_VALUE);
        ui.applyFieldStyle(variant);

        Label fileInfo = ui.createBodyText(selected.getName() + " · "
                + skin.width() + "×" + skin.height() + " · " + ui.formatBytes(skin.fileSize()));
        Label accountInfo = ui.createBodyText("上传到：" + (ui.selectedMicrosoftAccount == null
                ? ui.settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_NAME)
                : ui.selectedMicrosoftAccount.username()));
        VBox content = new VBox(12, preview, fileInfo, accountInfo,
                new Label("角色模型"), variant);
        content.setAlignment(javafx.geometry.Pos.CENTER);
        dialog.getDialogPane().setContent(content);
        ButtonType upload = new ButtonType("上传并使用", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(upload, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == upload ? variant.getValue() : null);
        dialog.showAndWait().ifPresent(selectedVariant ->
                uploadSkin(selected.toPath(), selectedVariant));
    }

    private void uploadSkin(Path skin, MinecraftSkinService.Variant variant) {
        ui.setControlsBusy(true);
        ui.setStatus("正在上传皮肤", "正在验证 Microsoft 登录并连接 Minecraft 皮肤服务…");
        ui.runAsync("ecl-upload-skin", () -> {
            try {
                MicrosoftAuth auth = ui.microsoftAccounts.authenticateMicrosoftAccount(false);
                MinecraftSkinService.UploadResult result = ui.minecraftSkinService.upload(
                        auth, skin, variant);
                Platform.runLater(() -> {
                    String account = result.profileName() == null || result.profileName().isBlank()
                            ? auth.getUsername() : result.profileName();
                    ui.setStatus("皮肤上传成功", account + " 已使用 " + variant + " 皮肤。重新进入游戏后生效。");
                    ui.setControlsBusy(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    ui.setStatus("皮肤上传失败", ui.cleanMessage(error));
                    ui.setControlsBusy(false);
                });
            }
        });
    }

    /**
     * Offline account path: pick a PNG, confirm the model, and copy it into the launcher data
     * directory. The skin is injected at launch time through the built-in Yggdrasil skin service,
     * so it works in single player and on offline-mode servers without any mods or premium login.
     */
    void chooseAndImportOfflineSkin() {
        String username = ui.usernameField.getText() == null ? "" : ui.usernameField.getText().trim();
        if (username.isBlank()) {
            ui.setStatus("请输入玩家名称", "离线皮肤需要绑定到具体的离线玩家名，请先在“账号模式”中填写玩家名称。");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择离线账号皮肤（PNG）");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG 皮肤图片 (*.png)", "*.png"));
        File selected = chooser.showOpenDialog(ui.primaryStage);
        if (selected == null) return;

        MinecraftSkinService.SkinImage skin;
        try {
            skin = ui.minecraftSkinService.inspect(selected.toPath());
        } catch (IOException error) {
            ui.setStatus("皮肤文件无效", ui.cleanMessage(error));
            return;
        }

        Dialog<MinecraftSkinService.Variant> dialog = new Dialog<>();
        dialog.initOwner(ui.primaryStage);
        dialog.setTitle("导入离线皮肤");
        dialog.setHeaderText("确认皮肤模型");
        dialog.setOnShown(event -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                ui.applyWindowIcon(stage);
            }
        });

        ImageView preview = new ImageView(new Image(selected.toURI().toString()));
        preview.setFitWidth(192);
        preview.setFitHeight(192);
        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        preview.getStyleClass().add("skin-preview");

        ComboBox<MinecraftSkinService.Variant> variant = new ComboBox<>();
        variant.getItems().setAll(MinecraftSkinService.Variant.values());
        variant.setValue(MinecraftSkinService.Variant.CLASSIC);
        variant.setMaxWidth(Double.MAX_VALUE);
        ui.applyFieldStyle(variant);

        Label fileInfo = ui.createBodyText(selected.getName() + " · "
                + skin.width() + "×" + skin.height() + " · " + ui.formatBytes(skin.fileSize()));
        Label accountInfo = ui.createBodyText("应用到离线账号：" + username
                + "\n皮肤与玩家名（含大小写）绑定；改名后需要重新导入。"
                + "\n本地皮肤服务会在启动游戏时自动注入，无需正版账号。");
        VBox content = new VBox(12, preview, fileInfo, accountInfo,
                new Label("角色模型"), variant);
        content.setAlignment(javafx.geometry.Pos.CENTER);
        dialog.getDialogPane().setContent(content);
        ButtonType importButton = new ButtonType("导入并使用", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == importButton ? variant.getValue() : null);
        dialog.showAndWait().ifPresent(selectedVariant ->
                importOfflineSkin(selected.toPath(), username, selectedVariant));
    }

    private void importOfflineSkin(Path skin, String username, MinecraftSkinService.Variant variant) {
        ui.setControlsBusy(true);
        ui.setStatus("正在导入皮肤", "正在校验并复制皮肤到本地数据目录…");
        ui.runAsync("ecl-import-offline-skin", () -> {
            try {
                String identity = OfflineSkinStore.identityForOffline(username);
                new OfflineSkinStore().importSkin(identity, skin, variant);
                Platform.runLater(() -> {
                    ui.setStatus("皮肤导入成功",
                            "离线账号 " + username + " 已使用本地皮肤，重新启动游戏后生效。");
                    ui.setControlsBusy(false);
                    ui.updateOfflineSkinControls();
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    ui.setStatus("皮肤导入失败", ui.cleanMessage(error));
                    ui.setControlsBusy(false);
                });
            }
        });
    }

    void removeOfflineSkin() {
        String username = ui.usernameField.getText() == null ? "" : ui.usernameField.getText().trim();
        if (username.isBlank()) {
            return;
        }
        String identity = OfflineSkinStore.identityForOffline(username);
        try {
            boolean removed = new OfflineSkinStore().remove(identity);
            if (removed) {
                ui.setStatus("皮肤已清除", "离线账号 " + username + " 已恢复默认皮肤。");
            } else {
                ui.setStatus("皮肤未找到", "该账号当前没有导入本地皮肤。");
            }
        } catch (RuntimeException failure) {
            ui.setStatus("清除皮肤失败", ui.cleanMessage(failure));
        }
        ui.updateOfflineSkinControls();
    }
}
