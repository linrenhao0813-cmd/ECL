package com.ecl.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.function.Function;

/** Common JavaFX control construction for launcher pages and dialogs. */
final class LauncherUiFactory {
    private LauncherUiFactory() {
    }

    static Button actionButton(String text, String styleClass, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", styleClass);
        button.setOnAction(event -> action.run());
        return button;
    }

    static Label valueLabel() {
        Label label = new Label();
        label.getStyleClass().add("info-value");
        label.setWrapText(true);
        return label;
    }

    static Label valueLabel(String text) {
        Label label = valueLabel();
        label.setText(text);
        return label;
    }

    static Label bodyText(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("status-detail");
        label.setWrapText(true);
        return label;
    }

    static ScrollPane wheelScrollPane(Node content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.getStyleClass().add("main-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setMinHeight(0);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);
        scrollPane.setFocusTraversable(false);
        scrollPane.addEventFilter(ScrollEvent.SCROLL,
                event -> scrollByWheel(scrollPane, event));
        return scrollPane;
    }

    static void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static VBox surface(String title, String subtitle, Node... content) {
        VBox box = new VBox(12);
        box.getStyleClass().add("surface");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("section-title");
        box.getChildren().add(titleLabel);
        if (subtitle != null && !subtitle.isBlank()) {
            Label subtitleLabel = new Label(subtitle);
            subtitleLabel.getStyleClass().add("section-subtitle");
            subtitleLabel.setWrapText(true);
            box.getChildren().add(subtitleLabel);
        }
        box.getChildren().addAll(content);
        return box;
    }

    static HBox infoRow(String key, Label valueLabel) {
        return row(key, valueLabel);
    }

    static HBox controlRow(String key, Node control) {
        return row(key, control);
    }

    static void configureLocalizedCombo(
            ComboBox<String> combo, Function<String, String> displayName) {
        combo.setCellFactory(list -> localizedCell(displayName));
        combo.setButtonCell(localizedCell(displayName));
        applyFieldStyle(combo);
        combo.setPrefWidth(220);
    }

    static void applyFieldStyle(Control control) {
        control.getStyleClass().add("field-control");
        control.setMaxWidth(Double.MAX_VALUE);
    }

    static Button iconActionButton(Class<?> anchor, String iconResource,
                                   String fallbackIcon, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(iconNode(anchor, iconResource, fallbackIcon,
                34, "icon-button-image"));
        button.getStyleClass().addAll("app-button", "icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> action.run());
        return button;
    }

    static Node iconNode(Class<?> anchor, String resourcePath, String fallbackText,
                         double size, String styleClass) {
        URL iconUrl = resourcePath == null ? null : anchor.getResource(resourcePath);
        if (iconUrl != null) {
            ImageView icon = new ImageView(new Image(iconUrl.toExternalForm()));
            icon.setFitWidth(size);
            icon.setFitHeight(size);
            icon.setPreserveRatio(true);
            icon.getStyleClass().add(styleClass);
            return icon;
        }
        Label fallback = new Label(fallbackText == null ? "" : fallbackText);
        fallback.getStyleClass().add(styleClass);
        return fallback;
    }

    private static HBox row(String key, Node value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("info-key");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, keyLabel, spacer, value);
        row.getStyleClass().add("info-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static ListCell<String> localizedCell(Function<String, String> displayName) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : displayName.apply(item));
            }
        };
    }

    private static void scrollByWheel(ScrollPane scrollPane, ScrollEvent event) {
        double deltaY = event.getDeltaY();
        if (deltaY == 0 || scrollPane.getContent() == null
                || targetsNestedScroller(event.getTarget(), scrollPane)) {
            return;
        }
        double contentHeight = scrollPane.getContent().getBoundsInLocal().getHeight();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double scrollableHeight = contentHeight - viewportHeight;
        if (scrollableHeight <= 0) {
            return;
        }
        double nextValue = Math.max(0, Math.min(1,
                scrollPane.getVvalue() - deltaY / scrollableHeight));
        if (nextValue != scrollPane.getVvalue()) {
            scrollPane.setVvalue(nextValue);
            event.consume();
        }
    }

    private static boolean targetsNestedScroller(Object target, ScrollPane outerScrollPane) {
        if (!(target instanceof Node node)) {
            return false;
        }
        for (Node current = node; current != null && current != outerScrollPane;
             current = current.getParent()) {
            if (current instanceof ListView<?> || current instanceof ScrollPane) {
                return true;
            }
        }
        return false;
    }
}
