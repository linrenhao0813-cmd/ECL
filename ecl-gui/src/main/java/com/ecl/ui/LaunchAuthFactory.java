package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.AuthProvider;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.OfflineAuth;
import com.ecl.auth.YggdrasilAuth;
import com.ecl.auth.YggdrasilSessionStore;
import com.ecl.exception.AuthException;
import javafx.application.Platform;

import java.io.IOException;

/** Creates launch authentication providers and persists provider-specific credentials. */
final class LaunchAuthFactory {
    private final LauncherUI ui;
    private final YggdrasilSessionStore yggdrasilSessions;

    LaunchAuthFactory(LauncherUI ui) {
        this(ui, new YggdrasilSessionStore());
    }

    LaunchAuthFactory(LauncherUI ui, YggdrasilSessionStore yggdrasilSessions) {
        this.ui = ui;
        this.yggdrasilSessions = yggdrasilSessions;
    }

    AuthProvider create(String authType, String server, String username, char[] password) {
        if (LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            MicrosoftAuth microsoftAuth = ui.microsoftAccounts.authenticateMicrosoftAccount(false);
            Platform.runLater(() -> {
                ui.usernameField.setText(microsoftAuth.getUsername());
                ui.updateRuntimeSummary();
            });
            return microsoftAuth;
        }

        if (LauncherUI.AUTH_YGGDRASIL.equals(authType)) {
            if (server.isBlank() || username.isBlank()) {
                throw new IllegalArgumentException("请填写完整的外置登录服务器和用户名。");
            }
            if (isBlank(password)) {
                return restoreYggdrasilSession(server, username);
            }
            YggdrasilAuth yggdrasilAuth = new YggdrasilAuth(server);
            yggdrasilAuth.setCredentials(username, password);
            yggdrasilAuth.login();
            yggdrasilSessions.save(server, yggdrasilAuth);
            persistYggdrasilIdentity(server, yggdrasilAuth);
            return yggdrasilAuth;
        }

        return new OfflineAuth(username.isBlank() ? "Player" : username);
    }

    private static boolean isBlank(char[] value) {
        if (value == null || value.length == 0) {
            return true;
        }
        for (char character : value) {
            if (!Character.isWhitespace(character)) {
                return false;
            }
        }
        return true;
    }

    private YggdrasilAuth restoreYggdrasilSession(String server, String username) {
        YggdrasilAuth cached = yggdrasilSessions.restore(server, username)
                .orElseThrow(() -> new IllegalArgumentException(
                        "外置登录会话不存在，请重新输入密码。"));
        try {
            if (!cached.validate()) {
                cached.refresh();
                yggdrasilSessions.save(server, cached);
            }
            persistYggdrasilIdentity(server, cached);
            return cached;
        } catch (IOException error) {
            throw new AuthException("外置登录会话已失效，请重新输入密码", error);
        }
    }

    private void persistYggdrasilIdentity(String server, YggdrasilAuth auth) {
        ui.settingsManager.set(ECLConfig.KEY_YGGDRASIL_SERVER, server);
        ui.settingsManager.set(ECLConfig.KEY_USERNAME, auth.getUsername());
        if (!ui.settingsManager.save()) {
            LauncherUI.LOGGER.warn("Failed to persist Yggdrasil session settings");
        }
        Platform.runLater(() -> {
            ui.usernameField.setText(auth.getUsername());
            ui.updateRuntimeSummary();
        });
    }
}
