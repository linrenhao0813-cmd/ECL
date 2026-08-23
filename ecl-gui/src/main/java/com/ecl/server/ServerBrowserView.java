package com.ecl.server;

import com.ecl.util.Messages;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 全球公开服务器浏览视图：分类导航 + 关键词搜索 + 服务器卡片列表，
 * 支持实时在线状态探测，以及把选中的服务器设为启动直连地址。
 */
public final class ServerBrowserView extends VBox implements AutoCloseable {
    private final ServerCatalog bundledCatalog = ServerCatalog.load();
    private final ServerDirectoryRefreshController directoryRefreshController =
            new ServerDirectoryRefreshController(new ServerDirectoryService());
    private final Consumer<String> statusConsumer;
    private final Consumer<String> connectConsumer;
    private final Map<String, ServerStatus> statusMap = new ConcurrentHashMap<>();
    private final Map<ServerCategory, Label> categoryCountLabels =
            new EnumMap<>(ServerCategory.class);
    private final ServerStatusProbeController statusProbeController =
            new ServerStatusProbeController();
    private final ServerCatalogFilter catalogFilter = new ServerCatalogFilter(bundledCatalog);
    private final ServerBrowserActions actions;

    private ListView<PublicServer> resultList;
    private TextField searchField;
    private Label resultCountLabel;
    private Button refreshStatusButton;
    private Button clearSearchButton;
    private Label selectedAddressLabel;
    private Button connectButton;
    private Button copyButton;
    private Button websiteButton;
    private volatile boolean closed;

    public ServerBrowserView(Consumer<String> statusConsumer, Consumer<String> connectConsumer) {
        this.statusConsumer = statusConsumer == null ? ignored -> { } : statusConsumer;
        this.connectConsumer = connectConsumer == null ? ignored -> { } : connectConsumer;
        this.actions = new ServerBrowserActions(this.statusConsumer, this.connectConsumer);
        buildView();
        refreshList();
        if (!Boolean.getBoolean("ecl.snapshot")) {
            probeMissingStatuses();
            startDirectoryRefresh(false);
        }
    }

    private void buildView() {
        getStyleClass().addAll("server-browser", "surface");
        setSpacing(12);
        setPadding(new Insets(16));
        setFillWidth(true);

        VBox navigation = buildCategoryNavigation();
        VBox content = new VBox(10);
        content.getStyleClass().add("server-browser-content");
        content.setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);

        Node resultArea = buildResultList();
        content.getChildren().addAll(buildSearchBar(), resultArea, buildActionRow());
        VBox.setVgrow(resultArea, Priority.ALWAYS);

        HBox layout = new HBox(16, navigation, content);
        layout.getStyleClass().add("server-browser-layout");
        layout.setAlignment(Pos.TOP_LEFT);
        layout.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(content, Priority.ALWAYS);
        VBox.setVgrow(layout, Priority.ALWAYS);
        getChildren().add(layout);
    }

    private VBox buildCategoryNavigation() {
        VBox navigation = new VBox(8);
        navigation.getStyleClass().add("server-category-nav");
        navigation.setPrefWidth(180);
        navigation.setMinWidth(180);
        navigation.setMaxWidth(180);

        Label title = new Label(Messages.get("server.category.title"));
        title.getStyleClass().add("server-category-nav-title");
        Label hint = new Label(Messages.get("server.category.hint"));
        hint.getStyleClass().add("server-category-nav-hint");
        navigation.getChildren().addAll(title, hint);

        List<ServerCategory> categories = List.of(
                ServerCategory.ALL, ServerCategory.SURVIVAL, ServerCategory.SMP,
                ServerCategory.PVP, ServerCategory.TECH, ServerCategory.ENTERTAINMENT);
        List<Button> categoryButtons = new ArrayList<>();
        for (ServerCategory category : categories) {
            Button button = createCategoryButton(category);
            button.setOnAction(event -> {
                catalogFilter.selectCategory(category);
                categoryButtons.forEach(item ->
                        item.getStyleClass().remove("server-category-item-active"));
                button.getStyleClass().add("server-category-item-active");
                refreshList();
            });
            categoryButtons.add(button);
            navigation.getChildren().add(button);
        }
        return navigation;
    }

    private Button createCategoryButton(ServerCategory category) {
        Label name = new Label(category.label());
        name.getStyleClass().add("server-category-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = new Label(Integer.toString(countFor(category)));
        count.getStyleClass().add("server-category-count");
        categoryCountLabels.put(category, count);
        HBox graphic = new HBox(8, name, spacer, count);
        graphic.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.setGraphic(graphic);
        graphic.prefWidthProperty().bind(button.widthProperty().subtract(24));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setAccessibleText(Messages.format(
                "server.category.accessible", category.label(), count.getText()));
        button.getStyleClass().add("server-category-item");
        if (category == catalogFilter.activeCategory()) {
            button.getStyleClass().add("server-category-item-active");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private int countFor(ServerCategory category) {
        return catalogFilter.count(category);
    }

    private Node buildSearchBar() {
        searchField = new TextField();
        searchField.setPromptText(Messages.get("server.search.prompt"));
        searchField.setAccessibleText(Messages.get("server.search.accessible"));
        searchField.getStyleClass().add("field-control");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        clearSearchButton = new Button(Messages.get("server.search.clear"));
        clearSearchButton.getStyleClass().addAll(
                "app-button", "ghost-button", "compact-button", "server-search-clear");
        clearSearchButton.setVisible(false);
        clearSearchButton.setManaged(false);
        clearSearchButton.setOnAction(event -> {
            searchField.clear();
            searchField.requestFocus();
        });
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            boolean hasQuery = newValue != null && !newValue.isBlank();
            clearSearchButton.setVisible(hasQuery);
            clearSearchButton.setManaged(hasQuery);
            refreshList();
        });

        refreshStatusButton = new Button(Messages.get("server.refresh"));
        refreshStatusButton.getStyleClass().addAll(
                "app-button", "secondary-button", "compact-button");
        refreshStatusButton.setOnAction(event -> {
            probeMissingStatuses();
            startDirectoryRefresh(true);
        });

        HBox searchBar = new HBox(8, searchField, clearSearchButton, refreshStatusButton);
        searchBar.getStyleClass().add("server-search-bar");
        searchBar.setAlignment(Pos.CENTER_LEFT);
        return searchBar;
    }

    private Node buildResultList() {
        resultCountLabel = new Label();
        resultCountLabel.getStyleClass().add("server-result-count");

        resultList = new ListView<>();
        resultList.getStyleClass().add("server-result-list");
        resultList.setMinHeight(300);
        resultList.setPrefHeight(480);
        resultList.setPlaceholder(new Label(Messages.get("server.result.empty")));
        resultList.setCellFactory(list -> new ServerListCell(
                list, statusMap::get, statusProbeController::isProbing));
        resultList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> updateActionRow(selected));
        VBox.setVgrow(resultList, Priority.ALWAYS);

        VBox listBox = new VBox(6, resultCountLabel, resultList);
        listBox.setMinHeight(0);
        VBox.setVgrow(listBox, Priority.ALWAYS);
        return listBox;
    }

    private Node buildActionRow() {
        selectedAddressLabel = new Label(Messages.get("server.action.select"));
        selectedAddressLabel.getStyleClass().add("server-selected-address");
        selectedAddressLabel.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        connectButton = actionButton(Messages.get("server.action.connect"), "primary-button");
        connectButton.setOnAction(event -> {
            PublicServer selected = resultList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                actions.connect(selected);
            }
        });
        copyButton = actionButton(Messages.get("server.action.copy"), "ghost-button");
        copyButton.setOnAction(event -> actions.copyAddress(
                resultList.getSelectionModel().getSelectedItem()));
        websiteButton = actionButton(Messages.get("server.action.website"), "ghost-button");
        websiteButton.setOnAction(event -> actions.openWebsite(
                resultList.getSelectionModel().getSelectedItem()));

        HBox actions = new HBox(8, connectButton, copyButton, websiteButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(12, selectedAddressLabel, spacer, actions);
        row.getStyleClass().add("server-action-row");
        row.setAlignment(Pos.CENTER_LEFT);
        updateActionRow(null);
        return row;
    }

    private static Button actionButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("app-button", styleClass, "compact-button");
        return button;
    }

    private void updateActionRow(PublicServer selected) {
        boolean hasSelection = selected != null;
        connectButton.setDisable(!hasSelection);
        copyButton.setDisable(!hasSelection);
        websiteButton.setDisable(!hasSelection || selected.websiteUri() == null);
        selectedAddressLabel.setText(hasSelection
                ? Messages.format("server.action.selected", selected.name(), selected.address())
                : Messages.get("server.action.select"));
    }

    private void refreshList() {
        List<PublicServer> filtered = catalogFilter.filter(searchField.getText());
        resultList.getItems().setAll(filtered);
        ServerCategory activeCategory = catalogFilter.activeCategory();
        String resultCount = activeCategory == ServerCategory.ALL
                ? Messages.format("server.result.count", filtered.size())
                : Messages.format("server.result.count.category",
                        filtered.size(), activeCategory.label());
        resultCountLabel.setText(resultCount + " · " + catalogFilter.directorySource());
        resultList.getSelectionModel().clearSelection();
        updateActionRow(null);
    }

    private void startDirectoryRefresh(boolean forceRefresh) {
        if (!directoryRefreshController.refresh(forceRefresh, () -> closed,
                this::finishDirectoryRefresh)) {
            return;
        }
        refreshStatusButton.setDisable(true);
        refreshStatusButton.setText(Messages.get("server.status.loading"));
    }

    private void finishDirectoryRefresh(
            ServerDirectoryService.DirectorySnapshot snapshot, Throwable error) {
        if (closed) {
            return;
        }
        Platform.runLater(() -> {
            refreshStatusButton.setText(Messages.get("server.refresh"));
            refreshStatusButton.setDisable(false);
            if (error != null || snapshot == null || snapshot.servers().isEmpty()) {
                resultList.refresh();
                statusConsumer.accept(Messages.get("server.status.directoryUnavailable"));
                probeMissingStatuses();
                return;
            }
            catalogFilter.applyDirectorySnapshot(snapshot);
            statusMap.clear();
            statusMap.putAll(snapshot.statuses());
            updateCategoryCounts();
            refreshList();
            String source = snapshot.cached()
                    ? Messages.get("server.source.cacheShort")
                    : Messages.get("server.source.onlineShort");
            statusConsumer.accept(Messages.format(
                    "server.status.loaded", source, catalogFilter.servers().size()));
            probeMissingStatuses();
        });
    }

    private void probeMissingStatuses() {
        statusProbeController.probe(catalogFilter.servers(), statusMap,
                () -> closed, () -> resultList.refresh());
    }

    private void updateCategoryCounts() {
        categoryCountLabels.forEach((category, label) ->
                label.setText(Integer.toString(countFor(category))));
    }

    @Override
    public void close() {
        closed = true;
        directoryRefreshController.close();
        statusProbeController.close();
    }


}
