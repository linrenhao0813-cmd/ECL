package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.OfflineAuth;
import com.ecl.auth.YggdrasilAuth;
import javafx.application.Platform;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Creates launch authentication providers and persists provider-specific credentials. */
final class LaunchAuthFactory {
    private final LauncherUI ui;

    LaunchAuthFactory(LauncherUI ui) {
        this.ui = ui;
    }

    AuthProvider create(String authType, String server, String username, String password) {
        if (LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            MicrosoftAuth microsoftAuth = ui.microsoftAccounts.authenticateMicrosoftAccount(false);
            Platform.runLater(() -> {
                ui.usernameField.setText(microsoftAuth.getUsername());
                ui.updateRuntimeSummary();
            });
            return microsoftAuth;
        }

        if (LauncherUI.AUTH_YGGDRASIL.equals(authType)) {
            String effectivePassword = password;
                if (effectivePassword == null || effectivePassword.isBlank()) {
                    effectivePassword = ui.settingsManager.getEncrypted(
                            yggdrasilCredentialKey(server, username));
                    if (ui.settingsManager.consumeUnreadableEncryptedSetting() != null) {
                        Platform.runLater(() -> ui.setStatus("保存的登录凭证不可读取",
                                "外置账户密码解密失败，请重新输入密码并登录。"));
                    }
                }
            if (server.isBlank() || username.isBlank()
                    || effectivePassword == null || effectivePassword.isBlank()) {
                throw new IllegalArgumentException("请填写完整的外置登录信息。");
            }
            YggdrasilAuth yggdrasilAuth = new YggdrasilAuth(server);
            yggdrasilAuth.setCredentials(username, effectivePassword);
            yggdrasilAuth.login();
            ui.settingsManager.setEncrypted(yggdrasilCredentialKey(server, username), effectivePassword);
            ui.settingsManager.remove("_enc_yggdrasilPassword");
            ui.settingsManager.set(ECLConfig.KEY_YGGDRASIL_SERVER, server);
            ui.settingsManager.set(ECLConfig.KEY_USERNAME, yggdrasilAuth.getUsername());
            if (!ui.settingsManager.save()) {
                LauncherUI.LOGGER.warn("Failed to persist Yggdrasil session settings");
            }
            return yggdrasilAuth;
        }

        return new OfflineAuth(username.isBlank() ? "Player" : username);
    }

    private String yggdrasilCredentialKey(String server, String username) {
        String normalizedServer = server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
        while (normalizedServer.endsWith("/")) {
            normalizedServer = normalizedServer.substring(0, normalizedServer.length() - 1);
        }
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String identity = normalizedServer + "\n" + normalizedUsername;
        return "yggdrasilPassword."
                + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
