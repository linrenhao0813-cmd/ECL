package com.ecl.server;

import com.ecl.util.Messages;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/** Renders one public server entry and its current availability badge. */
final class ServerListCell extends ListCell<PublicServer> {
    private final ListView<PublicServer> resultList;
    private final Function<String, ServerStatus> statusLookup;
    private final Predicate<String> probing;

    ServerListCell(ListView<PublicServer> resultList,
                   Function<String, ServerStatus> statusLookup,
                   Predicate<String> probing) {
        this.resultList = resultList;
        this.statusLookup = statusLookup;
        this.probing = probing;
    }

    @Override
    protected void updateItem(PublicServer server, boolean empty) {
        super.updateItem(server, empty);
        if (empty || server == null) {
            setGraphic(null);
            setText(null);
            setAccessibleText(null);
            return;
        }
        Label icon = new Label(server.iconText());
        icon.getStyleClass().addAll("server-icon", categoryStyleClass(server.categoryEnum()));
        Label name = new Label(server.name());
        name.getStyleClass().add("server-item-title");
        HBox titleRow = new HBox(8, name, statusBadge(server));
        titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label description = new Label(server.description());
        description.getStyleClass().add("server-item-description");
        description.setWrapText(true);
        description.setMaxWidth(Double.MAX_VALUE);

        String meta = String.join(" · ", nonEmpty(server.categoryEnum().label(), server.region(),
                server.version().isBlank() ? "" : Messages.format("server.meta.version", server.version()),
                server.address()));
        Label metaLabel = new Label(meta);
        metaLabel.getStyleClass().add("server-item-address");

        HBox tags = new HBox(5);
        tags.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        server.tags().forEach(tag -> tags.getChildren().add(tagBadge(tag)));
        VBox text = new VBox(4, titleRow, description, metaLabel, tags);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, icon, text);
        row.prefWidthProperty().bind(resultList.widthProperty().subtract(44));
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        setGraphic(row);
        setAccessibleText(server.name() + "，" + meta + "，" + server.description());
    }

    private Node statusBadge(PublicServer server) {
        ServerStatus status = statusLookup.apply(server.address());
        Label badge = new Label();
        if (probing.test(server.address())) {
            badge.setText(Messages.get("server.status.checking"));
            badge.getStyleClass().add("server-status-unknown");
        } else if (status == null) {
            badge.setText(Messages.get("server.status.pending"));
            badge.getStyleClass().add("server-status-unknown");
        } else {
            switch (status.state()) {
                case ONLINE -> {
                    badge.setText(Messages.format("server.status.online",
                            formatPlayerCount(status.playersOnline()),
                            formatPlayerCount(status.playersMax())));
                    badge.getStyleClass().add("server-status-online");
                }
                case OFFLINE -> {
                    badge.setText(Messages.get("server.status.offline"));
                    badge.getStyleClass().add("server-status-offline");
                }
                default -> {
                    badge.setText(Messages.get("server.status.unknown"));
                    badge.getStyleClass().add("server-status-unknown");
                }
            }
        }
        badge.getStyleClass().add("server-status-badge");
        return badge;
    }

    private static Label tagBadge(String tag) {
        Label badge = new Label(tag);
        badge.getStyleClass().add("server-tag");
        return badge;
    }

    private static String categoryStyleClass(ServerCategory category) {
        return switch (category) {
            case SURVIVAL -> "server-cat-survival";
            case SMP -> "server-cat-smp";
            case PVP -> "server-cat-pvp";
            case TECH -> "server-cat-tech";
            case ENTERTAINMENT -> "server-cat-entertainment";
            default -> "server-cat-all";
        };
    }

    private static List<String> nonEmpty(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).toList();
    }

    private static String formatPlayerCount(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
