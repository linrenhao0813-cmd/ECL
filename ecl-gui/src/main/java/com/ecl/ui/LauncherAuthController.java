package com.ecl.ui;

import com.ecl.auth.OfflineSkinStore;
import com.ecl.util.Messages;
import javafx.scene.control.Tooltip;

import java.util.Locale;

import static com.ecl.util.TextUtil.abbreviate;

/** Owns the account-mode controls, offline skins, and display-name helpers. */
final class LauncherAuthController {
    private final LauncherUI ui;

    LauncherAuthController(LauncherUI ui) {
        this.ui = ui;
    }

    void updateAuthFields() {
        String authType = ui.authTypeCombo.getValue();
        boolean microsoft = LauncherUI.AUTH_MICROSOFT.equals(authType);
        boolean offline = LauncherUI.AUTH_OFFLINE.equals(authType);

        ui.usernameField.setDisable(microsoft);
        LauncherUiFactory.setVisible(ui.usernameField, !microsoft);
        LauncherUiFactory.setVisible(ui.microsoftAccountCombo, microsoft);
        LauncherUiFactory.setVisible(ui.microsoftLoginBtn, microsoft);
        LauncherUiFactory.setVisible(ui.microsoftAddAccountBtn, microsoft);
        LauncherUiFactory.setVisible(ui.skinUploadBtn, microsoft || offline);

        if (microsoft) {
            ui.usernameField.setPromptText("授权后自动读取正版玩家名");
            ui.authSummaryLabel.setText("微软正版登录");
            ui.authHintLabel.setText("会优先静默恢复已保存的登录状态；仅在缓存和刷新令牌失效时显示设备码。 ");
            ui.skinUploadBtn.setText("上传皮肤");
            ui.skinUploadBtn.setTooltip(new Tooltip("上传 PNG 皮肤到当前 Minecraft Java 正版账号"));
        } else {
            ui.usernameField.setPromptText("输入玩家名称");
            ui.authSummaryLabel.setText("离线登录");
            ui.authHintLabel.setText("会为当前用户名生成本地 UUID，适合单机和快速调试。 ");
            ui.skinUploadBtn.setText("导入皮肤");
            ui.skinUploadBtn.setTooltip(new Tooltip("为离线账号导入本地 PNG 皮肤，启动游戏时自动注入，无需正版账号"));
        }

        updateOfflineSkinControls();
        ui.updateRuntimeSummary();
    }

    void updateOfflineSkinControls() {
        if (ui.offlineSkinRemoveBtn == null) {
            return;
        }
        boolean offline = LauncherUI.AUTH_OFFLINE.equals(ui.authTypeCombo.getValue());
        LauncherUiFactory.setVisible(ui.offlineSkinRemoveBtn, offline && offlineSkinExists());
        ui.offlineSkinRemoveBtn.setDisable(false);
    }

    boolean offlineSkinExists() {
        String username = ui.usernameField.getText() == null ? "" : ui.usernameField.getText().trim();
        if (username.isBlank()) {
            return false;
        }
        return new OfflineSkinStore()
                .find(OfflineSkinStore.identityForOffline(username))
                .isPresent();
    }

    String getAuthDisplayName() {
        String authType = ui.authTypeCombo == null ? LauncherUI.AUTH_OFFLINE : ui.authTypeCombo.getValue();
        if (LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            if (ui.selectedMicrosoftAccount != null
                    && ui.selectedMicrosoftAccount.username() != null
                    && !ui.selectedMicrosoftAccount.username().isBlank()) {
                return abbreviate(ui.selectedMicrosoftAccount.username().trim(), 18);
            }
        }
        String username = ui.usernameField == null ? "Steve" : ui.usernameField.getText();
        if (username == null || username.isBlank()) {
            username = LauncherUI.AUTH_MICROSOFT.equals(authType) ? "Microsoft" : "Steve";
        }
        return abbreviate(username.trim(), 18);
    }

    String normalizeAuthType(String value) {
        if (LauncherUI.AUTH_MICROSOFT.equals(value)
                || LauncherUI.AUTH_OFFLINE.equals(value)) {
            return value;
        }
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.contains("microsoft")) return LauncherUI.AUTH_MICROSOFT;
            if (normalized.contains("offline") || normalized.contains("离线") || normalized.contains("離線")) {
                return LauncherUI.AUTH_OFFLINE;
            }
        }
        return LauncherUI.AUTH_OFFLINE;
    }

    String authDisplayName(String type) {
        return LauncherUI.AUTH_MICROSOFT.equals(normalizeAuthType(type))
                ? Messages.get("auth.microsoft") : Messages.get("auth.offline");
    }
}
