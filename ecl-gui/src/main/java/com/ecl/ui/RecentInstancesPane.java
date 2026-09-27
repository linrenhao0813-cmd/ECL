package com.ecl.ui;

import com.ecl.util.Messages;
import com.ecl.game.DefaultGameRepository;
import com.ecl.game.PlaytimeTracker;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads real local play history off the FX thread; never invents recent sessions. */
final class RecentInstancesPane extends VBox {
    private final LauncherUI ui;
    private final HBox cards = new HBox(16);
    private long generation;

    RecentInstancesPane(LauncherUI ui) {
        super(12);
        this.ui = ui;
        getStyleClass().add("forest-recents");
        Label title = HomePageFactory.label(GuiMessages.get("forest.recent"), "forest-section-title");
        Button all = ui.createLinkButton(GuiMessages.get("forest.viewAll"), () -> ui.setActiveView(AppView.VERSIONS));
        all.setId("recent-view-all");
        HBox heading = new HBox(title, HomePageFactory.spacer(), all);
        heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        getChildren().addAll(heading, cards);
        showEmpty();
    }

    void refresh() {
        long request = ++generation;
        DefaultGameRepository repository = ui.gameRepository();
        ui.runAsync("ecl-recent-instances", () -> {
            List<RecentInstance> recent = readRecent(repository, ui.playtimeTracker);
            Platform.runLater(() -> {
                if (request != generation || ui.applicationStopping.get()) return;
                cards.getChildren().clear();
                if (recent.isEmpty()) showEmpty();
                else recent.forEach(item -> cards.getChildren().add(createCard(item)));
            });
        });
    }

    static List<RecentInstance> readRecent(DefaultGameRepository repository, PlaytimeTracker tracker) {
        List<RecentInstance> recent = new ArrayList<>();
        for (String id : repository.installedInstanceDirectories()) {
            try {
                var stats = tracker.stats(repository.instanceRoot(id));
                if (!stats.lastLaunchedAt().isBlank()) {
                    recent.add(new RecentInstance(id, Instant.parse(stats.lastLaunchedAt())));
                }
            } catch (Exception error) {
                LauncherUI.LOGGER.debug("Cannot read recent playtime for {}", id, error);
            }
        }
        return recent.stream().sorted(Comparator.comparing(RecentInstance::lastPlayed).reversed()).limit(2).toList();
    }

    private Button createCard(RecentInstance item) {
        Region thumbnail = new Region();
        thumbnail.getStyleClass().add("forest-recent-thumbnail");
        Label title = HomePageFactory.label(item.id(), "forest-recent-title");
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(item.lastPlayed());
        Label meta = HomePageFactory.label(GuiMessages.get("forest.lastPlayed", date), "forest-stat-caption");
        VBox text = new VBox(8, title, meta);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox content = new HBox(16, thumbnail, text);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Button card = new Button();
        card.setGraphic(content);
        card.setAccessibleText(item.id() + ", " + meta.getText());
        card.setTooltip(new Tooltip(item.id()));
        card.getStyleClass().add("forest-recent-card");
        card.setMinWidth(0);
        card.setPrefWidth(600);
        card.setMaxWidth(Double.MAX_VALUE);
        card.disableProperty().bind(ui.versionCombo.disabledProperty());
        card.setOnAction(event -> {
            ui.versionCombo.setValue(item.id());
            ui.updateRuntimeSummary();
            ui.setStatus(Messages.get("local.instances.selected"), item.id());
        });
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void showEmpty() {
        Label empty = HomePageFactory.label(GuiMessages.get("forest.noRecent"), "forest-empty");
        empty.setId("recent-empty");
        empty.setWrapText(true);
        empty.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(empty, Priority.ALWAYS);
        cards.getChildren().setAll(empty);
    }

    record RecentInstance(String id, Instant lastPlayed) { }
}
