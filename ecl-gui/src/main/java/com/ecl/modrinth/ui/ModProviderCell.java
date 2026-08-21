package com.ecl.modrinth.ui;

import com.ecl.modrinth.provider.ModMetadataProvider;
import javafx.scene.control.ListCell;

/** Renderer for Modrinth and CurseForge provider choices. */
final class ModProviderCell extends ListCell<ModMetadataProvider> {
    @Override
    protected void updateItem(ModMetadataProvider item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null : switch (item.source()) {
            case MODRINTH -> "Modrinth";
            case CURSEFORGE -> "CurseForge";
        });
    }
}
