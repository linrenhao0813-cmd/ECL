package com.ecl.modrinth.ui;

import com.ecl.modrinth.api.ModSearchIndex;
import javafx.scene.control.ListCell;

/** Localized renderer for search ordering choices. */
final class ModSearchIndexCell extends ListCell<ModSearchIndex> {
    @Override
    protected void updateItem(ModSearchIndex item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null : switch (item) {
            case RELEVANCE -> "相关度";
            case DOWNLOADS -> "下载量";
            case FOLLOWS -> "关注数";
            case NEWEST -> "最新发布";
            case UPDATED -> "最近更新";
        });
    }
}
