package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import static com.ecl.util.TextUtil.abbreviate;

/** Updates the launcher's account and version summary surfaces. */
final class RuntimeSummaryPresenter {
    private final LauncherUI ui;

    RuntimeSummaryPresenter(LauncherUI ui) {
        this.ui = ui;
    }

    void update() {
        String selectedVersion = ui.getSelectedVersion();
        String versionDisplay = selectedVersion == null || selectedVersion.isBlank()
                ? Messages.get("home.versionPending")
                : ui.versionManager.getVersionDisplayName(selectedVersion);
        updateVersion(selectedVersion, versionDisplay);
        ui.instanceUpdates.updateButton();
        ui.updateLaunchButtonLabel();
        updateContentTarget(selectedVersion, versionDisplay);
        updateAccount();
    }

    /**
     * Refreshes the content page banner that names the install target, when that page is open. The
     * banner must not reuse instance wording for server downloads, which have their own directory.
     */
    private void updateContentTarget(String selectedVersion, String versionDisplay) {
        if (ui.contentTargetLabel == null) {
            return;
        }
        ui.contentTargetLabel.setText(switch (ui.contentTargetMode == null ? "instance" : ui.contentTargetMode) {
            case "server" -> Messages.format("content.target.serverDir",
                    ui.getServerDownloadDir().getAbsolutePath());
            case "instances" -> Messages.get("content.target.instances");
            default -> selectedVersion == null || selectedVersion.isBlank()
                    ? Messages.get("content.target.none")
                    : Messages.format("content.target.instance", versionDisplay);
        });
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
        String metadata = versionMetadata(selectedVersion);
        if (ui.selectedRuntimeMetaLabel != null) {
            ui.selectedRuntimeMetaLabel.setText(metadata);
        }
    }

    /** "Minecraft &lt;version&gt; · &lt;loader&gt;" for the selected instance, or the choose hint. */
    private String versionMetadata(String selectedVersion) {
        if (selectedVersion == null || selectedVersion.isBlank()) {
            return GuiMessages.get("forest.chooseHint");
        }
        return ui.versionManager.getLocalVersionProfiles().stream()
                .filter(profile -> profile.profileId().equals(selectedVersion))
                .map(profile -> "Minecraft " + profile.minecraftVersion() + "  ·  "
                        + (profile.loader().isBlank() ? GuiMessages.get("forest.vanilla")
                        : ui.loaderChoiceForProfile(selectedVersion).displayName))
                .findFirst()
                .orElse("Minecraft " + selectedVersion + "  ·  " + GuiMessages.get("forest.vanilla"));
    }

    private void updateAccount() {
        String accountName = ui.getAuthDisplayName();
        setText(ui.topAuthBadgeLabel, accountName);
        ui.accountAvatarPresenter.update();
    }

    private static void setText(Label label, String value) {
        if (label != null) {
            label.setText(value);
        }
    }
}
