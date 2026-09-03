package com.ecl.ui;

import com.ecl.util.Messages;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Builds and caches the launcher home page. */
final class HomePageFactory {
    private final LauncherUI ui;

    HomePageFactory(LauncherUI ui) {
        this.ui = ui;
    }

    VBox getOrCreate() {
        if (ui.homePage == null) {
            ui.homePage = createLaunchPane();
        }
        return ui.homePage;
    }

    private VBox createLaunchPane() {
        VBox pane = new VBox(24);
        pane.getStyleClass().add("launch-pane");
        pane.setPrefWidth(LauncherUI.LAUNCH_WIDTH);
        pane.setMaxWidth(LauncherUI.LAUNCH_WIDTH);
        pane.setFillWidth(true);
        HBox.setHgrow(pane, Priority.ALWAYS);

        Label pageTitle = new Label(Messages.get("home.title"));
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label(Messages.get("home.subtitle"));
        pageSubtitle.getStyleClass().add("page-subtitle");
        VBox pageHeading = new VBox(6, pageTitle, pageSubtitle);
        pageHeading.getStyleClass().add("page-heading");
        pageHeading.setAlignment(Pos.CENTER);

        HBox hero = createLaunchHero();
        // Build the controls once at startup so launch/auth state is available to the
        // home summary. The visible editor now lives in Download > Game instances.
        ui.createForm();
        HBox summaryCards = createHomeSummaryCards();

        pane.getChildren().addAll(pageHeading, hero, summaryCards);
        return pane;
    }

    private HBox createLaunchHero() {
        Label eyebrow = new Label(Messages.get("home.currentInstance"));
        eyebrow.getStyleClass().add("launch-eyebrow");
        ui.selectedVersionTitleLabel = new Label(Messages.get("home.selectVersion"));
        ui.selectedVersionTitleLabel.getStyleClass().add("launch-version-big");
        ui.selectedRuntimeMetaLabel = new Label(Messages.format("home.runtimeMeta",
                Runtime.version().feature(), ui.gameLaunch.getMemoryDisplayText()));
        ui.selectedRuntimeMetaLabel.getStyleClass().add("launch-version-meta");
        ui.launchReadinessLabel = new Label(Messages.get("home.autoCheck"));
        ui.launchReadinessLabel.getStyleClass().add("ready-pill");

        VBox details = new VBox(16, eyebrow, ui.selectedVersionTitleLabel,
                ui.selectedRuntimeMetaLabel, ui.launchReadinessLabel, ui.createActionBar());
        details.getStyleClass().add("launch-details");
        details.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(details, Priority.ALWAYS);

        StackPane artwork = createHeroArtwork();
        HBox hero = new HBox(48, details, artwork);
        hero.getStyleClass().addAll("launch-surface", "launch-hero");
        hero.setAlignment(Pos.CENTER);
        return hero;
    }

    private StackPane createHeroArtwork() {
        Region glow = new Region();
        glow.getStyleClass().add("orb-glow");
        Region outerRing = new Region();
        outerRing.getStyleClass().add("orb-ring-outer");
        Region innerRing = new Region();
        innerRing.getStyleClass().add("orb-ring-inner");
        Region orb = new Region();
        orb.getStyleClass().add("orb-core");
        Label play = new Label("▶");
        play.getStyleClass().add("orb-play");

        StackPane artwork = new StackPane(glow, outerRing, innerRing, orb, play);
        artwork.getStyleClass().add("hero-artwork");
        artwork.setAlignment(Pos.CENTER);
        return artwork;
    }

    private HBox createHomeSummaryCards() {
        VBox accountCard = new VBox(14);
        accountCard.getStyleClass().addAll("home-card", "account-card");
        Label accountLabel = new Label(Messages.get("home.account"));
        accountLabel.getStyleClass().add("card-kicker");
        ui.homeAccountAvatarLabel = new Label("S");
        ui.homeAccountAvatarLabel.getStyleClass().add("account-avatar");
        ui.homeAccountNameLabel = new Label(ui.getAuthDisplayName());
        ui.homeAccountNameLabel.getStyleClass().add("card-title");
        ui.homeAccountTypeLabel = new Label(Messages.get("auth.offline"));
        ui.homeAccountTypeLabel.getStyleClass().add("card-subtitle");
        VBox accountText = new VBox(3, ui.homeAccountNameLabel, ui.homeAccountTypeLabel);
        HBox accountProfile = new HBox(14, ui.homeAccountAvatarLabel, accountText);
        accountProfile.setAlignment(Pos.CENTER_LEFT);
        Region accountSpacer = new Region();
        VBox.setVgrow(accountSpacer, Priority.ALWAYS);
        var manageAccount = ui.createLinkButton(
                Messages.get("home.manageAccount"), () -> ui.openInstanceSettings(true));
        ui.homeSkinUploadButton = ui.createLinkButton(
                Messages.get("home.uploadSkin"), () -> ui.skins.chooseAndUploadSkin());
        Region accountActionSpacer = new Region();
        HBox.setHgrow(accountActionSpacer, Priority.ALWAYS);
        HBox accountActions = new HBox(8, manageAccount, accountActionSpacer,
                ui.homeSkinUploadButton);
        accountActions.setAlignment(Pos.CENTER_LEFT);
        accountCard.getChildren().addAll(accountLabel, accountProfile, accountSpacer, accountActions);

        VBox environmentCard = new VBox(12);
        environmentCard.getStyleClass().addAll("home-card", "environment-card");
        Label environmentLabel = new Label(Messages.get("home.environment"));
        environmentLabel.getStyleClass().add("card-kicker");
        ui.homeEnvironmentStatusLabel = new Label(Messages.get("home.environmentStatus"));
        ui.homeEnvironmentStatusLabel.getStyleClass().add("card-title");
        Label environmentCheck = new Label("✓");
        environmentCheck.getStyleClass().add("environment-check");
        Region environmentTitleSpacer = new Region();
        HBox.setHgrow(environmentTitleSpacer, Priority.ALWAYS);
        HBox environmentTitle = new HBox(ui.homeEnvironmentStatusLabel,
                environmentTitleSpacer, environmentCheck);
        environmentTitle.setAlignment(Pos.CENTER_LEFT);
        ui.javaSummaryLabel = ui.createValueLabel(
                Messages.format("home.javaSummary", Runtime.version().feature()));
        ui.memorySummaryLabel = ui.createValueLabel(ui.gameLaunch.getMemoryDisplayText());
        ui.versionSummaryLabel = ui.createValueLabel(Messages.get("home.versionPending"));
        environmentCard.getChildren().addAll(environmentLabel, environmentTitle,
                ui.createSummaryRow(Messages.get("info.java"), ui.javaSummaryLabel),
                ui.createSummaryRow(Messages.get("home.memory"), ui.memorySummaryLabel),
                ui.createSummaryRow(Messages.get("home.instance"), ui.versionSummaryLabel));

        VBox taskCard = new VBox(12);
        taskCard.getStyleClass().addAll("home-card", "task-card");
        Label taskLabel = new Label(Messages.get("home.currentActivity"));
        taskLabel.getStyleClass().add("card-kicker");
        ui.statusLabel = new Label(Messages.get("home.noTasks"));
        ui.statusLabel.getStyleClass().add("card-title");
        ui.detailLabel = new Label(Messages.get("home.taskDetail"));
        ui.detailLabel.getStyleClass().add("card-subtitle");
        ui.detailLabel.setWrapText(true);
        ui.downloadProgress = new ProgressBar(0);
        ui.downloadProgress.getStyleClass().add("download-progress");
        ui.downloadProgress.setMaxWidth(Double.MAX_VALUE);
        Region taskSpacer = new Region();
        VBox.setVgrow(taskSpacer, Priority.ALWAYS);
        taskCard.getChildren().addAll(taskLabel, ui.statusLabel, ui.detailLabel,
                ui.downloadProgress, taskSpacer,
                ui.createLinkButton(Messages.get("home.viewTasks"),
                        () -> ui.openDownloadSection(DownloadSection.TASKS)));

        VBox playtimeCard = new VBox(12);
        playtimeCard.getStyleClass().addAll("home-card", "playtime-card");
        Label playtimeTitle = new Label(Messages.get("playtime.title"));
        playtimeTitle.getStyleClass().add("card-kicker");
        ui.playtimeTotalLabel = ui.createValueLabel(Messages.get("label.notSelected"));
        ui.playtimeRecentLabel = ui.createValueLabel(Messages.get("playtime.never"));
        ui.playtimeLaunchCountLabel = ui.createValueLabel("0");
        playtimeCard.getChildren().addAll(playtimeTitle,
                ui.createSummaryRow(Messages.get("playtime.total"), ui.playtimeTotalLabel),
                ui.createSummaryRow(Messages.get("playtime.lastLaunch"), ui.playtimeRecentLabel),
                ui.createSummaryRow(Messages.get("playtime.launches"), ui.playtimeLaunchCountLabel),
                ui.createLinkButton(Messages.get("shortcut.createLink"),
                        () -> ui.openDownloadSection(DownloadSection.INSTANCES)));

        double preferredCardWidth = (LauncherUI.LAUNCH_WIDTH - 72) / 4.0;
        for (VBox card : java.util.List.of(accountCard, environmentCard, taskCard, playtimeCard)) {
            card.setMinWidth(0);
            card.setPrefWidth(preferredCardWidth);
            card.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(card, Priority.ALWAYS);
        }
        HBox cards = new HBox(24, accountCard, environmentCard, taskCard, playtimeCard);
        cards.getStyleClass().add("home-summary");
        cards.setFillHeight(true);
        return cards;
    }
}
