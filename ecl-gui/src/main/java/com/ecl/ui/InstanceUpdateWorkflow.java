package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.service.InstanceUpdateService;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.io.IOException;

/** Presents one-click upgrades and keeps their progress visible on the home page. */
final class InstanceUpdateWorkflow {
    private final LauncherUI ui;
    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private boolean updating;

    InstanceUpdateWorkflow(LauncherUI ui) {
        this.ui = ui;
    }

    Button createButton() {
        ui.updateInstanceButton = ui.createActionButton(GuiMessages.get("instanceUpdate.button"),
                "secondary-button", this::start);
        ui.updateInstanceButton.setId("home-update-instance");
        ui.updateInstanceButton.setTooltip(new Tooltip(GuiMessages.get("instanceUpdate.hint")));
        updateButton();
        return ui.updateInstanceButton;
    }

    Button createButtonFor(String profileId) {
        Button button = ui.createActionButton(GuiMessages.get("instanceUpdate.button"),
                "secondary-button", () -> startFor(profileId));
        button.setTooltip(new Tooltip(GuiMessages.get("instanceUpdate.hint")));
        return button;
    }

    boolean isUpdating() {
        return updating;
    }

    VBox createStatusPane() {
        status.getStyleClass().add("forest-meta");
        status.setId("instance-update-status");
        status.setWrapText(true);
        status.setMaxWidth(600);
        progress.getStyleClass().add("download-progress");
        progress.setMaxWidth(410);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        VBox pane = new VBox(8, status, progress);
        pane.setId("instance-update-progress");
        pane.visibleProperty().bind(status.textProperty().isNotEmpty());
        pane.managedProperty().bind(pane.visibleProperty());
        return pane;
    }

    void updateButton() {
        if (ui.updateInstanceButton == null) return;
        String selected = ui.getSelectedVersion();
        ui.updateInstanceButton.setDisable(updating || ui.launchBtn == null || ui.launchBtn.isDisabled()
                || selected == null || selected.isBlank()
                || (ui.loaderChoiceForProfile(selected).vanilla() && !isPackProfile(selected))
                || ui.isVersionRunning(selected));
    }

    private boolean isPackProfile(String profileId) {
        try {
            var profile = HttpUtil.readJson(FileUtil.safeVersionJson(ECLConfig.getVersionsDir(), profileId));
            return profile.has("eclModpackName") || profile.has("eclModpackSource");
        } catch (IOException | IllegalArgumentException error) {
            return false;
        }
    }

    private void start() {
        startFor(ui.getSelectedVersion());
    }

    /** Upgrades one explicit instance, independent of the launch target. */
    void startFor(String selected) {
        if (updating || selected == null || selected.isBlank()) return;
        ModInstanceContext instance;
        try {
            instance = VersionProfileModInstanceContext.load(selected, ECLConfig.getVersionsDir().toPath(),
                    ui.getConfiguredGameRootDir().toPath(), ui.gameRepository().runDirectory(selected));
            ui.controller.registerModInstance(instance);
        } catch (IOException | IllegalArgumentException error) {
            status.setText(GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(error)));
            return;
        }
        updating = true;
        ui.setControlsBusy(true);
        progress.setVisible(true);
        status.setText(GuiMessages.get("instanceUpdate.button"));
        ui.controller.instanceUpdateService().update(instance, ui.controller.preferredModReleaseChannel(),
                update -> Platform.runLater(() -> status.setText(GuiMessages.get(
                        "instanceUpdate." + update.stage().name().toLowerCase(java.util.Locale.ROOT), update.detail()))),
                ECLConfig.getVersionsDir().toPath(), ui.getConfiguredGameRootDir().toPath(), ui.controller.modpackUpdateService())
                .whenComplete((result, error) -> Platform.runLater(() -> finish(selected, result, error)));
    }

    private void finish(String profileId, InstanceUpdateService.Result result, Throwable error) {
        updating = false;
        progress.setVisible(false);
        ui.controller.invalidateLaunchVersion(profileId);
        ui.versionManager.invalidateLocalVersionProfiles();
        ui.setControlsBusy(false);
        ui.updateRuntimeSummary();
        String summary;
        if (error != null) {
            summary = GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(error));
        } else if (result.packVersion() != null) {
            summary = result.packVersion().isBlank() ? GuiMessages.get("instanceUpdate.packCurrent")
                    : GuiMessages.get("instanceUpdate.packComplete", result.packVersion());
        } else {
            String loader = GuiMessages.get(result.loader().updated()
                    ? "instanceUpdate.loaderUpdated" : "instanceUpdate.loaderCurrent",
                    result.loader().loaderName(), result.loader().version());
            summary = GuiMessages.get("instanceUpdate.complete", loader, result.updatedMods(), result.skippedMods());
            if (!result.failures().isEmpty()) {
                summary += "\n" + GuiMessages.get("instanceUpdate.failures", result.failures().size()) + "\n"
                        + String.join("\n", result.failures().stream()
                                .map(failure -> failure.name() + ": " + ui.cleanMessage(failure.cause())).toList());
            }
        }
        if (result != null && !result.warnings().isEmpty()) summary += "\n" + String.join("\n", result.warnings());
        status.setText(summary);
        ui.setStatus(GuiMessages.get("instanceUpdate.button"), summary);
        updateButton();
    }
}
