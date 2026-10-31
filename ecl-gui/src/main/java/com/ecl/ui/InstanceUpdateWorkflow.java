package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.modrinth.model.ModUpdate;
import com.ecl.modrinth.service.InstanceUpdateService;
import com.ecl.modrinth.service.ModUpdateService;
import com.ecl.modrinth.transaction.PlannedModFile;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/** Checks a fixed update plan, confirms it, then applies its selected versions. */
final class InstanceUpdateWorkflow {
    private final LauncherUI ui;
    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final AtomicLong generation = new AtomicLong();
    private Button cancelButton;
    private boolean updating;
    private boolean cancellable;

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

    VBox createStatusPane() {
        status.getStyleClass().add("forest-meta");
        status.setId("instance-update-status");
        status.setWrapText(true);
        status.setMaxWidth(600);
        progress.getStyleClass().add("download-progress");
        progress.setMaxWidth(410);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        cancelButton = ui.createActionButton(GuiMessages.get("instanceUpdate.preview.cancelCheck"),
                "ghost-button", this::cancelPreflight);
        cancelButton.setId("instance-update-cancel-check");
        cancelButton.setAccessibleText(GuiMessages.get("instanceUpdate.preview.cancelCheck"));
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        VBox pane = new VBox(8, status, progress, cancelButton);
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

    VBox createStatusMirror() {
        Label message = new Label();
        message.textProperty().bind(status.textProperty());
        message.setWrapText(true);
        ProgressBar activity = new ProgressBar();
        activity.progressProperty().bind(progress.progressProperty());
        activity.visibleProperty().bind(progress.visibleProperty());
        activity.managedProperty().bind(activity.visibleProperty());
        Button cancel = ui.createActionButton(GuiMessages.get("instanceUpdate.preview.cancelCheck"),
                "ghost-button", this::cancelPreflight);
        cancel.visibleProperty().bind(cancelButton.visibleProperty());
        cancel.managedProperty().bind(cancel.visibleProperty());
        return new VBox(8, message, activity, cancel);
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

    /** The update target is captured once and stays independent of later selector changes. */
    void startFor(String profileId) {
        if (updating || profileId == null || profileId.isBlank()) return;
        if (ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId)) {
            status.setText(GuiMessages.get("instanceUpdate.launchPreparing"));
            return;
        }
        ModInstanceContext instance;
        try {
            instance = VersionProfileModInstanceContext.load(profileId,
                    ECLConfig.getVersionsDir().toPath(), ui.getConfiguredGameRootDir().toPath(),
                    ui.gameRepository().runDirectory(profileId));
            ui.controller.registerModInstance(instance);
        } catch (IOException | IllegalArgumentException error) {
            status.setText(GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(error)));
            return;
        }
        long currentGeneration = generation.incrementAndGet();
        updating = true;
        cancellable = true;
        setCancelVisible(true);
        ui.setControlsBusy(true);
        progress.setVisible(true);
        status.setText(GuiMessages.get("instanceUpdate.preview.checking"));
        try {
            ui.controller.instanceUpdateService().preview(instance,
                    ui.controller.preferredModReleaseChannel(), update -> Platform.runLater(() -> {
                        if (isCurrent(currentGeneration)) {
                            status.setText(GuiMessages.get("instanceUpdate." + update.stage()
                                    .name().toLowerCase(java.util.Locale.ROOT), update.detail()));
                        }
                    }), ECLConfig.getVersionsDir().toPath(), ui.getConfiguredGameRootDir().toPath(),
                    ui.controller.modpackUpdateService())
                    .whenComplete((preview, error) -> Platform.runLater(() -> {
                        if (!isCurrent(currentGeneration)) return;
                        if (error != null) {
                            finishWithoutMutation(currentGeneration,
                                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(unwrap(error))));
                        } else if (!preview.hasUpdates()) {
                            finishWithoutMutation(currentGeneration,
                                    GuiMessages.get("instanceUpdate.preview.none"));
                        } else if (preview.packProfile()) {
                            confirmPack(currentGeneration, preview);
                        } else {
                            chooseMods(currentGeneration, preview);
                        }
                    }));
        } catch (RuntimeException rejected) {
            finishWithoutMutation(currentGeneration,
                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(rejected)));
        }
        updateButton();
    }

    private void chooseMods(long taskGeneration, InstanceUpdateService.Preview preview) {
        Dialog<Set<String>> dialog = new Dialog<>();
        dialog.initOwner(ui.primaryStage);
        dialog.setTitle(GuiMessages.get("instanceUpdate.preview.title"));
        ButtonType continueButton = new ButtonType(GuiMessages.get("instanceUpdate.preview.continue"));
        dialog.getDialogPane().getButtonTypes().setAll(continueButton, ButtonType.CANCEL);
        List<CheckBox> choices = new ArrayList<>();
        VBox rows = new VBox(8);
        for (ModUpdate update : preview.modUpdates()) {
            String oldVersion = displayVersion(update.installedMod().versionNumber(),
                    update.installedMod().versionId());
            String newVersion = displayVersion(update.availableVersion().versionNumber(),
                    update.availableVersion().id());
            CheckBox choice = new CheckBox(GuiMessages.get("instanceUpdate.preview.mod",
                    update.installedMod().displayName(), oldVersion, newVersion));
            choice.setSelected(true);
            choice.setAccessibleText(update.installedMod().displayName());
            choice.setId("instance-update-mod-" + update.installedMod().projectId());
            choices.add(choice);
            rows.getChildren().add(choice);
        }
        VBox content = new VBox(12,
                new Label(GuiMessages.get("instanceUpdate.preview.instance",
                        ui.versionManager.getVersionDisplayName(preview.instance().profileId()),
                        preview.instance().profileId())),
                new Label(loaderChange(preview)),
                new Label(GuiMessages.get("instanceUpdate.preview.dependenciesUndetermined")),
                new Label(GuiMessages.get("instanceUpdate.preview.selectMods")),
                rows,
                skipDetails(preview));
        content.setPrefWidth(700);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(button -> button == continueButton
                ? selectedProjects(preview, choices) : null);
        dialog.showAndWait().ifPresent(selected -> {
            if (!isCurrent(taskGeneration)) return;
            if (selected.isEmpty() && !preview.loaderPlan().updateAvailable()) {
                finishWithoutMutation(taskGeneration,
                        GuiMessages.get("instanceUpdate.preview.noSelection"));
                return;
            }
            prepareExecution(taskGeneration, preview, selected);
        });
        if (!dialog.isShowing() && updating && generation.get() == taskGeneration
                && dialog.getResult() == null) {
            finishCancelled(taskGeneration);
        }
    }

    private Set<String> selectedProjects(InstanceUpdateService.Preview preview,
                                         List<CheckBox> choices) {
        Set<String> selected = new LinkedHashSet<>();
        for (int index = 0; index < choices.size(); index++) {
            if (choices.get(index).isSelected()) {
                selected.add(preview.modUpdates().get(index).installedMod().projectId());
            }
        }
        return Set.copyOf(selected);
    }

    private VBox skipDetails(InstanceUpdateService.Preview preview) {
        VBox details = new VBox(4);
        details.getChildren().add(new Label(GuiMessages.get("instanceUpdate.preview.skipped")));
        if (preview.skippedMods().isEmpty()) {
            details.getChildren().add(new Label(GuiMessages.get("instanceUpdate.preview.noneSkipped")));
        } else {
            preview.skippedMods().forEach(skipped -> details.getChildren().add(new Label(
                    GuiMessages.get("instanceUpdate.preview.skipItem", skipped.name(),
                            Messages.get(skipped.reasonKey())))));
        }
        return details;
    }

    private void prepareExecution(long taskGeneration, InstanceUpdateService.Preview preview,
                                  Set<String> selected) {
        status.setText(GuiMessages.get("instanceUpdate.prepare"));
        try {
            ui.controller.instanceUpdateService().prepare(preview, selected)
                    .whenComplete((execution, error) -> Platform.runLater(() -> {
                        if (!isCurrent(taskGeneration)) {
                            if (execution != null) execution.closeQuietly();
                            return;
                        }
                        if (error != null) {
                            finishWithoutMutation(taskGeneration,
                                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(unwrap(error))));
                        } else {
                            try {
                                confirmExecution(taskGeneration, execution);
                            } catch (RuntimeException failure) {
                                execution.closeQuietly();
                                finishWithoutMutation(taskGeneration,
                                        GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(failure)));
                            }
                        }
                    }));
        } catch (RuntimeException rejected) {
            finishWithoutMutation(taskGeneration,
                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(rejected)));
        }
    }

    private void preparePackExecution(long taskGeneration, InstanceUpdateService.Preview preview) {
        try {
            ui.controller.instanceUpdateService().prepare(preview, Set.of(),
                    ui.controller.modpackUpdateService(), update -> Platform.runLater(() -> {
                        if (isCurrent(taskGeneration)) {
                            status.setText(GuiMessages.get("instanceUpdate.prepare"));
                        }
                    }))
                    .whenComplete((execution, error) -> Platform.runLater(() -> {
                        if (!isCurrent(taskGeneration)) {
                            if (execution != null) execution.closeQuietly();
                            return;
                        }
                        if (error != null) {
                            finishWithoutMutation(taskGeneration,
                                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(unwrap(error))));
                        } else {
                            try {
                                confirmExecution(taskGeneration, execution);
                            } catch (RuntimeException failure) {
                                execution.closeQuietly();
                                finishWithoutMutation(taskGeneration,
                                        GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(failure)));
                            }
                        }
                    }));
        } catch (RuntimeException rejected) {
            finishWithoutMutation(taskGeneration,
                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(rejected)));
        }
    }

    private void confirmExecution(long taskGeneration,
                                  InstanceUpdateService.PreparedExecution execution) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                finalPlanText(execution),
                new ButtonType(GuiMessages.get("instanceUpdate.preview.apply")), ButtonType.CANCEL);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(GuiMessages.get("instanceUpdate.preview.finalTitle"));
        alert.setHeaderText(GuiMessages.get("instanceUpdate.preview.finalHeader"));
        ButtonType choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (!isCurrent(taskGeneration)) {
            execution.closeQuietly();
            return;
        }
        if (!GuiMessages.get("instanceUpdate.preview.apply").equals(choice.getText())) {
            execution.closeQuietly();
            finishCancelled(taskGeneration);
            return;
        }
        execute(taskGeneration, execution);
    }

    private String finalPlanText(InstanceUpdateService.PreparedExecution execution) {
        InstanceUpdateService.Preview preview = execution.preview();
        if (preview.packProfile()) {
            return finalPackPlan(execution);
        }
        StringBuilder text = new StringBuilder(loaderChange(preview));
        Set<String> rendered = new LinkedHashSet<>();
        for (ModUpdateService.PreparedModUpdate prepared : execution.modUpdates()) {
            String root = prepared.update().installedMod().projectId();
            for (PlannedModFile file : prepared.installationPlan().files()) {
                String key = file.version().projectId() + "@" + file.version().id();
                if (!rendered.add(key)) continue;
                String line = file.dependency()
                        ? GuiMessages.get("instanceUpdate.preview.dependency",
                                file.version().name(), file.version().versionNumber(), root)
                        : GuiMessages.get("instanceUpdate.preview.mod",
                                file.version().name(), prepared.update().installedMod().versionNumber(),
                                file.version().versionNumber());
                text.append("\n").append(line);
            }
            prepared.installationPlan().warnings().forEach(warning ->
                    text.append("\n").append(warning));
        }
        return text.toString();
    }

    private String finalPackPlan(InstanceUpdateService.PreparedExecution execution) {
        InstanceUpdateService.Preview preview = execution.preview();
        var plan = execution.packUpdate().preview();
        StringBuilder text = new StringBuilder(packDetails(preview));
        if (!preview.instance().minecraftVersion().equals(plan.minecraftVersion())) {
            text.append("\n").append(GuiMessages.get("instanceUpdate.preview.packMinecraft",
                    preview.instance().minecraftVersion(), plan.minecraftVersion()));
        }
        text.append("\n").append(GuiMessages.get("instanceUpdate.preview.packLoader",
                preview.instance().loaderName(), plan.loader(), plan.loaderVersion()));
        text.append("\n\n").append(GuiMessages.get("instanceUpdate.preview.packChanges",
                plan.changes().size()));
        plan.changes().stream().limit(60).forEach(change -> text.append("\n• ")
                .append(GuiMessages.get("instanceUpdate.preview.packChange."
                        + change.kind().name().toLowerCase(java.util.Locale.ROOT)))
                .append(" · ").append(change.path()));
        if (plan.changes().size() > 60) {
            text.append("\n").append(GuiMessages.get("instanceUpdate.preview.moreChanges",
                    plan.changes().size() - 60));
        }
        return text.toString();
    }

    private void confirmPack(long taskGeneration, InstanceUpdateService.Preview preview) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                packDetails(preview) + "\n\n"
                        + GuiMessages.get("instanceUpdate.preview.packContentsUnresolved"),
                new ButtonType(GuiMessages.get("instanceUpdate.preview.continue")), ButtonType.CANCEL);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(GuiMessages.get("instanceUpdate.preview.title"));
        alert.setHeaderText(GuiMessages.get("instanceUpdate.preview.packHeader"));
        ButtonType choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (!isCurrent(taskGeneration)) return;
        if (choice.getText().equals(GuiMessages.get("instanceUpdate.preview.continue"))) {
            preparePackExecution(taskGeneration, preview);
        } else {
            finishCancelled(taskGeneration);
        }
    }

    private String packDetails(InstanceUpdateService.Preview preview) {
        var update = preview.packUpdate();
        return GuiMessages.get("instanceUpdate.preview.pack", preview.packInstance().name(),
                preview.instance().profileId(), preview.instance().minecraftVersion(),
                preview.instance().loaderName(), preview.packInstance().currentVersion(),
                update.availableVersion().versionNumber());
    }

    private String loaderChange(InstanceUpdateService.Preview preview) {
        var loader = preview.loaderPlan();
        return loader.updateAvailable()
                ? GuiMessages.get("instanceUpdate.preview.loader", loader.loaderName(),
                        loader.currentVersion(), loader.targetVersion())
                : GuiMessages.get("instanceUpdate.preview.loaderCurrent", loader.loaderName(),
                        loader.currentVersion());
    }

    private void execute(long taskGeneration, InstanceUpdateService.PreparedExecution execution) {
        cancellable = false;
        setCancelVisible(false);
        status.setText(GuiMessages.get("instanceUpdate.button"));
        try {
            ui.controller.instanceUpdateService().execute(execution, update -> Platform.runLater(() -> {
                if (isCurrent(taskGeneration)) {
                    status.setText(GuiMessages.get("instanceUpdate." + update.stage()
                            .name().toLowerCase(java.util.Locale.ROOT), update.detail()));
                }
            }), ui.controller.modpackUpdateService())
                    .whenComplete((result, error) -> Platform.runLater(() -> {
                        if (!isCurrent(taskGeneration)) return;
                        finishExecution(taskGeneration, execution.preview().instance().profileId(), result, error);
                    }));
        } catch (RuntimeException rejected) {
            execution.closeQuietly();
            finishWithoutMutation(taskGeneration,
                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(rejected)));
        }
    }

    private void finishExecution(long taskGeneration, String profileId,
                                 InstanceUpdateService.Result result, Throwable error) {
        if (error != null) {
            finishWithoutMutation(taskGeneration,
                    GuiMessages.get("instanceUpdate.failed", ui.cleanMessage(unwrap(error))));
            return;
        }
        ui.controller.invalidateLaunchVersion(profileId);
        ui.versionManager.invalidateLocalVersionProfiles();
        ui.updateRuntimeSummary();
        String summary;
        if (result.packVersion() != null) {
            summary = GuiMessages.get("instanceUpdate.packComplete", result.packVersion());
        } else {
            String loader = GuiMessages.get(result.loader().updated()
                    ? "instanceUpdate.loaderUpdated" : "instanceUpdate.loaderCurrent",
                    result.loader().loaderName(), result.loader().version());
            summary = GuiMessages.get("instanceUpdate.complete", loader,
                    result.updatedMods(), result.skippedMods());
            if (!result.failures().isEmpty()) {
                summary += "\n" + GuiMessages.get("instanceUpdate.failures", result.failures().size()) + "\n"
                        + String.join("\n", result.failures().stream()
                                .map(failure -> failure.name() + ": "
                                        + ui.cleanMessage(failure.cause())).toList());
            }
        }
        if (!result.warnings().isEmpty()) summary += "\n" + String.join("\n", result.warnings());
        finishWithoutMutation(taskGeneration, summary);
        ui.setStatus(GuiMessages.get("instanceUpdate.button"), summary);
    }

    /** Completion adapter retained for the headless view test and existing internal callers. */
    void finish(String profileId, InstanceUpdateService.Result result, Throwable error) {
        long taskGeneration = generation.incrementAndGet();
        updating = true;
        finishExecution(taskGeneration, profileId, result, error);
    }

    private String displayVersion(String version, String fallback) {
        return version == null || version.isBlank() ? fallback : version;
    }

    private void finishWithoutMutation(long taskGeneration, String message) {
        if (!isCurrent(taskGeneration)) return;
        updating = false;
        cancellable = false;
        setCancelVisible(false);
        progress.setVisible(false);
        ui.setControlsBusy(false);
        status.setText(message);
        updateButton();
    }

    private void finishCancelled(long taskGeneration) {
        finishWithoutMutation(taskGeneration, GuiMessages.get("instanceUpdate.preview.cancelled"));
    }

    private void cancelPreflight() {
        if (!updating || !cancellable) return;
        finishCancelled(generation.incrementAndGet());
    }

    private boolean isCurrent(long taskGeneration) {
        return generation.get() == taskGeneration;
    }

    private void setCancelVisible(boolean visible) {
        if (cancelButton == null) return;
        cancelButton.setVisible(visible);
        cancelButton.setManaged(visible);
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
}
