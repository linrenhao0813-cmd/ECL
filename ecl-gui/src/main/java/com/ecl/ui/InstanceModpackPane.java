package com.ecl.ui;

import com.google.gson.JsonObject;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.io.IOException;

/** Exposes existing pack update/recovery workflows without editing pack ownership metadata. */
final class InstanceModpackPane extends VBox {
    InstanceModpackPane(LauncherUI ui) {
        super(12);
        setId("instance-modpack-settings");
        String profile = ui.getSelectedVersion();
        if (profile == null || profile.isBlank()) {
            getChildren().add(new Label(GuiMessages.get("instanceContent.noInstance")));
            return;
        }
        try {
            JsonObject metadata = ui.versionManager.loadVersionJson(profile);
            if (metadata == null || (!metadata.has("eclModpackName") && !metadata.has("eclModpackSource"))) {
                getChildren().add(new Label(GuiMessages.get("instanceContent.notModpack")));
                return;
            }
            Label information = new Label(GuiMessages.get("instanceContent.packInfo",
                    value(metadata, "eclModpackName"), value(metadata, "eclModpackVersion"),
                    value(metadata, "eclModpackSource"), value(metadata, "eclModpackProjectId")));
            information.setWrapText(true);
            InstanceUpgradeRecoveryWorkflow recovery = new InstanceUpgradeRecoveryWorkflow(ui);
            var update = ui.createActionButton(GuiMessages.get("instanceContent.checkUpdates"),
                    "secondary-button", () -> ui.instanceUpdates.startFor(profile));
            update.setId("modpack-check-updates");
            update.setDisable(!"modrinth".equals(value(metadata, "eclModpackSource")));
            var restore = ui.createActionButton(com.ecl.util.Messages.get("instanceRecovery.button"),
                    "secondary-button", () -> recovery.open(profile, () -> { }));
            restore.setId("modpack-restore-update");
            var config = ui.createActionButton(GuiMessages.get("instanceContent.openConfig"),
                    "secondary-button", () -> ui.openLocalFolder(new java.io.File(ui.resolveVersionGameDir(profile), "config"),
                            GuiMessages.get("instanceContent.openConfig")));
            config.setId("modpack-open-config");
            getChildren().addAll(information, new FlowPane(8, 8, update, restore, config), ui.instanceUpdates.createStatusMirror());
        } catch (IOException | RuntimeException error) {
            Label failure = new Label(ui.cleanMessage(error));
            failure.setWrapText(true);
            getChildren().add(failure);
        }
    }

    private static String value(JsonObject metadata, String key) {
        return metadata.has(key) ? metadata.get(key).getAsString() : "";
    }
}
