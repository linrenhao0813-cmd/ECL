package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.Locale;

import static com.ecl.util.TextUtil.abbreviate;

/** Updates the launcher's account and version summary surfaces. */
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
        updateAccount();
    }

    private void updateVersion(String selectedVersion, String versionDisplay) {
        if (ui.versionSummaryLabel != null) {
            ui.versionSummaryLabel.setText(abbreviate(versionDisplay, 26));
            ui.versionSummaryLabel.setTooltip(
                    selectedVersion == null ? null : new Tooltip(selectedVersion));
        }
        if (ui.selectedVersionTitleLabel != null) {
            ui.selectedVersionTitleLabel.setText(selectedVersion == null || selectedVersion.isBlank()
                    ? Messages.get("home.selectVersion") : selectedVersion);
            ui.selectedVersionTitleLabel.setTooltip(new Tooltip(versionDisplay));
        }
        if (ui.topVersionBadgeLabel != null) {
            ui.topVersionBadgeLabel.setText(selectedVersion == null || selectedVersion.isBlank()
                    ? Messages.get("label.notSelected") : abbreviate(versionDisplay, 16));
        }
        if (ui.selectedRuntimeMetaLabel != null) {
            String metadata = GuiMessages.get("forest.chooseHint");
            if (selectedVersion != null && !selectedVersion.isBlank()) {
                metadata = ui.versionManager.getLocalVersionProfiles().stream()
                        .filter(profile -> profile.profileId().equals(selectedVersion))
                        .map(profile -> "Minecraft " + profile.minecraftVersion() + "  ·  "
                                + (profile.loader().isBlank() ? GuiMessages.get("forest.vanilla")
                                : ui.loaderChoiceForProfile(selectedVersion).displayName))
                        .findFirst().orElse("Minecraft " + selectedVersion + "  ·  " + GuiMessages.get("forest.vanilla"));
            }
            ui.selectedRuntimeMetaLabel.setText(metadata);
        }
    }

    private void updateAccount() {
        String accountName = ui.getAuthDisplayName();
        setText(ui.topAuthBadgeLabel, accountName);
        setText(ui.homeAccountNameLabel, accountName);
        setText(ui.homeAccountTypeLabel, authModeLabel());
        if (ui.homeAccountAvatarLabel != null) {
            ui.homeAccountAvatarLabel.setText(accountName.isBlank()
                    ? "E" : accountName.substring(0, 1).toUpperCase(Locale.ROOT));
        }
    }

    private String authModeLabel() {
        String authType = ui.authTypeCombo == null
                ? LauncherUI.AUTH_OFFLINE : ui.authTypeCombo.getValue();
        if (LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            return GuiMessages.get("forest.microsoft");
        }
        if (LauncherUI.AUTH_YGGDRASIL.equals(authType)) {
            return GuiMessages.get("forest.external");
        }
        return GuiMessages.get("forest.offline");
    }

    private static void setText(Label label, String value) {
        if (label != null) {
            label.setText(value);
        }
    }
}
