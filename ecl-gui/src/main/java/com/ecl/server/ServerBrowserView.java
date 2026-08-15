package com.ecl.server;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 全球公开服务器浏览视图：分类导航 + 关键词搜索 + 服务器卡片列表，
 * 支持实时在线状态探测，以及把选中的服务器设为启动直连地址。
 */
public final class ServerBrowserView extends VBox implements AutoCloseable {
    private final ServerCatalog bundledCatalog = ServerCatalog.load();
    private final ServerDirectoryService directoryService = new ServerDirectoryService();
    private final Consumer<String> statusConsumer;
    private final Consumer<String> connectConsumer;
    private final Map<String, ServerStatus> statusMap = new ConcurrentHashMap<>();
    private final Map<ServerCategory, Label> categoryCountLabels =
            new EnumMap<>(ServerCategory.class);
    private final ExecutorService directoryExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ecl-server-directory");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private ServerCatalog catalog = bundledCatalog;
    private ServerCategory activeCategory = ServerCategory.ALL;
    private ListView<PublicServer> resultList;
    private TextField searchField;
    private Label resultCountLabel;
    private Button refreshStatusButton;
    private Button clearSearchButton;
    private Label selectedAddressLabel;
    private Button connectButton;
    private Button copyButton;
    private Button websiteButton;
    private String directorySource = "内置目录";
    private volatile boolean closed;

    public ServerBrowserView(Consumer<String> statusConsumer, Consumer<String> connectConsumer) {
        this.statusConsumer = statusConsumer == null ? ignored -> { } : statusConsumer;
        this.connectConsumer = connectConsumer == null ? ignored -> { } : connectConsumer;
        buildView();
        refreshList();
        if (!Boolean.getBoolean("ecl.snapshot")) {
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

        Label title = new Label("服务器分类");
        title.getStyleClass().add("server-category-nav-title");
        Label hint = new Label("按玩法筛选服务器");
        hint.getStyleClass().add("server-category-nav-hint");
        navigation.getChildren().addAll(title, hint);

        List<ServerCategory> categories = List.of(
                ServerCategory.ALL, ServerCategory.SURVIVAL, ServerCategory.SMP,
                ServerCategory.PVP, ServerCategory.TECH, ServerCategory.ENTERTAINMENT);
        List<Button> categoryButtons = new ArrayList<>();
        for (ServerCategory category : categories) {
            Button button = createCategoryButton(category);
            button.setOnAction(event -> {
                activeCategory = category;
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
        button.setAccessibleText(category.label() + "，" + count.getText() + " 个服务器");
        button.getStyleClass().add("server-category-item");
        if (category == activeCategory) {
            button.getStyleClass().add("server-category-item-active");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private int countFor(ServerCategory category) {
        return catalog.filter(category, "").size();
    }

    private Node buildSearchBar() {
        searchField = new TextField();
        searchField.setPromptText("搜索名称、地址、玩法、标签或地区");
        searchField.setAccessibleText("搜索公开服务器");
        searchField.getStyleClass().add("field-control");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        clearSearchButton = new Button("清除");
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

        refreshStatusButton = new Button("刷新列表");
        refreshStatusButton.getStyleClass().addAll(
                "app-button", "secondary-button", "compact-button");
        refreshStatusButton.setOnAction(event -> startDirectoryRefresh(true));

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
        resultList.setPlaceholder(new Label("没有找到匹配的服务器"));
        resultList.setCellFactory(list -> new ServerCell());
        resultList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> updateActionRow(selected));
        VBox.setVgrow(resultList, Priority.ALWAYS);

        VBox listBox = new VBox(6, resultCountLabel, resultList);
        listBox.setMinHeight(0);
        VBox.setVgrow(listBox, Priority.ALWAYS);
        return listBox;
    }

    private Node buildActionRow() {
        selectedAddressLabel = new Label("选择一个服务器查看操作");
        selectedAddressLabel.getStyleClass().add("server-selected-address");
        selectedAddressLabel.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        connectButton = actionButton("设为启动直连", "primary-button");
        connectButton.setOnAction(event -> {
            PublicServer selected = resultList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                connectConsumer.accept(selected.address());
            }
        });
        copyButton = actionButton("复制地址", "ghost-button");
        copyButton.setOnAction(event -> copySelectedAddress());
        websiteButton = actionButton("打开详情", "ghost-button");
        websiteButton.setOnAction(event -> openSelectedWebsite());

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
        websiteButton.setDisable(!hasSelection
                || selected.website() == null || selected.website().isBlank());
        selectedAddressLabel.setText(hasSelection
                ? selected.name() + " · " + selected.address()
                : "选择一个服务器查看操作");
    }

    private void refreshList() {
        List<PublicServer> filtered = catalog.filter(activeCategory, searchField.getText());
        resultList.getItems().setAll(filtered);
        resultCountLabel.setText("共 " + filtered.size() + " 个服务器"
                + (activeCategory == ServerCategory.ALL
                ? "" : "（" + activeCategory.label() + "）")
                + " · " + directorySource);
        resultList.getSelectionModel().clearSelection();
        updateActionRow(null);
    }

    private void startDirectoryRefresh(boolean forceRefresh) {
        if (closed || refreshing.getAndSet(true)) {
            return;
        }
        refreshStatusButton.setDisable(true);
        refreshStatusButton.setText("正在加载…");
        CompletableFuture.supplyAsync(
                        () -> directoryService.load(forceRefresh), directoryExecutor)
                .whenComplete(this::finishDirectoryRefresh);
    }

    private void finishDirectoryRefresh(
            ServerDirectoryService.DirectorySnapshot snapshot, Throwable error) {
        refreshing.set(false);
        if (closed) {
            return;
        }
        Platform.runLater(() -> {
            refreshStatusButton.setText("刷新列表");
            refreshStatusButton.setDisable(false);
            if (error != null || snapshot == null || snapshot.servers().isEmpty()) {
                resultList.refresh();
                statusConsumer.accept("在线目录暂时不可用，正在显示内置服务器");
                return;
            }
            catalog = bundledCatalog.withDiscoveredServers(snapshot.servers());
            statusMap.clear();
            statusMap.putAll(snapshot.statuses());
            directorySource = snapshot.cached() ? "缓存目录" : "minecraft-java-servers.com";
            updateCategoryCounts();
            refreshList();
            String source = snapshot.cached() ? "缓存" : "在线目录";
            statusConsumer.accept("已从" + source + "加载 " + catalog.servers().size()
                    + " 个公开服务器");
        });
    }

    private void updateCategoryCounts() {
        categoryCountLabels.forEach((category, label) ->
                label.setText(Integer.toString(countFor(category))));
    }

    private void copySelectedAddress() {
        PublicServer selected = resultList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(selected.address());
        Clipboard.getSystemClipboard().setContent(content);
        statusConsumer.accept("已复制服务器地址：" + selected.address());
    }

    private void openSelectedWebsite() {
        PublicServer selected = resultList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.website().isBlank()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(selected.website()));
            } else {
                statusConsumer.accept("当前系统无法打开浏览器，请复制官网地址："
                        + selected.website());
            }
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            statusConsumer.accept("无法打开官网：" + selected.website());
        }
    }

    @Override
    public void close() {
        closed = true;
        directoryExecutor.shutdownNow();
    }

    private final class ServerCell extends ListCell<PublicServer> {
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
            titleRow.setAlignment(Pos.CENTER_LEFT);

            Label description = new Label(server.description());
            description.getStyleClass().add("server-item-description");
            description.setWrapText(true);
            description.setMaxWidth(Double.MAX_VALUE);

            String meta = String.join(" · ", nonEmpty(
                    server.categoryEnum().label(), server.region(),
                    "版本 " + server.version(), server.address()));
            Label metaLabel = new Label(meta);
            metaLabel.getStyleClass().add("server-item-address");

            HBox tags = new HBox(5);
            tags.setAlignment(Pos.CENTER_LEFT);
            server.tags().forEach(tag -> tags.getChildren().add(tagBadge(tag)));

            VBox text = new VBox(4, titleRow, description, metaLabel, tags);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, icon, text);
            row.prefWidthProperty().bind(resultList.widthProperty().subtract(44));
            row.setMaxWidth(Double.MAX_VALUE);
            row.setAlignment(Pos.TOP_LEFT);
            setGraphic(row);
            setAccessibleText(server.name() + "，" + meta + "，" + server.description());
        }

        private Node statusBadge(PublicServer server) {
            ServerStatus status = statusMap.get(server.address());
            Label badge = new Label();
            if (status == null) {
                badge.setText(refreshing.get() ? "检测中…" : "待检测");
                badge.getStyleClass().add("server-status-unknown");
            } else {
                switch (status.state()) {
                    case ONLINE -> {
                        badge.setText("在线 · " + formatPlayerCount(status.playersOnline())
                                + " / " + formatPlayerCount(status.playersMax()));
                        badge.getStyleClass().add("server-status-online");
                    }
                    case OFFLINE -> {
                        badge.setText("离线");
                        badge.getStyleClass().add("server-status-offline");
                    }
                    default -> {
                        badge.setText("状态未知");
                        badge.getStyleClass().add("server-status-unknown");
                    }
                }
            }
            badge.getStyleClass().add("server-status-badge");
            return badge;
        }

        private Label tagBadge(String tag) {
            Label badge = new Label(tag);
            badge.getStyleClass().add("server-tag");
            return badge;
        }
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
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static String formatPlayerCount(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
