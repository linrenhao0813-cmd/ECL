package com.ecl.modrinth.ui;

import com.ecl.modrinth.model.ModVersion;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

import java.util.function.Supplier;

/** Renderer for compatible mod version choices and recommendation badges. */
final class ModVersionCell extends ListCell<ModVersion> {
    private final Supplier<String> recommendedVersionId;

    ModVersionCell(Supplier<String> recommendedVersionId) {
        this.recommendedVersionId = recommendedVersionId;
    }

    @Override
    protected void updateItem(ModVersion item, boolean empty) {
        super.updateItem(item, empty);
        setText(null);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }
        Label version = new Label(item.versionNumber());
        version.getStyleClass().add("mod-version-number");
        HBox badges = new HBox(5, version,
                ModUiControls.versionBadge(ModUiFormatter.channelLabel(item.versionType()),
                        "mod-channel-" + ModUiFormatter.normalizedChannel(item.versionType())));
        item.loaders().stream().limit(2).forEach(loader -> badges.getChildren().add(
                ModUiControls.versionBadge(ModUiFormatter.loaderDisplay(loader),
                        "mod-loader-badge")));
        if (item.featured() || item.id().equals(recommendedVersionId.get())) {
            badges.getChildren().add(
                    ModUiControls.versionBadge("★ 推荐", "mod-recommended"));
        }
        badges.setAlignment(Pos.CENTER_LEFT);
        setGraphic(badges);
    }
}
