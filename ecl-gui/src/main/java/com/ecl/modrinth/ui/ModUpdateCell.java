package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.ModUpdate;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

/** Renderer for available mod updates. */
final class ModUpdateCell extends ListCell<ModUpdate> {
    @Override
    protected void updateItem(ModUpdate update, boolean empty) {
        super.updateItem(update, empty);
        if (empty || update == null) {
            setGraphic(null);
            setText(null);
            return;
        }
        Label title = new Label(update.installedMod().displayName());
        title.getStyleClass().add("mod-item-title");
        Label detail = new Label(update.installedMod().versionNumber() + "  →  "
                + update.availableVersion().versionNumber() + "  ·  "
                + update.availableVersion().versionType());
        detail.getStyleClass().add("status-detail");
        setGraphic(new VBox(3, title, detail));
    }
}
