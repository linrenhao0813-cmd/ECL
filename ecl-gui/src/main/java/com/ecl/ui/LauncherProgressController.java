package com.ecl.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.ProgressBar;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns progress-bar visibility, determinate progress, and pulse animations. */
final class LauncherProgressController {
    private final Map<ProgressBar, Timeline> animations = new HashMap<>();

    void start(ProgressBar progressBar) {
        if (progressBar == null) {
            return;
        }

        stop(progressBar, false);
        progressBar.setVisible(true);
        progressBar.getProperties().put("pulse-step", 0);

        Timeline timeline = new Timeline(new KeyFrame(
                Duration.millis(260), event -> advancePulse(progressBar)));
        timeline.setCycleCount(Timeline.INDEFINITE);
        animations.put(progressBar, timeline);
        timeline.play();
        advancePulse(progressBar);
    }

    void update(ProgressBar progressBar, long downloaded, long total) {
        if (progressBar == null) {
            return;
        }

        progressBar.setVisible(true);
        if (total > 0) {
            progressBar.setProgress(clamp((double) downloaded / total, 0, 1));
        } else {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
    }

    void stop(ProgressBar progressBar, boolean hide) {
        if (progressBar == null) {
            return;
        }

        Timeline timeline = animations.remove(progressBar);
        if (timeline != null) {
            timeline.stop();
        }
        progressBar.getStyleClass().removeAll(
                "progress-pulse-a", "progress-pulse-b", "progress-pulse-c");
        progressBar.getProperties().remove("pulse-step");
        if (hide) {
            progressBar.setVisible(false);
        }
    }

    void stopAll() {
        for (ProgressBar progressBar : List.copyOf(animations.keySet())) {
            stop(progressBar, false);
        }
    }

    private void advancePulse(ProgressBar progressBar) {
        progressBar.getStyleClass().removeAll(
                "progress-pulse-a", "progress-pulse-b", "progress-pulse-c");
        int step = ((Number) progressBar.getProperties()
                .getOrDefault("pulse-step", 0)).intValue();
        progressBar.getStyleClass().add(switch (step) {
            case 0 -> "progress-pulse-a";
            case 1 -> "progress-pulse-b";
            default -> "progress-pulse-c";
        });
        progressBar.getProperties().put("pulse-step", (step + 1) % 3);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
