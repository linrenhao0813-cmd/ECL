package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.MicrosoftAccountStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URL;
import java.util.List;

/** Owns Microsoft account login, device-code flows and the saved account list. */
final class MicrosoftAccountCoordinator {
    private final LauncherUI ui;

    MicrosoftAccountCoordinator(LauncherUI ui) {
        this.ui = ui;
    }

    void loginMicrosoftAccount() {
        ui.authTypeCombo.setValue(LauncherUI.AUTH_MICROSOFT);
        ui.updateAuthFields();
        ui.setControlsBusy(true);
        ui.setStatus("微软正版登录", "正在尝试恢复已保存的 Microsoft 登录状态。");

        ui.runAsync("ecl-login-microsoft", () -> {
            try {
                MicrosoftAuth microsoftAuth = authenticateMicrosoftAccount(false);
                Platform.runLater(() -> {
                    ui.usernameField.setText(microsoftAuth.getUsername());
                    refreshMicrosoftAccountChoices(microsoftAuth.getUUID());
                    ui.setStatus(ui.lastMicrosoftAccountPersisted
                                    ? "微软正版登录成功" : "微软登录成功，多账号保存失败",
                            ui.lastMicrosoftAccountPersisted
                                    ? "已登录 " + microsoftAuth.getUsername() + "，现在可以直接启动游戏。"
                                    : "当前登录可用，但账号列表无法写入；请检查 ECL 数据目录权限。");
                    ui.updateRuntimeSummary();
                    ui.setControlsBusy(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    ui.setStatus("微软正版登录失败", ui.cleanMessage(e));
                    ui.setControlsBusy(false);
                });
            }
        });
    }

    void addMicrosoftAccount() {
        ui.authTypeCombo.setValue(LauncherUI.AUTH_MICROSOFT);
        ui.updateAuthFields();
        ui.setControlsBusy(true);
        ui.setStatus("添加 Microsoft 账号", "正在申请新的设备登录代码。");
        ui.runAsync("ecl-add-microsoft-account", () -> {
            try {
                MicrosoftAuth auth = authenticateMicrosoftAccount(true);
                Platform.runLater(() -> {
                    ui.usernameField.setText(auth.getUsername());
                    refreshMicrosoftAccountChoices(auth.getUUID());
                    ui.setStatus(ui.lastMicrosoftAccountPersisted
                                    ? "Microsoft 账号已添加" : "账号登录成功但保存失败",
                            ui.lastMicrosoftAccountPersisted
                                    ? auth.getUsername() + " 已保存，可在账号下拉框中切换。"
                                    : "当前登录可用，但无法写入多账号列表；请检查数据目录权限。");
                    ui.setControlsBusy(false);
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    ui.setStatus("添加 Microsoft 账号失败", ui.cleanMessage(error));
                    ui.setControlsBusy(false);
                });
            }
        });
    }

    MicrosoftAuth authenticateMicrosoftAccount(boolean forceNew) {
        MicrosoftAccountStore.Account selected = forceNew ? null : ui.selectedMicrosoftAccount;
        MicrosoftAuth.CachedSession cachedSession = selected == null
                ? new MicrosoftAuth.CachedSession(
                        forceNew ? null : ui.settingsManager.getEncrypted("microsoftRefreshToken"),
                        forceNew ? null : ui.settingsManager.getEncrypted("microsoftAccessToken"),
                        forceNew ? 0 : ui.settingsManager.get(ECLConfig.KEY_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT),
                        forceNew ? null : ui.settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_NAME),
                        forceNew ? null : ui.settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_UUID))
                : new MicrosoftAuth.CachedSession(
                        selected.refreshToken(), selected.accessToken(),
                        selected.accessTokenExpiresAt(), selected.username(), selected.uuid());
        MicrosoftAuth microsoftAuth = new MicrosoftAuth(cachedSession, new MicrosoftAuth.LoginListener() {
            @Override
            public void onDeviceCode(MicrosoftAuth.DeviceCode deviceCode) {
                Platform.runLater(() -> {
                    boolean copied = copyMicrosoftDeviceCodeToClipboard(deviceCode.getUserCode());
                    ui.setStatus("微软正版登录", copied
                            ? "登录代码已自动复制: " + deviceCode.getUserCode() + "。浏览器打开后直接粘贴完成授权。"
                            : "无法自动复制登录代码，请手动复制 " + deviceCode.getUserCode() + " 完成授权。");
                    showMicrosoftDeviceCodeDialog(deviceCode);
                    openMicrosoftVerificationPage(deviceCode);
                });
            }

            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> ui.setStatus("微软正版登录", message));
            }
        });
        microsoftAuth.login();
        MicrosoftAuth.CachedSession authenticatedSession = microsoftAuth.getCachedSession();
        String refreshToken = authenticatedSession.refreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            ui.settingsManager.setEncrypted("microsoftRefreshToken", refreshToken);
        }
        ui.settingsManager.setEncrypted("microsoftAccessToken", authenticatedSession.accessToken());
        ui.settingsManager.set(ECLConfig.KEY_MICROSOFT_ACCESS_TOKEN_EXPIRES_AT,
                authenticatedSession.accessTokenExpiresAt());
        ui.settingsManager.set(ECLConfig.KEY_AUTH_TYPE, LauncherUI.AUTH_MICROSOFT);
        ui.settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_NAME, authenticatedSession.username());
        ui.settingsManager.set(ECLConfig.KEY_MICROSOFT_PROFILE_UUID, authenticatedSession.uuid());
        ui.settingsManager.set(ECLConfig.KEY_USERNAME, authenticatedSession.username());
        MicrosoftAccountStore.Account storedAccount = new MicrosoftAccountStore.Account(
                authenticatedSession.uuid(), authenticatedSession.username(),
                authenticatedSession.refreshToken(), authenticatedSession.accessToken(),
                authenticatedSession.accessTokenExpiresAt());
        ui.lastMicrosoftAccountPersisted = ui.microsoftAccountStore.save(storedAccount);
        ui.selectedMicrosoftAccount = storedAccount;
        if (!ui.settingsManager.save()) {
            Platform.runLater(() -> ui.setStatus("微软登录信息保存失败", "登录已成功，但无法保存刷新令牌，请检查目录权限或查看日志。"));
        }
        return microsoftAuth;
    }

    private void refreshMicrosoftAccountChoices(String selectedUuid) {
        if (ui.microsoftAccountCombo == null) return;
        List<MicrosoftAccountStore.Account> accounts = ui.microsoftAccountStore.list();
        ui.microsoftAccountCombo.getItems().setAll(accounts);
        accounts.stream()
                .filter(account -> account.uuid().equalsIgnoreCase(selectedUuid))
                .findFirst()
                .ifPresent(ui.microsoftAccountCombo::setValue);
    }

    private void showMicrosoftDeviceCodeDialog(MicrosoftAuth.DeviceCode deviceCode) {
        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initOwner(ui.primaryStage);
        dialog.initModality(Modality.NONE);
        dialog.setTitle("Microsoft 登录");
        ui.applyWindowIcon(dialog);

        Label title = new Label("Microsoft 登录");
        title.getStyleClass().add("section-title");
        Label message = ui.createBodyText("登录代码已自动复制。打开浏览器访问验证地址并粘贴代码完成授权，授权成功后可以关闭本窗口。");

        TextField codeField = new TextField(deviceCode.getUserCode());
        codeField.setEditable(false);
        codeField.setFocusTraversable(true);
        ui.applyFieldStyle(codeField);

        TextField urlField = new TextField(deviceCode.getVerificationUri());
        urlField.setEditable(false);
        ui.applyFieldStyle(urlField);

        Button openButton = ui.createActionButton("打开浏览器", "primary-button", () -> openMicrosoftVerificationPage(deviceCode));
        Button copyButton = ui.createActionButton("复制代码", "secondary-button", () -> copyMicrosoftDeviceCode(deviceCode.getUserCode()));
        Button closeButton = ui.createActionButton("关闭", "ghost-button", dialog::close);
        HBox actions = new HBox(10, openButton, copyButton, closeButton);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox root = new VBox(12,
                title,
                message,
                ui.createInfoRow("登录码", ui.createStaticValueLabel(deviceCode.getUserCode())),
                codeField,
                ui.createInfoRow("验证 URL", ui.createStaticValueLabel(deviceCode.getVerificationUri())),
                urlField,
                actions
        );
        root.getStyleClass().add("root-pane");
        root.setPadding(new Insets(18));

        Scene scene = new Scene(root, 520, 330);
        URL stylesheet = getClass().getResource("/css/launcher.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.setScene(scene);
        ui.applyThemeToScene(scene, ui.settingsManager.get(ECLConfig.KEY_THEME));
        dialog.show();
        codeField.requestFocus();
        codeField.selectAll();
    }

    private void openMicrosoftVerificationPage(MicrosoftAuth.DeviceCode deviceCode) {
        try {
            ui.openExternalUrl(deviceCode.getVerificationUri());
            ui.setStatus("微软正版登录", "登录代码已自动复制: " + deviceCode.getUserCode() + "。浏览器打开后直接粘贴完成授权。");
        } catch (Exception e) {
            ui.setStatus("无法打开微软登录页面", ui.cleanMessage(e) + "；请手动打开 " + deviceCode.getVerificationUri());
        }
    }

    private void copyMicrosoftDeviceCode(String userCode) {
        if (copyMicrosoftDeviceCodeToClipboard(userCode)) {
            ui.setStatus("已复制微软登录代码", userCode);
        } else {
            ui.setStatus("复制微软登录代码失败", "请手动选择并复制 " + userCode);
        }
    }

    private boolean copyMicrosoftDeviceCodeToClipboard(String userCode) {
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(userCode);
            Clipboard.getSystemClipboard().setContent(content);
            return true;
        } catch (Exception e) {
            LauncherUI.LOGGER.warn("Failed to copy Microsoft device code", e);
            return false;
        }
    }
}
