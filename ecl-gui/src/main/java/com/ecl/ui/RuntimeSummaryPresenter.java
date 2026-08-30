package com.ecl.ui;

import com.ecl.util.JavaRuntimeUtil;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static com.ecl.util.TextUtil.abbreviate;

/** Updates the launcher's account, version and runtime summary surfaces. */
final class RuntimeSummaryPresenter {
    private final LauncherUI ui;

    RuntimeSummaryPresenter(LauncherUI ui) {
        this.ui = ui;
    }

    void update() {
        String selectedVersion = ui.versionCombo == null ? null : ui.versionCombo.getValue();
        String versionDisplay = selectedVersion == null || selectedVersion.isBlank()
                ? Messages.get("home.versionPending")
                : ui.versionManager.getVersionDisplayName(selectedVersion);
        updateVersion(selectedVersion, versionDisplay);

        GameLaunchCoordinator.RuntimeSummary runtime = ui.gameLaunch.runtimeSummary(selectedVersion);
        updateJava(runtime.javaText(), runtime.javaPath());
        updateAccountAndMemory(runtime);
        updateEnvironmentAndReadiness(selectedVersion, runtime);
        ui.gameLaunch.updatePlaytimeSummary();
    }

    private void updateJava(String javaText, String javaPath) {
        if (ui.javaSummaryLabel != null) {
            ui.javaSummaryLabel.setText(javaText);
            ui.javaSummaryLabel.setTooltip(javaPath == null || javaPath.isBlank()
                    ? null : new Tooltip(javaPath));
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

    private void updateAccountAndMemory(GameLaunchCoordinator.RuntimeSummary runtime) {
        if (ui.selectedRuntimeMetaLabel != null) {
            ui.selectedRuntimeMetaLabel.setText(
                    Messages.format("home.runtimeMeta", runtime.javaText(), runtime.memoryText()));
        }
        String accountName = ui.getAuthDisplayName();
        setText(ui.topAuthBadgeLabel, accountName);
        setText(ui.homeAccountNameLabel, accountName);
        setText(ui.homeAccountTypeLabel, authModeLabel());
        if (ui.homeAccountAvatarLabel != null) {
            ui.homeAccountAvatarLabel.setText(accountName.isBlank()
                    ? "E" : accountName.substring(0, 1).toUpperCase(Locale.ROOT));
        }
        setText(ui.memorySummaryLabel, runtime.memoryText());
        setText(ui.topMemoryBadgeLabel, runtime.memoryText());
        if (ui.jvmArgsSummaryLabel != null) {
            boolean blankArguments = runtime.jvmArguments().isBlank();
            ui.jvmArgsSummaryLabel.setText(blankArguments
                    ? Messages.get("label.notSet") : abbreviate(runtime.jvmArguments(), 68));
            ui.jvmArgsSummaryLabel.setTooltip(
                    blankArguments ? null : new Tooltip(runtime.jvmArguments()));
        }
    }

    private void updateEnvironmentAndReadiness(String selectedVersion,
                                               GameLaunchCoordinator.RuntimeSummary runtime) {
        boolean configuredJava = !runtime.javaPath().isBlank()
                && JavaRuntimeUtil.isUsableJavaPath(runtime.javaPath());
        if (ui.runtimeBadgeLabel != null) {
            ui.runtimeBadgeLabel.setText(configuredJava ? "自定义" : Messages.get("label.auto"));
        }
        if (ui.homeEnvironmentStatusLabel != null) {
            ui.homeEnvironmentStatusLabel.setText(configuredJava
                    ? Messages.get("home.envConfigured") : Messages.get("home.envAutoPrepare"));
        }

        String readiness = Messages.get("home.readiness.pendingInstance");
        if (ui.gameLaunch.isGameProcessRunning()) {
            readiness = Messages.get("home.readiness.running");
        } else if (selectedVersion != null && !selectedVersion.isBlank()) {
            boolean downloaded = ui.versionManager.isVersionDownloaded(selectedVersion);
            if (!downloaded) {
                CompletableFuture<Boolean> migration =
                        ui.versionManager.ensureVersionDownloadedAsync(selectedVersion);
                if (migration.isDone() && !migration.isCompletedExceptionally()) {
                    downloaded = Boolean.TRUE.equals(migration.getNow(false));
                } else {
                    String checkedVersion = selectedVersion;
                    migration.thenAccept(ready -> {
                        if (!Boolean.TRUE.equals(ready)) return;
                        Platform.runLater(() -> {
                            String current = ui.versionCombo == null
                                    ? null : ui.versionCombo.getValue();
                            if (checkedVersion.equals(current)) update();
                        });
                    });
                }
            }
            readiness = downloaded
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
