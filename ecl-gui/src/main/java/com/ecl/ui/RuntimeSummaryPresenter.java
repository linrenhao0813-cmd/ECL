package com.ecl.ui;

import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.Messages;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.Locale;

import static com.ecl.util.TextUtil.abbreviate;

/** Updates the launcher's account, version and runtime summary surfaces. */
final class RuntimeSummaryPresenter {
    private final LauncherUI ui;

    RuntimeSummaryPresenter(LauncherUI ui) {
        this.ui = ui;
    }

    void update() {
        String launcherJava = "启动器 Java " + Runtime.version().feature();
        updateJava(launcherJava);

        String selectedVersion = ui.versionCombo == null ? null : ui.versionCombo.getValue();
        String versionDisplay = selectedVersion == null || selectedVersion.isBlank()
                ? Messages.get("home.versionPending")
                : ui.versionManager.getVersionDisplayName(selectedVersion);
        updateVersion(selectedVersion, versionDisplay);

        String memoryText = ui.gameLaunch.getMemoryDisplayText();
        updateAccountAndMemory(launcherJava, memoryText);
        updateEnvironmentAndReadiness(selectedVersion);
        ui.gameLaunch.updatePlaytimeSummary();
    }

    private void updateJava(String launcherJava) {
        if (ui.javaSummaryLabel != null) {
            ui.javaSummaryLabel.setText(launcherJava);
            ui.javaSummaryLabel.setTooltip(ui.javaPath == null || ui.javaPath.isBlank()
                    ? null : new Tooltip(ui.javaPath));
        }
        setSummaryText(ui.gameDirSummaryLabel, ui.getActiveGameDir().getAbsolutePath(), 68);
    }

    private void updateVersion(String selectedVersion, String versionDisplay) {
        if (ui.versionSummaryLabel != null) {
            ui.versionSummaryLabel.setText(abbreviate(versionDisplay, 26));
            ui.versionSummaryLabel.setTooltip(
                    selectedVersion == null ? null : new Tooltip(selectedVersion));
        }
        if (ui.selectedVersionTitleLabel != null) {
            ui.selectedVersionTitleLabel.setText(selectedVersion == null || selectedVersion.isBlank()
                    ? Messages.get("home.selectVersion") : versionDisplay);
        }
        if (ui.topVersionBadgeLabel != null) {
            ui.topVersionBadgeLabel.setText(selectedVersion == null || selectedVersion.isBlank()
                    ? Messages.get("label.notSelected") : abbreviate(versionDisplay, 16));
        }
    }

    private void updateAccountAndMemory(String launcherJava, String memoryText) {
        if (ui.selectedRuntimeMetaLabel != null) {
            ui.selectedRuntimeMetaLabel.setText(
                    Messages.format("home.runtimeMeta", launcherJava, memoryText));
        }
        String accountName = ui.getAuthDisplayName();
        setText(ui.topAuthBadgeLabel, accountName);
        setText(ui.homeAccountNameLabel, accountName);
        setText(ui.homeAccountTypeLabel, authModeLabel());
        if (ui.homeAccountAvatarLabel != null) {
            ui.homeAccountAvatarLabel.setText(accountName.isBlank()
                    ? "E" : accountName.substring(0, 1).toUpperCase(Locale.ROOT));
        }
        setText(ui.memorySummaryLabel, memoryText);
        setText(ui.topMemoryBadgeLabel, memoryText);
        if (ui.jvmArgsSummaryLabel != null) {
            boolean blankArguments = ui.extraJvmArgs == null || ui.extraJvmArgs.isBlank();
            ui.jvmArgsSummaryLabel.setText(blankArguments
                    ? Messages.get("label.notSet") : abbreviate(ui.extraJvmArgs, 68));
            ui.jvmArgsSummaryLabel.setTooltip(
                    blankArguments ? null : new Tooltip(ui.extraJvmArgs));
        }
    }

    private void updateEnvironmentAndReadiness(String selectedVersion) {
        boolean configuredJava = JavaRuntimeUtil.isUsableJavaPath(ui.javaPath);
        if (ui.runtimeBadgeLabel != null) {
            ui.runtimeBadgeLabel.setText(configuredJava
                    ? String.valueOf(Runtime.version().feature()) : Messages.get("label.auto"));
        }
        if (ui.homeEnvironmentStatusLabel != null) {
            ui.homeEnvironmentStatusLabel.setText(configuredJava
                    ? Messages.get("home.envConfigured") : Messages.get("home.envAutoPrepare"));
        }

        String readiness = Messages.get("home.readiness.pendingInstance");
        if (ui.gameLaunch.isGameProcessRunning()) {
            readiness = Messages.get("home.readiness.running");
        } else if (selectedVersion != null && !selectedVersion.isBlank()) {
            readiness = ui.versionManager.isVersionDownloaded(selectedVersion)
                    ? Messages.get("home.readiness.installed")
                    : Messages.get("home.readiness.prepareFirst");
        }
        if (ui.launchReadinessLabel != null) {
            ui.launchReadinessLabel.setText("●  " + readiness);
        }
    }

    private String authModeLabel() {
        String authType = ui.authTypeCombo == null
                ? LauncherUI.AUTH_OFFLINE : ui.authTypeCombo.getValue();
        if (LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            return "Microsoft 账号";
        }
        if (LauncherUI.AUTH_YGGDRASIL.equals(authType)) {
            return "外置登录";
        }
        return "离线登录";
    }

    private static void setSummaryText(Label label, String value, int maxLength) {
        if (label == null) {
            return;
        }
        boolean blank = value == null || value.isBlank();
        label.setText(blank ? "未设置" : abbreviate(value, maxLength));
        label.setTooltip(blank ? null : new Tooltip(value));
    }

    private static void setText(Label label, String value) {
        if (label != null) {
            label.setText(value);
        }
    }
}
