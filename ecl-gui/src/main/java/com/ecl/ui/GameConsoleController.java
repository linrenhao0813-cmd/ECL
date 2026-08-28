package com.ecl.ui;

import com.ecl.ECLConfig;
import javafx.application.Platform;
import javafx.scene.control.TextArea;

/** Batches game-process output into the launcher console without retaining unbounded text. */
final class GameConsoleController {
    private final LauncherUI ui;

    GameConsoleController(LauncherUI ui) { this.ui = ui; }

    void appendLine(String line) {
        ui.liveGameLog.appendLine(line);
        if (ui.applicationStopping.get()) return;
        synchronized (ui.pendingConsoleText) {
            ui.pendingConsoleText.append(line).append(System.lineSeparator());
            int excess = ui.pendingConsoleText.length() - ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS;
            if (excess > 0) ui.pendingConsoleText.delete(0, excess);
        }
        if (ui.consoleFlushScheduled.compareAndSet(false, true)) Platform.runLater(this::flush);
    }

    private void flush() {
        String batch;
        synchronized (ui.pendingConsoleText) {
            batch = ui.pendingConsoleText.toString();
            ui.pendingConsoleText.setLength(0);
        }
        ui.consoleFlushScheduled.set(false);
        TextArea area = ui.liveConsoleArea;
        if (area != null && !batch.isEmpty()) {
            area.appendText(batch);
            int excess = area.getLength() - ECLConfig.MAX_CAPTURED_GAME_LOG_CHARS;
            if (excess > 0) area.deleteText(0, excess);
            area.positionCaret(area.getLength());
        }
        synchronized (ui.pendingConsoleText) {
            if (!ui.applicationStopping.get() && ui.pendingConsoleText.length() > 0
                    && ui.consoleFlushScheduled.compareAndSet(false, true)) Platform.runLater(this::flush);
        }
    }
}
