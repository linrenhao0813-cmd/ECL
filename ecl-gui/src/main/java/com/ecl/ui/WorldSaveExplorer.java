package com.ecl.ui;

import com.ecl.game.WorldSave;
import com.ecl.util.Messages;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Explorer-style navigation over discovered worlds; all file operations remain in existing services. */
final class WorldSaveExplorer extends VBox {
    private static final Location ROOT = new Location("", null);
    private final LauncherUI ui;
    private final Consumer<WorldSave> showDetails;
    private final Runnable refresh;
    private final TreeView<Location> tree = new TreeView<>();
    private final TableView<WorldSave> table = new TableView<>();
    private final TextField search = new TextField();
    private final HBox breadcrumbs = new HBox(3);
    private final StackPane content = new StackPane();
    private final ScrollPane detailScroll;
    private final Label status = new Label();
    private final Label selectionHint = new Label();
    private final Button back;
    private final Button forward;
    private final Button up;
    private final Button open;
    private final Button folder;
    private final List<Location> history = new ArrayList<>(List.of(ROOT));
    private int historyIndex;
    private boolean syncingTree;
    private boolean loading;
    private String loadError;
    private List<WorldSave> worlds = List.of();

    WorldSaveExplorer(LauncherUI ui, Node details, Consumer<WorldSave> showDetails, Runnable refresh) {
        this.ui = ui;
        this.showDetails = showDetails;
        this.refresh = refresh;
        setId("world-save-explorer");
        getStyleClass().add("save-explorer");
        detailScroll = new ScrollPane(details);
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.getStyleClass().add("explorer-detail-scroll");
        back = navigationButton("←", "explorer.back", () -> travel(-1));
        forward = navigationButton("→", "explorer.forward", () -> travel(1));
        up = navigationButton("↑", "explorer.up", this::goUp);
        open = ui.createActionButton(GuiMessages.get("explorer.open"), "secondary-button", this::openSelected);
        folder = ui.createActionButton(GuiMessages.get("explorer.folder"), "ghost-button", this::openFolder);
        build();
        rebuildTree();
        renderLocation();
    }

    private void build() {
        breadcrumbs.getStyleClass().add("explorer-address");
        breadcrumbs.setAlignment(Pos.CENTER_LEFT);
        breadcrumbs.setMinWidth(0);
        HBox.setHgrow(breadcrumbs, Priority.ALWAYS);
        search.setId("save-search");
        search.setPromptText(GuiMessages.get("explorer.search"));
        search.setPrefWidth(230);
        search.setMinWidth(150);
        search.getStyleClass().add("explorer-search");
        search.textProperty().addListener((observable, previous, value) -> filterWorlds());
        HBox navigation = new HBox(6, back, forward, up, breadcrumbs, search);
        navigation.setAlignment(Pos.CENTER_LEFT);
        navigation.getStyleClass().add("explorer-navigation");
        Button reload = navigationButton("↻", "explorer.refresh", refresh);
        selectionHint.getStyleClass().add("status-detail");
        selectionHint.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(selectionHint, Priority.ALWAYS);
        HBox toolbar = new HBox(10, open, folder, selectionHint, reload);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("explorer-toolbar");

        configureTree();
        configureTable();
        Label navigationTitle = new Label(GuiMessages.get("explorer.navigation"));
        navigationTitle.getStyleClass().add("explorer-nav-title");
        VBox sidebar = new VBox(8, navigationTitle, tree);
        sidebar.getStyleClass().add("explorer-sidebar");
        sidebar.setPrefWidth(225);
        sidebar.setMinWidth(180);
        sidebar.setMaxWidth(225);
        VBox.setVgrow(tree, Priority.ALWAYS);
        content.setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);
        HBox body = new HBox(sidebar, content);
        body.setMinHeight(380);
        body.setPrefHeight(470);
        VBox.setVgrow(body, Priority.ALWAYS);
        status.getStyleClass().add("explorer-status");
        getChildren().addAll(navigation, toolbar, body, status);
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.isAltDown() && event.getCode() == KeyCode.LEFT) travel(-1);
            else if (event.isAltDown() && event.getCode() == KeyCode.RIGHT) travel(1);
            else if (event.isAltDown() && event.getCode() == KeyCode.UP) goUp();
            else if (event.getCode() == KeyCode.F5) refresh.run();
            else if (event.isControlDown() && event.getCode() == KeyCode.F) search.requestFocus();
            else return;
            event.consume();
        });
    }

    private void configureTree() {
        tree.setId("save-navigation-tree");
        tree.getStyleClass().add("explorer-tree");
        tree.setCellFactory(view -> new TreeCell<>() {
            @Override protected void updateItem(Location item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(locationName(item));
                setGraphic(folderIcon());
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (!syncingTree && selected != null) navigate(selected.getValue());
        });
    }

    private void configureTable() {
        table.setId("save-file-table");
        table.getStyleClass().add("explorer-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<WorldSave, String> name = column("explorer.name", WorldSave::name, 250);
        TableColumn<WorldSave, String> modified = column("explorer.modified", WorldSaveExplorer::modifiedTime, 150);
        modified.setComparator(Comparator.naturalOrder());
        table.getColumns().add(name);
        table.getColumns().add(modified);
        table.getColumns().add(column("explorer.version", WorldSave::minecraftVersion, 100));
        table.getColumns().add(column("explorer.type", WorldSave::loaderLabel, 140));
        table.getColumns().add(column("explorer.instance", world -> world.sharedDirectory()
                ? Messages.get("saves.shared") : world.instanceId(), 180));
        table.setRowFactory(view -> {
            TableRow<WorldSave> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getClickCount() == 2) openWorld(row.getItem());
            });
            MenuItem edit = new MenuItem(GuiMessages.get("explorer.open"));
            edit.setOnAction(event -> openWorld(row.getItem()));
            MenuItem reveal = new MenuItem(GuiMessages.get("explorer.folder"));
            reveal.setOnAction(event -> revealFolder(row.getItem()));
            ContextMenu menu = new ContextMenu(edit, reveal);
            row.emptyProperty().addListener((observable, previous, empty) -> row.setContextMenu(empty ? null : menu));
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) { openSelected(); event.consume(); }
        });
        table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> updateActions());
    }

    private static TableColumn<WorldSave, String> column(String key, Function<WorldSave, String> value, double width) {
        TableColumn<WorldSave, String> column = new TableColumn<>(GuiMessages.get(key));
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        column.setMinWidth(75);
        return column;
    }

    private Button navigationButton(String text, String key, Runnable action) {
        Button button = ui.createActionButton(text, "ghost-button", action);
        button.getStyleClass().add("explorer-nav-button");
        button.setTooltip(new Tooltip(GuiMessages.get(key)));
        button.setAccessibleText(GuiMessages.get(key));
        return button;
    }

    void setLoading() {
        loading = true;
        loadError = null;
        status.setText(GuiMessages.get("explorer.loading"));
        updatePlaceholder();
    }

    void showLoadError(String message) {
        loading = false;
        loadError = message;
        status.setText(GuiMessages.get("explorer.failed", message));
        updatePlaceholder();
    }

    void setWorlds(List<WorldSave> scanned, String groupId, String worldName) {
        worlds = List.copyOf(scanned);
        loading = false;
        loadError = null;
        if (worldName != null) {
            worlds.stream().filter(world -> world.name().equals(worldName) && world.groupId().equals(groupId))
                    .findFirst().ifPresent(world -> history.set(historyIndex, locationOf(world)));
        }
        if (currentWorld() == null && location().worldPath() != null) history.set(historyIndex, ROOT);
        rebuildTree();
        renderLocation();
    }

    private void rebuildTree() {
        syncingTree = true;
        try {
            TreeItem<Location> root = new TreeItem<>(ROOT);
            root.setExpanded(true);
            worlds.stream().map(WorldSave::groupId).distinct().sorted().forEach(group -> {
                TreeItem<Location> directory = new TreeItem<>(new Location(group, null));
                worlds.stream().filter(world -> world.groupId().equals(group)).sorted(Comparator.comparing(WorldSave::name))
                        .forEach(world -> directory.getChildren().add(new TreeItem<>(locationOf(world))));
                root.getChildren().add(directory);
            });
            tree.setRoot(root);
        } finally {
            syncingTree = false;
        }
    }

    private void navigate(Location target) {
        if (target == null || target.equals(location())) return;
        history.subList(historyIndex + 1, history.size()).clear();
        history.add(target);
        historyIndex++;
        search.clear();
        renderLocation();
    }

    private void travel(int direction) {
        int next = historyIndex + direction;
        if (next < 0 || next >= history.size()) return;
        historyIndex = next;
        search.clear();
        renderLocation();
    }

    private void goUp() {
        navigate(location().worldPath() == null ? ROOT : new Location(location().groupId(), null));
    }

    private void renderLocation() {
        WorldSave world = currentWorld();
        showDetails.accept(world);
        content.getChildren().setAll(world == null ? table : detailScroll);
        search.setDisable(world != null);
        if (world != null) detailScroll.setVvalue(0);
        filterWorlds();
        rebuildBreadcrumbs(world);
        selectTreeLocation(tree.getRoot());
        back.setDisable(historyIndex == 0);
        forward.setDisable(historyIndex == history.size() - 1);
        up.setDisable(location().equals(ROOT));
        updateActions();
    }

    private void selectTreeLocation(TreeItem<Location> item) {
        if (item == null) return;
        if (item.getValue().equals(location())) {
            syncingTree = true;
            try {
                for (TreeItem<Location> parent = item.getParent(); parent != null; parent = parent.getParent()) parent.setExpanded(true);
                tree.getSelectionModel().select(item);
            } finally {
                syncingTree = false;
            }
            return;
        }
        item.getChildren().forEach(this::selectTreeLocation);
    }

    private void rebuildBreadcrumbs(WorldSave world) {
        breadcrumbs.getChildren().clear();
        addBreadcrumb(GuiMessages.get("explorer.root"), ROOT);
        if (!location().groupId().isBlank()) addBreadcrumb(groupName(location().groupId()), new Location(location().groupId(), null));
        if (world != null) addBreadcrumb(world.name(), location());
    }

    private void addBreadcrumb(String title, Location target) {
        if (!breadcrumbs.getChildren().isEmpty()) breadcrumbs.getChildren().add(new Label("›"));
        Button button = ui.createActionButton(title, "ghost-button", () -> navigate(target));
        button.getStyleClass().add("explorer-breadcrumb");
        button.setMinWidth(35);
        button.setTooltip(new Tooltip(title));
        breadcrumbs.getChildren().add(button);
    }

    private void filterWorlds() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        table.getItems().setAll(worlds.stream()
                .filter(world -> location().groupId().isBlank() || location().groupId().equals(world.groupId()))
                .filter(world -> (world.name() + " " + world.instanceId() + " " + world.minecraftVersion()
                        + " " + world.loaderLabel()).toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(WorldSave::lastModified).reversed()).toList());
        table.sort();
        if (loadError != null) status.setText(GuiMessages.get("explorer.failed", loadError));
        else if (loading) status.setText(GuiMessages.get("explorer.loading"));
        else status.setText(currentWorld() == null
                ? GuiMessages.get("explorer.count", table.getItems().size(), worlds.size()) : currentWorld().directory().toString());
        updatePlaceholder();
    }

    private void updatePlaceholder() {
        Label placeholder = new Label(loadError != null ? GuiMessages.get("explorer.retryHint")
                : GuiMessages.get(loading ? "explorer.loading" : search.getText().isBlank() ? "explorer.empty" : "explorer.noResults"));
        placeholder.getStyleClass().add("status-detail");
        table.setPlaceholder(placeholder);
    }

    private void updateActions() {
        WorldSave selected = currentWorld() == null ? table.getSelectionModel().getSelectedItem() : currentWorld();
        open.setDisable(selected == null || currentWorld() != null);
        folder.setDisable(selected == null);
        selectionHint.setText(selected == null ? GuiMessages.get("explorer.openHint") : selected.name());
    }

    private void openSelected() { openWorld(table.getSelectionModel().getSelectedItem()); }

    private void openWorld(WorldSave world) { if (world != null) navigate(locationOf(world)); }

    private void openFolder() {
        revealFolder(currentWorld() == null ? table.getSelectionModel().getSelectedItem() : currentWorld());
    }

    private void revealFolder(WorldSave world) {
        if (world != null) ui.openLocalFolder(world.directory().toFile(), GuiMessages.get("explorer.folder"));
    }

    private WorldSave currentWorld() {
        return worlds.stream().filter(world -> Objects.equals(world.directory(), location().worldPath())).findFirst().orElse(null);
    }

    private Location location() { return history.get(historyIndex); }

    private static Location locationOf(WorldSave world) { return new Location(world.groupId(), world.directory()); }

    private String locationName(Location location) {
        if (location.worldPath() != null) return location.worldPath().getFileName().toString();
        return location.groupId().isBlank() ? GuiMessages.get("explorer.root") : groupName(location.groupId());
    }

    private String groupName(String groupId) {
        return worlds.stream().filter(world -> world.groupId().equals(groupId)).findFirst()
                .map(world -> world.sharedDirectory() ? Messages.get("saves.shared")
                        : world.minecraftVersion() + " · " + world.loaderLabel()).orElse(GuiMessages.get("explorer.root"));
    }

    private static String modifiedTime(WorldSave world) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(world.lastModified()));
    }

    private static Node folderIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent("M1 4 H7 L9 6 H17 V16 H1 Z");
        icon.getStyleClass().add("explorer-folder-icon");
        return icon;
    }

    private record Location(String groupId, Path worldPath) { }
}
