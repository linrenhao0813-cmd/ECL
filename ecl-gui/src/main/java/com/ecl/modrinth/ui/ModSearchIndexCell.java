package com.ecl.modrinth.ui;

import com.ecl.modrinth.api.ModSearchIndex;
import com.ecl.util.Messages;
import javafx.scene.control.ListCell;

/** Localized renderer for search ordering choices. */
final class ModSearchIndexCell extends ListCell<ModSearchIndex> {
    @Override
    protected void updateItem(ModSearchIndex item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null : Messages.get("modBrowser.sort." + item.apiValue()));
    }
}
