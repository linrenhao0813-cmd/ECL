package com.ecl.ui;

import com.ecl.auth.AuthProvider;
import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.OfflineAuth;
import javafx.application.Platform;

/** Creates launch authentication providers and persists provider-specific credentials. */
final class LaunchAuthFactory {
    private final LauncherUI ui;

    LaunchAuthFactory(LauncherUI ui) {
        this.ui = ui;
    }

    AuthProvider create(String authType, String username) {
        if (LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            MicrosoftAuth microsoftAuth = ui.microsoftAccounts.authenticateMicrosoftAccount(false);
            Platform.runLater(() -> {
                ui.usernameField.setText(microsoftAuth.getUsername());
                ui.updateRuntimeSummary();
            });
            return microsoftAuth;
        }

        if (!LauncherUI.AUTH_OFFLINE.equals(authType)) {
            throw new IllegalArgumentException("不支持的登录方式: " + authType);
        }
        return new OfflineAuth(username.isBlank() ? "Player" : username);
    }
}
