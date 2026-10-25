package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.download.GameDownloader;
import com.ecl.download.GameInstallationRepairService;
import com.ecl.download.GameInstallationVerifier;
import com.ecl.download.DownloadTaskCenter;
import com.ecl.game.DefaultIsolationType;
import com.ecl.game.InstanceGameSettings;
import com.ecl.game.InstanceGameSettingsStore;
import com.ecl.modrinth.instance.ModLoader;
import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.instance.VersionProfileModInstanceContext;
import com.ecl.util.FileUtil;
import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.ecl.util.Messages;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Keeps inspection and repair work tied to the instance that started it. */
final class InstanceCheckRepairWorkflow {
    private final LauncherUI ui;
    private final Set<String> busyProfiles = new HashSet<>();

    InstanceCheckRepairWorkflow(LauncherUI ui) {
        this.ui = ui;
    }

    boolean isBusy(String profileId) {
        return busyProfiles.contains(profileId);
    }

    void start(String profileId, Runnable onRefreshed) {
        if (profileId == null || profileId.isBlank() || !busyProfiles.add(profileId)) return;
        if (isPreparing(profileId)) {
            busyProfiles.remove(profileId);
            showMessage(profileId, Messages.get("instanceRepair.launchPreparing"));
            onRefreshed.run();
            return;
        }
        Path versions = ECLConfig.getVersionsDir().toPath().toAbsolutePath().normalize();
        Path libraries = ECLConfig.getLibrariesDir().toPath().toAbsolutePath().normalize();
        Path assets = ECLConfig.getAssetsDir().toPath().toAbsolutePath().normalize();
        Path gameRoot = ui.getConfiguredGameRootDir().toPath().toAbsolutePath().normalize();
        DownloadTaskCenter.TaskHandle<Inspection> task;
        try {
            task = ui.downloadTaskCenter.submit(
                    Messages.format("instanceRepair.inspectTask", profileId), context -> {
                        context.updateStatus(Messages.get("instanceRepair.inspecting"));
                        requireActive(context);
                        GameInstallationVerifier.Report report = ui.controller
                                .gameInstallationRepairService()
                                .inspect(profileId, versions, libraries, assets, gameRoot.resolve("versions"));
                        com.ecl.launcher.VersionManager.VersionDownloadTarget target = null;
                        String targetError = "";
                        try {
                            target = ui.versionManager.resolveDownloadTarget(profileId);
                        } catch (IOException | RuntimeException error) {
                            targetError = ui.cleanMessage(error);
                        }
                        ModInstanceContext instance = null;
                        String instanceError = "";
                        try {
                            instance = loadRepairContext(profileId, versions, gameRoot, target);
                            ui.controller.registerModInstance(instance);
                        } catch (IOException | IllegalArgumentException error) {
                            instanceError = ui.cleanMessage(error);
                        }
                        requireActive(context);
                        context.updateProgress(1);
                        return new Inspection(profileId, report, instance, instanceError,
                                target, targetError, versions, libraries, assets, gameRoot);
                    });
        } catch (RuntimeException rejected) {
            busyProfiles.remove(profileId);
            onRefreshed.run();
            showMessage(profileId, Messages.format("instanceRepair.failed", ui.cleanMessage(rejected)));
            return;
        }
        task.completion().whenComplete((inspection, error) -> Platform.runLater(() -> {
            if (error != null) {
                finish(profileId, Messages.format(isCancelled(error)
                        ? "instanceRepair.cancelled" : "instanceRepair.failed", ui.cleanMessage(error)),
                        onRefreshed, false);
                return;
            }
            showReport(inspection, onRefreshed);
        }));
    }

    private void showReport(Inspection inspection, Runnable onRefreshed) {
        boolean canRepair = inspection.instance() != null && GameInstallationRepairService.canRepair(
                inspection.report(), inspection.target(), inspection.versions());
        TextArea details = new TextArea(formatReport(inspection));
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefColumnCount(78);
        details.setPrefRowCount(20);
        details.setAccessibleText(Messages.get("instanceRepair.report"));
        ButtonType repair = new ButtonType(Messages.get("instanceRepair.repair"));
        ButtonType close = new ButtonType(Messages.get("settings.cancel"));
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "", repair, close);
        alert.initOwner(ui.primaryStage);
        alert.setTitle(Messages.get("instanceRepair.reportTitle"));
        alert.setHeaderText(Messages.format("instanceRepair.reportHeader", inspection.profileId()));
        alert.getDialogPane().setContent(details);
        alert.getDialogPane().lookupButton(repair).setDisable(!canRepair);
        ButtonType choice = alert.showAndWait().orElse(close);
        if (choice == repair && canRepair) {
            confirmRepair(inspection, onRefreshed);
        } else {
            finish(inspection.profileId(), Messages.get("instanceRepair.inspectComplete"),
                    onRefreshed, false);
        }
    }

    private String formatReport(Inspection inspection) {
        StringBuilder output = new StringBuilder();
        output.append(Messages.format("instanceRepair.summary",
                inspection.report().count(GameInstallationVerifier.Status.VERIFIED),
                inspection.report().count(GameInstallationVerifier.Status.MISSING),
                inspection.report().count(GameInstallationVerifier.Status.CORRUPT),
                inspection.report().count(GameInstallationVerifier.Status.UNVERIFIABLE)));
        for (GameInstallationVerifier.Category category : GameInstallationVerifier.Category.values()) {
            long count = inspection.report().checks().stream()
                    .filter(check -> check.category() == category).count();
            if (count > 0) output.append("\n").append(Messages.format("instanceRepair.categoryCount",
                    Messages.get("instanceRepair.category." + category.name().toLowerCase(java.util.Locale.ROOT)), count));
        }
        if (!inspection.instanceError().isBlank()) {
            output.append("\n\n").append(Messages.format("instanceRepair.instanceUnavailable",
                    inspection.instanceError()));
        }
        if (!inspection.targetError().isBlank()) {
            output.append("\n\n").append(Messages.format("instanceRepair.targetUnavailable",
                    inspection.targetError()));
        }
        List<GameInstallationVerifier.Check> issues = inspection.report().issues();
        if (!issues.isEmpty()) {
            output.append("\n\n").append(Messages.get("instanceRepair.issues"));
            issues.stream().limit(40).forEach(check -> output.append("\n• ")
                    .append(Messages.get("instanceRepair.category."
                            + check.category().name().toLowerCase(java.util.Locale.ROOT)))
                    .append(" · ").append(Messages.get("instanceRepair.status."
                            + check.status().name().toLowerCase(java.util.Locale.ROOT)))
                    .append(" · ").append(check.path())
                    .append(check.reason().isBlank() ? "" : " — " + check.reason()));
            if (issues.size() > 40) {
                output.append("\n").append(Messages.format("instanceRepair.more", issues.size() - 40));
            }
        }
        return output.toString();
    }

    private void confirmRepair(Inspection inspection, Runnable onRefreshed) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                Messages.get("instanceRepair.confirmBody"),
                new ButtonType(Messages.get("instanceRepair.confirmRepair")), ButtonType.CANCEL);
        confirm.initOwner(ui.primaryStage);
        confirm.setTitle(Messages.get("instanceRepair.confirmTitle"));
        confirm.setHeaderText(Messages.format("instanceRepair.confirmHeader", inspection.profileId()));
        ButtonType choice = confirm.showAndWait().orElse(ButtonType.CANCEL);
        if (!choice.getText().equals(Messages.get("instanceRepair.confirmRepair"))) {
            finish(inspection.profileId(), Messages.get("instanceRepair.cancelled"), onRefreshed, false);
            return;
        }
        startRepair(inspection, onRefreshed);
    }

    private void startRepair(Inspection inspection, Runnable onRefreshed) {
        if (isPreparing(inspection.profileId())) {
            finish(inspection.profileId(), Messages.get("instanceRepair.launchPreparing"),
                    onRefreshed, false);
            return;
        }
        DownloadTaskCenter.TaskHandle<GameInstallationRepairService.Result> task;
        try {
            task = ui.downloadTaskCenter.submit(
                Messages.format("instanceRepair.repairTask", inspection.profileId()), context -> {
                    requireActive(context);
                    if (isPreparing(inspection.profileId())) {
                        throw new IOException(Messages.get("instanceRepair.launchPreparing"));
                    }
                    try (GameDownloader downloader = new GameDownloader()) {
                        context.registerCancellation(downloader::cancelDownload);
                        GameDownloader.DownloadListener listener = new GameDownloader.DownloadListener() {
                            @Override public void onStatus(String message) { context.updateStatus(message); }
                            @Override public void onProgress(long downloaded, long total) {
                                context.updateProgress(downloaded, total);
                            }
                            @Override public void onError(String message) { context.updateStatus(message); }
                            @Override public void onComplete() { context.updateProgress(1); }
                        };
                        GameInstallationRepairService.Result result = ui.controller
                                .gameInstallationRepairService().repair(inspection.instance(),
                                        inspection.report(), inspection.target(), downloader, listener,
                                        context::isCancelled, inspection.versions(), inspection.libraries(),
                                        inspection.assets(), inspection.gameRoot());
                        requireActive(context);
                        return result;
                    }
                });
        } catch (RuntimeException rejected) {
            finish(inspection.profileId(), Messages.format("instanceRepair.failed", ui.cleanMessage(rejected)),
                    onRefreshed, false);
            return;
        }
        task.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                finish(inspection.profileId(), Messages.format(isCancelled(error)
                        ? "instanceRepair.cancelled" : "instanceRepair.failed", ui.cleanMessage(error)),
                        onRefreshed, false);
                return;
            }
            String summary = Messages.format("instanceRepair.repairSummary",
                    result.repaired().size(), result.failed().size(), result.unverifiable().size());
            finish(inspection.profileId(), summary, onRefreshed, true);
        }));
    }

    private void finish(String profileId, String message, Runnable onRefreshed, boolean repaired) {
        busyProfiles.remove(profileId);
        if (repaired) {
            ui.controller.invalidateLaunchVersion(profileId);
            ui.versionManager.invalidateLocalVersionProfiles();
            ui.updateRuntimeSummary();
        }
        onRefreshed.run();
        ui.setStatus(Messages.get("instanceRepair.title"), message);
        if (!Messages.get("instanceRepair.inspectComplete").equals(message)) showMessage(profileId, message);
    }

    private ModInstanceContext loadRepairContext(String profileId, Path versions, Path gameRoot,
            com.ecl.launcher.VersionManager.VersionDownloadTarget target) throws IOException {
        try {
            return VersionProfileModInstanceContext.load(profileId, versions, gameRoot,
                    ui.gameRepository().runDirectory(profileId));
        } catch (IOException | RuntimeException damagedProfile) {
            if (target == null) throw new IOException(Messages.format(
                    "instanceRepair.contextUnavailable", ui.cleanMessage(damagedProfile)), damagedProfile);
        }
        Path versionsRoot = gameRoot.resolve("versions").normalize();
        Path instanceRoot = FileUtil.safeResolveUnder(versionsRoot.toFile(), profileId).toPath();
        FileUtil.validateExistingAncestors(versionsRoot, instanceRoot);
        JsonObject profile = null;
        Path profileFile = FileUtil.safeVersionJson(versions.toFile(), profileId).toPath();
        if (Files.isRegularFile(profileFile)) {
            try {
                profile = HttpUtil.readJson(profileFile.toFile());
            } catch (IOException damaged) {
                if (!profileId.equals(target.downloadVersionId())) {
                    throw new IOException(Messages.format("instanceRepair.customMetadataDamaged",
                            ui.cleanMessage(damaged)), damaged);
                }
            }
        } else if (!profileId.equals(target.downloadVersionId())) {
            throw new IOException(Messages.get("instanceRepair.customMetadataMissing"));
        }
        String minecraft = profile == null ? target.downloadVersionId()
                : JsonUtil.getString(profile, "eclMinecraftVersion", target.downloadVersionId());
        ModLoader loader = ModLoader.fromApiName(profile == null ? ""
                : JsonUtil.getString(profile, "eclModLoader", ""));
        InstanceGameSettingsStore settingsStore = new InstanceGameSettingsStore();
        Path settingsFile = settingsStore.settingsFile(instanceRoot);
        FileUtil.validateExistingAncestors(instanceRoot, settingsFile);
        boolean settingsExist = Files.isRegularFile(settingsFile);
        InstanceGameSettings settings = settingsStore.load(instanceRoot);
        Path runDirectory;
        if (settings.overridesRunningDirectory()) {
            runDirectory = settings.hasCustomDirectory()
                    ? Path.of(settings.runningDirectory()).toAbsolutePath().normalize() : instanceRoot;
        } else if (!settingsExist && hasLegacyIsolatedData(instanceRoot)) {
            runDirectory = instanceRoot;
        } else {
            DefaultIsolationType isolation = DefaultIsolationType.parse(
                    ui.settingsManager.get(ECLConfig.KEY_DEFAULT_ISOLATION_TYPE));
            runDirectory = isolation == DefaultIsolationType.ALWAYS
                    || isolation == DefaultIsolationType.MODDED && loader.supportsMods()
                    ? instanceRoot : gameRoot;
        }
        return new RepairInstance(VersionProfileModInstanceContext.instanceIdFor(instanceRoot),
                profileId, minecraft, loader, runDirectory);
    }

    private static boolean hasLegacyIsolatedData(Path instanceRoot) {
        for (String name : List.of("mods", "saves", "config", "logs", "resourcepacks",
                "shaderpacks", "screenshots", "options.txt", "servers.dat")) {
            if (Files.exists(instanceRoot.resolve(name))) return true;
        }
        return false;
    }

    private void showMessage(String profileId, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner((Window) ui.primaryStage);
        alert.setTitle(Messages.get("instanceRepair.title"));
        alert.setHeaderText(profileId);
        alert.showAndWait();
    }

    private static void requireActive(DownloadTaskCenter.TaskContext context) {
        if (context.isCancelled()) throw new CancellationException("Instance repair cancelled");
    }

    private static boolean isCancelled(Throwable error) {
        while (error != null) {
            if (error instanceof CancellationException || error instanceof InterruptedException) return true;
            error = error.getCause();
        }
        return false;
    }

    private boolean isPreparing(String profileId) {
        return ui.gameLaunch != null && ui.gameLaunch.isPreparingVersion(profileId);
    }

    private record Inspection(String profileId, GameInstallationVerifier.Report report,
                              ModInstanceContext instance, String instanceError,
                              com.ecl.launcher.VersionManager.VersionDownloadTarget target,
                              String targetError, Path versions, Path libraries, Path assets,
                              Path gameRoot) { }

    private record RepairInstance(UUID instanceId, String profileId, String minecraftVersion,
                                  ModLoader loader, Path gameDirectory) implements ModInstanceContext {
        @Override public Path modsDirectory() { return gameDirectory.resolve("mods"); }
    }
}
