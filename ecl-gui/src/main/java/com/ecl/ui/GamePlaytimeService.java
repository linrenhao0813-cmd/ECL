package com.ecl.ui;

import com.ecl.util.Messages;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Owns best-effort launch/session accounting and the corresponding home-page summary. */
final class GamePlaytimeService {
    private final LauncherUI ui;

    GamePlaytimeService(LauncherUI ui) { this.ui = ui; }

    void recordLaunch(String version, long startedAt) {
        try { ui.playtimeTracker.recordLaunch(ui.resolveVersionInstanceRoot(version).toPath(), startedAt); }
        catch (IOException error) { LauncherUI.LOGGER.warn("Cannot record launch statistics for {}", version, error); }
    }

    void recordSession(String version, long startedAt, long elapsedNanos) {
        try { ui.playtimeTracker.recordSession(ui.resolveVersionInstanceRoot(version).toPath(), startedAt,
                System.currentTimeMillis(), elapsedNanos); }
        catch (IOException error) { LauncherUI.LOGGER.warn("Cannot record playtime statistics for {}", version, error); }
    }

    void updateSummary() {
        if (ui.playtimeTotalLabel == null || ui.versionCombo == null) return;
        String selected = ui.versionCombo.getValue();
        if (selected == null || selected.isBlank()) {
            ui.playtimeTotalLabel.setText(Messages.get("label.notSelected"));
            ui.playtimeRecentLabel.setText(Messages.get("playtime.never"));
            ui.playtimeLaunchCountLabel.setText("0");
            return;
        }
        try {
            var stats = ui.playtimeTracker.stats(ui.resolveVersionInstanceRoot(selected).toPath());
            ui.playtimeTotalLabel.setText(format(stats.totalSeconds()));
            ui.playtimeRecentLabel.setText(stats.lastLaunchedAt().isBlank() ? Messages.get("playtime.never")
                    : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.parse(stats.lastLaunchedAt())));
            ui.playtimeLaunchCountLabel.setText(String.valueOf(stats.launchCount()));
        } catch (Exception error) {
            ui.playtimeTotalLabel.setText(Messages.get("playtime.unavailable"));
            ui.playtimeRecentLabel.setText("-");
            ui.playtimeLaunchCountLabel.setText("-");
        }
    }

    private static String format(long seconds) {
        long safe = Math.max(0, seconds);
        long hours = safe / 3600;
        long minutes = safe % 3600 / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
